package com.ep.business.auth.controller;

import com.ep.business.auth.AccountLockedException;
import com.ep.business.auth.CaptchaVerificationException;
import com.ep.business.auth.InvalidCredentialsException;
import com.ep.business.auth.PasswordNotSetException;
import com.ep.web.common.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * D-227: HTTP mappings for the auth-specific exceptions. Higher precedence than
 * the shared {@code GlobalExceptionHandler} (which maps the generic
 * {@code Exception} to 500) so auth failures get their precise status codes:
 * 400 captcha, 401 credentials, 409 password-not-set, 423 locked.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    private static ResponseEntity<ErrorResponse> body(String code, String message, HttpStatus status) {
        String traceId = org.slf4j.MDC.get("traceId");
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, traceId != null ? traceId : "N/A",
                        System.currentTimeMillis()));
    }

    @ExceptionHandler(CaptchaVerificationException.class)
    public ResponseEntity<ErrorResponse> handleCaptcha(CaptchaVerificationException ex) {
        log.warn("Captcha verification failed: {}", ex.getMessage());
        return body("CAPTCHA_INVALID", ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        // Warn only — expected on every wrong password; no stack trace needed.
        log.warn("Login rejected: {}", ex.getMessage());
        return body("INVALID_CREDENTIALS", ex.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(PasswordNotSetException.class)
    public ResponseEntity<ErrorResponse> handlePasswordNotSet(PasswordNotSetException ex) {
        log.warn("Login rejected (no password set): {}", ex.getMessage());
        return body("PASSWORD_NOT_SET", ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(AccountLockedException ex) {
        log.warn("Login rejected (account locked): {}", ex.getMessage());
        return body("ACCOUNT_LOCKED", ex.getMessage(), HttpStatus.LOCKED);
    }

    /**
     * D-241 (CLC): malformed requests (missing phone/captcha fields, logout
     * without a token) are CLIENT errors and must surface as 400 — throwing
     * IllegalArgumentException without a mapping leaked a 500 and tripped
     * the functional test suite (T1.3 observed 415/500 family).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Auth request rejected (client error): {}", ex.getMessage());
        return body("INVALID_ARGUMENT", ex.getMessage(), HttpStatus.BAD_REQUEST);
    }
}
