-- SP-02 Data Middle-Platform — Data Fabric metadata layer (minimal viable)
-- D-074: minimal viable subset of ADR-D05 / sp02-sdd §12.1 智能数据目录.
-- Catalog: iceberg_jdbc (jdbc:postgresql://catalog-pg:5432/iceberg)
-- Warehouse: s3://edu-iceberg/warehouse
--
-- Populated by MetadataCollector from CDC DDL events (Pulsar
-- persistent://public/cdc/ddl) and JDBC information-schema scans.
-- GNN lineage / NL query / AIOps (§12.1 M4) are intentionally out of scope.

CREATE DATABASE IF NOT EXISTS meta;

-- Column-level metadata for every table in the Data Fabric.
-- Partitioned by tenant and day so multi-tenant scans stay cheap.
CREATE TABLE IF NOT EXISTS meta.column_metadata (
    tenant_id     STRING       NOT NULL COMMENT '租户ID',
    database      STRING       NOT NULL COMMENT '库名(schema)',
    table_name    STRING       NOT NULL COMMENT '表名',
    column_name   STRING       NOT NULL COMMENT '列名',
    data_type     STRING       NOT NULL COMMENT '列数据类型(原始DDL文本)',
    nullable      BOOLEAN      NOT NULL COMMENT '是否可空',
    comment       STRING                COMMENT '列注释',
    collected_at  TIMESTAMP(6) NOT NULL COMMENT '采集时间',
    PRIMARY KEY (tenant_id, database, table_name, column_name)
) PARTITIONED BY (tenant_id, days(collected_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- =========================================================================
-- M4 Data Fabric — additional Iceberg tables (sp02-sdd §12.6)
-- =========================================================================

-- §12.6 元数据表 — column-level lineage edges.
-- Populated by LineageParser (rule-based, inferred_by='rule') and the
-- future GNN layer (inferred_by='gnn').
CREATE TABLE IF NOT EXISTS meta.column_lineage (
    source_table   STRING       NOT NULL COMMENT '源表',
    source_column  STRING       NOT NULL COMMENT '源列(*表示通配)',
    target_table   STRING       NOT NULL COMMENT '目标表',
    target_column  STRING       NOT NULL COMMENT '目标列(*表示通配)',
    confidence     DOUBLE       NOT NULL COMMENT '置信度0.0-1.0',
    inferred_by    STRING       NOT NULL COMMENT '推理方式(rule/gnn)',
    created_at     TIMESTAMP(6) NOT NULL COMMENT '生成时间'
) PARTITIONED BY (days(created_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- §12.6 元数据表 — 360° data asset quality profile.
CREATE TABLE IF NOT EXISTS meta.data_asset_profile (
    table_name      STRING       NOT NULL COMMENT '表名',
    quality_score   INT          NOT NULL COMMENT '质量评分0-100',
    query_count_7d  INT                   COMMENT '近7天查询次数',
    query_count_30d INT                   COMMENT '近30天查询次数',
    storage_bytes   BIGINT                COMMENT '存储字节数',
    cost_monthly    DECIMAL(10,2)         COMMENT '月成本',
    updated_at      TIMESTAMP(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (table_name)
) WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- §12.6 特征漂移报告 — PSI/KL/KS drift detection results.
CREATE TABLE IF NOT EXISTS meta.feature_drift_report (
    feature_name     STRING       NOT NULL COMMENT '特征名',
    drift_type       STRING       NOT NULL COMMENT '漂移类型(psi/kl/ks)',
    drift_value      DOUBLE       NOT NULL COMMENT '漂移值',
    threshold        DOUBLE       NOT NULL COMMENT '告警阈值',
    baseline_window  STRING                COMMENT '基线时间窗口',
    current_window   STRING                COMMENT '当前时间窗口',
    detected_at      TIMESTAMP(6) NOT NULL COMMENT '检测时间'
) PARTITIONED BY (days(detected_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- §12.6 AIOps指标历史 — sampled Prometheus metrics.
CREATE TABLE IF NOT EXISTS ops.metrics_history (
    metric_name  STRING       NOT NULL COMMENT '指标名',
    metric_value DOUBLE       NOT NULL COMMENT '指标值',
    labels       MAP<STRING,STRING>     COMMENT '标签',
    recorded_at  TIMESTAMP(6) NOT NULL COMMENT '记录时间'
) PARTITIONED BY (days(recorded_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- §12.6 Flink作业执行画像.
CREATE TABLE IF NOT EXISTS ops.flink_job_profile (
    job_id             STRING       NOT NULL COMMENT '作业ID',
    input_records      BIGINT                COMMENT '输入记录数',
    duration_ms        BIGINT                COMMENT '执行时长(ms)',
    parallelism        INT                   COMMENT '并行度',
    tm_cores           INT                   COMMENT 'TaskManager核数',
    tm_memory_mb       INT                   COMMENT 'TaskManager内存(MB)',
    backpressure_ratio DOUBLE                COMMENT '反压比例',
    executed_at        TIMESTAMP(6) NOT NULL COMMENT '执行时间'
) PARTITIONED BY (days(executed_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- §12.6 NL查询审计日志.
CREATE TABLE IF NOT EXISTS audit.nl_query_log (
    user_id      STRING       NOT NULL COMMENT '用户ID',
    question     STRING       NOT NULL COMMENT '自然语言问题',
    generated_sql STRING               COMMENT '生成的SQL',
    executed     BOOLEAN               COMMENT '是否执行',
    result_rows  INT                   COMMENT '结果行数',
    latency_ms   INT                   COMMENT '延迟(ms)',
    queried_at   TIMESTAMP(6) NOT NULL COMMENT '查询时间'
) PARTITIONED BY (days(queried_at))
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);
