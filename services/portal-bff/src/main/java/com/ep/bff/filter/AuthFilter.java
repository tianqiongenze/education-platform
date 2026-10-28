package com.ep.bff.filter;

import com.ep.bff.auth.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * AuthFilter — 最高优先级, 剥离伪造 X-User-Id/X-Role 头,
 * 从 Bearer JWT 解析后注入可信头给下游.
 * 白名单: /api/auth/, /api/v1/auth/, /healthz, /actuator/, /.well-known/,
 *         /proxy/ (D-107: aggregated Swagger UI fetches api-docs via
 *                 the proxy from the browser without a Bearer token),
 *         /v3/api-docs, /swagger-ui (D-070/D-107: serve the UI itself),
 *         /graphql (D-graphql: read-only course aggregation — same
 *                 pass-through posture as /proxy/**; only public course reads
 *                 are exposed, no user-scoped fields).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> WHITELIST = Set.of(
        "/api/auth/", "/api/v1/auth/", "/healthz", "/actuator/", "/.well-known/", "/error",
        "/proxy/", "/v3/api-docs", "/swagger-ui", "/graphql"
    );
    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (isWhitelisted(path)) {
            chain.doFilter(req, res);
            return;
        }
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            res.setStatus(401);
            return;
        }
        Claims claims;
        try {
            claims = jwtService.parse(auth.substring(7));
        } catch (Exception e) {
            // Only JWT parse/validation failures should produce 401.
            // Downstream controller/RPC exceptions must propagate to Spring's
            // error handler so the real status (400/404/500/etc.) is returned.
            res.setStatus(401);
            return;
        }
        chain.doFilter(new AuthHeaderWrapper(req, claims.getSubject(), claims.get("role", String.class)), res);
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }

    /** Strips forged auth headers, injects trusted JWT-derived values */
    private static class AuthHeaderWrapper extends HttpServletRequestWrapper {
        private final String userId;
        private final String role;

        AuthHeaderWrapper(HttpServletRequest req, String userId, String role) {
            super(req);
            this.userId = userId;
            this.role = role;
        }

        @Override
        public String getHeader(String name) {
            if ("X-User-Id".equalsIgnoreCase(name)) return userId;
            if ("X-Role".equalsIgnoreCase(name)) return role;
            if ("Authorization".equalsIgnoreCase(name)) return null; // strip, prevent downstream misuse
            return super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if ("X-User-Id".equalsIgnoreCase(name) || "X-Role".equalsIgnoreCase(name)) {
                return java.util.Collections.enumeration(java.util.List.of(
                    "X-User-Id".equalsIgnoreCase(name) ? userId : role));
            }
            if ("Authorization".equalsIgnoreCase(name)) {
                return java.util.Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }
}
