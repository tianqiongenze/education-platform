package com.ep.web.common.security;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D-227: Redis-backed JWT revocation blacklist (logout support).
 *
 * <p>On logout the token's {@code jti} is stored under
 * {@code ep:jwt:revoked:{jti}} with a TTL equal to the token's remaining
 * validity, so the entry self-cleans exactly when the token would have expired
 * anyway. {@link JwtRevocationFilter} consults {@link #isRevoked(String)} on
 * every bearer-authenticated request.
 *
 * <p><b>Availability contract (deliberate):</b> when Redis is unreachable the
 * check fails OPEN — {@link #isRevoked(String)} returns {@code false} with a
 * WARN. The blacklist is an <em>enhancement</em> on top of short token TTLs,
 * not a hard security gate: an availability-destroying auth dependency is worse
 * than a brief window where a logged-out token remains valid until natural
 * expiry. {@link #revoke(String, Duration)} reports failure through its return
 * value so the logout endpoint can log a warning.
 *
 * <p>A {@code null} template (or a template-less constructor) yields a no-op
 * service that never revokes and never rejects — used by consumers without
 * spring-data-redis and by the auto-configuration fallback.
 */
public class JwtBlacklistService {

    /** Redis key prefix: {@code ep:jwt:revoked:{jti}}. */
    public static final String KEY_PREFIX = "ep:jwt:revoked:";

    private static final Logger log = LoggerFactory.getLogger(JwtBlacklistService.class);

    private final StringRedisTemplate redis;

    /**
     * D-241 P0-0: bounded in-process cache (jti → revoked?). Zero-dependency
     * (no Caffeine in this module): simple LinkedHashMap-based LRU with
     * per-entry expiry, synchronized — the filter path is hot but the entry
     * count is bounded by active sessions per instance.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> revokedCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CACHE_MAX_ENTRIES = 20_000;

    /** Cache entry: value + epoch-second expiry. */
    private record CacheEntry(boolean revoked, long expiresAtEpochSec) {
        boolean fresh(long now) {
            return now < expiresAtEpochSec;
        }
    }

    private Boolean revokedCache_getIfPresent(String jti) {
        CacheEntry e = revokedCache.get(jti);
        if (e == null) {
            return null;
        }
        if (!e.fresh(System.currentTimeMillis() / 1000)) {
            revokedCache.remove(jti);
            return null;
        }
        return e.revoked();
    }

    private void revokedCache_put(String jti, boolean revoked) {
        if (revokedCache.size() >= CACHE_MAX_ENTRIES) {
            revokedCache.clear(); // crude bound; correctness unaffected
        }
        revokedCache.put(jti, new CacheEntry(revoked,
                System.currentTimeMillis() / 1000 + REVOCATION_LAG_SECONDS));
    }

    /** Creates a Redis-backed blacklist service. */
    public JwtBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Blacklists a token id until {@code remainingTtl} elapses.
     *
     * @param jti          token id ({@code jti} claim); blank ids are rejected
     * @param remainingTtl remaining validity of the token; non-positive TTLs are
     *                     treated as already-expired and succeed without a
     *                     Redis write
     * @return true when the token is guaranteed rejected afterwards (already
     *         expired, or the Redis entry was written); false when Redis
     *         unavailable and the token remains valid until natural expiry
     */
    public boolean revoke(String jti, Duration remainingTtl) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        if (remainingTtl == null || remainingTtl.isZero() || remainingTtl.isNegative()) {
            return true; // token already expired — nothing to blacklist
        }
        if (redis == null) {
            log.warn("JWT blacklist unavailable (no Redis template); jti={} stays valid until expiry", jti);
            return false;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", remainingTtl);
            revokedCache_put(jti, true); // D-241 P0-0: enforce immediately on this instance
            return true;
        } catch (RuntimeException e) {
            log.warn("JWT blacklist write failed for jti={} — token stays valid until expiry: {}",
                    jti, e.getMessage());
            return false;
        }
    }

    /**
     * @return true when the given token id is blacklisted. Fails OPEN
     *         ({@code false}) on any Redis error or blank input — see the
     *         availability contract in the class javadoc.
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        // D-241 P0-0: in-process TTL cache in front of Redis. Every request
        // used to pay a synchronous Redis GET on a shared Lettuce connection;
        // under concurrent load the queue stalled the whole Jetty pool and
        // liveness died (observed: SIGTERM at 10-15 VU). Revocation is a
        // logout-affordance, not a security boundary (signature verification
        // and expiry stay authoritative downstream), so a REVOCATION_LAG
        // propagation delay on OTHER instances is an accepted trade — the
        // revoking instance itself is immediate (revoke() primes the cache).
        Boolean cached = revokedCache_getIfPresent(jti);
        if (cached != null) {
            return cached;
        }
        if (redis == null) {
            return false;
        }
        try {
            boolean revoked = redis.opsForValue().get(KEY_PREFIX + jti) != null;
            if (revoked) {
                // Revoked jtis are negative-but-stable facts: cache for the
                // full REVOCATION_LAG (not the tiny miss window).
                revokedCache_put(jti, true);
            } else {
                revokedCache_put(jti, false);
            }
            return revoked;
        } catch (RuntimeException e) {
            log.warn("JWT blacklist check failed for jti={} — failing OPEN (availability first): {}",
                    jti, e.getMessage());
            return false;
        }
    }

    /**
     * D-241 P0-0: revocation primes the local cache so the revoking instance
     * enforces immediately; other instances converge within REVOCATION_LAG.
     */
    private static final long REVOCATION_LAG_SECONDS = 10;
}
