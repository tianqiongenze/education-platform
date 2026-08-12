package com.ep.knowledge.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ep.knowledge.tenant.entity.ApiKey;
import com.ep.knowledge.tenant.mapper.ApiKeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D-179: Mockito-extension unit test for {@link ApiKeyServiceImpl}.
 *
 * <p>Mocks the {@link ApiKeyMapper} so the create/validate/list/revoke logic
 * can be exercised without a database, following the sp07 service-test
 * convention (no Spring context, no H2).
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceImplTest {

    @Mock
    private ApiKeyMapper apiKeyMapper;

    private ApiKeyServiceImpl apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyServiceImpl(apiKeyMapper);
    }

    @Test
    void testCreate_generates_ep_prefixed_key_with_active_status_and_persists() {
        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);

        ApiKey created = apiKeyService.create("t-001");

        assertNotNull(created);
        assertEquals("t-001", created.getTenantId());
        assertNotNull(created.getApiKey());
        assertTrue(created.getApiKey().startsWith("ep_"),
                "apiKey must have the ep_ prefix");
        assertTrue(created.getApiKey().length() > "ep_".length(),
                "apiKey must have a body after the prefix");
        assertNotNull(created.getSecret(), "secret must be generated");
        assertEquals(64, created.getSecret().length(),
                "secret must be 64 hex chars (32 bytes)");
        assertTrue(created.getSecret().matches("[0-9a-f]{64}"),
                "secret must be lowercase hex");
        assertEquals("ACTIVE", created.getStatus());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyMapper, times(1)).insert(captor.capture());
        assertSame(created, captor.getValue());
    }

    @Test
    void testCreate_generates_unique_keys_across_invocations() {
        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);

        ApiKey a = apiKeyService.create("t-001");
        ApiKey b = apiKeyService.create("t-001");

        assertNotEquals(a.getApiKey(), b.getApiKey(), "apiKeys must be unique");
        assertNotEquals(a.getSecret(), b.getSecret(), "secrets must be unique");
    }

    @Test
    void testValidate_returns_tenantId_for_active_key() {
        ApiKey key = new ApiKey();
        key.setId("ak-1");
        key.setTenantId("t-001");
        key.setApiKey("ep_abc123");
        key.setStatus("ACTIVE");
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(key);

        String tenantId = apiKeyService.validate("ep_abc123");

        assertEquals("t-001", tenantId);
    }

    @Test
    void testValidate_returns_null_when_key_not_found() {
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        String tenantId = apiKeyService.validate("ep_nonexistent");

        assertNull(tenantId);
    }

    @Test
    void testValidate_returns_null_for_revoked_key() {
        ApiKey key = new ApiKey();
        key.setId("ak-2");
        key.setTenantId("t-001");
        key.setApiKey("ep_revoked");
        key.setStatus("REVOKED");
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(key);

        String tenantId = apiKeyService.validate("ep_revoked");

        assertNull(tenantId, "revoked keys must not validate");
    }

    @Test
    void testRevoke_updates_status_to_revoked() {
        ApiKey existing = new ApiKey();
        existing.setId("ak-3");
        existing.setTenantId("t-001");
        existing.setApiKey("ep_xyz");
        existing.setStatus("ACTIVE");
        when(apiKeyMapper.selectById("ak-3")).thenReturn(existing);
        when(apiKeyMapper.updateById(existing)).thenReturn(1);

        apiKeyService.revoke("ak-3");

        assertEquals("REVOKED", existing.getStatus(), "status should be updated to REVOKED");
        verify(apiKeyMapper, times(1)).selectById("ak-3");
        verify(apiKeyMapper, times(1)).updateById(existing);
    }

    @Test
    void testList_returns_keys_for_tenant() {
        ApiKey k1 = new ApiKey();
        k1.setId("ak-1");
        k1.setTenantId("t-001");
        ApiKey k2 = new ApiKey();
        k2.setId("ak-2");
        k2.setTenantId("t-001");
        when(apiKeyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(k1, k2));

        List<ApiKey> keys = apiKeyService.list("t-001");

        assertEquals(2, keys.size());
        assertEquals("ak-1", keys.get(0).getId());
        assertEquals("ak-2", keys.get(1).getId());
        verify(apiKeyMapper, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    void testRevoke_then_validate_returns_null() {
        // After revoke(), the persisted key has status REVOKED. validate() must
        // therefore return null even though the key row still exists.
        ApiKey existing = new ApiKey();
        existing.setId("ak-4");
        existing.setTenantId("t-001");
        existing.setApiKey("ep_xyz");
        existing.setStatus("ACTIVE");
        when(apiKeyMapper.selectById("ak-4")).thenReturn(existing);
        when(apiKeyMapper.updateById(existing)).thenReturn(1);
        // After revoke, validate() looks up by api_key and gets the REVOKED row.
        ApiKey revokedKey = new ApiKey();
        revokedKey.setId("ak-4");
        revokedKey.setTenantId("t-001");
        revokedKey.setApiKey("ep_xyz");
        revokedKey.setStatus("REVOKED");
        when(apiKeyMapper.selectOne(any(Wrapper.class))).thenReturn(revokedKey);

        apiKeyService.revoke("ak-4");
        String tenantId = apiKeyService.validate("ep_xyz");

        assertNull(tenantId, "after revoke, validate must return null");
        verify(apiKeyMapper, times(1)).selectById("ak-4");
        verify(apiKeyMapper, times(1)).updateById(existing);
    }

    @Test
    void testRevoke_is_noop_when_key_not_found() {
        when(apiKeyMapper.selectById("missing")).thenReturn(null);

        apiKeyService.revoke("missing");

        verify(apiKeyMapper, times(1)).selectById("missing");
        verify(apiKeyMapper, never()).updateById(any(ApiKey.class));
    }
}
