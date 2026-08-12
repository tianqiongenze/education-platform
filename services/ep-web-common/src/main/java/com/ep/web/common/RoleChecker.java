package com.ep.web.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Static authorization helpers for education-platform controllers.
 *
 * <p>This is the single shared copy of a utility that was previously
 * copy-pasted into six services (core-business, grading-service, and four
 * sp07-* sub-services). Only the package differed between copies; the logic
 * was byte-identical, so it has been hoisted into ep-web-common unchanged.</p>
 *
 * <p>The BFF ({@code portal-bff}) AuthFilter strips any client-supplied
 * {@code X-Role} header and re-injects a trusted value derived from the JWT.
 * Controllers therefore call {@link #requireRole(HttpServletRequest, String...)}
 * before performing privileged actions; the header is the single source of
 * truth for the caller's role inside each service.</p>
 *
 * <p>This is a utility class, not a Spring bean; all methods are static.</p>
 */
public final class RoleChecker {

    /** Platform-wide admin role (used by tenant-service quota endpoint). */
    public static final String PLATFORM_ADMIN = "platform_admin";

    private RoleChecker() {
    }

    /**
     * Extract the caller's role from the {@code X-Role} header injected by the
     * BFF AuthFilter.
     *
     * @param request the current HTTP request
     * @return the role header value, or {@code "anonymous"} when absent
     */
    public static String getRole(HttpServletRequest request) {
        String role = request.getHeader("X-Role");
        if (role == null || role.isBlank()) {
            // Security D-076: default to "anonymous" (not "admin") when no BFF is
            // present. Tests/standalone setups can still bypass by setting the
            // ep.test.role system property explicitly.
            return System.getProperty("ep.test.role", "anonymous");
        }
        return role;
    }

    /**
     * Require one of the allowed roles; throw {@link ForbiddenException} (HTTP
     * 403) if the caller's role is not in the allowed set.
     *
     * @param request      the current HTTP request
     * @param allowedRoles the roles permitted to perform this action
     * @throws ForbiddenException if the caller's role is not allowed
     */
    public static void requireRole(HttpServletRequest request, String... allowedRoles) {
        String role = getRole(request);
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new ForbiddenException("Role '" + role + "' is not authorized for this action");
    }
}
