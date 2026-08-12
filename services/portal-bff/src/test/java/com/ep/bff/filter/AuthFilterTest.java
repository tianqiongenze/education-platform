package com.ep.bff.filter;

import com.ep.bff.auth.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * AuthFilter 测试 — 白名单/伪造头剥离/Bearer 解析注入/401.
 */
class AuthFilterTest {
    private JwtService jwt;
    private AuthFilter filter;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        jwt = new JwtService(kp.getPrivate(), kp.getPublic());
        filter = new AuthFilter(jwt);
    }

    @Test
    @DisplayName("whitelist /api/auth/ path passes without token")
    void whitelistAuthPathPassesWithoutToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        final boolean[] chainCalled = {false};
        filter.doFilterInternal(req, res, (rq, rs) -> chainCalled[0] = true);
        assertEquals(200, res.getStatus());
        assertTrue(chainCalled[0]);
    }

    @Test
    @DisplayName("whitelist /api/v1/auth/ path passes without token (D-112)")
    void whitelistV1AuthPathPassesWithoutToken() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        final boolean[] chainCalled = {false};
        filter.doFilterInternal(req, res, (rq, rs) -> chainCalled[0] = true);
        assertEquals(200, res.getStatus());
        assertTrue(chainCalled[0]);
    }

    @Test
    @DisplayName("whitelist /healthz passes without token")
    void whitelistHealthzPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/healthz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(200, res.getStatus());
    }

    @Test
    @DisplayName("whitelist /actuator/ passes without token")
    void whitelistActuatorPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(200, res.getStatus());
    }

    @Test
    @DisplayName("forged X-User-Id stripped, 401 without Bearer")
    void forgedXUserIdStrippedAndRejectedWithoutBearer() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("X-User-Id", "99999");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(401, res.getStatus());
    }

    @Test
    @DisplayName("valid Bearer injects X-User-Id and X-Role")
    void validBearerInjectsXUserIdAndRole() throws Exception {
        String token = jwt.issue(42L, "u@x.com", "n", "teacher");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        final String[] seenUserId = {null};
        final String[] seenRole = {null};
        filter.doFilterInternal(req, res, (rq, rs) -> {
            seenUserId[0] = ((HttpServletRequest) rq).getHeader("X-User-Id");
            seenRole[0] = ((HttpServletRequest) rq).getHeader("X-Role");
        });
        assertEquals("42", seenUserId[0]);
        assertEquals("teacher", seenRole[0]);
    }

    @Test
    @DisplayName("Authorization header stripped from downstream request")
    void authorizationStrippedFromDownstream() throws Exception {
        String token = jwt.issue(42L, "u@x.com", "n", "teacher");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        final String[] seenAuth = {""};
        filter.doFilterInternal(req, res, (rq, rs) -> {
            seenAuth[0] = ((HttpServletRequest) rq).getHeader("Authorization");
        });
        assertNull(seenAuth[0]);
    }

    @Test
    @DisplayName("invalid Bearer returns 401")
    void invalidBearerReturns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/course/1");
        req.addHeader("Authorization", "Bearer invalidtoken");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(401, res.getStatus());
    }

    @Test
    @DisplayName("whitelist /.well-known/ passes without token")
    void whitelistWellKnownPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/.well-known/jwks.json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, (rq, rs) -> {});
        assertEquals(200, res.getStatus());
    }
}
