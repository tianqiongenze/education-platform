package com.ep.knowledge.retrieval.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus client and properties wiring.
 *
 * <p>Iron Law 0: the bean is only created when {@code milvus.enabled=true} (default true),
 * so tests that don't need Milvus can disable it via {@code milvus.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.collection:ep_knowledge}")
    private String collection;

    @Value("${milvus.vector-field:embedding}")
    private String vectorField;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    @Bean
    public MilvusProperties milvusProperties() {
        return new MilvusProperties(collection, vectorField);
    }

    /**
     * Immutable holder for Milvus collection / field configuration.
     * GraalVM AOT friendly: simple record, no reflection needed.
     */
    public record MilvusProperties(String collection, String vectorField) {
    }
}
