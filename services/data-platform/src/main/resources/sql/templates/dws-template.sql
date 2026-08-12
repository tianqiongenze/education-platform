-- ============================================================
-- DWS Template — Industry-Agnostic Data Warehouse Service
-- D-099: Extracted from education-specific DDL for reuse across industries.
-- ============================================================
--
-- HOW TO USE:
--   1. Copy this file to a new industry-specific DDL.
--   2. Replace {{BEHAVIOR_METRIC}} with your industry's behavior metric name (e.g., learning_metric, browsing_metric).
--   3. Replace {{BEHAVIOR_COLUMNS}} with your industry's aggregation columns.
--   4. Replace {{TRADE_ENTITY}} with your trade entity name (default: product_id).
--   5. Add domain-specific DWS tables as needed.
--   6. Execute against your Iceberg catalog.
--
-- DESIGN PRINCIPLES:
--   - DWS tables are Flink streaming aggregations from ODS → DWS (Iceberg).
--   - Minute-level granularity is the finest DWS level; hourly/daily rollups derive from minute tables.
--   - All tables include PRIMARY KEY constraints for Iceberg upsert semantics.
--   - Risk + billing tables are industry-agnostic (fraud detection, payment reconciliation).
--
-- Catalog: iceberg_jdbc
-- ============================================================

CREATE DATABASE IF NOT EXISTS dws;

-- ============================================================
-- DWS {{BEHAVIOR_METRIC}} minute aggregation
-- Education: play_cnt, complete_cnt, submit_cnt, quiz_cnt
-- Retail:    view_cnt, cart_cnt, purchase_cnt, review_cnt
-- ============================================================
CREATE TABLE IF NOT EXISTS dws.dws_{{BEHAVIOR_METRIC}}_min (
    stat_min      TIMESTAMP(6) NOT NULL COMMENT 'Stat minute',
    user_id       BIGINT       NOT NULL,
    product_id    BIGINT                ,
    -- {{BEHAVIOR_COLUMNS}}: replace with your industry's metric columns
    action_cnt    BIGINT                COMMENT 'Action count (generic)',
    total_dur_sec BIGINT                COMMENT 'Total duration (seconds)',
    PRIMARY KEY (stat_min, user_id)
) PARTITIONED BY days(stat_min)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- ============================================================
-- DWS trade metric minute aggregation (industry-agnostic)
-- ============================================================
CREATE TABLE IF NOT EXISTS dws.dws_trade_metric_min (
    stat_min        TIMESTAMP(6) NOT NULL,
    product_id      BIGINT       NOT NULL,
    pay_cnt         BIGINT                COMMENT 'Payment count',
    pay_amount      DECIMAL(18,4)         COMMENT 'Payment amount',
    refund_cnt      BIGINT                COMMENT 'Refund count',
    refund_amount   DECIMAL(18,4)         COMMENT 'Refund amount',
    PRIMARY KEY (stat_min, product_id)
) PARTITIONED BY days(stat_min)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- ============================================================
-- DWS risk event (industry-agnostic fraud detection)
-- ============================================================
CREATE TABLE IF NOT EXISTS dws.dws_risk_event (
    event_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    risk_type    STRING       NOT NULL COMMENT 'cheating/refund_abuse/account_takeover/fraud',
    risk_score   DOUBLE                COMMENT 'Risk score 0-1',
    detail       STRING                COMMENT 'Detail JSON',
    ts           TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id)
) PARTITIONED BY days(ts)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- ============================================================
-- DWS billing diff (industry-agnostic payment reconciliation)
-- ============================================================
CREATE TABLE IF NOT EXISTS dws.dws_billing_diff (
    diff_id      BIGINT       NOT NULL,
    order_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    expected     DECIMAL(18,4)         COMMENT 'Expected amount',
    actual       DECIMAL(18,4)         COMMENT 'Actual amount',
    diff_amount  DECIMAL(18,4)         COMMENT 'Difference amount',
    diff_type    STRING                COMMENT 'under/over/missing',
    ts           TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (diff_id)
) PARTITIONED BY days(ts)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');
