package com.ep.knowledge.tenant;

import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.mapper.TenantMapper;
import com.ep.knowledge.tenant.service.TenantService;
import com.ep.knowledge.tenant.service.impl.TenantServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit test for {@link TenantServiceImpl} using Mockito. Avoids Spring Boot's
 * @MockBean (deprecated/removed in Spring Boot 4) and keeps the test fast and
 * isolated from the unavailable DB/Redis environment.
 */
class TenantServiceApplicationTest {

    private TenantMapper tenantMapper;
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        tenantService = new TenantServiceImpl(tenantMapper);
    }

    @Test
    void contextLoads() {
        assertNotNull(tenantService);
    }

    @Test
    void createTenantSetsDefaults() {
        Tenant input = new Tenant();
        input.setName("TestCorp");
        when(tenantMapper.insert(any(Tenant.class))).thenReturn(1);

        Tenant created = tenantService.create(input);

        assertNotNull(created.getCreatedAt());
        assertEquals("FREE", created.getPlan());
        assertEquals(1000L, created.getQuota());
        verify(tenantMapper, times(1)).insert(any(Tenant.class));
    }

    @Test
    void getTenantReturnsFromMapper() {
        Tenant t = new Tenant();
        t.setId("t1");
        t.setName("TestCorp");
        when(tenantMapper.selectById("t1")).thenReturn(t);

        Tenant result = tenantService.get("t1");
        assertNotNull(result);
        assertEquals("TestCorp", result.getName());
    }
}
