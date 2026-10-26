package com.ep.web.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D-234: auto-configuration for the generic transactional outbox.
 *
 * <p>Activation requirements (all three must hold):</p>
 * <ol>
 *   <li>{@code ep.outbox.enabled=true} (default false — explicit opt-in, the
 *       same discipline D-223 taught us: a control that ships disabled by
 *       default is documented loudly here);</li>
 *   <li>a {@link JdbcTemplate} bean (spring-boot-starter-jdbc, present in
 *       every Java service via the datasource starter);</li>
 *   <li>a host-provided {@link OutboxPublisherPort} bean — the broker adapter.
 *       Without it the outbox would accept rows forever and never publish,
 *       so the configuration refuses to start the relay in that case.</li>
 * </ol>
 *
 * <p>Table creation ({@code ep_outbox}) runs on startup when
 * {@code ep.outbox.create-table=true} (default): the DDL is portable across
 * H2 / MySQL / TiDB / CockroachDB, matching the ep-data dual-DB strategy.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ep.outbox.enabled", havingValue = "true")
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxAutoConfiguration.class);

    /** Portable DDL — same shape as the order-platform reference (D-217). */
    static final String DDL = """
            CREATE TABLE IF NOT EXISTS ep_outbox (
                event_id VARCHAR(128) PRIMARY KEY,
                aggregate_type VARCHAR(64) NOT NULL,
                aggregate_id VARCHAR(64) NOT NULL,
                event_type VARCHAR(128) NOT NULL,
                payload TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL,
                published_at TIMESTAMP NULL,
                retry_count INT NOT NULL DEFAULT 0
            )""";

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectProvider<PlatformTransactionManager> txManager) {
        return new OutboxWriter(jdbc, txManager.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(OutboxPublisherPort.class)
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, OutboxPublisherPort publisher,
                                   OutboxProperties properties) {
        if (properties.isCreateTable()) {
            try {
                jdbc.execute(DDL);
                log.info("D-234: ep_outbox table ensured (CREATE TABLE IF NOT EXISTS)");
            } catch (Exception e) {
                log.warn("D-234: ep_outbox table create skipped/failed: {} (rely on migrations)", e.getMessage());
            }
        }
        log.info("D-234: outbox relay started (interval={}ms, batch={}, maxRetries={})",
                properties.getRelayIntervalMillis(), properties.getBatchSize(), properties.getMaxRetries());
        return new OutboxRelay(jdbc, publisher, properties);
    }
}
