package com.ep.grading.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * D-048 P0-6: Redis-backed idempotency key store with 24h TTL.
 * <p>
 * Replaces the previous in-process {@code ConcurrentHashMap<String, Long>} index, which was
 * lost on restart and not shared across instances. Each key stores the id of the first
 * request that claimed it; subsequent callers receive the same id and fetch the persisted
 * entity from the repository instead of duplicating the side-effect.
 */
@Component
public class IdempotencyService {

    /** 24 hours. */
    public static final Duration TTL = Duration.ofHours(24);

    private static final String PREFIX = "ep:idem:";

    private final ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;

    public IdempotencyService(ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
    }

    /**
     * Attempt to claim the given idempotency key. When Redis is unavailable (unit tests,
     * local dev without infra), falls back to returning {@code null} — callers must then
     * rely on the unique DB constraint as the second line of defense.
     *
     * @param key        idempotency key supplied by the client (ignored when blank)
     * @param valueId    the entity id to associate with the key
     * @return the previously stored id, or {@code null} when the key was newly claimed
     *         (or Redis is unavailable)
     */
    @Nullable
    public String claimOrFetch(@Nullable String key, @Nullable String valueId) {
        if (key == null || key.isBlank()) {
            return null;
        }
        RedisTemplate<String, String> template = redisTemplateProvider.getIfAvailable();
        if (template == null) {
            return null;
        }
        String redisKey = PREFIX + key;
        ValueOperations<String, String> ops = template.opsForValue();
        String existing = ops.get(redisKey);
        if (existing != null) {
            return existing;
        }
        // D-077: when valueId is null (probe-only call to check if key exists), skip the
        // setIfAbsent — we're not claiming yet, just checking. The caller will re-invoke
        // with a real valueId after persisting the entity.
        if (valueId == null) {
            return null;
        }
        Boolean set = ops.setIfAbsent(redisKey, valueId, TTL);
        if (Boolean.TRUE.equals(set)) {
            return null;
        }
        return ops.get(redisKey);
    }
}
