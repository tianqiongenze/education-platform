package com.ep.data.nlquery;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Rate limiter for NL Query requests (D-190, §12.4.3 security).
 *
 * <p>Limits each user to {@code maxRequests} queries per {@code windowSeconds}
 * sliding window. Uses Redis in production (via {@link StringRedisTemplate})
 * for distributed rate limiting; falls back to an in-memory
 * {@link ConcurrentHashMap} in dev/CI (single-instance limiting).
 *
 * <p><b>EP_DEV_ONLY guard:</b> When {@code EP_DEV_ONLY} is unset, blank,
 * or {@code "true"}, the in-memory limiter is used. Production deployments
 * set {@code EP_DEV_ONLY=false} and configure a Redis instance.
 *
 * <p>Thread-safety (D-039): uses {@link ConcurrentHashMap} and
 * {@link AtomicInteger} — no synchronized blocks.
 *
 * <p>Default limits: 30 requests per 60 seconds per user.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Default max requests per window. */
    public static final int DEFAULT_MAX_REQUESTS = 30;

    /** Default window size in seconds. */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    private final int maxRequests;
    private final int windowSeconds;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    /** In-memory rate-limit state: userId → [count, windowStartEpochMs]. */
    private final ConcurrentHashMap<String, long[]> inMemoryState = new ConcurrentHashMap<>();

    /**
     * Construct with defaults and Redis provider.
     *
     * @param redisProvider Spring {@link ObjectProvider} for
     *        {@link StringRedisTemplate}; may be null in tests
     */
    public RateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        this(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW_SECONDS, redisProvider);
    }

    /** Default constructor — dev/CI mode (no Redis, default limits). */
    public RateLimiter() {
        this(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW_SECONDS, null);
    }

    /**
     * Construct with custom limits.
     *
     * @param maxRequests    max requests per window
     * @param windowSeconds  window size in seconds
     * @param redisProvider  Redis template provider (null for in-memory)
     */
    public RateLimiter(int maxRequests, int windowSeconds, ObjectProvider<StringRedisTemplate> redisProvider) {
        this.maxRequests = Math.max(1, maxRequests);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.redisProvider = redisProvider;
    }

    /**
     * Check whether a user is allowed to make a request.
     *
     * <p>If allowed, the request counter is incremented. If the window has
     * elapsed since the first request in the current window, the counter
     * resets.
     *
     * @param userId user identifier
     * @return {@code true} if the request is allowed, {@code false} if rate-limited
     */
    public boolean allowRequest(String userId) {
        if (userId == null || userId.isBlank()) {
            return true; // no user ID → no rate limit (handled upstream)
        }

        String redisKey = "nlquery:ratelimit:" + userId;

        StringRedisTemplate redis = resolveRedis();
        if (redis != null && !isDevOnly()) {
            return allowRedis(redis, redisKey);
        }
        return allowInMemory(userId);
    }

    /**
     * Get the current request count for a user in the active window.
     *
     * @param userId user identifier
     * @return current count, or 0 if no requests in the current window
     */
    public int getCurrentCount(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long[] state = inMemoryState.get(userId);
        if (state == null) {
            return 0;
        }
        long windowStart = state[0];
        long count = state[1];
        if (now - windowStart >= windowSeconds * 1000L) {
            return 0;
        }
        return (int) count;
    }

    /** Get the max requests per window. */
    public int getMaxRequests() {
        return maxRequests;
    }

    /** Get the window size in seconds. */
    public int getWindowSeconds() {
        return windowSeconds;
    }

    // ─── Internal helpers ─────────────────────────────────────────────

    private boolean allowRedis(StringRedisTemplate redis, String key) {
        try {
            Long current = redis.opsForValue().increment(key);
            if (current == null) {
                return true; // Redis error → allow
            }
            if (current == 1L) {
                // First request in the window — set expiry.
                redis.expire(key, java.time.Duration.ofSeconds(windowSeconds));
            }
            if (current > maxRequests) {
                log.debug("[RateLimiter] Rate limited (Redis): key={} count={} max={}",
                        key, current, maxRequests);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[RateLimiter] Redis error, falling back to in-memory: {}", e.getMessage());
            return allowInMemory(key);
        }
    }

    private boolean allowInMemory(String userId) {
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;

        while (true) {
            long[] existing = inMemoryState.get(userId);
            if (existing == null) {
                long[] fresh = {now, 1L};
                long[] prev = inMemoryState.putIfAbsent(userId, fresh);
                if (prev == null) {
                    return true; // first request
                }
                existing = prev;
            }

            long windowStart = existing[0];
            long count = existing[1];

            if (now - windowStart >= windowMs) {
                // Window expired — reset.
                long[] reset = {now, 1L};
                if (inMemoryState.replace(userId, existing, reset)) {
                    return true;
                }
                // CAS failed — retry.
                continue;
            }

            if (count >= maxRequests) {
                log.debug("[RateLimiter] Rate limited (in-memory): user={} count={} max={}",
                        userId, count, maxRequests);
                return false;
            }

            long[] updated = {windowStart, count + 1};
            if (inMemoryState.replace(userId, existing, updated)) {
                return true;
            }
            // CAS failed — retry.
        }
    }

    private StringRedisTemplate resolveRedis() {
        if (redisProvider == null) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }

    private static boolean isDevOnly() {
        String dev = System.getenv("EP_DEV_ONLY");
        if (dev == null || dev.isBlank()) {
            return true;
        }
        return !"false".equalsIgnoreCase(dev);
    }
}
