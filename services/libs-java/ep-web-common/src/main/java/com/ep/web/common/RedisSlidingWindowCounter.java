package com.ep.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Risk-control v7 (D-233): Redis-backed <b>sliding-window</b> counter shared
 * by every service instance that connects to the same Redis, so the per-IP /
 * per-user budget is cluster-wide.
 *
 * <p><b>Why sliding window (v7 upgrade)</b>: the previous fixed-window
 * implementation (INCR + EXPIRE) allowed a 2× burst at window boundaries —
 * an attacker could spend a full budget at {@code t=59s} and another full
 * budget at {@code t=60s}. The sliding window (Redis official ZSET pattern:
 * ZADD + ZREMRANGEBYSCORE + ZCARD) counts requests within any trailing
 * {@code windowSeconds} span, eliminating the boundary burst entirely.</p>
 *
 * <p><b>Half-open self-healing (v7 upgrade)</b>: the previous implementation
 * degraded to in-memory counting permanently until restart. This version
 * re-probes Redis after a 30s cooldown: the first request after the cooldown
 * attempts the Redis path again and restores cluster-wide counting on
 * success. During the cooldown all increments stay on the in-memory fallback
 * — no per-request probing storm.</p>
 *
 * <p><b>Key layout</b>: {@code ep:risk:req:{scope}:{id}} ZSET where members
 * are per-request UUIDs (so concurrent identical timestamps don't collapse)
 * and scores are epoch millis. Expiry equals the window length; a key that
 * receives no traffic self-destructs via Redis TTL.</p>
 */
public class RedisSlidingWindowCounter implements RiskControlFilter.WindowCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowCounter.class);

    /** Cooldown before the next half-open probe after a Redis failure (ms). */
    static final long RECOVERY_COOLDOWN_MS = 30_000L;

    private final StringRedisTemplate redis;
    private final RiskControlFilter.WindowCounter fallback = new RiskControlFilter.MemorySlidingWindowCounter();
    private final ClockNanos clock;
    private volatile boolean degraded = false;
    private volatile long lastFailureNanos = 0;

    /** Injectable monotonic clock for tests. */
    interface ClockNanos {
        long now();
    }

    private static final ClockNanos SYSTEM_NANOS = System::nanoTime;

    public RedisSlidingWindowCounter(StringRedisTemplate redis) {
        this(redis, SYSTEM_NANOS);
    }

    /** Test-friendly constructor: injects the monotonic clock. */
    RedisSlidingWindowCounter(StringRedisTemplate redis, ClockNanos clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long increment(String key, int windowSeconds) {
        if (!degraded || shouldProbe()) {
            try {
                long count = incrementRedis(key, windowSeconds);
                if (degraded) {
                    log.info("Risk control: Redis recovered, restoring cluster-wide sliding window");
                }
                degraded = false;
                return count;
            } catch (RedisConnectionFailureException e) {
                degrade(e);
            } catch (RuntimeException e) {
                // Any other Redis infrastructure error (timeout, command error):
                // degrade rather than fail the request path.
                degrade(e);
            }
        }
        return fallback.increment(key, windowSeconds);
    }

    @Override
    public long remainingSeconds(String key, int windowSeconds) {
        if (!degraded) {
            try {
                Long ttl = redis.getExpire(key);
                if (ttl != null && ttl >= 0) {
                    return Math.max(1, ttl);
                }
                return windowSeconds;
            } catch (RuntimeException e) {
                degrade(e);
            }
        }
        return fallback.remainingSeconds(key, windowSeconds);
    }

    /**
     * Sliding-window increment via ZSET (Redis official rate-limiter pattern):
     * ZADD now / ZREMRANGEBYSCORE purge older-than-window / EXPIRE idle-key
     * self-destruct, then ZCARD to read the trailing-window count.
     */
    private long incrementRedis(String key, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;
        String member = UUID.randomUUID().toString();

        redis.opsForZSet().add(key, member, now);
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStart - 1);
        redis.expire(key, java.time.Duration.ofSeconds(windowSeconds));

        Long count = redis.opsForZSet().zCard(key);
        return count == null ? 1L : count;
    }

    private boolean shouldProbe() {
        return clock.now() - lastFailureNanos >= RECOVERY_COOLDOWN_MS * 1_000_000L;
    }

    /** Visible for tests. */
    boolean isDegraded() {
        return degraded;
    }

    private void degrade(RuntimeException cause) {
        lastFailureNanos = clock.now();
        if (!degraded) {
            degraded = true;
            log.warn("Risk control: Redis unavailable, degrading to per-instance in-memory sliding window "
                    + "(half-open re-probe in {}s): {}", RECOVERY_COOLDOWN_MS / 1000, cause.getMessage());
        }
    }

    /** UTF-8 helper kept for potential byte-level pipeline optimization. */
    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
