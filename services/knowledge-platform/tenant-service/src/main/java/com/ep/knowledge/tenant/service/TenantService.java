package com.ep.knowledge.tenant.service;

import com.ep.knowledge.tenant.entity.Tenant;

public interface TenantService {

    Tenant create(Tenant tenant);

    Tenant get(String id);

    Tenant updateQuota(String id, Long quota);

    void delete(String id);
}
