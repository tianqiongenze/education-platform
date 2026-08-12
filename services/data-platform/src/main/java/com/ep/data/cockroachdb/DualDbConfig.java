package com.ep.data.cockroachdb;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * D-194: Dual-datasource configuration (TiDB primary + CockroachDB overseas).
 * <p>
 * Only active when {@code ep.db.dual.enabled=true}. Builds two Hikari pools —
 * {@code primaryDataSource} (TiDB, MySQL protocol) and
 * {@code overseasDataSource} (CockroachDB, PostgreSQL protocol) — and exposes
 * a {@link RoutingDataSource} as the {@code @Primary} {@link DataSource} so
 * all repositories transparently route to the right database based on
 * {@link TenantContext#getRegion()}.
 * <p>
 * EP_DEV_ONLY: when disabled, the standard single datasource auto-configured
 * by Spring Boot remains in effect.
 */
@Configuration
@ConditionalOnProperty(name = "ep.db.dual.enabled", havingValue = "true")
public class DualDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DualDbConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "ep.db.primary")
    public DataSource primaryDataSource() {
        log.info("D-194: configuring primary (TiDB) datasource");
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties(prefix = "ep.db.overseas")
    public DataSource overseasDataSource() {
        log.info("D-194: configuring overseas (CockroachDB) datasource");
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DataSource routingDataSource(DataSource primaryDataSource,
                                        DataSource overseasDataSource) {
        log.info("D-194: activating RoutingDataSource (domestic→primary, overseas→overseas)");
        RoutingDataSource routing = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(RoutingDataSource.KEY_PRIMARY, primaryDataSource);
        targets.put(RoutingDataSource.KEY_OVERSEAS, overseasDataSource);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primaryDataSource);
        return routing;
    }
}
