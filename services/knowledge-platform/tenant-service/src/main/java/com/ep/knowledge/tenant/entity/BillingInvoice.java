package com.ep.knowledge.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * D-179: Billing invoice for a tenant for a billing period (YYYY-MM).
 */
@TableName("tenant_billing_invoice")
public class BillingInvoice {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField("tenant_id")
    private String tenantId;

    /** Billing period in YYYY-MM format. */
    private String period;

    @TableField("total_amount_cents")
    private Long totalAmountCents;

    /** PENDING | PAID | OVERDUE */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Long getTotalAmountCents() { return totalAmountCents; }
    public void setTotalAmountCents(Long totalAmountCents) { this.totalAmountCents = totalAmountCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
