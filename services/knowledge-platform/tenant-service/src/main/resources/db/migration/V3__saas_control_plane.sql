-- D-179: SaaS Control Plane — Metering + Billing tables.
-- Versioned DDL (V3). DBA-reviewed, NOT auto-executed (no Flyway to protect
-- shared PG cluster). Apply manually to the `tenant` database.
--
-- Tables:
--   tenant_usage_record    — one row per metered usage event
--   tenant_billing_invoice — one row per generated invoice per tenant/period

CREATE TABLE IF NOT EXISTS tenant_usage_record (
    id            VARCHAR(64)  PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(32)  NOT NULL,             -- API_CALL | LLM_TOKEN | STORAGE | AI_INFERENCE
    quantity      BIGINT       NOT NULL DEFAULT 0,
    recorded_at   TIMESTAMP    NOT NULL,
    INDEX idx_tenant_period (tenant_id, recorded_at)
);

CREATE TABLE IF NOT EXISTS tenant_billing_invoice (
    id                  VARCHAR(64)  PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    period              VARCHAR(7)   NOT NULL,        -- YYYY-MM
    total_amount_cents  BIGINT       NOT NULL DEFAULT 0,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PAID | OVERDUE
    created_at          TIMESTAMP    NOT NULL,
    INDEX idx_tenant (tenant_id)
);
