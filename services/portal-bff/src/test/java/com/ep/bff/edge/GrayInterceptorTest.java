package com.ep.bff.edge;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GrayInterceptor (SDD §5.1 GrayInterceptor).
 */
class GrayInterceptorTest {

    @Test
    @DisplayName("ratio=0 always returns stable")
    void ratioZeroAlwaysStable() {
        GrayInterceptor interceptor = new GrayInterceptor(0.0);
        assertEquals(GrayInterceptor.TAG_STABLE, interceptor.computeTag("user-1"));
        assertEquals(GrayInterceptor.TAG_STABLE, interceptor.computeTag("user-2"));
        assertEquals(GrayInterceptor.TAG_STABLE, interceptor.computeTag("user-999"));
    }

    @Test
    @DisplayName("ratio=100 always returns canary")
    void ratioHundredAlwaysCanary() {
        GrayInterceptor interceptor = new GrayInterceptor(100.0);
        assertEquals(GrayInterceptor.TAG_CANARY, interceptor.computeTag("user-1"));
        assertEquals(GrayInterceptor.TAG_CANARY, interceptor.computeTag("user-2"));
        assertEquals(GrayInterceptor.TAG_CANARY, interceptor.computeTag("any-user"));
    }

    @Test
    @DisplayName("ratio=50 splits users consistently (same user always gets same tag)")
    void ratioFiftyIsConsistent() {
        GrayInterceptor interceptor = new GrayInterceptor(50.0);
        String tag1 = interceptor.computeTag("consistent-user-1");
        String tag2 = interceptor.computeTag("consistent-user-1");
        assertEquals(tag1, tag2, "same user must get same tag on repeated calls");
    }

    @Test
    @DisplayName("null userId returns stable")
    void nullUserIdReturnsStable() {
        GrayInterceptor interceptor = new GrayInterceptor(50.0);
        assertEquals(GrayInterceptor.TAG_STABLE, interceptor.computeTag(null));
        assertEquals(GrayInterceptor.TAG_STABLE, interceptor.computeTag(""));
    }

    @Test
    @DisplayName("preHandle sets X-Gray-Tag response header")
    void preHandleSetsGrayTagHeader() throws Exception {
        GrayInterceptor interceptor = new GrayInterceptor(0.0);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/123");
        req.addHeader("X-User-Id", "user-42");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, res, null));
        assertEquals(GrayInterceptor.TAG_STABLE, res.getHeader(GrayInterceptor.GRAY_TAG_HEADER));
    }

    @Test
    @DisplayName("ratio is clamped to [0, 100]")
    void ratioIsClamped() {
        GrayInterceptor negative = new GrayInterceptor(-10.0);
        assertEquals(GrayInterceptor.TAG_STABLE, negative.computeTag("user-1"));

        GrayInterceptor overflow = new GrayInterceptor(150.0);
        assertEquals(GrayInterceptor.TAG_CANARY, overflow.computeTag("user-1"));
    }

    @Test
    @DisplayName("ratio=5 produces some canary and some stable across users")
    void ratioFiveProducesMix() {
        GrayInterceptor interceptor = new GrayInterceptor(5.0);
        int canaryCount = 0;
        int stableCount = 0;
        for (int i = 0; i < 1000; i++) {
            String tag = interceptor.computeTag("user-" + i);
            if (GrayInterceptor.TAG_CANARY.equals(tag)) canaryCount++;
            else stableCount++;
        }
        // With ratio=5, approximately 5% should be canary (allow range 1-10%)
        assertTrue(canaryCount > 0, "at least some canary expected with ratio=5");
        assertTrue(canaryCount < 100, "canary should be minority with ratio=5");
        assertTrue(stableCount > 900, "majority should be stable with ratio=5");
    }
}
