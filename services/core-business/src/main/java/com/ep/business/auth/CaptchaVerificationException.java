package com.ep.business.auth;

/**
 * D-227: captcha verification failed (unknown captchaId, expired captcha,
 * already-consumed captcha, or wrong answer). Mapped to HTTP 400 by
 * {@code AuthExceptionHandler}.
 */
public class CaptchaVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CaptchaVerificationException(String message) {
        super(message);
    }
}
