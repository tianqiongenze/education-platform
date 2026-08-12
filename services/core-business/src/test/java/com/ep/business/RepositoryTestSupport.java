package com.ep.business;

import com.ep.business.event.PulsarClientProperties;
import com.ep.business.event.PulsarEventPublisher;
import com.ep.cache.CacheService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * D-132: test-only beans for the {@code @DataJpaTest} slice tests
 * ({@code CourseRepositoryTest}, {@code UserRepositoryTest}).
 * <p>
 * Those slice tests bootstrap the full {@code BusinessApplication} context (so
 * {@code @DubboService} beans like {@code CourseServiceImpl} are instantiated),
 * but the JPA slice does not register the event-infrastructure beans
 * ({@link PulsarEventPublisher}, {@link PulsarClientProperties}) or the
 * {@link CacheService} those services depend on. Rather than load a real Pulsar
 * client or Redis-backed cache, this config supplies a no-op publisher (with
 * {@code ep.pulsar.enabled=false}) and a CacheService backed by null
 * providers (all lookups miss → loader invoked, put/evict are no-ops) so the
 * context wires and the repository layer is exercised in isolation.
 * <p>
 * EP_DEV_ONLY-safe: never touches a broker or Redis.
 */
@TestConfiguration
public class RepositoryTestSupport {

    @Bean
    CacheService cacheService() {
        ObjectProvider<org.springframework.cache.CacheManager> cmProvider = new EmptyObjectProvider<>();
        ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, Object>> redisProvider = new EmptyObjectProvider<>();
        ObjectProvider<com.ep.cache.CacheBroadcast> broadcastProvider = new EmptyObjectProvider<>();
        return new CacheService(cmProvider, redisProvider, broadcastProvider);
    }

    @Bean
    PulsarClientProperties pulsarClientProperties() {
        return new PulsarClientProperties();
    }

    @Bean
    PulsarEventPublisher pulsarEventPublisher(PulsarClientProperties properties) {
        // pulsarEnabled=false → the publisher never touches Pulsar (no-op on init + publish).
        return new PulsarEventPublisher(null, properties, false);
    }

    /**
     * Minimal {@link ObjectProvider} that always returns null (no bean available).
     * Used to construct a CacheService where all infrastructure providers are
     * absent, so every cache lookup misses and the loader is invoked directly.
     */
    static final class EmptyObjectProvider<T> implements ObjectProvider<T> {
        @Override public T getObject(Object... args) { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
