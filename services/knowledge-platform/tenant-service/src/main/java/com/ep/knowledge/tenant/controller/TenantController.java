package com.ep.knowledge.tenant.controller;

import com.ep.web.common.RoleChecker;
import com.ep.knowledge.tenant.entity.ApiKey;
import com.ep.knowledge.tenant.entity.BillingInvoice;
import com.ep.knowledge.tenant.entity.Tenant;
import com.ep.knowledge.tenant.service.ApiKeyService;
import com.ep.knowledge.tenant.service.BillingService;
import com.ep.knowledge.tenant.service.MeteringService;
import com.ep.knowledge.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * D-179: Tenant control plane endpoints.
 *
 * <p>Extends the original tenant CRUD surface with three new capabilities:
 * <ul>
 *   <li><b>API Keys</b> — create / list / revoke tenant API keys</li>
 *   <li><b>Metering</b> — record usage events and query usage summaries</li>
 *   <li><b>Billing</b> — generate invoices for a billing period and list invoices</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "租户管理", description = "租户、API Key、计量与账单管理")
@Validated
public class TenantController {

    private final TenantService tenantService;
    private final ApiKeyService apiKeyService;
    private final MeteringService meteringService;
    private final BillingService billingService;

    public TenantController(TenantService tenantService,
                            ApiKeyService apiKeyService,
                            MeteringService meteringService,
                            BillingService billingService) {
        this.tenantService = tenantService;
        this.apiKeyService = apiKeyService;
        this.meteringService = meteringService;
        this.billingService = billingService;
    }

    // ----- Tenant CRUD ---------------------------------------------------

    @PostMapping
    @Operation(summary = "创建租户")
    public Tenant create(@Valid @RequestBody Tenant tenant) {
        return tenantService.create(tenant);
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询租户")
    public ResponseEntity<Tenant> get(@PathVariable String id) {
        Tenant t = tenantService.get(id);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    @PutMapping("/{id}/quota")
    @Operation(summary = "更新租户配额 (platform_admin only)")
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

    // ----- API Keys ------------------------------------------------------

    @PostMapping("/{id}/api-keys")
    @Operation(summary = "为租户创建新的 API Key")
    public ApiKey createApiKey(@PathVariable String id) {
        return apiKeyService.create(id);
    }

    @GetMapping("/{id}/api-keys")
    @Operation(summary = "列出租户的 API Keys")
    public List<ApiKey> listApiKeys(@PathVariable String id) {
        return apiKeyService.list(id);
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @Operation(summary = "吊销租户的 API Key")
    public ResponseEntity<Void> revokeApiKey(@PathVariable String id,
                                             @PathVariable String keyId,
                                             HttpServletRequest request) {
        // Security D-179: revoking a key is a privileged operation.
        RoleChecker.requireRole(request, RoleChecker.PLATFORM_ADMIN);
        apiKeyService.revoke(keyId);
        return ResponseEntity.noContent().build();
    }

    // ----- Metering ------------------------------------------------------

    @PostMapping("/{id}/usage")
    @Operation(summary = "记录租户用量事件")
    public ResponseEntity<Void> recordUsage(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        String resourceType = (String) body.get("resourceType");
        Object qtyObj = body.get("quantity");
        if (resourceType == null || resourceType.isBlank() || qtyObj == null) {
            return ResponseEntity.badRequest().build();
        }
        long quantity;
        try {
            quantity = Long.parseLong(qtyObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
        meteringService.record(id, resourceType, quantity);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/usage/{period}")
    @Operation(summary = "查询租户在某计费周期的用量汇总")
    public Map<String, Long> summarizeUsage(@PathVariable String id,
                                            @PathVariable String period) {
        return meteringService.summarize(id, period);
    }

    // ----- Billing -------------------------------------------------------

    @PostMapping("/{id}/invoices/{period}")
    @Operation(summary = "为租户生成指定周期的账单")
    public BillingInvoice generateInvoice(@PathVariable String id,
                                          @PathVariable String period) {
        return billingService.generateInvoice(id, period);
    }

    @GetMapping("/{id}/invoices")
    @Operation(summary = "列出租户的账单")
    public List<BillingInvoice> listInvoices(@PathVariable String id) {
        return billingService.listInvoices(id);
    }
}
