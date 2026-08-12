-- ============================================================
-- ODS Template — Industry-Agnostic Operational Data Store
-- D-099: Extracted from education-specific DDL for reuse across industries.
-- ============================================================
--
-- HOW TO USE:
--   1. Copy this file to a new industry-specific DDL (e.g., retail-ods-tables.sql).
--   2. Replace {{INDUSTRY_NAME}} with your industry (e.g., retail, healthcare, finance).
--   3. Replace {{PRODUCT_ENTITY}} with your primary product/entity table name (e.g., sku, service, asset).
--   4. Replace {{BEHAVIOR_EVENT}} with your user behavior event stream name (e.g., browse_event, claim_event).
--   5. Add or remove columns as needed for your domain.
--   6. Execute against your Iceberg catalog (Trino/Presto).
--
-- DESIGN PRINCIPLES:
--   - ODS tables are full CDC row images from the operational database (TiDB).
--   - All tables include CDC metadata columns: op_type, source_cell, ts_ms, ingested_at.
--   - Partitioning strategy: days(created_at) for dimension tables; days(ts) + hours(ts) for high-volume event tables.
--   - Compression: zstd (best ratio for Parquet columnar).
--
-- Catalog: iceberg_jdbc
-- Warehouse: s://your-iceberg-warehouse/
-- ============================================================

CREATE DATABASE IF NOT EXISTS ods;

-- ============================================================
-- ODS order / transaction table (generalised)
-- D-100: course_id → generic product_id (column name kept for back-compat)
-- ============================================================
CREATE TABLE IF NOT EXISTS ods.ods_order (
    order_id          BIGINT       NOT NULL COMMENT 'Order/Transaction ID',
    user_id           BIGINT       NOT NULL COMMENT 'User ID',
    product_id        BIGINT                COMMENT 'Product/Item ID (nullable for non-inventory products)',
    amount            DECIMAL(18,4)        COMMENT 'Transaction amount',
    status            INT                  COMMENT '0=pending 1=paid 2=refunded 3=cancelled',
    product_type      STRING               COMMENT 'Product type discriminator (e.g., COURSE, SKU, MEMBERSHIP)',
    tenant_id         STRING               COMMENT 'Multi-tenant scope (D-044)',
    idempotency_key   STRING               COMMENT 'Idempotency key',
    op_type           STRING       NOT NULL COMMENT 'CDC op type: c/u/d/r',
    source_cell       STRING       NOT NULL COMMENT 'Source cell name (multi-region)',
    ts_ms             BIGINT       NOT NULL COMMENT 'CDC event timestamp (ms)',
    created_at        TIMESTAMP(6)         COMMENT 'Order creation time',
    updated_at        TIMESTAMP(6)         COMMENT 'Order update time',
    ingested_at       TIMESTAMP(6) NOT NULL COMMENT 'Iceberg ingestion time',
    PRIMARY KEY (order_id, ts_ms)
) PARTITIONED BY days(created_at)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd',
    'commit.manifest.min-count-to-merge' = '20'
);

-- ============================================================
-- ODS user behavior event stream (generalised)
-- Replace {{BEHAVIOR_EVENT}} columns with your industry's event taxonomy.
-- Education: event_type 1=play 2=complete 3=submit 4=quiz
-- Retail:    event_type 1=view 2=cart 3=purchase 4=review
-- ============================================================
CREATE TABLE IF NOT EXISTS ods.ods_{{BEHAVIOR_EVENT}} (
    event_id     BIGINT       NOT NULL COMMENT 'Event ID',
    user_id      BIGINT       NOT NULL COMMENT 'User ID',
    product_id   BIGINT                COMMENT 'Product/Item ID (nullable)',
    ref_id       BIGINT                COMMENT 'Secondary reference ID (e.g., lesson_id, variant_id)',
    event_type   INT          NOT NULL COMMENT 'Event type code (industry-specific)',
    duration_sec INT                   COMMENT 'Duration in seconds (if applicable)',
    op_type      STRING       NOT NULL,
    source_cell  STRING       NOT NULL,
    ts           TIMESTAMP(6) NOT NULL COMMENT 'Event time',
    ts_ms        BIGINT       NOT NULL,
    ingested_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id, ts_ms)
) PARTITIONED BY days(ts), hours(ts)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- ============================================================
-- ODS user dimension table (industry-agnostic)
-- ============================================================
CREATE TABLE IF NOT EXISTS ods.ods_user (
    user_id     BIGINT       NOT NULL COMMENT 'User ID',
    username    STRING                COMMENT 'Username',
    phone       STRING                COMMENT 'Phone (masked)',
    email       STRING                COMMENT 'Email (masked)',
    status      INT                   COMMENT '0=disabled 1=active',
    tenant_id   STRING                COMMENT 'Tenant ID (D-044)',
    op_type     STRING       NOT NULL,
    source_cell STRING      NOT NULL,
    ts_ms       BIGINT       NOT NULL,
    created_at  TIMESTAMP(6)          COMMENT 'Creation time',
    ingested_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, ts_ms)
) PARTITIONED BY days(created_at)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);
