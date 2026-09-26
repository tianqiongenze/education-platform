package com.ep.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * D-216: assembles the dual-pool read/write-split datasource graph.
 *
 * <pre>
 *                    ┌──────────────────────────┐
 *   JPA / JdbcTemplate  │ LazyConnectionDataSourceProxy │  (deferred routing)
 *                    └───────────┬──────────────┘
 *                                │
 *                       EpRoutingDataSource
 *                        │                │
 *                   WRITE_KEY          READ_KEY
 *                        │                │
 *                writeHikariDataSource  readHikariDataSource
 *                (CRDB: leader)         (CRDB: follower reads / TiDB: replica)
 * </pre>
 *
 * D-230 semantics ("default-on CRDB", cf. the ESP reference design):
 * activated when {@code ep.data.enabled=true}, or by default whenever an
 * endpoint is reachable via configuration ({@code ep.data.write.*} or the
 * legacy {@code spring.datasource.url}, which is then inherited as the pool
 * endpoint); {@code ep.data.enabled=false} opts out entirely, and a bare
 * zero-config context (unit-test slices) stays inert. See
 * {@link EpDataEnabledCondition} for the truth table.
 */
@AutoConfiguration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(EpDataProperties.class)
@Conditional(EpDataEnabledCondition.class)
@Import(RoutingOverrideAspect.class)
public class EpDataAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EpDataAutoConfiguration.class);

    /** CRDB session-level follower reads for the read-only pool. */
    static final String CRDB_FOLLOWER_READS_INIT_SQL =
            "SET default_transaction_use_followers = on";

    /** Legacy spring.datasource.url, captured for the D-230 inherit mode. */
    private final String inheritedUrl;

    public EpDataAutoConfiguration(Environment env) {
        this.inheritedUrl = env.getProperty("spring.datasource.url");
    }

    @Bean(name = "writeDataSource", destroyMethod = "close")
    public HikariDataSource writeDataSource(EpDataProperties props) {
        return buildPool(props, props.getWrite(), false);
    }

    @Bean(name = "readDataSource", destroyMethod = "close")
    public HikariDataSource readDataSource(EpDataProperties props,
            @Qualifier("writeDataSource") HikariDataSource writePool) {
        boolean collapsed = !props.isReadReplicas() || !props.getProvider().supportsReadReplicas();
        if (collapsed) {
            log.info("[ep-data] read pool collapsed onto write pool (provider={}, readReplicas={})",
                    props.getProvider(), props.isReadReplicas());
        }
        return collapsed ? writePool : buildPool(props, props.getRead(), true);
    }

    /**
     * Primary DataSource consumed by JPA / JdbcTemplate. Deliberately NOT
     * guarded by @ConditionalOnMissingBean(DataSource.class): the two pool
     * beans above are themselves DataSource-typed, so the guard would match
     * them and silently skip this bean. @Primary lets an application that
     * really wants full control still override with its own primary bean.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public DataSource dataSource(EpDataProperties props,
            @Qualifier("writeDataSource") DataSource writePool,
            @Qualifier("readDataSource") DataSource readPool) {
        EpRoutingDataSource routing = new EpRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(EpRoutingDataSource.WRITE_KEY, writePool);
        targets.put(EpRoutingDataSource.READ_KEY, readPool);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(writePool);
        routing.afterPropertiesSet();
        log.info("[ep-data] read/write split active: provider={}, followerReads={}, write={}, read={}",
                props.getProvider(), props.isFollowerReads(),
                safeUrl(props.getWrite()), safeUrl(props.getRead()));
        // Lazy proxy defers connection acquisition (and thus the routing
        // decision) until the first statement — required for tx readOnly routing.
        return new LazyConnectionDataSourceProxy(routing);
    }

    private HikariDataSource buildPool(EpDataProperties props, EpDataProperties.Pool pool,
            boolean readOnly) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(resolveUrl(props, pool));
        cfg.setUsername(pool.getUsername());
        cfg.setPassword(pool.getPassword());
        cfg.setDriverClassName(props.getProvider().driverClassName());
        cfg.setMaximumPoolSize(pool.getMaximumPoolSize());
        cfg.setMinimumIdle(pool.getMinimumIdle());
        cfg.setConnectionTimeout(pool.getConnectionTimeoutMillis());
        cfg.setPoolName((readOnly ? "ep-read" : "ep-write") + "-pool");
        cfg.setReadOnly(readOnly);

        // Provider specialisation: CockroachDB
        if (props.getProvider() == DataSourceProvider.COCKROACHDB) {
            // Application name shows up in CRDB console / SHOW SESSIONS.
            String appName = "edu-platform-" + (readOnly ? "ro" : "rw");
            cfg.addDataSourceProperty("ApplicationName", appName);
            if (readOnly && props.isFollowerReads()) {
                // Stale follower reads: served by the nearest replica instead of
                // the Raft leaseholder — big latency win for read-heavy paths.
                cfg.setConnectionInitSql(CRDB_FOLLOWER_READS_INIT_SQL);
            }
        }
        String initSql = pool.getConnectionInitSql();
        if (initSql != null && !initSql.isBlank()) {
            cfg.setConnectionInitSql(initSql);
        }
        return new HikariDataSource(cfg);
    }

    private String resolveUrl(EpDataProperties props, EpDataProperties.Pool pool) {
        if (pool.getUrl() != null && !pool.getUrl().isBlank()) {
            return pool.getUrl();
        }
        // D-230 inherit mode: ep-data activated by default (or explicitly)
        // without ep.data.* endpoints, but a legacy spring.datasource.url
        // exists — route both pools onto it (read pool collapses onto write
        // semantics when both URLs coincide).
        if (inheritedUrl != null && !inheritedUrl.isBlank()
                && pool.getHost() == null && pool.getDatabase() == null) {
            return inheritedUrl;
        }
        if (pool.getHost() == null || pool.getDatabase() == null) {
            throw new IllegalStateException(
                    "ep-data pool needs either ep.data.*.url or both host and database (pool="
                            + (pool == props.getWrite() ? "write" : "read") + ")");
        }
        return props.getProvider().urlTemplate().formatted(pool.getHost(), pool.getDatabase());
    }

    private static String safeUrl(EpDataProperties.Pool pool) {
        return pool.getUrl() != null ? pool.getUrl()
                : (pool.getHost() != null ? pool.getHost() + "/" + pool.getDatabase() : "?");
    }
}
