package com.ep.web.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Risk-control P0-3: Redis-backed fixed-window counter ({@code INCR} +
 * {@code EXPIRE}) shared by every service instance that connects to the same
 * Redis, so the per-IP / per-user budget is cluster-wide.
 *
 * <p>Degradation: if Redis is unreachable ({@link RedisConnectionFailureException}
 * or any infrastructure error) the counter transparently switches to a
 * per-instance {@link RiskControlFilter.MemoryWindowCounter} so the rate limit
 * stays enforced — per-instance instead of cluster-wide. The switch is sticky
 * for the life of the counter instance; Redis recovery is picked up on the next
 * filter bean refresh (restart). Counts recorded in Redis before the switch are
 * not carried over — a deliberate, documented trade-off (availability of the
 * 429 path over cross-instance precision for a single window).</p>
 */
public class RedisWindowCounter implements RiskControlFilter.WindowCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisWindowCounter.class);

    private final StringRedisTemplate redis;
    private final RiskControlFilter.MemoryWindowCounter fallback = new RiskControlFilter.MemoryWindowCounter();
    private volatile boolean degraded = false;

    public RedisWindowCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, int windowSeconds) {
        if (degraded) {
            return fallback.increment(key, windowSeconds);
        }
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return fallback.increment(key, windowSeconds);
            }
            if (count == 1L) {
                // First hit of the window: arm the expiry. A concurrent first-hit
                // race only re-arms the same TTL — harmless.
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count;
        } catch (RedisConnectionFailureException e) {
            degrade(e);
            return fallback.increment(key, windowSeconds);
        } catch (RuntimeException e) {
            // Any other Redis infrastructure error (timeout, command error):
            // degrade rather than fail the request path.
            degrade(e);
            return fallback.increment(key, windowSeconds);
        }
    }

    @Override
    public long remainingSeconds(String key, int windowSeconds) {
        if (degraded) {
            return fallback.remainingSeconds(key, windowSeconds);
        }
        try {
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0) {
                return windowSeconds;
            }
            return Math.max(1, ttl);
        } catch (RuntimeException e) {
            degrade(e);
            return fallback.remainingSeconds(key, windowSeconds);
        }
    }

    /** Visible for tests. */
    boolean isDegraded() {
        return degraded;
    }

    private void degrade(RuntimeException cause) {
        if (!degraded) {
            degraded = true;
            log.warn("Risk control: Redis unavailable, degrading to per-instance in-memory window: {}",
                    cause.getMessage());
        }
    }
}
