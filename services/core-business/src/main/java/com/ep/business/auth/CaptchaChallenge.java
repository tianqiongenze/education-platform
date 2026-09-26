package com.ep.business.auth;

/**
 * D-227: issued captcha challenge returned by {@code GET /api/auth/captcha}.
 * JSON (not SVG) so mini-program clients render the question as plain text.
 *
 * @param captchaId opaque id the client must echo back on {@code POST /login}
 * @param question  plain-text math question, e.g. {@code "7 + 5 = ?"}
 */
public record CaptchaChallenge(String captchaId, String question) {
}
