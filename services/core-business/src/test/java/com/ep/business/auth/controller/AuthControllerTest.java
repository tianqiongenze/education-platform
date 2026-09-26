package com.ep.business.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import com.ep.business.auth.AuthService;
import com.ep.business.auth.CaptchaChallenge;
import com.ep.business.auth.CaptchaService;
import com.ep.business.auth.JwtTokenService;
import com.ep.business.auth.LoginRequest;
import com.ep.business.auth.LogoutRequest;
import com.ep.business.user.User;
import com.ep.business.user.UserRepository;
import com.ep.business.user.UserStatus;
import com.ep.web.common.GlobalExceptionHandler;
import com.ep.web.common.risk.LoginAttemptLimiter;
import com.ep.web.common.security.JwtBlacklistService;
import com.ep.web.common.security.JwtPayloadReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;

/**
 * D-227: {@link AuthController} — the captcha → login → logout closed loop.
 * Pure standalone MockMvc (no Spring context); Redis mocked; the lockout
 * limiter, credential check and JWT issuer are the real components.
 */
class AuthControllerTest {

    private static String privateKeyPem;

    private final ObjectMapper mapper = new ObjectMapper();
    private CaptchaService captchaService;
    private UserRepository repository;
    private AuthService authService;
    private JwtTokenService tokenService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MockMvc mockMvc;

    private User activeUser;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    void setUp() {
        captchaService = mock(CaptchaService.class);
        repository = mock(UserRepository.class);
        valueOps = (ValueOperations<String, String>) mock(ValueOperations.class);
        redis = mock(StringRedisTemplate.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);

        authService = new AuthService(repository, new BCryptPasswordEncoder(),
                new LoginAttemptLimiter(5, 15 * 60_000L, null));
        tokenService = new JwtTokenService(privateKeyPem, 3600);

        activeUser = new User(42L, "demo", "13800000000", "demo@ep.com",
                new BCryptPasswordEncoder().encode("pw123"), "Demo", null,
                UserStatus.ACTIVE.getCode(), 0L, 0L, 0L);
        lenient().when(repository.findByPhone("13800000000")).thenReturn(Optional.of(activeUser));

        AuthController controller = new AuthController(captchaService, authService, tokenService,
                new JwtBlacklistService(redis));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    private String body(LoginRequest request) throws Exception {
        return mapper.writeValueAsString(request);
    }

    // ------------------------------------------------------------- captcha

    @Test
    @DisplayName("GET /captcha issues a JSON challenge")
    void getCaptcha() throws Exception {
        when(captchaService.issue()).thenReturn(new CaptchaChallenge("cid-1", "7 + 5 = ?"));

        mockMvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captchaId").value("cid-1"))
                .andExpect(jsonPath("$.question").value("7 + 5 = ?"));
    }

    // --------------------------------------------------------------- login

    @Test
    @DisplayName("login success returns a JWT with a fresh jti and a user summary without secrets")
    void loginSuccess() throws Exception {
        when(captchaService.verify("cid-1", "12")).thenReturn(true);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", "pw123", "cid-1", "12"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.userId").value(42))
                .andExpect(jsonPath("$.user.username").value("demo"))
                .andExpect(jsonPath("$.user.phone").value("13800000000"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        String token = mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        String jti = JwtPayloadReader.stringClaim(token, "jti");
        UUID.fromString(jti); // must parse as UUID
        assertEquals("42", JwtPayloadReader.stringClaim(token, "sub"));
    }

    @Test
    @DisplayName("wrong captcha answer → 400, credentials never checked")
    void loginWrongCaptcha() throws Exception {
        when(captchaService.verify("cid-1", "999")).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", "pw123", "cid-1", "999"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
        verify(repository, org.mockito.Mockito.never()).findByPhone(anyString());
    }

    @Test
    @DisplayName("wrong password → 401")
    void loginWrongPassword() throws Exception {
        when(captchaService.verify("cid-1", "12")).thenReturn(true);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", "totally-wrong", "cid-1", "12"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("unknown phone → 401 with the same uniform message")
    void loginUnknownPhone() throws Exception {
        when(captchaService.verify("cid-2", "5")).thenReturn(true);
        when(repository.findByPhone("13999999999")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13999999999", "pw123", "cid-2", "5"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("exhausted lockout budget → 423 LOCKED before password work")
    void loginLocked() throws Exception {
        AuthService strict = new AuthService(repository, new BCryptPasswordEncoder(),
                new LoginAttemptLimiter(1, 60_000L, null));
        AuthController controller = new AuthController(captchaService, strict, tokenService,
                new JwtBlacklistService(redis));
        MockMvc strictMockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler(), new GlobalExceptionHandler())
                .build();

        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        // First attempt burns the whole budget (maxAttempts=1) via a wrong password.
        strictMockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", "wrong", "cid-1", "12"))))
                .andExpect(status().isUnauthorized());

        // Second attempt — even with the CORRECT password — is locked out.
        strictMockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", "pw123", "cid-2", "12"))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("missing fields → 400")
    void loginMissingFields() throws Exception {
        when(captchaService.verify(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("13800000000", null, "cid-1", "12"))))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------- logout

    @Test
    @DisplayName("logout via Authorization header blacklists the jti with the token's remaining TTL → 204")
    void logoutViaHeader() throws Exception {
        JwtTokenService.IssuedToken issued = tokenService.issue(42L, "demo", "13800000000", "demo@ep.com");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + issued.token()))
                .andExpect(status().isNoContent());

        String jti = JwtPayloadReader.stringClaim(issued.token(), "jti");
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(JwtBlacklistService.KEY_PREFIX + jti), eq("1"), ttl.capture());
        assertTrue(ttl.getValue().toSeconds() > 3500 && ttl.getValue().toSeconds() <= 3600,
                "TTL must equal the token's remaining validity, was " + ttl.getValue());
    }

    @Test
    @DisplayName("logout via JSON body token → 204")
    void logoutViaBody() throws Exception {
        JwtTokenService.IssuedToken issued = tokenService.issue(42L, "demo", "13800000000", "demo@ep.com");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LogoutRequest(issued.token()))))
                .andExpect(status().isNoContent());

        verify(valueOps).set(eq(JwtBlacklistService.KEY_PREFIX + issued.jti()), eq("1"),
                any(Duration.class));
    }

    @Test
    @DisplayName("logout without any token → 400")
    void logoutWithoutToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("logout with a token lacking a jti claim → 400")
    void logoutTokenWithoutJti() throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"42\"}".getBytes());
        String jtiLessToken = "H." + payload + ".S";

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + jtiLessToken))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------- closed loop: logout → 401

    @Test
    @DisplayName("closed loop: after logout the blacklisted jti makes the revocation filter answer 401")
    void logoutThenFilterRejects() throws Exception {
        JwtTokenService.IssuedToken issued = tokenService.issue(42L, "demo", "13800000000", "demo@ep.com");

        when(valueOps.get(eq(JwtBlacklistService.KEY_PREFIX + issued.jti()))).thenReturn("1");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + issued.token()))
                .andExpect(status().isNoContent());

        // Simulate the downstream JwtRevocationFilter for the same token.
        JwtBlacklistService blacklist = new JwtBlacklistService(redis);
        com.ep.web.common.security.JwtRevocationFilter filter =
                new com.ep.web.common.security.JwtRevocationFilter(blacklist);
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/orders");
        req.addHeader("Authorization", "Bearer " + issued.token());
        org.springframework.mock.web.MockHttpServletResponse res =
                new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {
            throw new AssertionError("revoked token must not reach the chain");
        });
        assertEquals(401, res.getStatus());
        assertNotNull(res.getHeader("WWW-Authenticate"));
    }
}
