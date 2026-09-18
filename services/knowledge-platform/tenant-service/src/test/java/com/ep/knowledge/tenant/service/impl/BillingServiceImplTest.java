package com.ep.knowledge.tenant.service.impl;

import com.ep.knowledge.tenant.entity.BillingInvoice;
import com.ep.knowledge.tenant.mapper.BillingInvoiceMapper;
import com.ep.knowledge.tenant.service.MeteringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-179: Mockito-extension unit test for {@link BillingServiceImpl}.
 *
 * <p>Mocks the {@link BillingInvoiceMapper} and a {@link MeteringService} stub
 * so the invoice-generation pricing logic and list/get delegations can be
 * exercised without a database, following the knowledge-platform service-test convention.
 */
@ExtendWith(MockitoExtension.class)
class BillingServiceImplTest {

    @Mock
    private BillingInvoiceMapper billingInvoiceMapper;

    @Mock
    private MeteringService meteringService;

    private BillingServiceImpl billingService;

    @BeforeEach
    void setUp() {
        billingService = new BillingServiceImpl(billingInvoiceMapper, meteringService);
    }

    @Test
    void testGenerateInvoice_computes_amount_and_persists_pending_invoice() {
        // Pricing (in mills = 1/1000 cent):
        //   API_CALL     = 1 mill each     -> 0.001 cent
        //   LLM_TOKEN    = 2 mills each    -> 0.002 cent
        //   AI_INFERENCE = 50 mills each   -> 0.05  cent
        // 1000 API_CALL  = 1000 mills      = 1 cent
        // 1000 LLM_TOKEN = 2000 mills      = 2 cents
        // 10   AI_INFER  = 500  mills      = 0.5 cent (rounds to 1 cent)
        // Total mills    = 3500 mills      = 3.5 cents -> rounds to 4 cents
        Map<String, Long> usage = Map.of(
                "API_CALL", 1000L,
                "LLM_TOKEN", 1000L,
                "AI_INFERENCE", 10L);
        when(meteringService.summarize("t-001", "2026-08")).thenReturn(usage);
        when(billingInvoiceMapper.insert(any(BillingInvoice.class))).thenReturn(1);

        BillingInvoice invoice = billingService.generateInvoice("t-001", "2026-08");

        assertNotNull(invoice);
        assertEquals("t-001", invoice.getTenantId());
        assertEquals("2026-08", invoice.getPeriod());
        assertEquals("PENDING", invoice.getStatus());
        assertNotNull(invoice.getCreatedAt());
        // 1000*1 + 1000*2 + 10*50 = 3500 mills -> 4 cents (rounded)
        assertEquals(4L, invoice.getTotalAmountCents(),
                "totalAmountCents should be 4 cents (3500 mills rounded)");

        ArgumentCaptor<BillingInvoice> captor = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(billingInvoiceMapper, times(1)).insert(captor.capture());
        assertSame(invoice, captor.getValue(), "the inserted invoice is the returned one");
    }

    @Test
    void testGenerateInvoice_zero_usage_yields_zero_amount_invoice() {
        when(meteringService.summarize("t-empty", "2026-08")).thenReturn(Map.of());
        when(billingInvoiceMapper.insert(any(BillingInvoice.class))).thenReturn(1);

        BillingInvoice invoice = billingService.generateInvoice("t-empty", "2026-08");

        assertEquals(0L, invoice.getTotalAmountCents());
        assertEquals("PENDING", invoice.getStatus());
    }

    @Test
    void testListInvoices_returns_invoices_for_tenant_ordered_desc() {
        LocalDateTime now = LocalDateTime.now();
        BillingInvoice inv1 = invoice("inv-1", "t-001", "2026-08", 100L, "PENDING", now.minusDays(1));
        BillingInvoice inv2 = invoice("inv-2", "t-001", "2026-07", 200L, "PAID", now.minusDays(30));
        // The mapper returns whatever the DB query yields; ordering is enforced via the
        // LambdaQueryWrapper passed to selectList, so the mock returns them in order.
        when(billingInvoiceMapper.selectList(any())).thenReturn(List.of(inv1, inv2));

        List<BillingInvoice> result = billingService.listInvoices("t-001");

        assertEquals(2, result.size());
        assertEquals("inv-1", result.get(0).getId());
        assertEquals("inv-2", result.get(1).getId());
        verify(billingInvoiceMapper, times(1)).selectList(any());
    }

    @Test
    void testGenerateInvoice_unknown_resource_type_is_skipped() {
        // Unknown types must not crash and must not contribute to the total.
        Map<String, Long> usage = Map.of(
                "API_CALL", 1000L,            // 1000 mills -> 1 cent
                "UNKNOWN_TYPE", 999_999L);    // ignored
        when(meteringService.summarize("t-001", "2026-08")).thenReturn(usage);
        when(billingInvoiceMapper.insert(any(BillingInvoice.class))).thenReturn(1);

        BillingInvoice invoice = billingService.generateInvoice("t-001", "2026-08");

        assertEquals(1L, invoice.getTotalAmountCents(),
                "only the known type should be priced");
    }

    private static BillingInvoice invoice(String id, String tenantId, String period,
                                          long amountCents, String status,
                                          LocalDateTime createdAt) {
        BillingInvoice inv = new BillingInvoice();
        inv.setId(id);
        inv.setTenantId(tenantId);
        inv.setPeriod(period);
        inv.setTotalAmountCents(amountCents);
        inv.setStatus(status);
        inv.setCreatedAt(createdAt);
        return inv;
    }
}
