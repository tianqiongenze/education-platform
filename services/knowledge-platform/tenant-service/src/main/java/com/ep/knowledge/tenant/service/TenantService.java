package com.ep.knowledge.tenant.service;

import com.ep.knowledge.tenant.entity.Tenant;

public interface TenantService {

    Tenant create(Tenant tenant);

    Tenant get(String id);

    Tenant updateQuota(String id, Long quota);

    void delete(String id);

    /** D-201: transition a tenant from PENDING to ACTIVE. */
    Tenant activate(String id);

    /** D-201: transition a tenant from ACTIVE to SUSPENDED. */
    Tenant suspend(String id);

    /** D-201: transition a tenant from SUSPENDED to ACTIVE. */
    Tenant reactivate(String id);
}
