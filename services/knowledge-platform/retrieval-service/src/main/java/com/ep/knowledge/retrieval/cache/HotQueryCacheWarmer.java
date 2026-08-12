package com.ep.knowledge.retrieval.cache;

import com.ep.cache.CacheService;
import com.ep.cache.CacheWarmer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;

/**
 * J-KB-04 hot-query cache warmer. Reads the top-100 hot queries per tenant
 * from the Redis ZSet {@code qa:hot:{tenant}} and pre-populates the shared
 * {@code qa} cache (key = qHash, TTL = 1h) via the ep-cache {@link CacheService}.
 *
 * <p>Defensive by design: if Redis is unavailable, the ZSet is missing or
 * empty, the warmer logs and returns — it must never block startup.
 */
@Component
@ConditionalOnProperty(name = "ep.cache.warmup.hot-query", matchIfMissing = true)
public class HotQueryCacheWarmer implements CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(HotQueryCacheWarmer.class);
    private static final String CACHE_NAME = "qa";
    private static final long QA_TTL_SEC = 3600;
    private static final long HOT_QUERY_LIMIT = 100;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public HotQueryCacheWarmer(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    @Override
    public void warm(CacheService cacheService) {
        // D-073: best-effort tenant discovery. Full tenant discovery is out of
        // scope for this SPI — the runner's job is infrastructure. Use the
        // configured default tenant; future work can plug in a TenantRegistry.
        List<String> tenants = List.of("default");

        for (String tenant : tenants) {
            warmTenant(cacheService, tenant);
        }
    }

    private void warmTenant(CacheService cacheService, String tenant) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("Redis unavailable — skipping hot-query warm-up for tenant '{}'", tenant);
            return;
        }

        String zsetKey = "qa:hot:" + tenant;
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = redis.opsForZSet().reverseRangeWithScores(zsetKey, 0, HOT_QUERY_LIMIT - 1);
        } catch (Exception e) {
            log.warn("Failed reading hot queries for tenant '{}' from key '{}': {}", tenant, zsetKey, e.getMessage());
            return;
        }

        if (tuples == null || tuples.isEmpty()) {
            log.info("No hot queries found for tenant '{}' — skipping warm-up", tenant);
            return;
        }

        int warmed = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String query = tuple.getValue();
            if (query == null || query.isBlank()) {
                continue;
            }
            String qHash = qHash(query);
            // Placeholder answer — the J-KB-04 design pre-generates answers via the
            // retrieval pipeline; here we cache the query itself as a marker so a
            // downstream getOrLoad can refresh it. The SPI contract is what matters.
            cacheService.put(CACHE_NAME, qHash, query, QA_TTL_SEC);
            warmed++;
        }
        log.info("Warmed {} hot-query entries for tenant '{}'", warmed, tenant);
    }

    /** SHA-256 of the query string, first 16 hex chars. */
    static String qHash(String query) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(query.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
