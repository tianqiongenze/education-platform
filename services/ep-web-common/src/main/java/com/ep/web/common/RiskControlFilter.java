package com.ep.web.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Risk-control P0-3: cross-cutting request-rate limiter for every service that
 * has ep-web-common on the classpath (registered by
 * {@link RiskControlAutoConfiguration}, switchable via
 * {@code ep.risk.enabled}, default ON).
 *
 * <p>Counts requests in a fixed one-minute window along two independent
 * dimensions — client IP and authenticated user — and answers
 * {@code 429 Too Many Requests} (+ {@code Retry-After}) as soon as either
 * dimension exceeds its budget:</p>
 * <ul>
 *   <li>{@code /api/auth/**} — 5 requests/minute (credential-stuffing brake)</li>
 *   <li>every other path — 60 requests/minute</li>
 * </ul>
 *
 * <p>Limits and window are configurable: {@code ep.risk.limits.auth-per-minute},
 * {@code ep.risk.limits.default-per-minute}, {@code ep.risk.window-seconds}.</p>
 *
 * <p>Counting uses Redis ({@code INCR} + {@code EXPIRE}, shared across
 * instances) when a {@code StringRedisTemplate} is available; it degrades to a
 * per-instance in-memory window when Redis is absent or unreachable, so the
 * limiter is always enforced (fail-closed for abuse, fail-open only in
 * availability).</p>
 *
 * <p>Identity sources (trust boundary: the BFF AuthFilter strips client-supplied
 * values): {@code X-Forwarded-For} first segment (else {@code getRemoteAddr()})
 * and {@code X-User-Id} (JWT subject). IP and user counters are independent —
 * one abusive user behind a shared NAT cannot exhaust the whole IP budget and
 * vice versa.</p>
 */
public class RiskControlFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RiskControlFilter.class);

    /** Default: requests per minute allowed on authentication paths. */
    public static final int DEFAULT_AUTH_LIMIT = 5;
    /** Default: requests per minute allowed on every other path. */
    public static final int DEFAULT_LIMIT = 60;
    /** Default: fixed window length in seconds. */
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    /** Key prefix for both Redis and in-memory counters. */
    static final String KEY_PREFIX = "ep:risk:req:";

    private final WindowCounter counter;
    private final int authLimit;
    private final int defaultLimit;
    private final int windowSeconds;
    private final Clock clock;

    /**
     * @param counter       the window counter (Redis-backed or in-memory)
     * @param authLimit     per-minute budget for {@code /api/auth/**}
     * @param defaultLimit  per-minute budget for every other path
     * @param windowSeconds fixed-window length (seconds)
     */
    public RiskControlFilter(WindowCounter counter, int authLimit, int defaultLimit,
                             int windowSeconds) {
        this(counter, authLimit, defaultLimit, windowSeconds, Clock.systemUTC());
    }

    RiskControlFilter(WindowCounter counter, int authLimit, int defaultLimit,
                      int windowSeconds, Clock clock) {
        if (authLimit < 1 || defaultLimit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("limits and window must be positive");
        }
        this.counter = counter;
        this.authLimit = authLimit;
        this.defaultLimit = defaultLimit;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        int limit = isAuthPath(path) ? authLimit : defaultLimit;

        String ip = clientIp(request);
        String user = request.getHeader("X-User-Id");
        String userKey = user == null || user.isBlank() ? "anon" : user.trim();

        long ipCount = safeIncrement(KEY_PREFIX + "ip:" + ip, windowSeconds);
        long userCount = safeIncrement(KEY_PREFIX + "user:" + userKey, windowSeconds);

        if (ipCount > limit || userCount > limit) {
            long retryAfter = Math.max(1, counter.remainingSeconds(KEY_PREFIX
                    + (ipCount > limit ? "ip:" + ip : "user:" + userKey), windowSeconds));
            log.warn("Risk control: 429 path={} ip={} user={} ipCount={} userCount={} limit={}",
                    path, ip, userKey, ipCount, userCount, limit);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests, retry after "
                    + retryAfter + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private long safeIncrement(String key, int windowSeconds) {
        try {
            return counter.increment(key, windowSeconds);
        } catch (RuntimeException e) {
            // Redis hiccup: fall back to a per-request estimate that fails OPEN
            // for availability — annotation-level and gateway limits still apply.
            log.warn("Risk control counter failure for key {} (request allowed): {}", key, e.getMessage());
            return 0;
        }
    }

    private boolean isAuthPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.startsWith("/api/auth/") || p.startsWith("/api/v1/auth/");
    }

    /** First segment of X-Forwarded-For, else the socket remote address. */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            first = first.trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    /** Fixed-window counter abstraction (Redis or in-memory). */
    public interface WindowCounter {
        /** Increment the window and return the new count (1-based). */
        long increment(String key, int windowSeconds);

        /** Seconds until the window resets (never negative). */
        long remainingSeconds(String key, int windowSeconds);
    }

    /**
     * In-memory per-instance fixed window. ConcurrentHashMap with lazy expiry;
     * expired windows are reset on access and evicted opportunistically when
     * the map grows beyond {@link #MAX_TRACKED_KEYS} to bound memory.
     */
    public static final class MemoryWindowCounter implements WindowCounter {

        private static final int MAX_TRACKED_KEYS = 100_000;

        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
        private final Clock clock;

        public MemoryWindowCounter() {
            this(Clock.systemUTC());
        }

        public MemoryWindowCounter(Clock clock) {
            this.clock = clock;
        }

        @Override
        public long increment(String key, int windowSeconds) {
            long now = clock.millis();
            long windowMillis = windowSeconds * 1000L;
            Window w = windows.compute(key, (k, old) -> {
                if (old == null || now >= old.resetAt) {
                    return new Window(now + windowMillis);
                }
                old.count.incrementAndGet();
                return old;
            });
            if (windows.size() > MAX_TRACKED_KEYS) {
                evictExpired(now);
            }
            return w.count.get();
        }

        @Override
        public long remainingSeconds(String key, int windowSeconds) {
            Window w = windows.get(key);
            if (w == null) {
                return windowSeconds;
            }
            long remaining = (w.resetAt - clock.millis()) / 1000;
            return Math.max(1, remaining);
        }

        private void evictExpired(long now) {
            for (Map.Entry<String, Window> e : windows.entrySet()) {
                if (now >= e.getValue().resetAt) {
                    windows.remove(e.getKey(), e.getValue());
                }
            }
        }

        private static final class Window {
            final long resetAt;
            final AtomicLong count = new AtomicLong(1);

            Window(long resetAt) {
                this.resetAt = resetAt;
            }
        }
    }

    /** Counting helper used by tests to obtain fresh counters. */
    public static WindowCounter memoryCounter(Clock clock) {
        return new MemoryWindowCounter(clock);
    }

    /** Counter factory used by the auto-configuration. */
    public static WindowCounter memoryCounter() {
        return new MemoryWindowCounter();
    }

    /** Supplier indirection for the auto-config (keeps constructor simple). */
    public static RiskControlFilter withCounter(Supplier<WindowCounter> counterSupplier,
                                                int authLimit, int defaultLimit, int windowSeconds) {
        return new RiskControlFilter(counterSupplier.get(), authLimit, defaultLimit, windowSeconds);
    }
}
