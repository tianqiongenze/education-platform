package com.ep.bff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * D-106: Configuration for the unified API gateway proxy.
 * <p>
 * Binds {@code ep.proxy.*} from application.yml:
 * <ul>
 *   <li>{@code enabled} — EP_DEV_ONLY guard, default false. When false the
 *       ProxyController returns 503 instead of forwarding (so a dev box
 *       without the backend services running does not hang on timeouts).</li>
 *   <li>{@code timeout-ms} — connect + read timeout for upstream calls.</li>
 *   <li>{@code routes} — path prefix → backend base URL map.</li>
 * </ul>
 * D-040 (GraalVM): uses only JDK + Spring built-ins, no reflective access.
 *
 * <p>D-177: Moved from {@code com.ep.bff.proxy} to {@code com.ep.bff.config}
 * to satisfy the ArchUnit rule that {@code @Configuration} classes must reside
 * in a {@code ..config..} package. The {@code ProxyProps} record is still
 * accessible as {@code com.ep.bff.config.ProxyConfig.ProxyProps}.
 */
@Configuration
@ConfigurationProperties(prefix = "ep.proxy")
public class ProxyConfig {

    /**
     * Java record binding the nested {@code ep.proxy} properties.
     * <p>
     * Using a record keeps the binding GraalVM-friendly (no reflective
     * setters) and immutable (D-039: no shared mutable state, no sync needed).
     */
    public record ProxyProps(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue Map<String, String> routes
    ) {
    }

    /**
     * RestClient backed by the JDK HttpClient (no new dependencies; shipped
     * with Spring Boot 4). Configured with the connect/read timeout from
     * {@code ep.proxy.timeout-ms}.
     */
    /**
     * D-231: Boot 4 auto-configures a Jackson 3 (tools.jackson) ObjectMapper;
     * the BFF's servlet filters and event consumers are written against the
     * Jackson 2 API (com.fasterxml). Expose the Jackson 2 mapper explicitly.
     */
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper bffJackson2ObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public RestClient proxyRestClient(ProxyProps props) {
        int timeoutMs = props.timeoutMs() > 0 ? props.timeoutMs() : 10000;
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder()
            .requestFactory(requestFactory)
            .build();
    }
}
