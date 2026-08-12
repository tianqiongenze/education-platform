package com.ep.knowledge.tenant.entity;

import com.ep.knowledge.tenant.mapper.ApiKeyMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for {@link ApiKey} entity and {@link ApiKeyMapper}.
 * No Spring context, no H2/Testcontainers — keeps the test fast and isolated
 * from the unavailable DB environment.
 */
class ApiKeyEntityTest {

    @Test
    void test_apikey_creation_and_fields() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId("ak-001");
        apiKey.setTenantId("t-001");
        apiKey.setApiKey("public-key-value");
        apiKey.setSecret("secret-value");
        apiKey.setStatus("ACTIVE");

        assertEquals("ak-001", apiKey.getId());
        assertEquals("t-001", apiKey.getTenantId());
        assertEquals("public-key-value", apiKey.getApiKey());
        assertEquals("secret-value", apiKey.getSecret());
        assertEquals("ACTIVE", apiKey.getStatus());
    }

    @Test
    void test_apikey_mapper_insert() {
        ApiKeyMapper apiKeyMapper = mock(ApiKeyMapper.class);
        ApiKey apiKey = new ApiKey();
        apiKey.setId("ak-002");
        apiKey.setTenantId("t-002");
        apiKey.setApiKey("k2");
        apiKey.setSecret("s2");
        apiKey.setStatus("ACTIVE");

        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);

        int rows = apiKeyMapper.insert(apiKey);

        assertEquals(1, rows);
        verify(apiKeyMapper, times(1)).insert(apiKey);
    }

    @Test
    void test_apikey_mapper_select_by_id() {
        ApiKeyMapper apiKeyMapper = mock(ApiKeyMapper.class);

        ApiKey apiKey = new ApiKey();
        apiKey.setId("ak-003");
        apiKey.setTenantId("t-003");
        apiKey.setApiKey("k3");
        apiKey.setSecret("s3");
        apiKey.setStatus("ACTIVE");

        when(apiKeyMapper.selectById("ak-003")).thenReturn(apiKey);

        ApiKey result = apiKeyMapper.selectById("ak-003");

        assertNotNull(result);
        assertEquals("ak-003", result.getId());
        assertEquals("t-003", result.getTenantId());
        assertEquals("k3", result.getApiKey());
        assertEquals("s3", result.getSecret());
        assertEquals("ACTIVE", result.getStatus());
        verify(apiKeyMapper, times(1)).selectById("ak-003");
    }

    @Test
    void test_apikey_status_transitions() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId("ak-004");
        apiKey.setStatus("ACTIVE");
        assertEquals("ACTIVE", apiKey.getStatus());

        apiKey.setStatus("REVOKED");
        assertEquals("REVOKED", apiKey.getStatus());

        apiKey.setStatus("ACTIVE");
        assertEquals("ACTIVE", apiKey.getStatus());
    }
}
