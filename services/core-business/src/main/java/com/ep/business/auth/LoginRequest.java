package com.ep.business.auth;

/**
 * D-227: request body for {@code POST /api/auth/login}.
 * All fields are required; missing/blank values fail with HTTP 400.
 *
 * @param phone         registered phone number
 * @param password      plaintext password (transport-protected by TLS)
 * @param captchaId     captcha id from {@code GET /api/auth/captcha}
 * @param captchaAnswer user's answer to the captcha question
 */
public record LoginRequest(String phone, String password, String captchaId, String captchaAnswer) {

    public boolean hasRequiredFields() {
        return phone != null && !phone.isBlank()
                && password != null && !password.isBlank()
                && captchaId != null && !captchaId.isBlank()
                && captchaAnswer != null && !captchaAnswer.isBlank();
    }
}
