package com.ep.knowledge.tenant.service;

import com.ep.knowledge.tenant.entity.ApiKey;

import java.util.List;

/**
 * D-179: API Key service — CRUD lifecycle for tenant API keys.
 */
public interface ApiKeyService {

    /**
     * Create a new API key for a tenant.
     *
     * @param tenantId the tenant id
     * @return the created ApiKey (with apiKey and secret populated, status ACTIVE)
     */
    ApiKey create(String tenantId);

    /**
     * Validate an API key.
     *
     * @param apiKey the public api key value
     * @return the tenantId if the key is valid and ACTIVE, null otherwise
     */
    String validate(String apiKey);

    /**
     * List API keys for a tenant.
     *
     * @param tenantId the tenant id
     * @return list of api keys for the tenant
     */
    List<ApiKey> list(String tenantId);

    /**
     * Revoke an API key.
     *
     * @param keyId the api key id (PK)
     */
    void revoke(String keyId);
}
