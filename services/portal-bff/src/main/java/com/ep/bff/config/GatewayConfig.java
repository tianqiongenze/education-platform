package com.ep.bff.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway edge configuration (SDD §5.1 GatewayConfig).
 *
 * <p>Activates the Netty + Disruptor edge layer when
 * {@code ep.gateway.netty.enabled=true}.
 *
 * <p>This class is intentionally minimal — the actual beans
 * ({@link com.ep.bff.edge.NettyEdgeServer}, {@link com.ep.bff.edge.GatewayRingBuffer})
 * are component-scanned via their own {@code @ConditionalOnProperty} annotations.
 * This class exists to match the SDD class diagram and to serve as a future
 * extension point for edge-layer configuration (SSL context, connection limits, etc.).
 *
 * <p>Resides in the {@code config} package to satisfy the ArchitectureTest rule
 * that {@code @Configuration} classes must live in {@code ..config..}.
 */
@Configuration
@ConditionalOnProperty(name = "ep.gateway.netty.enabled", havingValue = "true")
public class GatewayConfig {
    // Bean wiring is handled by @Component + @ConditionalOnProperty on
    // NettyEdgeServer and GatewayRingBuffer. This class provides a
    // configuration namespace for future edge-layer settings.
}
