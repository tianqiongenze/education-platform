package com.ep.knowledge.tenant.service;

/**
 * Token-bucket rate limiter backed by Redis.
 */
public interface QuotaService {

    /**
     * Try to consume {@code tokens} for the given tenant.
     * @return true if allowed, false if rate-limited.
     */
    boolean tryAcquire(String tenantId, int tokens);

    /**
     * Get remaining quota for the tenant.
     */
    long remaining(String tenantId);
}
