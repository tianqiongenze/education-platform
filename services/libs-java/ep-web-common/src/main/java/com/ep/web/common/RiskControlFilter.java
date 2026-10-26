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
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Risk-control P0-3 (v7, D-233): cross-cutting request-rate limiter for every
 * service that has ep-web-common on the classpath (registered by
 * {@link RiskControlAutoConfiguration}, switchable via {@code ep.risk.enabled},
 * default ON).
 *
 * <p><b>v7 upgrade — sliding window</b>: counts requests within any trailing
 * {@code windowSeconds} span instead of fixed calendar windows, eliminating
 * the 2× boundary burst (full budget at {@code t=59s} + full budget at
 * {@code t=60s}) that the previous fixed-window design allowed.</p>
 *
 * <p><b>v7 upgrade — trusted-proxy-aware client IP</b>: {@code X-Forwarded-For}
 * is client-controllable and spoofable. The filter now trusts the XFF chain
 * only when {@code ep.risk.trusted-proxies} (default {@code 0}) declares how
 * many proxies sit in front of this service; the client IP is then taken as
 * the (N+1)-th entry from the right end of the chain. With the default of 0
 * the header is ignored entirely and the socket address is used — a spoofed
 * XFF can no longer rotate the rate-limit identity to dodge the per-IP budget
 * (2025 industry standard, e.g. CDN/Ingress fronted deployments).</p>
 *
 * <p>Two independent dimensions — client IP and authenticated user — each with
 * their own budget; exceeding either answers {@code 429 Too Many Requests}
 * (+ {@code Retry-After}):</p>
 * <ul>
 *   <li>{@code /api/auth/**} — 5 requests/minute (credential-stuffing brake)</li>
 *   <li>every other path — 60 requests/minute</li>
 * </ul>
 *
 * <p>Limits and window are configurable: {@code ep.risk.limits.auth-per-minute},
 * {@code ep.risk.limits.default-per-minute}, {@code ep.risk.window-seconds},
 * {@code ep.risk.trusted-proxies}.</p>
 *
 * <p>Counting uses Redis ({@link RedisSlidingWindowCounter}, cluster-wide) when
 * a {@code StringRedisTemplate} is available; it degrades to a per-instance
 * in-memory sliding window when Redis is absent or unreachable, so the limiter
 * is always enforced (fail-closed for abuse, fail-open only in availability).</p>
 *
 * <p>Identity sources (trust boundary: the BFF AuthFilter strips client-supplied
 * values): resolved client IP (see trusted-proxy rule above) and
 * {@code X-User-Id} (JWT subject). IP and user counters are independent — one
 * abusive user behind a shared NAT cannot exhaust the whole IP budget and vice
 * versa.</p>
 */
public class RiskControlFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RiskControlFilter.class);

    /** Default: requests per minute allowed on authentication paths. */
    public static final int DEFAULT_AUTH_LIMIT = 5;
    /** Default: requests per minute allowed on every other path. */
    public static final int DEFAULT_LIMIT = 60;
    /** Default: sliding-window length in seconds. */
    public static final int DEFAULT_WINDOW_SECONDS = 60;
    /** Default: number of trusted reverse proxies in front of this service. */
    public static final int DEFAULT_TRUSTED_PROXIES = 0;

    /** Key prefix for both Redis and in-memory counters. */
    static final String KEY_PREFIX = "ep:risk:req:";

    private final WindowCounter counter;
    private final int authLimit;
    private final int defaultLimit;
    private final int windowSeconds;
    private final int trustedProxies;
    private final Clock clock;

    /**
     * @param counter       the window counter (Redis-backed or in-memory)
     * @param authLimit     per-minute budget for {@code /api/auth/**}
     * @param defaultLimit  per-minute budget for every other path
     * @param windowSeconds sliding-window length (seconds)
     */
    public RiskControlFilter(WindowCounter counter, int authLimit, int defaultLimit,
                             int windowSeconds) {
        this(counter, authLimit, defaultLimit, windowSeconds, DEFAULT_TRUSTED_PROXIES, Clock.systemUTC());
    }

    /** Full constructor: trusted-proxy count and injectable clock (tests). */
    public RiskControlFilter(WindowCounter counter, int authLimit, int defaultLimit,
                             int windowSeconds, int trustedProxies, Clock clock) {
        if (authLimit < 1 || defaultLimit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("limits and window must be positive");
        }
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("trustedProxies must be >= 0");
        }
        this.counter = counter;
        this.authLimit = authLimit;
        this.defaultLimit = defaultLimit;
        this.windowSeconds = windowSeconds;
        this.trustedProxies = trustedProxies;
        this.clock = clock;
    }

    RiskControlFilter(WindowCounter counter, int authLimit, int defaultLimit,
                      int windowSeconds, Clock clock) {
        this(counter, authLimit, defaultLimit, windowSeconds, DEFAULT_TRUSTED_PROXIES, clock);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        int limit = isAuthPath(path) ? authLimit : defaultLimit;

        String ip = clientIp(request, trustedProxies);
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
            // Counter hiccup: fail OPEN for availability — annotation-level and
            // gateway limits still apply.
            log.warn("Risk control counter failure for key {} (request allowed): {}", key, e.getMessage());
            return 0;
        }
    }

    private boolean isAuthPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.startsWith("/api/auth/") || p.startsWith("/api/v1/auth/");
    }

    /**
     * Trusted-proxy-aware client IP resolution (v7).
     *
     * <p>With {@code trustedProxies == 0} (default) {@code X-Forwarded-For} is
     * ignored — the socket remote address is returned, so a client cannot
     * forge/rotate its identity through a spoofed header. With
     * {@code trustedProxies == N > 0}: the socket peer is the last trusted
     * proxy, and the client IP is the N-th entry counting from the right end
     * of the XFF chain (each trusted proxy appended exactly one entry). If the
     * chain is shorter than N entries the socket address wins (defensive
     * fallback against header-stripping middleboxes).</p>
     */
    static String clientIp(HttpServletRequest request, int trustedProxies) {
        if (trustedProxies > 0) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] segments = xff.split(",");
                // socket peer = 1 trusted proxy; XFF entries counting from the
                // right: 1st = peer's upstream view, Nth = real client.
                int clientIndex = segments.length - trustedProxies;
                if (clientIndex >= 0 && clientIndex < segments.length) {
                    String candidate = segments[clientIndex].trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    /** Sliding-window counter abstraction (Redis or in-memory). */
    public interface WindowCounter {
        /** Record a hit and return the trailing-window count (1-based). */
        long increment(String key, int windowSeconds);

        /** Seconds until the oldest recorded hit falls out of the window. */
        long remainingSeconds(String key, int windowSeconds);
    }

    /**
     * In-memory per-instance <b>sliding</b> window (v7): per key, a deque of
     * epoch-milli timestamps; each increment purges entries older than the
     * window and appends the current time. Memory is bounded — maps that grow
     * beyond {@link #MAX_TRACKED_KEYS} trigger an opportunistic sweep of
     * fully-expired keys.
     */
    public static final class MemorySlidingWindowCounter implements WindowCounter {

        private static final int MAX_TRACKED_KEYS = 100_000;

        private final ConcurrentHashMap<String, HitLog> windows = new ConcurrentHashMap<>();
        private final Clock clock;

        public MemorySlidingWindowCounter() {
            this(Clock.systemUTC());
        }

        public MemorySlidingWindowCounter(Clock clock) {
            this.clock = clock;
        }

        @Override
        public long increment(String key, int windowSeconds) {
            long now = clock.millis();
            long windowMillis = windowSeconds * 1000L;
            HitLog hitLog = windows.compute(key, (k, old) -> {
                if (old == null) {
                    return new HitLog(windowMillis);
                }
                return old;
            });
            long count = hitLog.record(now);
            if (windows.size() > MAX_TRACKED_KEYS) {
                evictIdle(now, windowMillis);
            }
            return count;
        }

        @Override
        public long remainingSeconds(String key, int windowSeconds) {
            HitLog hitLog = windows.get(key);
            if (hitLog == null) {
                return windowSeconds;
            }
            long oldest = hitLog.oldestWithinWindow(clock.millis());
            if (oldest < 0) {
                return windowSeconds;
            }
            long remaining = 1 + (clock.millis() - oldest) / 1000;
            return Math.max(1, remaining);
        }

        private void evictIdle(long now, long windowMillis) {
            for (Iterator<Map.Entry<String, HitLog>> it = windows.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, HitLog> e = it.next();
                if (e.getValue().isIdle(now, windowMillis)) {
                    it.remove();
                }
            }
        }

        /** Timestamp deque for one key; guarded by its own monitor (cheap, per-key). */
        private static final class HitLog {
            private final ArrayDeque<Long> hits = new ArrayDeque<>();
            private final long windowMillis;
            private volatile long lastTouchMillis;

            HitLog(long windowMillis) {
                this.windowMillis = windowMillis;
            }

            synchronized long record(long now) {
                purgeExpired(now);
                hits.addLast(now);
                lastTouchMillis = now;
                return hits.size();
            }

            synchronized long oldestWithinWindow(long now) {
                purgeExpired(now);
                return hits.isEmpty() ? -1 : hits.peekFirst();
            }

            synchronized boolean isIdle(long now, long windowMillis) {
                return now - lastTouchMillis >= windowMillis;
            }

            private void purgeExpired(long now) {
                long cutoff = now - windowMillis;
                while (!hits.isEmpty() && hits.peekFirst() <= cutoff) {
                    hits.pollFirst();
                }
            }
        }
    }

    /**
     * Fixed-window fallback kept for source compatibility with deployments
     * that explicitly opt into the legacy algorithm. New deployments should
     * use {@link MemorySlidingWindowCounter}.
     */
    @Deprecated
    public static final class MemoryWindowCounter implements WindowCounter {

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

        private static final class Window {
            final long resetAt;
            final AtomicLong count = new AtomicLong(1);

            Window(long resetAt) {
                this.resetAt = resetAt;
            }
        }
    }

    /** Counting helper used by tests to obtain fresh sliding counters. */
    public static WindowCounter memoryCounter(Clock clock) {
        return new MemorySlidingWindowCounter(clock);
    }

    /** Counter factory used by the auto-configuration. */
    public static WindowCounter memoryCounter() {
        return new MemorySlidingWindowCounter();
    }

    /** Supplier indirection for the auto-config (keeps constructor simple). */
    public static RiskControlFilter withCounter(Supplier<WindowCounter> counterSupplier,
                                                int authLimit, int defaultLimit, int windowSeconds) {
        return new RiskControlFilter(counterSupplier.get(), authLimit, defaultLimit, windowSeconds);
    }
}
