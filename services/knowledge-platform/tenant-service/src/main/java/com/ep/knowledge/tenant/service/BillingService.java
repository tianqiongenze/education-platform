package com.ep.knowledge.tenant.service;

import com.ep.knowledge.tenant.entity.BillingInvoice;

import java.util.List;

/**
 * D-179: Billing service — generates and manages invoices for tenant usage.
 */
public interface BillingService {

    /**
     * Generate an invoice for a tenant for a given period (YYYY-MM).
     *
     * @param tenantId the tenant id
     * @param period   the billing period in YYYY-MM format
     * @return the generated invoice (status PENDING)
     */
    BillingInvoice generateInvoice(String tenantId, String period);

    /**
     * Get an invoice by id.
     *
     * @param invoiceId the invoice id
     * @return the invoice, or null if not found
     */
    BillingInvoice getInvoice(String invoiceId);

    /**
     * List invoices for a tenant, most recent first.
     *
     * @param tenantId the tenant id
     * @return list of invoices for the tenant
     */
    List<BillingInvoice> listInvoices(String tenantId);
}
