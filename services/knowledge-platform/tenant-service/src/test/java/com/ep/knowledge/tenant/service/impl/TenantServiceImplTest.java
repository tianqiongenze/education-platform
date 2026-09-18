package com.ep.knowledge.tenant.service.impl;

import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mockito-extension unit test for {@link TenantServiceImpl}.
 *
 * <p>Mocks the {@link TenantMapper} MyBatis-Plus interface so the tenant CRUD
 * logic (default plan/quota, created-at stamping, quota update, delete) can be
 * exercised without a database. Follows the knowledge-platform service-test convention.
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceImplTest {

    @Mock
    private TenantMapper tenantMapper;

    private TenantServiceImpl tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantServiceImpl(tenantMapper);
    }

    @Test
    void create_inserts_and_sets_defaults_for_blank_fields() {
        Tenant input = new Tenant();
        input.setId("t1");
        input.setName("Acme");

        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);

        Tenant result = tenantService.create(input);

        assertSame(input, result);
        assertEquals("FREE", result.getPlan(), "plan should default to FREE");
        assertEquals(1000L, result.getQuota(), "quota should default to 1000");
        assertNotNull(result.getCreatedAt(), "createdAt should be stamped");
        verify(tenantMapper, times(1)).insert(input);
    }

    @Test
    void create_preserves_explicit_plan_and_quota() {
        Tenant input = new Tenant();
        input.setId("t1");
        input.setPlan("PRO");
        input.setQuota(5000L);
        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);

        Tenant result = tenantService.create(input);

        assertEquals("PRO", result.getPlan());
        assertEquals(5000L, result.getQuota());
    }

    @Test
    void create_preserves_existing_created_at() {
        Tenant input = new Tenant();
        input.setId("t1");
        java.time.LocalDateTime fixed = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        input.setCreatedAt(fixed);
        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);

        Tenant result = tenantService.create(input);

        assertEquals(fixed, result.getCreatedAt(), "existing createdAt must not be overwritten");
    }

    @Test
    void create_captures_tenant_into_mapper() {
        Tenant input = new Tenant();
        input.setId("t1");
        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);

        tenantService.create(input);

        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).insert(captor.capture());
        assertEquals("t1", captor.getValue().getId());
    }

    @Test
    void get_delegates_to_selectById() {
        Tenant t = new Tenant();
        t.setId("t1");
        t.setName("Acme");
        when(tenantMapper.selectById("t1")).thenReturn(t);

        Tenant result = tenantService.get("t1");

        assertSame(t, result);
        verify(tenantMapper).selectById("t1");
    }

    @Test
    void get_returns_null_when_not_found() {
        when(tenantMapper.selectById("missing")).thenReturn(null);

        assertNull(tenantService.get("missing"));
    }

    @Test
    void updateQuota_returns_null_when_tenant_missing() {
        when(tenantMapper.selectById("ghost")).thenReturn(null);

        Tenant result = tenantService.updateQuota("ghost", 999L);

        assertNull(result);
        verify(tenantMapper, never()).updateById(any(Tenant.class));
    }

    @Test
    void updateQuota_updates_and_returns_tenant() {
        Tenant existing = new Tenant();
        existing.setId("t1");
        existing.setQuota(1000L);
        when(tenantMapper.selectById("t1")).thenReturn(existing);
        when(tenantMapper.updateById(existing)).thenReturn(1);

        Tenant result = tenantService.updateQuota("t1", 5000L);

        assertSame(existing, result);
        assertEquals(5000L, result.getQuota());
        verify(tenantMapper).updateById(existing);
    }

    @Test
    void delete_delegates_to_deleteById() {
        tenantService.delete("t1");

        verify(tenantMapper, times(1)).deleteById("t1");
    }
}
