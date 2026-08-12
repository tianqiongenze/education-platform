package com.ep.knowledge.tenant.controller;

import com.ep.web.common.ForbiddenException;
import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.service.ApiKeyService;
import com.ep.knowledge.tenant.service.BillingService;
import com.ep.knowledge.tenant.service.MeteringService;
import com.ep.knowledge.tenant.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test for {@link TenantController} role authorization (D-076).
 * Verifies that quota changes require the platform_admin role.
 */
class TenantControllerTest {

    private TenantService tenantService;
    private ApiKeyService apiKeyService;
    private MeteringService meteringService;
    private BillingService billingService;
    private TenantController controller;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        apiKeyService = mock(ApiKeyService.class);
        meteringService = mock(MeteringService.class);
        billingService = mock(BillingService.class);
        controller = new TenantController(tenantService, apiKeyService, meteringService, billingService);
    }

    private HttpServletRequest requestWithRole(String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(role);
        return req;
    }

    @Test
    void updateQuota_allowedForPlatformAdmin() {
        HttpServletRequest req = requestWithRole("platform_admin");
        Tenant t = new Tenant();
        t.setId("t1");
        t.setQuota(5000L);
        when(tenantService.updateQuota("t1", 5000L)).thenReturn(t);

        ResponseEntity<Tenant> result = controller.updateQuota("t1", Map.of("quota", 5000L), req);

        assertNotNull(result.getBody());
        assertEquals(5000L, result.getBody().getQuota());
        verify(tenantService).updateQuota("t1", 5000L);
    }

    @Test
    void updateQuota_deniedForNonAdmin() {
        HttpServletRequest req = requestWithRole("tenant_user");
        assertThrows(ForbiddenException.class,
                () -> controller.updateQuota("t1", Map.of("quota", 5000L), req));
        verify(tenantService, never()).updateQuota(any(), anyLong());
    }

    @Test
    void updateQuota_deniedWhenRoleHeaderAbsent() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Role")).thenReturn(null);
        assertThrows(ForbiddenException.class,
                () -> controller.updateQuota("t1", Map.of("quota", 5000L), req));
        verify(tenantService, never()).updateQuota(any(), anyLong());
    }

    @Test
    void updateQuota_badRequestWhenQuotaMissing() {
        HttpServletRequest req = requestWithRole("platform_admin");
        ResponseEntity<Tenant> result = controller.updateQuota("t1", Map.of(), req);
        assertTrue(result.getStatusCode().is4xxClientError());
        verify(tenantService, never()).updateQuota(any(), anyLong());
    }
}
