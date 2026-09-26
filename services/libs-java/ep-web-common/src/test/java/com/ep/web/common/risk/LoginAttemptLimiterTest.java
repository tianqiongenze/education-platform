package com.ep.web.common.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-219: LoginAttemptLimiter unit tests (in-memory backend — no Redis).
 * Redis-backed behaviour is contract-identical (INCR/EXPIRE/DEL) and covered
 * by the RedisWindowCounter integration path.
 */
class LoginAttemptLimiterTest {

    @Test
    @DisplayName("5 failures lock the principal, then isLocked reports true")
    void lockoutAfterMaxAttempts() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 15 * 60_000L, null);
        String user = "alice";

        assertFalse(limiter.isLocked(user), "fresh principal is not locked");
        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure(user), "attempt " + (i + 1) + " should not lock yet");
        }
        assertTrue(limiter.recordFailure(user), "5th failure must trigger lockout");
        assertTrue(limiter.isLocked(user), "principal is locked after threshold");
    }

    @Test
    @DisplayName("successful login resets the failure counter")
    void successResetsCounter() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 15 * 60_000L, null);
        String user = "bob";
        limiter.recordFailure(user);
        limiter.recordFailure(user);
        limiter.recordFailure(user);

        limiter.recordSuccess(user);
        assertFalse(limiter.isLocked(user));
        // Counter cleared: 4 more failures do not lock (need 5 fresh ones).
        assertFalse(limiter.recordFailure(user));
        assertFalse(limiter.recordFailure(user));
        assertFalse(limiter.recordFailure(user));
        assertFalse(limiter.recordFailure(user));
        assertFalse(limiter.isLocked(user), "counter must have been reset by success");
    }

    @Test
    @DisplayName("lockout expires after the cooldown window (virtual time)")
    void lockoutExpires() throws Exception {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(2, 50L, null);
        String user = "carol";
        limiter.recordFailure(user);
        limiter.recordFailure(user);
        assertTrue(limiter.isLocked(user));

        Thread.sleep(80);
        assertFalse(limiter.isLocked(user), "lockout must expire after cooldown");
    }

    @Test
    @DisplayName("blank principals are ignored")
    void blankPrincipalIgnored() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 1000L, null);
        assertFalse(limiter.recordFailure(null));
        assertFalse(limiter.recordFailure("  "));
        assertFalse(limiter.isLocked(null));
    }
}
