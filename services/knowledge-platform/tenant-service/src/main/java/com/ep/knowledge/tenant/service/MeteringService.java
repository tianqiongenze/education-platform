package com.ep.knowledge.tenant.service;

import java.util.Map;

/**
 * D-179: Metering service — records and aggregates tenant usage events.
 */
public interface MeteringService {

    /**
     * Record a usage event for a tenant.
     *
     * @param tenantId     the tenant id
     * @param resourceType one of API_CALL | LLM_TOKEN | STORAGE | AI_INFERENCE
     * @param quantity     the usage quantity
     */
    void record(String tenantId, String resourceType, long quantity);

    /**
     * Get usage summary for a tenant in a period (YYYY-MM).
     *
     * @param tenantId the tenant id
     * @param period   the billing period in YYYY-MM format
     * @return a map keyed by resourceType, value = summed quantity for that type
     */
    Map<String, Long> summarize(String tenantId, String period);
}
