package com.ep.business.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * D-227: {@link CaptchaService} unit tests — issuance, one-shot verification,
 * Redis TTL propagation, expiry and the in-memory degradation path.
 */
class CaptchaServiceTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps =
            (ValueOperations<String, String>) mock(ValueOperations.class);

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    /** Evaluates an issued question ("a + b = ?" / "a - b = ?") to the expected answer. */
    private static int solve(String question) {
        Matcher m = Pattern.compile("(\\d+) ([+-]) (\\d+)").matcher(question);
        assertTrue(m.find(), "question must be an arithmetic expression: " + question);
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(3));
        return "+".equals(m.group(2)) ? a + b : a - b;
    }

    @Test
    @DisplayName("issue returns a captchaId + question and stores the answer with a 5-minute TTL")
    void issueStoresAnswerWithTtl() {
        CaptchaService service = new CaptchaService(redis);

        CaptchaChallenge challenge = service.issue();

        assertTrue(challenge.captchaId() != null && !challenge.captchaId().isBlank());
        assertTrue(challenge.question().contains("= ?"));
        assertNotEquals(0, solve(challenge.question()));

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
                eq(CaptchaService.KEY_PREFIX + challenge.captchaId()),
                answer.capture(),
                eq(CaptchaService.REDIS_TTL));
        assertEquals(String.valueOf(solve(challenge.question())), answer.getValue(),
                "stored answer must solve the issued question");
        assertEquals(Duration.ofMinutes(5), CaptchaService.REDIS_TTL);
    }

    @Test
    @DisplayName("correct answer verifies once — one-shot: the captcha is consumed")
    void verifyIsOneShot() {
        CaptchaService service = new CaptchaService(redis);
        CaptchaChallenge challenge = service.issue();
        when(valueOps.getAndDelete(CaptchaService.KEY_PREFIX + challenge.captchaId()))
                .thenReturn(String.valueOf(solve(challenge.question())), (String) null);

        assertTrue(service.verify(challenge.captchaId(), String.valueOf(solve(challenge.question()))),
                "first attempt with the correct answer must pass");
        assertFalse(service.verify(challenge.captchaId(), String.valueOf(solve(challenge.question()))),
                "second attempt must fail — captcha already consumed");
    }

    @Test
    @DisplayName("wrong answer consumes the captcha and fails")
    void wrongAnswerConsumesAndFails() {
        CaptchaService service = new CaptchaService(redis);
        CaptchaChallenge challenge = service.issue();
        when(valueOps.getAndDelete(CaptchaService.KEY_PREFIX + challenge.captchaId()))
                .thenReturn(String.valueOf(solve(challenge.question())));

        assertFalse(service.verify(challenge.captchaId(), "?"),
                "non-numeric answer must fail regardless of the issued question");
        verify(valueOps).getAndDelete(CaptchaService.KEY_PREFIX + challenge.captchaId());
    }

    @Test
    @DisplayName("blank inputs never verify")
    void blankInputsFail() {
        CaptchaService service = new CaptchaService(redis);
        assertFalse(service.verify(null, "5"));
        assertFalse(service.verify(" ", "5"));
        assertFalse(service.verify("some-id", null));
        assertFalse(service.verify("some-id", "  "));
    }

    @Test
    @DisplayName("expired captcha (in-memory path, virtual clock) does not verify")
    void expiredCaptchaFails() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CaptchaService service = new CaptchaService(null, clock); // no Redis → memory store

        CaptchaChallenge challenge = service.issue();
        int answer = solve(challenge.question());
        assertTrue(service.verify(challenge.captchaId(), String.valueOf(answer)),
                "fresh captcha verifies on the memory path");

        CaptchaChallenge second = service.issue();
        clock.advance(Duration.ofMinutes(6)); // TTL is 5 minutes

        assertFalse(service.verify(second.captchaId(), String.valueOf(solve(second.question()))),
                "captcha must be unusable after the TTL");
        assertNotEquals(challenge.captchaId(), second.captchaId(), "ids must be unique");
    }

    @Test
    @DisplayName("Redis outage degrades to the per-instance memory store and the loop still closes")
    void redisOutageDegradesToMemory() {
        when(valueOps.getAndDelete(anyString()))
                .thenThrow(new RuntimeException("cluster down"));
        org.mockito.Mockito.doThrow(new RuntimeException("cluster down")).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));
        CaptchaService service = new CaptchaService(redis);

        CaptchaChallenge challenge = service.issue();
        int answer = solve(challenge.question());
        assertTrue(service.verify(challenge.captchaId(), String.valueOf(answer)),
                "login loop must keep working when Redis is unavailable");
        assertFalse(service.verify(challenge.captchaId(), String.valueOf(answer)),
                "one-shot semantics survive the degradation");
    }

    /** Mutable clock for expiry tests (virtual time). */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
