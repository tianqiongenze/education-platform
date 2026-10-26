package com.ep.web.common.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D-236 (v7 P2): automatic IP ban service — closes the loop that the rate
 * limiter left open. The {@code RiskControlFilter} answers 429 for over-budget
 * requests; a persistent abuser resets its budget every window and keeps
 * hammering. This service counts 429 violations per IP and, once the count
 * crosses {@code violationsToBan} inside the tracking window, bans the IP for
 * {@code banTtl} — subsequent requests are answered 403 before any limiter
 * work, so a banned attacker costs one Redis GET per request instead of a
 * full filter chain.
 *
 * <p><b>Storage</b>: Redis SET (cluster-wide, TTL = ban duration) with a
 * per-instance in-memory fallback that mirrors the risk-control counter
 * degradation policy. The memory fallback enforces bans locally when Redis is
 * unreachable — weaker (per-instance) but never absent.</p>
 *
 * <p><b>2025 industry practice</b> (美团/腾讯 anti-abuse layer ④): automatic
 * short-ban escalation above the rate limiter, with a manual allowlist escape
 * hatch — {@link #unban} for operator intervention.</p>
 */
public class IpBanService {

    private static final Logger log = LoggerFactory.getLogger(IpBanService.class);

    /** Redis key for the ban set member: {@code ep:risk:ban:<ip>}. */
    static final String BAN_KEY_PREFIX = "ep:risk:ban:";

    /** Default: 429 violations within the window before an automatic ban. */
    public static final int DEFAULT_VIOLATIONS_TO_BAN = 3;

    /** Default: ban duration. */
    public static final Duration DEFAULT_BAN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final int violationsToBan;
    private final Duration banTtl;
    private final Clock clock;

    /** In-memory fallback state: ip → violation counter (sticky-window). */
    private final ConcurrentHashMap<String, AtomicInteger> memoryViolations = new ConcurrentHashMap<>();
    /** In-memory fallback: banned-until timestamp per ip (0 = not banned). */
    private final ConcurrentHashMap<String, Long> memoryBannedUntil = new ConcurrentHashMap<>();

    public IpBanService(StringRedisTemplate redis, int violationsToBan, Duration banTtl) {
        this(redis, violationsToBan, banTtl, Clock.systemUTC());
    }

    /** Test-friendly constructor. */
    IpBanService(StringRedisTemplate redis, int violationsToBan, Duration banTtl, Clock clock) {
        if (violationsToBan < 1) {
            throw new IllegalArgumentException("violationsToBan must be positive");
        }
        if (banTtl == null || banTtl.isNegative() || banTtl.isZero()) {
            throw new IllegalArgumentException("banTtl must be positive");
        }
        this.redis = redis;
        this.violationsToBan = violationsToBan;
        this.banTtl = banTtl;
        this.clock = clock;
    }

    /**
     * Records one 429 violation for the IP. When the count reaches
     * {@code violationsToBan} the IP is banned and the violation ledger resets.
     *
     * @return {@code true} if this call triggered the ban
     */
    public boolean recordViolation(String ip) {
        if (ip == null || ip.isBlank() || "unknown".equals(ip)) {
            return false;
        }
        if (redis != null) {
            try {
                String key = BAN_KEY_PREFIX + "viol:" + ip;
                Long count = redis.opsForValue().increment(key);
                if (count != null) {
                    if (count == 1L) {
                        redis.expire(key, banTtl.multipliedBy(2)); // ledger outlives the ban
                    }
                    if (count >= violationsToBan) {
                        ban(ip);
                        redis.delete(key);
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException e) {
                log.warn("IpBanService: Redis violation ledger unavailable ({}), degrading to memory", e.getMessage());
                // fall through to memory path
            }
        }
        AtomicInteger counter = memoryViolations.computeIfAbsent(ip, k -> new AtomicInteger());
        if (counter.incrementAndGet() >= violationsToBan) {
            memoryViolations.remove(ip);
            banInMemory(ip);
            return true;
        }
        return false;
    }

    /**
     * Whether the IP is currently banned. Banned IPs should be answered 403
     * before the limiter chain runs.
     */
    public boolean isBanned(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (redis != null) {
            try {
                Boolean member = redis.hasKey(BAN_KEY_PREFIX + ip);
                if (Boolean.TRUE.equals(member)) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Redis hiccup: fall through to the in-memory state
            }
        }
        Long until = memoryBannedUntil.get(ip);
        if (until != null) {
            if (clock.millis() < until) {
                return true;
            }
            memoryBannedUntil.remove(ip, until); // expired — lazy cleanup
        }
        return false;
    }

    /** Operator escape hatch: lift a ban immediately. */
    public void unban(String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        if (redis != null) {
            try {
                redis.delete(BAN_KEY_PREFIX + ip);
            } catch (RuntimeException e) {
                log.warn("IpBanService: Redis unban failed: {}", e.getMessage());
            }
        }
        memoryBannedUntil.remove(ip);
    }

    private void ban(String ip) {
        if (redis != null) {
            try {
                redis.opsForValue().set(BAN_KEY_PREFIX + ip, "1", banTtl);
                log.warn("IpBanService: IP {} auto-banned for {} ({} violations)", ip, banTtl, violationsToBan);
                return;
            } catch (RuntimeException e) {
                log.warn("IpBanService: Redis ban write failed ({}), banning in memory", e.getMessage());
            }
        }
        banInMemory(ip);
    }

    private void banInMemory(String ip) {
        memoryBannedUntil.put(ip, clock.millis() + banTtl.toMillis());
        log.warn("IpBanService: IP {} auto-banned in-memory for {} ({} violations)", ip, banTtl, violationsToBan);
    }
}
