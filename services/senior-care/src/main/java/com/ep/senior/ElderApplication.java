package com.ep.senior;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * D-147: 康养养老事业群 (Elder Care BU) — standalone Spring Boot application.
 *
 * <p>Provides elder profile management, health record tracking, and care plan
 * execution. Follows the same DDD + Dubbo + JPA pattern as core-business.
 */
@SpringBootApplication
@EnableDubbo
public class ElderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElderApplication.class, args);
    }
}
