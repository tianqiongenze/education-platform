-- ============================================================
-- OPM (Order-Payment Management) Generalised DDL
-- D-100: Industry-agnostic order + payment tables.
-- ============================================================
--
-- This DDL creates the operational tables for the OPM middle platform,
-- which manages orders and payments across any industry.
--
-- The ep_order table generalises the original education-specific order:
--   - course_id column kept for backward compatibility (nullable since D-100)
--   - product_type discriminator added (COURSE, MEMBERSHIP, SKU, etc.)
--   - tenant_id added for multi-tenant SaaS (D-044)
--
-- Target database: TiDB (MySQL-compatible)
-- ============================================================

-- ============================================================
-- ep_order: Generalised order table
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_order (
    order_id          BIGINT       NOT NULL AUTO_RANDOM COMMENT 'Order ID',
    user_id           BIGINT       NOT NULL              COMMENT 'User ID',
    course_id         BIGINT                             COMMENT 'Product ID (D-100: generalised; nullable for non-inventory products; column name kept for back-compat)',
    amount            DECIMAL(18,2) NOT NULL             COMMENT 'Order amount',
    status            INT          NOT NULL DEFAULT 0    COMMENT '0=pending 1=paid 2=refunded 3=cancelled',
    pay_method        VARCHAR(32)                        COMMENT 'Payment method (ALIPAY, WECHAT, MOCK, etc.)',
    idempotency_key   VARCHAR(64)  NOT NULL              COMMENT 'Idempotency key (unique)',
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT 'Multi-tenant scope (D-044)',
    product_type      VARCHAR(32)  NOT NULL DEFAULT 'COURSE'  COMMENT 'Product type discriminator (D-100)',
    created_at        BIGINT       NOT NULL              COMMENT 'Creation timestamp (ms)',
    updated_at        BIGINT       NOT NULL              COMMENT 'Last update timestamp (ms)',
    lock_version      BIGINT                             COMMENT 'Optimistic lock version',
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_idempotency (idempotency_key),
    KEY idx_user (user_id),
    KEY idx_user_course (user_id, course_id),
    KEY idx_tenant (tenant_id),
    KEY idx_product_type (product_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ep_payment: Payment record table (OPM Step 4)
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_payment (
    payment_id        BIGINT       NOT NULL AUTO_RANDOM COMMENT 'Payment ID',
    order_id          BIGINT       NOT NULL              COMMENT 'Associated order ID',
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT 'Multi-tenant scope',
    channel           VARCHAR(32)  NOT NULL              COMMENT 'Payment channel (ALIPAY/WECHAT/MOCK)',
    amount            DECIMAL(18,2) NOT NULL             COMMENT 'Payment amount',
    currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT 'Currency code',
    channel_tx_id     VARCHAR(128)                       COMMENT 'Channel transaction ID',
    status            INT          NOT NULL DEFAULT 0    COMMENT '0=pending 1=success 2=failed 3=refunded',
    fail_reason       VARCHAR(256)                       COMMENT 'Failure reason (if status=2)',
    created_at        BIGINT       NOT NULL              COMMENT 'Creation timestamp (ms)',
    updated_at        BIGINT       NOT NULL              COMMENT 'Last update timestamp (ms)',
    PRIMARY KEY (payment_id),
    KEY idx_order (order_id),
    KEY idx_tenant (tenant_id),
    KEY idx_channel_tx (channel_tx_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- ep_refund: Refund record table
-- ============================================================
CREATE TABLE IF NOT EXISTS ep_refund (
    refund_id         BIGINT       NOT NULL AUTO_RANDOM COMMENT 'Refund ID',
    order_id          BIGINT       NOT NULL              COMMENT 'Associated order ID',
    payment_id        BIGINT                             COMMENT 'Associated payment ID',
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT 'Multi-tenant scope',
    amount            DECIMAL(18,2) NOT NULL             COMMENT 'Refund amount',
    reason            VARCHAR(256)                       COMMENT 'Refund reason',
    status            INT          NOT NULL DEFAULT 0    COMMENT '0=pending 1=success 2=failed',
    created_at        BIGINT       NOT NULL              COMMENT 'Creation timestamp (ms)',
    updated_at        BIGINT       NOT NULL              COMMENT 'Last update timestamp (ms)',
    PRIMARY KEY (refund_id),
    KEY idx_order (order_id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
