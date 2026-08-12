package com.ep.order;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SP-03 Order middle-platform entry point.
 * <p>
 * Exposes {@link com.ep.order.api.OrderService} over Dubbo (port 20884) and a
 * REST fa\u00e7ade under {@code /api/v1/orders} for direct callers.
 */
@SpringBootApplication
@EnableDubbo
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
