package com.ep.web.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * D-227: {@link JwtRevocationFilter} unit tests.
 *
 * <p>Verifies the contract required by the logout loop: a blacklisted
 * {@code jti} is rejected with 401 (and the chain is never invoked), while
 * tokens without a {@code jti} claim, malformed tokens, anonymous requests and
 * non-revoked tokens all pass through (fail-open).</p>
 */
class JwtRevocationFilterTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps =
            (ValueOperations<String, String>) mock(ValueOperations.class);

    private JwtRevocationFilter filter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        filter = new JwtRevocationFilter(new JwtBlacklistService(redis));
    }

    // ------------------------------------------------------------- helpers

    private static String jwtWithPayload(String json) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".c2lnbmF0dXJl";
    }

    private static String tokenWithJti(String jti) {
        return jwtWithPayload("{\"sub\":\"42\",\"jti\":\"" + jti + "\",\"exp\":1893456000}");
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(JwtRevocationFilter.AUTHORIZATION_HEADER, "Bearer " + token);
        return request;
    }

    /** Runs the filter; returns true when the downstream chain was reached. */
    private boolean chainReached(MockHttpServletRequest request) throws Exception {
        AtomicBoolean reached = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        return reached.get();
    }

    // --------------------------------------------------------------- tests

    @Test
    @DisplayName("blacklisted jti → 401, chain never invoked")
    void blacklistedTokenRejected() throws Exception {
        String jti = "revoked-jti";
        when(valueOps.get(JwtBlacklistService.KEY_PREFIX + jti)).thenReturn("1");

        MockHttpServletRequest request = requestWithToken(tokenWithJti(jti));
        AtomicBoolean reached = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertEquals(false, reached.get(), "revoked token must not reach the chain");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("TOKEN_REVOKED"));
        assertEquals("Bearer error=\"invalid_token\"", response.getHeader("WWW-Authenticate"));
    }

    @Test
    @DisplayName("non-revoked jti → pass through")
    void validTokenPasses() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        assertTrue(chainReached(requestWithToken(tokenWithJti("fresh-jti"))));
    }

    @Test
    @DisplayName("token without a jti claim → pass through (verification is downstream's job)")
    void tokenWithoutJtiPasses() throws Exception {
        when(valueOps.get(anyString())).thenReturn("1"); // even a populated blacklist
        assertTrue(chainReached(requestWithToken(jwtWithPayload("{\"sub\":\"42\"}"))));
    }

    @Test
    @DisplayName("malformed token → pass through")
    void malformedTokenPasses() throws Exception {
        assertTrue(chainReached(requestWithToken("garbage-not-a-jwt")));
        assertTrue(chainReached(requestWithToken("a.b")));
    }

    @Test
    @DisplayName("no Authorization header → pass through")
    void anonymousRequestPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        assertTrue(chainReached(request));
    }

    @Test
    @DisplayName("non-Bearer Authorization header → pass through")
    void nonBearerHeaderPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        request.addHeader(JwtRevocationFilter.AUTHORIZATION_HEADER, "Basic YWN0dWF0b3I6c2VjcmV0");
        assertTrue(chainReached(request));
    }

    @Test
    @DisplayName("scheme prefix is matched case-insensitively")
    void bearerPrefixCaseInsensitive() throws Exception {
        String jti = "lower-bearer-jti";
        when(valueOps.get(JwtBlacklistService.KEY_PREFIX + jti)).thenReturn("1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
        request.addHeader(JwtRevocationFilter.AUTHORIZATION_HEADER, "bearer " + tokenWithJti(jti));
        AtomicBoolean reached = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertEquals(false, reached.get(), "revoked token must not reach the chain");
        assertEquals(401, response.getStatus());
    }
}
