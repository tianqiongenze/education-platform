package com.ep.order;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * core-business Order middle-platform entry point.
 * <p>
 * Exposes {@link com.ep.order.api.OrderService} over Dubbo (port 20884) and a
 * REST fa\u00e7ade under {@code /api/v1/orders} for direct callers.
 * <p>
 * D-217: {@link EnableScheduling} enables the {@code @Scheduled} outbox relay
 * ({@code OutboxRelayService}) that drains {@code ep_outbox} and forwards
 * domain events to Pulsar asynchronously.
 */
@SpringBootApplication
@EnableDubbo
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
