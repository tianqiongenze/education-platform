package com.ep.knowledge.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ep.knowledge.tenant.entity.ApiKey;
import com.ep.knowledge.tenant.mapper.ApiKeyMapper;
import com.ep.knowledge.tenant.service.ApiKeyService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * D-179: API Key service implementation.
 *
 * <p>{@code create()} generates a public {@code apiKey} with the {@code ep_}
 * prefix followed by a random UUID, and a {@code secret} consisting of 32
 * random bytes rendered as a 64-character lowercase hex string. The new key is
 * inserted with status {@code ACTIVE}.
 *
 * <p>{@code validate()} looks up the key by its public {@code apiKey} value and
 * returns the associated {@code tenantId} if the key is {@code ACTIVE};
 * otherwise it returns {@code null}.
 *
 * <p>{@code revoke()} updates the status of the key with the given primary-key
 * id to {@code REVOKED}.
 *
 * <p>Note: {@code tenant_api_key} IS registered as a tenant-scoped table in
 * {@code MybatisPlusConfig.TENANT_TABLES}, so the tenant-line interceptor will
 * auto-append a {@code tenant_id = ?} filter based on {@code TenantContext}.
 * The tests mock the mapper directly, bypassing the interceptor, so the explicit
 * {@code tenantId} argument is what the service passes through.
 */
@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final String KEY_PREFIX = "ep_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyServiceImpl(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    @Override
    public ApiKey create(String tenantId) {
        ApiKey apiKey = new ApiKey();
        apiKey.setTenantId(tenantId);
        apiKey.setApiKey(KEY_PREFIX + UUID.randomUUID().toString().replace("-", ""));
        apiKey.setSecret(generateSecret());
        apiKey.setStatus("ACTIVE");
        apiKeyMapper.insert(apiKey);
        return apiKey;
    }

    @Override
    public String validate(String apiKey) {
        ApiKey found = apiKeyMapper.selectOne(
                new QueryWrapper<ApiKey>().eq("api_key", apiKey));
        if (found == null || !"ACTIVE".equals(found.getStatus())) {
            return null;
        }
        return found.getTenantId();
    }

    @Override
    public List<ApiKey> list(String tenantId) {
        return apiKeyMapper.selectList(
                new QueryWrapper<ApiKey>().eq("tenant_id", tenantId));
    }

    @Override
    public void revoke(String keyId) {
        ApiKey toRevoke = apiKeyMapper.selectById(keyId);
        if (toRevoke == null) {
            return;
        }
        toRevoke.setStatus("REVOKED");
        apiKeyMapper.updateById(toRevoke);
    }

    /** Generate a 32-byte random secret, hex-encoded to 64 lowercase chars. */
    private static String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
