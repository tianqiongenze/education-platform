package com.ep.web.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 虚拟线程 DB 连接限流自动配置。
 *
 * <p>P1-6：在启用 Spring 虚拟线程（spring.threads.virtual.enabled=true）后，
 * 虚拟线程数量可远超平台线程，但底层 HikariCP 连接池容量有限。本配置在
 * 获取 DB 连接前用 {@link java.util.concurrent.Semaphore} 限流，把同时争抢
 * 连接的虚拟线程数控制在 HikariCP maximumPoolSize 量级，避免连接池被打穿、
 * 虚拟线程 pinning、级联超时雪崩。</p>
 *
 * <p>启用方式：在消费服务的 application.yml 中开启
 * {@code ep.db.concurrent-acquire-limit.enabled=true}。默认关闭，避免对现有
 * 未做适配的服务造成行为变更。</p>
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code ep.db.concurrent-acquire-limit.enabled}  是否启用（默认 false）</li>
 *   <li>{@code ep.db.concurrent-acquire-limit.permits}  许可数（默认 20，建议与 HikariCP maximumPoolSize 一致）</li>
 *   <li>{@code ep.db.concurrent-acquire-limit.timeout-ms} 获取许可超时（默认 5000ms）</li>
 * </ul>
 *
 * <p>注意：使用 Spring Boot 3.x 的 {@link AutoConfiguration} 注解，并通过
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册，确保作为 provided 依赖被各服务自动装配。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "ep.db.concurrent-acquire-limit.enabled", havingValue = "true")
public class VirtualThreadDbAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadDbAutoConfiguration.class);

    /** 默认许可数：与项目内多数服务 HikariCP maximumPoolSize 默认值对齐。 */
    static final int DEFAULT_PERMITS = 20;
    /** 默认获取许可超时（毫秒）。 */
    static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * DB 连接信号量 Bean。
     *
     * <p>permits 来源于 {@code ep.db.concurrent-acquire-limit.permits}，
     * 未配置时使用默认值 {@value #DEFAULT_PERMITS}。建议消费服务将其设置为
     * 与自身 HikariCP {@code maximum-pool-size} 相同的值。</p>
     *
     * @param permits 许可数
     * @return DbConnectionSemaphore 实例
     */
    @Bean
    public DbConnectionSemaphore dbConnectionSemaphore(
            @Value("${ep.db.concurrent-acquire-limit.permits:" + DEFAULT_PERMITS + "}") int permits) {
        log.info("启用虚拟线程 DB 连接限流：DbConnectionSemaphore(permits={})", permits);
        return new DbConnectionSemaphore(permits);
    }

    /**
     * DB 连接执行模板 Bean。
     *
     * <p>超时时间来源于 {@code ep.db.concurrent-acquire-limit.timeout-ms}，
     * 未配置时使用默认值 {@value #DEFAULT_TIMEOUT_MS}ms。</p>
     *
     * @param semaphore DB 连接信号量
     * @param timeoutMs 获取许可超时（毫秒）
     * @return DbConnectionTemplate 实例
     */
    @Bean
    public DbConnectionTemplate dbConnectionTemplate(
            DbConnectionSemaphore semaphore,
            @Value("${ep.db.concurrent-acquire-limit.timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") long timeoutMs) {
        log.info("启用虚拟线程 DB 连接执行模板：DbConnectionTemplate(timeoutMs={})", timeoutMs);
        return new DbConnectionTemplate(semaphore, timeoutMs);
    }
}
