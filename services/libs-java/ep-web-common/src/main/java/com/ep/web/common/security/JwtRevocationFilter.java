package com.ep.web.common.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * D-227: rejects requests whose Bearer token has been revoked (logged out).
 *
 * <p>The filter reads the {@code Authorization: Bearer ...} header, extracts
 * the {@code jti} claim from the token payload <b>without verifying the
 * signature</b> (signature verification stays the responsibility of the
 * downstream authentication component — this filter only performs the cheap
 * blacklist cutoff), and answers {@code 401 Unauthorized} when the token id is
 * blacklisted in {@link JwtBlacklistService}.
 *
 * <p>Fail-safe behaviour (availability first):
 * <ul>
 *   <li>no/blank Authorization header → pass through (anonymous or
 *       session-authenticated requests are not this filter's concern)</li>
 *   <li>malformed token or token without a {@code jti} claim → pass through;
 *       such tokens are rejected downstream during real verification</li>
 *   <li>blacklist backend unavailable → {@link JwtBlacklistService#isRevoked}
 *       fails open → pass through with a server-side WARN</li>
 * </ul>
 *
 * <p>Registered by {@link JwtRevocationAutoConfiguration} for every servlet web
 * application carrying ep-web-common; switchable via
 * {@code ep.jwt-revocation.enabled} (default ON).
 */
public class JwtRevocationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationFilter.class);

    /** Authorization header inspected for bearer tokens. */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    /** Accepted scheme prefix (case-insensitive per RFC 7235 conventions). */
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtBlacklistService blacklist;

    public JwtRevocationFilter(JwtBlacklistService blacklist) {
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            String jti = JwtPayloadReader.stringClaim(token, "jti");
            if (jti != null && !jti.isBlank() && blacklist.isRevoked(jti)) {
                log.warn("D-227: rejecting request with revoked JWT (jti={}) on path={}",
                        jti, request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"code\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
