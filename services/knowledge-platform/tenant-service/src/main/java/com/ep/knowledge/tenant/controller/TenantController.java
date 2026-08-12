package com.ep.knowledge.tenant.controller;

import com.ep.web.common.RoleChecker;
import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "租户管理", description = "租户与API Key管理")
@Validated
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public Tenant create(@Valid @RequestBody Tenant tenant) {
        return tenantService.create(tenant);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> get(@PathVariable String id) {
        Tenant t = tenantService.get(id);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    @PutMapping("/{id}/quota")
    public ResponseEntity<Tenant> updateQuota(@PathVariable String id,
                                              @RequestBody Map<String, Long> body,
                                              HttpServletRequest request) {
        // Security D-076: quota changes are platform-admin-only.
        RoleChecker.requireRole(request, RoleChecker.PLATFORM_ADMIN);
        Long quota = body.get("quota");
        if (quota == null) {
            return ResponseEntity.badRequest().build();
        }
        Tenant t = tenantService.updateQuota(id, quota);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }
}
