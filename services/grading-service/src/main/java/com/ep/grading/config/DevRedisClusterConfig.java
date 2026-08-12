package com.ep.grading.config;

import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Function;

/**
 * D-077: Dev-only Redis cluster configuration for local port-forwarded K8s Redis cluster.
 * <p>
 * The shared K8s Redis is a 3-master cluster (redis-cluster-new-0/1/2). When port-forwarded
 * to localhost:6379/6380/6381, the cluster nodes still report their K8s internal DNS names
 * in CLUSTER NODES output. Lettuce's default topology refresh discovers these and tries to
 * connect to unreachable K8s DNS hostnames, causing MOVED redirect failures.
 * <p>
 * This config uses {@link MappingSocketAddressResolver} to remap K8s DNS names to
 * localhost ports, so Lettuce can follow MOVED redirects correctly.
 * <p>
 * Production uses standard Spring Boot Redis autoconfiguration (single property-driven config).
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "ep.dev.redis-cluster.enabled", matchIfMissing = false)
public class DevRedisClusterConfig {

    /**
     * K8s Redis cluster node DNS suffix — all 3 masters share this pattern.
     */
    private static final String K8S_REDIS_PREFIX = "redis-cluster-new-";

    /**
     * Remaps K8s cluster node DNS names to localhost ports.
     * redis-cluster-new-0 → 127.0.0.1:6379
     * redis-cluster-new-1 → 127.0.0.1:6380
     * redis-cluster-new-2 → 127.0.0.1:6381
     */
    private HostAndPort remapK8sRedisHost(HostAndPort input) {
        String host = input.getHostText();
        if (host != null && host.startsWith(K8S_REDIS_PREFIX)) {
            // Extract node index: "redis-cluster-new-0" → "0"
            String remainder = host.substring(K8S_REDIS_PREFIX.length());
            int dotIdx = remainder.indexOf('.');
            String nodeIndex = dotIdx > 0 ? remainder.substring(0, dotIdx) : remainder;
            int localPort = switch (nodeIndex) {
                case "0" -> 6379;
                case "1" -> 6380;
                case "2" -> 6381;
                default -> 6379;
            };
            return HostAndPort.of("127.0.0.1", localPort);
        }
        return input;
    }

    @Bean(destroyMethod = "shutdown")
    public ClientResources devRedisClientResources() {
        Function<HostAndPort, HostAndPort> mapper = this::remapK8sRedisHost;
        DefaultClientResources.Builder builder = DefaultClientResources.builder();
        builder.socketAddressResolver(MappingSocketAddressResolver.create(mapper));
        return builder.build();
    }

    @Bean
    public RedisConnectionFactory devRedisConnectionFactory(ObjectProvider<ClientResources> clientResourcesProvider) {
        RedisClusterConfiguration config = new RedisClusterConfiguration();
        config.clusterNode(new RedisNode("127.0.0.1", 6379));
        config.clusterNode(new RedisNode("127.0.0.1", 6380));
        config.clusterNode(new RedisNode("127.0.0.1", 6381));
        config.setMaxRedirects(3);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        ClientResources cr = clientResourcesProvider.getIfAvailable();
        if (cr != null) {
            factory.setClientResources(cr);
        }
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
