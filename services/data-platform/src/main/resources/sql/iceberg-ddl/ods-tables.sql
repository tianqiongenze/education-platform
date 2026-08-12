-- SP-02 Data Middle-Platform — ODS (Operational Data Store) Iceberg tables
-- D-028: TiCDC → Pulsar (Avro) → Flink CDC 3.x → Iceberg
-- D-099: This is the EDUCATION instantiation of ods-template.sql.
--        For other industries, copy templates/ods-template.sql and replace placeholders.
-- Catalog: iceberg_jdbc (jdbc:postgresql://catalog-pg:5432/iceberg)
-- Warehouse: s3://edu-iceberg/warehouse

CREATE DATABASE IF NOT EXISTS ods;

-- ODS order: full CDC row image from order_0000..order_1023
CREATE TABLE IF NOT EXISTS ods.ods_order (
    order_id          BIGINT       NOT NULL COMMENT '订单ID',
    user_id           BIGINT       NOT NULL COMMENT '用户ID',
    course_id         BIGINT       NOT NULL COMMENT '课程ID',
    amount            DECIMAL(18,4)        COMMENT '订单金额',
    status            INT                  COMMENT '订单状态 0=待支付 1=已支付 2=已退款 3=已取消',
    idempotency_key   STRING               COMMENT '幂等键',
    op_type           STRING       NOT NULL COMMENT 'CDC操作类型 c/u/d/r',
    source_cell       STRING       NOT NULL COMMENT '源单元名',
    ts_ms             BIGINT       NOT NULL COMMENT 'CDC事件时间戳(ms)',
    created_at        TIMESTAMP(6)         COMMENT '订单创建时间',
    updated_at        TIMESTAMP(6)         COMMENT '订单更新时间',
    ingested_at       TIMESTAMP(6) NOT NULL COMMENT '入库时间',
    PRIMARY KEY (order_id, ts_ms)
) PARTITIONED BY days(created_at)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd',
    'commit.manifest.min-count-to-merge' = '20'
);

-- ODS learning_event: high-volume learning behavior stream
CREATE TABLE IF NOT EXISTS ods.ods_learning_event (
    event_id     BIGINT       NOT NULL COMMENT '事件ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    course_id    BIGINT                COMMENT '课程ID',
    lesson_id    BIGINT                COMMENT '课时ID',
    event_type   INT          NOT NULL COMMENT '1=play 2=complete 3=submit 4=quiz',
    duration_sec INT                   COMMENT '时长(秒)',
    op_type      STRING       NOT NULL,
    source_cell  STRING       NOT NULL,
    ts           TIMESTAMP(6) NOT NULL COMMENT '事件时间',
    ts_ms        BIGINT       NOT NULL,
    ingested_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id, ts_ms)
) PARTITIONED BY days(ts), hours(ts)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);

-- ODS user: dimension table (low volume)
CREATE TABLE IF NOT EXISTS ods.ods_user (
    user_id    BIGINT       NOT NULL COMMENT '用户ID',
    username   STRING                COMMENT '用户名',
    phone      STRING                COMMENT '手机号(脱敏)',
    status     INT                   COMMENT '状态 0=禁用 1=正常',
    tenant_id  BIGINT                COMMENT '租户ID',
    op_type    STRING       NOT NULL,
    source_cell STRING      NOT NULL,
    ts_ms      BIGINT       NOT NULL,
    created_at TIMESTAMP(6)          COMMENT '创建时间',
    ingested_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id, ts_ms)
) PARTITIONED BY days(created_at)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);
