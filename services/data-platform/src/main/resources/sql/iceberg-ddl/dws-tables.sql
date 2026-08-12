-- SP-02 Data Middle-Platform — DWS (Data Warehouse Service) aggregated tables
-- Flink streaming aggregation from ODS → DWS (Iceberg)
-- D-099: This is the EDUCATION instantiation of dws-template.sql.
--        For other industries, copy templates/dws-template.sql and replace placeholders.
-- Catalog: iceberg_jdbc

CREATE DATABASE IF NOT EXISTS dws;

-- 学习指标分钟聚合 (learning metric minute)
CREATE TABLE IF NOT EXISTS dws.dws_learning_metric_min (
    stat_min     TIMESTAMP(6) NOT NULL COMMENT '统计分钟',
    user_id      BIGINT       NOT NULL,
    course_id    BIGINT                ,
    play_cnt     BIGINT                COMMENT '播放次数',
    complete_cnt BIGINT                COMMENT '完成次数',
    submit_cnt   BIGINT                COMMENT '提交次数',
    quiz_cnt     BIGINT                COMMENT '测验次数',
    total_dur_sec BIGINT               COMMENT '总时长(秒)',
    PRIMARY KEY (stat_min, user_id)
) PARTITIONED BY days(stat_min)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- 交易指标分钟聚合 (trade metric minute)
CREATE TABLE IF NOT EXISTS dws.dws_trade_metric_min (
    stat_min        TIMESTAMP(6) NOT NULL,
    course_id       BIGINT       NOT NULL,
    pay_cnt         BIGINT                COMMENT '支付笔数',
    pay_amount      DECIMAL(18,4)         COMMENT '支付金额',
    refund_cnt      BIGINT                COMMENT '退款笔数',
    refund_amount   DECIMAL(18,4)         COMMENT '退款金额',
    PRIMARY KEY (stat_min, course_id)
) PARTITIONED BY days(stat_min)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- 直播指标 (live metric)
CREATE TABLE IF NOT EXISTS dws.dws_live_metric (
    live_id      BIGINT       NOT NULL COMMENT '直播ID',
    stat_min     TIMESTAMP(6) NOT NULL,
    online_cnt   BIGINT                COMMENT '在线人数峰值',
    like_cnt     BIGINT                COMMENT '点赞数',
    msg_cnt      BIGINT                COMMENT '弹幕数',
    PRIMARY KEY (live_id, stat_min)
) PARTITIONED BY days(stat_min)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- 风控事件 (risk event)
CREATE TABLE IF NOT EXISTS dws.dws_risk_event (
    event_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    risk_type    STRING       NOT NULL COMMENT 'risk_type: cheating/refund_abuse/account_takeover',
    risk_score   DOUBLE                COMMENT '风险分 0-1',
    detail       STRING                COMMENT '详情JSON',
    ts           TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id)
) PARTITIONED BY days(ts)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');

-- 账单差异 (billing diff)
CREATE TABLE IF NOT EXISTS dws.dws_billing_diff (
    diff_id      BIGINT       NOT NULL,
    order_id     BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    expected     DECIMAL(18,4)         COMMENT '应收金额',
    actual       DECIMAL(18,4)         COMMENT '实收金额',
    diff_amount  DECIMAL(18,4)         COMMENT '差异金额',
    diff_type    STRING                COMMENT 'diff_type: under/over/missing',
    ts           TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (diff_id)
) PARTITIONED BY days(ts)
WITH ('format-version'='2','write.parquet.compression-codec'='zstd');
