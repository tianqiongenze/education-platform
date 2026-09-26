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
        if (jti == null || jti.isBlank() || redis == null) {
            return false;
        }
        try {
            return redis.opsForValue().get(KEY_PREFIX + jti) != null;
        } catch (RuntimeException e) {
            log.warn("JWT blacklist check failed for jti={} — failing OPEN (availability first): {}",
                    jti, e.getMessage());
            return false;
        }
    }
}
