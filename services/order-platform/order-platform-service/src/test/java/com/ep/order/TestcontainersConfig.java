package com.ep.order;

import io.seata.saga.engine.StateMachineEngine;
import io.seata.saga.engine.StateMachineConfig;
import io.seata.saga.engine.AsyncCallback;
import io.seata.saga.engine.exception.EngineExecutionException;
import io.seata.saga.engine.exception.ForwardInvalidException;
import io.seata.saga.statelang.domain.StateMachineInstance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

/**
 * D-119: Spring test configuration for the Testcontainers IT.
 *
 * <p>Starts an isolated MySQL 8.0 container (TiDB is MySQL-protocol compatible)
 * and exposes its JDBC URL to the Spring {@code DataSource} via
 * {@code @DynamicPropertySource}. The container is a {@code static} field so it
 * is shared across all test methods in {@link OrderServiceTestcontainersIT}
 * (started once, stopped at JVM shutdown — self-contained, no shared cluster).
 *
 * <p>Also provides a no-op {@link StateMachineEngine} bean so the Spring context
 * boots without dialing a Seata TC server. The Saga state-machine JSON files
 * stay on the classpath; only the engine is stubbed for the IT.
 */
@Testcontainers
@TestConfiguration
public class TestcontainersConfig {

    /**
     * MySQL 8.0 container. TiDB is MySQL-protocol compatible, so this exercises
     * the same dialect / driver / DDL the production TiDB swap will use.
     * {@code withReuse(false)} keeps the test self-contained (no leaked
     * containers between CI runs).
     */
    @Container
    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("orderdb_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(false);

    /**
     * Expose the container's JDBC URL / credentials to Spring's
     * {@code DataSource} auto-configuration. {@link OrderServiceTestcontainersIT}
     * references this via {@code @DynamicPropertySource}.
     */
    public static String jdbcUrl() {
        return MYSQL.getJdbcUrl();
    }

    public static String username() {
        return MYSQL.getUsername();
    }

    public static String password() {
        return MYSQL.getPassword();
    }

    /**
     * No-op Saga engine so the Spring context boots without a live Seata TC.
     * The IT does not exercise the Saga flow directly (that needs a real TC +
     * Dubbo registry); it exercises the {@code createOrder}/{@code payOrder}/
     * {@code refundOrder} JPA write path against real MySQL.
     */
    @Bean
    @Primary
    public StateMachineEngine stateMachineEngine() {
        return new StubStateMachineEngine();
    }

    /**
     * Minimal stub that satisfies the {@link StateMachineEngine} contract without
     * dialing a TC server. Every method throws {@link UnsupportedOperationException}
     * because the IT only exercises the non-Saga JPA write path; no Saga method
     * should be invoked during the test run.
     */
    static final class StubStateMachineEngine implements StateMachineEngine {
        @Override
        public StateMachineInstance start(
                String stateMachineName, String tenantId, Map<String, Object> startParams)
                throws EngineExecutionException {
            throw new UnsupportedOperationException(
                    "D-119 IT stub: Saga start not supported without a live TC");
        }

        @Override
        public StateMachineInstance startWithBusinessKey(
                String stateMachineName, String tenantId, String businessKey,
                Map<String, Object> startParams) throws EngineExecutionException {
            throw new UnsupportedOperationException(
                    "D-119 IT stub: Saga startWithBusinessKey not supported without a live TC");
        }

        @Override
        public StateMachineInstance startAsync(
                String stateMachineName, String tenantId, Map<String, Object> startParams,
                AsyncCallback callback) throws EngineExecutionException {
            throw new UnsupportedOperationException("D-119 IT stub: startAsync not supported");
        }

        @Override
        public StateMachineInstance startWithBusinessKeyAsync(
                String stateMachineName, String tenantId, String businessKey,
                Map<String, Object> startParams, AsyncCallback callback)
                throws EngineExecutionException {
            throw new UnsupportedOperationException(
                    "D-119 IT stub: startWithBusinessKeyAsync not supported");
        }

        @Override
        public StateMachineInstance forward(
                String instanceId, Map<String, Object> expandParams)
                throws ForwardInvalidException {
            throw new UnsupportedOperationException("D-119 IT stub: forward not supported");
        }

        @Override
        public StateMachineInstance forwardAsync(
                String instanceId, Map<String, Object> expandParams, AsyncCallback callback)
                throws ForwardInvalidException {
            throw new UnsupportedOperationException("D-119 IT stub: forwardAsync not supported");
        }

        @Override
        public StateMachineInstance compensate(
                String instanceId, Map<String, Object> expandParams)
                throws EngineExecutionException {
            throw new UnsupportedOperationException("D-119 IT stub: compensate not supported");
        }

        @Override
        public StateMachineInstance compensateAsync(
                String instanceId, Map<String, Object> expandParams, AsyncCallback callback)
                throws EngineExecutionException {
            throw new UnsupportedOperationException("D-119 IT stub: compensateAsync not supported");
        }

        @Override
        public StateMachineInstance skipAndForward(
                String instanceId, Map<String, Object> expandParams)
                throws EngineExecutionException {
            throw new UnsupportedOperationException("D-119 IT stub: skipAndForward not supported");
        }

        @Override
        public StateMachineInstance skipAndForwardAsync(
                String instanceId, AsyncCallback callback) throws EngineExecutionException {
            throw new UnsupportedOperationException(
                    "D-119 IT stub: skipAndForwardAsync not supported");
        }

        @Override
        public StateMachineConfig getStateMachineConfig() {
            return null;
        }

        @Override
        public StateMachineInstance reloadStateMachineInstance(String instanceId) {
            throw new UnsupportedOperationException(
                    "D-119 IT stub: reloadStateMachineInstance not supported");
        }
    }
}
