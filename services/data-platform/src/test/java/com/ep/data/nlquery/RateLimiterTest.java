package com.ep.data.nlquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link RateLimiter} (D-190).
 *
 * <p>No Spring context — the limiter is a plain POJO instantiated with
 * {@code new}. EP_DEV_ONLY is unset in CI, so the limiter uses the
 * in-memory CAS-loop path.
 */
class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
    }

    @Test
    void first_request_is_allowed() {
        assertTrue(limiter.allowRequest("user1"),
                "first request should be allowed");
    }

    @Test
    void requests_under_limit_are_allowed() {
        // Default limit is 30 per 60 seconds.
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.allowRequest("user1"),
                    "request " + i + " should be allowed under the limit");
        }
    }

    @Test
    void request_over_limit_is_rejected() {
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.allowRequest("user1"), "request " + i + " should be allowed");
        }
        assertFalse(limiter.allowRequest("user1"),
                "31st request should be rate-limited");
    }

    @Test
    void different_users_have_independent_limits() {
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.allowRequest("user1"), "user1 request " + i + " should be allowed");
        }
        // user2 has a fresh limit.
        assertTrue(limiter.allowRequest("user2"),
                "user2 first request should be allowed (independent from user1)");
    }

    @Test
    void null_user_id_is_allowed() {
        assertTrue(limiter.allowRequest(null),
                "null userId should be allowed (no rate limit)");
    }

    @Test
    void blank_user_id_is_allowed() {
        assertTrue(limiter.allowRequest("  "),
                "blank userId should be allowed (no rate limit)");
    }

    @Test
    void get_current_count_returns_zero_for_new_user() {
        assertEquals(0, limiter.getCurrentCount("new-user"),
                "new user should have count 0");
    }

    @Test
    void get_current_count_increments_after_requests() {
        limiter.allowRequest("user1");
        limiter.allowRequest("user1");
        assertEquals(2, limiter.getCurrentCount("user1"),
                "user1 should have count 2 after two requests, got "
                        + limiter.getCurrentCount("user1"));
    }

    @Test
    void custom_limits_respected() {
        RateLimiter custom = new RateLimiter(3, 60, null);
        assertTrue(custom.allowRequest("u1"), "1st request should be allowed");
        assertTrue(custom.allowRequest("u1"), "2nd request should be allowed");
        assertTrue(custom.allowRequest("u1"), "3rd request should be allowed");
        assertFalse(custom.allowRequest("u1"), "4th request should be rate-limited");
    }

    @Test
    void get_max_requests_and_window_seconds() {
        RateLimiter custom = new RateLimiter(10, 120, null);
        assertEquals(10, custom.getMaxRequests(), "maxRequests should be 10");
        assertEquals(120, custom.getWindowSeconds(), "windowSeconds should be 120");
    }

    @Test
    void default_limits_match_spec() {
        assertEquals(RateLimiter.DEFAULT_MAX_REQUESTS, limiter.getMaxRequests(),
                "default maxRequests should be " + RateLimiter.DEFAULT_MAX_REQUESTS);
        assertEquals(RateLimiter.DEFAULT_WINDOW_SECONDS, limiter.getWindowSeconds(),
                "default windowSeconds should be " + RateLimiter.DEFAULT_WINDOW_SECONDS);
    }

    @Test
    void zero_or_negative_limits_clamped_to_one() {
        RateLimiter clamped = new RateLimiter(0, -5, null);
        assertEquals(1, clamped.getMaxRequests(),
                "maxRequests should be clamped to 1, got " + clamped.getMaxRequests());
        assertEquals(1, clamped.getWindowSeconds(),
                "windowSeconds should be clamped to 1, got " + clamped.getWindowSeconds());
    }

    @Test
    void concurrent_requests_thread_safe() throws InterruptedException {
        RateLimiter small = new RateLimiter(5, 60, null);
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> results[idx] = small.allowRequest("concurrent-user"));
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        int allowed = 0;
        for (boolean b : results) {
            if (b) {
                allowed++;
            }
        }
        assertEquals(5, allowed,
                "exactly 5 requests should be allowed (limit), got " + allowed);
    }
}
