package com.ep.bff;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BFF Application — Spring Boot 4.0.7 + Jetty (ADR-T13: 禁 Undertow/Tomcat).
 * 鉴权: JWT RS256 (ADR-A02), AuthFilter 剥离伪造头注入 X-User-Id/X-Role.
 * D-032: @EnableDubbo 启用 Dubbo 3.3.6 消费端, 通过 @DubboReference 调用 core-business 远程服务.
 * D-077: Seata disabled via application.yml seata.enabled=false (TC server unavailable).
 */
@SpringBootApplication
@EnableDubbo
public class BffApplication {
    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
