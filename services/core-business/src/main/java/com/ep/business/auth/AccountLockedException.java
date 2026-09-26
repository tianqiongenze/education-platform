package com.ep.business.auth;

/**
 * D-227: the principal exceeded the allowed failed-login budget and is locked
 * out ({@code LoginAttemptLimiter}). Mapped to HTTP 423 (LOCKED) by
 * {@code AuthExceptionHandler}.
 */
public class AccountLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccountLockedException(String message) {
        super(message);
    }
}
