package com.ep.order.config;

import io.seata.saga.engine.StateMachineEngine;
import io.seata.saga.engine.impl.DefaultStateMachineConfig;
import io.seata.saga.engine.impl.ProcessCtrlStateMachineEngine;
import io.seata.saga.engine.repo.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

/**
 * D-113: Seata Saga state-machine engine wiring.
 *
 * <p>Loads the {@code create-order-saga.json} and {@code cancel-order-saga.json}
 * definitions from the classpath and exposes a single {@link StateMachineEngine}
 * bean that {@link com.ep.order.service.OrderServiceImpl} invokes to drive the
 * long-running order -> stock -> payment flow.
 *
 * <h3>Why a dedicated config</h3>
 * The Saga engine needs the JSON state-machine definitions registered with a
 * {@link DefaultStateMachineConfig} before any forward/compensate invocation.
 * Centralising that here keeps {@code OrderServiceImpl} focused on orchestration
 * and lets the integration tests inject a stubbed engine.
 *
 * <h3>No live TC required for code-only changes</h3>
 * The engine is constructed in-process; it does not dial a Seata TC server at
 * boot. The state machine persists Saga execution context in the local
 * {@code seata_state_machine} tables (created lazily by the engine when a real
 * datasource is configured). For dev/CI without a TC, the forward/compensate
 * methods still run because each {@code @SagaCompensable} action is a plain
 * Spring bean invocation orchestrated by the engine.
 */
@Configuration
public class SagaStateMachineConfig {

    private static final Logger log = LoggerFactory.getLogger(SagaStateMachineConfig.class);

    @Value("classpath:saga/create-order-saga.json")
    private Resource createOrderSagaResource;

    @Value("classpath:saga/cancel-order-saga.json")
    private Resource cancelOrderSagaResource;

    /**
     * Build and return the Saga state-machine engine with both order-flow
     * definitions registered.
     *
     * <p>Uses Seata's {@link StateMachineRepository#registryByResources} to
     * parse and register the JSON definitions. This is the official API
     * (confirmed from the 2.0.0 jar); there is no {@code registry(Resource)}
     * overload and no {@code seata-saga-starter} uber-jar in Seata 2.x.
     *
     * @return the singleton {@link StateMachineEngine} used by OrderServiceImpl
     */
    @Bean
    public StateMachineEngine stateMachineEngine() throws IOException {
        log.info("D-113: initialising Seata Saga state-machine engine");
        DefaultStateMachineConfig config = new DefaultStateMachineConfig();
        StateMachineRepository repository = config.getStateMachineRepository();
        // Register both JSON definitions. registryByResources parses the JSON
        // via the statelang parser and stores the resulting StateMachine domain
        // object keyed by its Name + Version + TenantId.
        validateResource(createOrderSagaResource, "createOrderSaga");
        validateResource(cancelOrderSagaResource, "cancelOrderSaga");
        repository.registryByResources(
                new Resource[]{createOrderSagaResource, cancelOrderSagaResource}, "D-113");
        log.info("D-113: registered saga state machines: createOrderSaga, cancelOrderSaga");
        ProcessCtrlStateMachineEngine engine = new ProcessCtrlStateMachineEngine();
        engine.setStateMachineConfig(config);
        return engine;
    }

    /**
     * Fail fast if a saga definition is missing on the classpath (Iron Law 0:
     * no silent failure). The actual parsing is done by
     * {@link StateMachineRepository#registryByResources}.
     */
    private void validateResource(Resource resource, String name) {
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "D-113: saga state-machine definition not found on classpath: " + name);
        }
    }
}
