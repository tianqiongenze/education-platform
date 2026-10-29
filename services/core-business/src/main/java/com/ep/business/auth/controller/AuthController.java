package com.ep.business.auth.controller;

import java.time.Duration;

import com.ep.business.auth.AuthService;
import com.ep.business.auth.CaptchaChallenge;
import com.ep.business.auth.CaptchaService;
import com.ep.business.auth.CaptchaVerificationException;
import com.ep.business.auth.JwtTokenService;
import com.ep.business.auth.LoginRequest;
import com.ep.business.auth.LoginResponse;
import com.ep.business.auth.LogoutRequest;
import com.ep.web.common.security.JwtBlacklistService;
import com.ep.web.common.security.JwtPayloadReader;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-227: authentication endpoints — the captcha + JWT + logout closed loop.
 *
 * <pre>
 *   GET  /api/auth/captcha  → {captchaId, question}
 *   POST /api/auth/login    → captcha check → credential check → JWT (with jti)
 *   POST /api/auth/logout   → jti → revocation blacklist → 204
 * </pre>
 *
 * <p>After logout the ep-web-common {@code JwtRevocationFilter} rejects any
 * request bearing the revoked token with 401 (blacklist check runs in every
 * downstream service that carries ep-web-common).</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Fallback blacklist horizon when a token carries no {@code exp} claim. */
    static final Duration DEFAULT_REVOKE_TTL = Duration.ofHours(1);

    private static final String BEARER_PREFIX = "Bearer ";

    private final CaptchaService captchaService;
    private final AuthService authService;
    private final JwtTokenService tokenService;
    private final JwtBlacklistService blacklist;

    public AuthController(CaptchaService captchaService,
                          AuthService authService,
                          JwtTokenService tokenService,
                          JwtBlacklistService blacklist) {
        this.captchaService = captchaService;
        this.authService = authService;
        this.tokenService = tokenService;
        this.blacklist = blacklist;
    }

    /** Issues a fresh math captcha (JSON, one-shot, 5-minute validity). */
    @GetMapping("/captcha")
    public CaptchaChallenge captcha() {
        return captchaService.issue();
    }

    /**
     * Verifies the captcha (wrong → 400), then the credentials (wrong → 401 and
     * a failure recorded against the lockout limiter), then issues a JWT with a
     * fresh {@code jti} plus a sanitized user summary.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request == null || !request.hasRequiredFields()) {
            throw new IllegalArgumentException("phone, password, captchaId and captchaAnswer are required");
        }
        if (!captchaService.verify(request.captchaId(), request.captchaAnswer())) {
            throw new CaptchaVerificationException(
                    "Captcha verification failed: the answer is wrong or the captcha expired");
        }
        var user = authService.authenticate(request.phone(), request.password());
        // D-242: role from the aggregate; legacy rows (null role) fall back to "user".
        var issued = tokenService.issue(user.getUserId(), user.getUsername(), user.getPhone(), user.getEmail(),
                user.getRole() == null ? "user" : user.getRole());
        return LoginResponse.of(issued, user);
    }

    /**
     * Revokes the supplied token (Authorization header takes precedence over the
     * JSON body). The token payload is parsed <b>without signature
     * verification</b> — the same advisory-parse contract as the revocation
     * filter; a blacklisted {@code jti} only affects tokens the caller already
     * possesses. Returns 204 even when the blacklist is unavailable
     * (availability-first; the failure is logged server-side).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest body,
                                       HttpServletRequest request) {
        String token = bearerToken(request.getHeader("Authorization"));
        if (token == null && body != null) {
            token = body.token();
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "token must be supplied via the Authorization header or the request body");
        }
        String jti = JwtPayloadReader.stringClaim(token, "jti");
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("Token carries no jti claim — nothing to revoke");
        }
        boolean revoked = blacklist.revoke(jti, remainingTtl(token));
        if (!revoked) {
            log.warn("D-227: logout could not blacklist jti={} (Redis unavailable?) — "
                    + "token remains valid until natural expiry", jti);
        }
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /** Remaining validity from the (unverified) exp claim; capped at a sane horizon. */
    private static Duration remainingTtl(String token) {
        Long exp = JwtPayloadReader.numericClaim(token, "exp");
        if (exp == null) {
            return DEFAULT_REVOKE_TTL;
        }
        return Duration.ofMillis(exp * 1000L - System.currentTimeMillis());
    }
}
