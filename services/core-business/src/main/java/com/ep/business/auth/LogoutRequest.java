package com.ep.business.auth;

/**
 * D-227: request body for {@code POST /api/auth/logout}. Optional — the token
 * may alternatively be supplied via the {@code Authorization: Bearer} header.
 *
 * @param token the JWT to revoke
 */
public record LogoutRequest(String token) {
}
