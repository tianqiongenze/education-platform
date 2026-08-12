package com.ep.knowledge.tenant.service.impl;

import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.mapper.TenantMapper;
import com.ep.knowledge.tenant.service.TenantService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;

    public TenantServiceImpl(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public Tenant create(Tenant tenant) {
        if (tenant.getCreatedAt() == null) {
            tenant.setCreatedAt(LocalDateTime.now());
        }
        if (tenant.getPlan() == null) {
            tenant.setPlan("FREE");
        }
        if (tenant.getQuota() == null) {
            tenant.setQuota(1000L);
        }
        tenantMapper.insert(tenant);
        return tenant;
    }

    @Override
    public Tenant get(String id) {
        return tenantMapper.selectById(id);
    }

    @Override
    public Tenant updateQuota(String id, Long quota) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) {
            return null;
        }
        t.setQuota(quota);
        tenantMapper.updateById(t);
        return t;
    }

    @Override
    public void delete(String id) {
        tenantMapper.deleteById(id);
    }
}
