package com.ep.memory.config;

import com.ep.memory.MemoryClient;
import com.ep.memory.MemoryManager;
import com.ep.memory.MemoryRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * D-045 Spring Boot auto-configuration for the ep-memory SDK.
 *
 * <p>Wires {@link MemoryManager} with a Dubbo {@link MemoryRpcService} (when
 * available on the registry) and an HTTP {@link MemoryClient} fallback. The
 * {@code ep.memory.use-rpc} flag picks the primary path; the non-primary path
 * remains as a fallback when its bean exists.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(MemoryConfig.class)
@ConditionalOnProperty(prefix = "ep.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    /**
     * Optional Dubbo reference. Injected via {@link DubboReference} on a
     * dedicated configuration class so consumers without Dubbo on the
     * classpath can still use the HTTP-only path.
     */
    @Bean
    @ConditionalOnMissingBean
    MemoryClient memoryClient(MemoryConfig config, ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        if (mapper == null) {
            mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
        }
        return new MemoryClient(config.getGatewayUrl(), config.getTimeoutSec(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    MemoryManager memoryManager(MemoryConfig config,
                                ObjectProvider<MemoryRpcService> rpcServiceProvider,
                                ObjectProvider<MemoryClient> memoryClientProvider) {
        MemoryRpcService rpc = rpcServiceProvider.getIfAvailable();
        MemoryClient http = memoryClientProvider.getIfAvailable();
        log.info("MemoryManager initialized: useRpc={}, rpcBound={}, httpBound={}",
                config.isUseRpc(), rpc != null, http != null);
        return new MemoryManager(rpc, http, config.isUseRpc());
    }
}
