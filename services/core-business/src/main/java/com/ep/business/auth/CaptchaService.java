package com.ep.business.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D-227: math captcha service for the login loop.
 *
 * <p>Issues human-solvable arithmetic challenges ({@code "7 + 5 = ?"}) and
 * verifies submitted answers. Challenges are JSON documents
 * ({@code {captchaId, question}}) rather than SVG images so the mini-program
 * client can render them as plain text.
 *
 * <p>Storage: the answer is stored in Redis under {@code ep:captcha:{captchaId}}
 * with a 5-minute TTL. Verification is <b>one-shot</b> (the stored answer is
 * consumed atomically via {@code GETDEL} on first verification attempt — right
 * or wrong), which bounds online guessing to a single try per issued captcha.
 * When Redis is unavailable the service degrades to a per-instance in-memory
 * store (same degradation philosophy as {@code LoginAttemptLimiter}) so the
 * login loop keeps working in dev-local / unit tests.
 *
 * <p>Answer comparison uses {@link MessageDigest#isEqual} (constant time) to
 * avoid timing side channels.
 */
public class CaptchaService {

    /** Redis key prefix: {@code ep:captcha:{captchaId}}. */
    public static final String KEY_PREFIX = "ep:captcha:";

    /** TTL of a stored captcha in Redis. */
    public static final Duration REDIS_TTL = Duration.ofMinutes(5);
    /** Mirrors {@link #REDIS_TTL} for the in-memory fallback store. */
    static final long MEMORY_TTL_MILLIS = 5 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, MemoryCaptcha> memoryStore = new ConcurrentHashMap<>();

    /** Production constructor (system UTC clock). */
    public CaptchaService(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    /** Test constructor with an injectable {@link Clock} (expiry testing). */
    CaptchaService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Issues a new captcha challenge and stores its answer.
     *
     * @return challenge containing the {@code captchaId} and the plain-text
     *         {@code question} (e.g. {@code "12 - 4 = ?"})
     */
    public CaptchaChallenge issue() {
        int a = 1 + random.nextInt(9);
        int b = 1 + random.nextInt(9);
        boolean plus = random.nextBoolean();
        if (!plus && b > a) {
            // Keep subtraction results non-negative (target audience: humans).
            int tmp = a;
            a = b;
            b = tmp;
        }
        String answer = String.valueOf(plus ? a + b : a - b);
        String question = a + (plus ? " + " : " - ") + b + " = ?";
        String captchaId = UUID.randomUUID().toString();
        store(captchaId, answer);
        return new CaptchaChallenge(captchaId, question);
    }

    /**
     * Verifies a submitted answer. One-shot: the stored answer is consumed on
     * the first attempt regardless of the outcome.
     *
     * @return true when the answer matches the stored value
     */
    public boolean verify(String captchaId, String answer) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) {
            return false;
        }
        String expected = consume(captchaId.trim());
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                answer.trim().getBytes(StandardCharsets.UTF_8));
    }

    private void store(String captchaId, String answer) {
        if (redis != null) {
            try {
                redis.opsForValue().set(KEY_PREFIX + captchaId, answer, REDIS_TTL);
                return;
            } catch (RuntimeException e) {
                log.warn("Captcha Redis write failed, degrading to per-instance store: {}", e.getMessage());
            }
        }
        memoryStore.put(captchaId, new MemoryCaptcha(answer, clock.millis() + MEMORY_TTL_MILLIS));
    }

    /** Atomic consume: read + delete in one Redis round-trip (GETDEL). */
    private String consume(String captchaId) {
        if (redis != null) {
            try {
                return redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
            } catch (RuntimeException e) {
                log.warn("Captcha Redis read failed, using per-instance store: {}", e.getMessage());
            }
        }
        MemoryCaptcha captcha = memoryStore.remove(captchaId);
        if (captcha == null) {
            return null;
        }
        // One-shot + lazy expiry: removal above already guarantees single use.
        return clock.millis() > captcha.expiresAt ? null : captcha.answer;
    }

    /** In-memory fallback entry. */
    private record MemoryCaptcha(String answer, long expiresAt) {
    }
}
