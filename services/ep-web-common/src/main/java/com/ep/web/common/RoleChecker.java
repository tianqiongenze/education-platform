package com.ep.web.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * Static authorization helpers for education-platform controllers.
 *
 * <p>This is the single shared copy of a utility that was previously
 * copy-pasted into six services (core-business, grading-service, and four
 * knowledge-platform sub-services). Only the package differed between copies; the logic
 * was byte-identical, so it has been hoisted into ep-web-common unchanged.</p>
 *
 * <p>The BFF ({@code portal-bff}) AuthFilter strips any client-supplied
 * {@code X-Role} header and re-injects a trusted value derived from the JWT.
 * Controllers therefore call {@link #requireRole(HttpServletRequest, String...)}
 * before performing privileged actions; the header is the single source of
 * truth for the caller's role inside each service.</p>
 *
 * <p>Risk-control P0-2 (IDOR): the same trust boundary applies to the
 * {@code X-User-Id} header (JWT subject, injected by the BFF AuthFilter).
 * Use {@link #getUserId(HttpServletRequest)} together with
 * {@link #requireOwnership(HttpServletRequest, Long)} on every endpoint that
 * takes a resource owner id as a path/query parameter, so a logged-in user
 * cannot read another user's resources by swapping ids.</p>
 *
 * <p>This is a utility class, not a Spring bean; all methods are static.</p>
 */
public final class RoleChecker {

    /** Platform-wide admin role (used by tenant-service quota endpoint). */
    public static final String PLATFORM_ADMIN = "platform_admin";

    /**
     * P0-2: roles that bypass per-user ownership checks. A member of any of
     * these roles may read/administer resources owned by other users.
     */
    public static final Set<String> ADMIN_ROLES = Set.of("admin", "super_admin", PLATFORM_ADMIN);

    private RoleChecker() {
    }

    /**
     * Extract the caller's trusted user id from the {@code X-User-Id} header
     * injected by the BFF AuthFilter (JWT subject).
     *
     * <p>Unlike {@link #getRole(HttpServletRequest)} there is deliberately NO
     * system-property fallback: an absent header means the request did not pass
     * the BFF, and ownership checks must fail closed.</p>
     *
     * @param request the current HTTP request
     * @return the caller's user id, or {@code null} when the header is absent/blank
     */
    public static Long getUserId(HttpServletRequest request) {
        String id = request.getHeader("X-User-Id");
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Tests whether the caller holds any of the given roles. */
    public static boolean hasRole(HttpServletRequest request, String... roles) {
        String role = getRole(request);
        for (String allowed : roles) {
            if (allowed.equals(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests whether the caller holds an admin-equivalent role
     * ({@link #ADMIN_ROLES}), which bypasses ownership checks.
     */
    public static boolean isAdmin(HttpServletRequest request) {
        return ADMIN_ROLES.contains(getRole(request));
    }

    /**
     * P0-2 horizontal-authorization (IDOR) guard. Throws {@link ForbiddenException}
     * unless the caller owns the target resource ({@code targetUserId} equals the
     * BFF-injected {@code X-User-Id}) or holds an admin-equivalent role.
     *
     * <p>Fail-closed: a request without a trusted {@code X-User-Id} header is
     * rejected (unless the caller is admin, which the BFF may legitimately send
     * for service-to-service calls).</p>
     *
     * @param request      the current HTTP request
     * @param targetUserId the owner of the resource being accessed
     * @throws ForbiddenException when the caller is neither the owner nor an admin
     */
    public static void requireOwnership(HttpServletRequest request, Long targetUserId) {
        if (isAdmin(request)) {
            return;
        }
        Long caller = getUserId(request);
        if (caller == null || targetUserId == null || !caller.equals(targetUserId)) {
            throw new ForbiddenException(
                    "Caller " + (caller == null ? "(anonymous)" : caller)
                            + " is not allowed to access resources of user " + targetUserId);
        }
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
