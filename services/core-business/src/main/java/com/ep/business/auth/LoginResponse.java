package com.ep.business.auth;

import com.ep.business.user.User;

/**
 * D-227: response body for a successful {@code POST /api/auth/login}.
 * Deliberately returns a <b>user summary</b> — never the {@link User} entity,
 * whose {@code passwordHash} must never leave the service.
 *
 * @param token     signed RS256 JWT (contains a {@code jti} for revocation)
 * @param tokenType always {@code "Bearer"}
 * @param expiresIn token lifetime in seconds
 * @param user      sanitized user summary
 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserSummary user) {

    /**
     * @param userId   platform user id (JWT subject)
     * @param username login name
     * @param nickname display name (falls back to username)
     * @param phone    registered phone
     * @param email    email (may be null)
     */
    public record UserSummary(Long userId, String username, String nickname, String phone, String email) {
    }

    public static LoginResponse of(JwtTokenService.IssuedToken issued, User user) {
        return new LoginResponse(issued.token(), "Bearer", issued.expiresIn(),
                new UserSummary(user.getUserId(), user.getUsername(),
                        user.getNickname() == null ? user.getUsername() : user.getNickname(),
                        user.getPhone(), user.getEmail()));
    }
}
