package com.ep.web.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoleChecker}. Verifies the byte-identical behavior that
 * was previously copy-pasted into six services: X-Role header lookup, the
 * anonymous default, and role-based enforcement.
 */
class RoleCheckerTest {

    @AfterEach
    void clearTestRole() {
        // Ensure ep.test.role never leaks between tests.
        System.clearProperty("ep.test.role");
    }

    private HttpServletRequest requestWithRole(String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(role);
        return req;
    }

    private HttpServletRequest requestWithoutRole() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(null);
        return req;
    }

    @Test
    void test_admin_role_passes_admin_check() {
        HttpServletRequest req = requestWithRole("admin");
        assertDoesNotThrow(() -> RoleChecker.requireRole(req, "admin", "super_admin"));
    }

    @Test
    void test_anonymous_role_fails_admin_check() {
        HttpServletRequest req = requestWithRole("anonymous");
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> RoleChecker.requireRole(req, "admin", "super_admin"));
        assertEquals("Role 'anonymous' is not authorized for this action", ex.getMessage());
    }

    @Test
    void test_null_role_defaults_anonymous() {
        // Missing X-Role header must default to "anonymous" (Security D-076),
        // never to "admin".
        HttpServletRequest req = requestWithoutRole();
        assertEquals("anonymous", RoleChecker.getRole(req));
        assertThrows(ForbiddenException.class,
                () -> RoleChecker.requireRole(req, "admin", "super_admin"));
    }

    @Test
    void test_custom_role_passes_correct_check() {
        HttpServletRequest req = requestWithRole("teacher");
        assertDoesNotThrow(() -> RoleChecker.requireRole(req, "student", "teacher"));
    }

    @Test
    void test_require_role_throws_forbidden_on_mismatch() {
        HttpServletRequest req = requestWithRole("student");
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> RoleChecker.requireRole(req, "teacher", "admin"));
        assertEquals("Role 'student' is not authorized for this action", ex.getMessage());
    }

    @Test
    void test_require_admin_throws_forbidden_for_student() {
        HttpServletRequest req = requestWithRole("student");
        assertThrows(ForbiddenException.class,
                () -> RoleChecker.requireRole(req, "admin", "super_admin"));
    }

    @Test
    void test_ep_test_role_system_property_overrides_default() {
        // Standalone/test setups can bypass the missing header by setting
        // -Dep.test.role=admin; this preserves the existing escape hatch.
        HttpServletRequest req = requestWithoutRole();
        System.setProperty("ep.test.role", "admin");
        assertEquals("admin", RoleChecker.getRole(req));
        assertDoesNotThrow(() -> RoleChecker.requireRole(req, "admin"));
    }
}
