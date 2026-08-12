package com.ep.knowledge.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ep.knowledge.tenant.entity.BillingInvoice;
import com.ep.knowledge.tenant.mapper.BillingInvoiceMapper;
import com.ep.knowledge.tenant.service.BillingService;
import com.ep.knowledge.tenant.service.MeteringService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * D-179: Billing service implementation.
 *
 * <p>{@code generateInvoice()} calls {@link MeteringService#summarize} to fetch
 * the tenant's usage for the period, applies the unit pricing below, and creates
 * a {@link BillingInvoice} with status {@code PENDING}.
 *
 * <p>Unit pricing (in cents):
 * <ul>
 *   <li>API_CALL      = 0.001 cents each  -> 1 mill    per 1000 calls</li>
 *   <li>LLM_TOKEN     = 0.002 cents each  -> 2 mills   per 1000 tokens</li>
 *   <li>STORAGE       = 0.0001 cents/byte -> 1 mill    per 10000 bytes</li>
 *   <li>AI_INFERENCE  = 0.05  cents each  -> 5 mills   per 100 inferences</li>
 * </ul>
 *
 * <p>Because sub-cent unit prices cannot be represented as integer cents, the
 * total is computed using mill (1/10 cent) precision internally and rounded to
 * the nearest whole cent at the end. This matches standard SaaS billing
 * rounding conventions.
 *
 * <p>{@code tenant_billing_invoice} is NOT a tenant-scoped table in
 * {@code MybatisPlusConfig.TENANT_TABLES}; manual tenantId filters are used.
 */
@Service
public class BillingServiceImpl implements BillingService {

    /** Sub-cent unit prices expressed in mills (1 mill = 1/1000 cent). */
    private static final Map<String, Long> UNIT_PRICE_MILLS = Map.of(
            "API_CALL",     1L,   // 0.001 cent
            "LLM_TOKEN",    2L,   // 0.002 cent
            "STORAGE",      0L,   // 0.0001 cent -> 1 mill per 10 bytes
            "AI_INFERENCE", 50L   // 0.05 cent
    );

    /** For STORAGE, pricing is per byte; the mill-unit value is 1 mill per 10 bytes. */
    private static final long STORAGE_BYTES_PER_MILL = 10L;

    private final BillingInvoiceMapper billingInvoiceMapper;
    private final MeteringService meteringService;

    public BillingServiceImpl(BillingInvoiceMapper billingInvoiceMapper,
                              MeteringService meteringService) {
        this.billingInvoiceMapper = billingInvoiceMapper;
        this.meteringService = meteringService;
    }

    @Override
    public BillingInvoice generateInvoice(String tenantId, String period) {
        Map<String, Long> usage = meteringService.summarize(tenantId, period);

        long totalMills = 0L;
        for (Map.Entry<String, Long> entry : usage.entrySet()) {
            String type = entry.getKey();
            long qty = entry.getValue();
            Long unitMills = UNIT_PRICE_MILLS.get(type);
            if (unitMills == null) {
                // Unknown resource type — skip it (cannot be priced).
                continue;
            }
            if ("STORAGE".equals(type)) {
                // 0.0001 cent/byte -> 1 mill per 10 bytes
                totalMills += Math.addExact(qty / STORAGE_BYTES_PER_MILL, 0L);
            } else {
                // mills = unitMills * qty
                totalMills = Math.addExact(totalMills, Math.multiplyExact(unitMills, qty));
            }
        }

        // Convert mills (1/1000 cent) to cents, rounding to nearest whole cent.
        long totalCents = Math.round(totalMills / 1000.0);

        BillingInvoice invoice = new BillingInvoice();
        invoice.setTenantId(tenantId);
        invoice.setPeriod(period);
        invoice.setTotalAmountCents(totalCents);
        invoice.setStatus("PENDING");
        invoice.setCreatedAt(LocalDateTime.now());
        billingInvoiceMapper.insert(invoice);
        return invoice;
    }

    @Override
    public BillingInvoice getInvoice(String invoiceId) {
        return billingInvoiceMapper.selectById(invoiceId);
    }

    @Override
    public List<BillingInvoice> listInvoices(String tenantId) {
        return billingInvoiceMapper.selectList(
                new QueryWrapper<BillingInvoice>()
                        .eq("tenant_id", tenantId)
                        .orderByDesc("created_at"));
    }
}
