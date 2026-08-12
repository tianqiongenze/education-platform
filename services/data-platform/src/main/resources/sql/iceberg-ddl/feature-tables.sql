-- SP-02 Data Middle-Platform — Offline feature store (Iceberg)
-- Serves AI中台 (SP-04) training/offline evaluation
-- Catalog: iceberg_jdbc

CREATE DATABASE IF NOT EXISTS feature_offline;

-- Learner activity features (offline store)
-- Partitioned by feature date for time-travel training set generation
CREATE TABLE IF NOT EXISTS feature_offline.learner_activity (
    user_id                BIGINT       NOT NULL COMMENT '用户ID',
    feature_date           DATE         NOT NULL COMMENT '特征快照日期',
    play_cnt_7d            BIGINT                COMMENT '7天播放次数',
    play_cnt_30d           BIGINT                COMMENT '30天播放次数',
    complete_cnt_7d        BIGINT                COMMENT '7天完成次数',
    complete_cnt_30d       BIGINT                COMMENT '30天完成次数',
    submit_cnt_7d          BIGINT                COMMENT '7天提交次数',
    total_dur_sec_30d      BIGINT                COMMENT '30天总时长(秒)',
    avg_daily_dur_sec_30d  DOUBLE                COMMENT '30天日均时长(秒)',
    active_days_30d        INT                   COMMENT '30天活跃天数',
    last_active_ts         TIMESTAMP(6)          COMMENT '最近活跃时间',
    ingested_at            TIMESTAMP(6) NOT NULL COMMENT '特征计算时间',
    PRIMARY KEY (user_id, feature_date)
) PARTITIONED BY days(feature_date)
WITH (
    'format-version' = '2',
    'write.format.default' = 'parquet',
    'write.parquet.compression-codec' = 'zstd'
);
