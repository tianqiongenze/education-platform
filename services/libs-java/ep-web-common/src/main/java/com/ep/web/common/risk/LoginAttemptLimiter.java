package com.ep.web.common.risk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D-219: Login-failure lockout component (risk-control infrastructure).
 *
 * <p>Counts failed login attempts per principal (username / phone / device id)
 * and locks the principal for a cooldown window after {@code maxAttempts}
 * failures. Successful login resets the counter.
 *
 * <p>Two backends:
 * <ul>
 *   <li><b>Redis</b> (production): {@code INCR} + {@code EXPIRE} on
 *       {@code user:login:fail:{principal}} — cluster-wide counters. Redis
 *       failures degrade to the in-memory backend (protection never
 *       disappears, scope shrinks to the node).</li>
 *   <li><b>In-memory</b> (fallback / unit tests): {@link ConcurrentHashMap}
 *       window with lazy expiry — single-node only.</li>
 * </ul>
 *
 * <p>The component exposes the decision API ({@link #isLocked},
 * {@link #recordFailure}, {@link #recordSuccess}); it does not perform
 * authentication. The login endpoint (planned) wires this in front of the
 * credential check.
 */
public class LoginAttemptLimiter {

    /** Redis key template for the failure counter: {@code user:login:fail:{principal}}. */
    public static final String KEY_TEMPLATE = "user:login:fail:%s";

    private final int maxAttempts;
    private final long lockoutMillis;
    private final StringRedisTemplate redis;

    // In-memory fallback state: principal -> (count, lockedUntil).
    private final Map<String, FailState> localState = new ConcurrentHashMap<>();

    /** One failure-tracking window for a principal. */
    static final class FailState {
        final AtomicInteger count = new AtomicInteger();
        volatile long lockedUntil;
    }

    /**
     * @param maxAttempts   failures allowed before lockout
     * @param lockoutMillis lockout duration in milliseconds
     * @param redis         optional Redis template (null → local mode)
     */
    public LoginAttemptLimiter(int maxAttempts, long lockoutMillis, StringRedisTemplate redis) {
        this.maxAttempts = maxAttempts;
        this.lockoutMillis = lockoutMillis;
        this.redis = redis;
    }

    /**
     * @return true when the principal is currently locked out
     */
    public boolean isLocked(String principal) {
        if (principal == null || principal.isBlank()) {
            return false;
        }
        if (redis != null) {
            try {
                String count = redis.opsForValue().get(KEY_TEMPLATE.formatted(principal));
                return count != null && Long.parseLong(count) >= maxAttempts;
            } catch (RedisConnectionFailureException e) {
                // fall through to local state
            }
        }
        return isLockedLocal(principal);
    }

    private boolean isLockedLocal(String principal) {
        FailState st = localState.get(principal);
        if (st == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (st.lockedUntil > now) {
            return true;
        }
        if (st.lockedUntil > 0) {
            // Lockout expired: reset lazily.
            st.count.set(0);
            st.lockedUntil = 0;
        }
        return false;
    }

    /**
     * Records one failed attempt. When the threshold is reached the principal
     * is locked for the cooldown window.
     *
     * @return true if this failure triggered a lockout
     */
    public boolean recordFailure(String principal) {
        if (principal == null || principal.isBlank()) {
            return false;
        }
        if (redis != null) {
            try {
                String key = KEY_TEMPLATE.formatted(principal);
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, java.time.Duration.ofMillis(Math.max(1000, lockoutMillis)));
                }
                return count != null && count >= maxAttempts;
            } catch (RedisConnectionFailureException e) {
                // fall through to local state
            }
        }
        long now = System.currentTimeMillis();
        FailState st = localState.computeIfAbsent(principal, k -> new FailState());
        synchronized (st) {
            if (st.lockedUntil > 0 && st.lockedUntil <= now) {
                st.count.set(0);
                st.lockedUntil = 0;
            }
            int count = st.count.incrementAndGet();
            if (count >= maxAttempts) {
                st.lockedUntil = now + lockoutMillis;
                return true;
            }
            return false;
        }
    }

    /**
     * Records a successful login — clears the failure counter.
     */
    public void recordSuccess(String principal) {
        if (principal == null || principal.isBlank()) {
            return;
        }
        if (redis != null) {
            try {
                redis.delete(KEY_TEMPLATE.formatted(principal));
                return;
            } catch (RedisConnectionFailureException e) {
                // fall through to local state
            }
        }
        localState.remove(principal);
    }

    /**
     * @return configured failures-before-lockout threshold
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @return lockout window in milliseconds
     */
    public long lockoutMillis() {
        return lockoutMillis;
    }
}
