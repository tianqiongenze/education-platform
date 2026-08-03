# SP-02 数据中台 软件详细设计（SDD）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-02-SDD | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-02-SAD v1.0 + SRS v1.0 |

> 本 SDD 兑现深度：Flink Job 逐个设计（source/sink/算子/并行度/checkpoint）、Iceberg 表 DDL、CDC pipeline YAML、Feature Store 特征定义、Avro schema、监控指标。承接 SAD ADR-D01..D-04，可直接用于编码与部署。

---

## 1. 总体作业清单

| Job ID | 类型 | 层 | 功能 | 并行度 | checkpoint |
|--------|------|----|------|--------|-----------|
| J-CDC-ORDER | Flink CDC pipeline | ② | 订单分库分表合并入 Iceberg ods_order | 32 | 5min |
| J-CDC-LEARN | Flink CDC pipeline | ② | 学习事件合并入 Iceberg ods_learning_event | 64 | 5min |
| J-CDC-USER | Flink CDC pipeline | ② | 用户表合并入 Iceberg ods_user | 8 | 5min |
| J-RT-LEARN | Flink SQL | ① | 实时学习指标（在线人数/完播率/提交速率） | 16 | 1min |
| J-RT-LIVE | Flink CEP | ① | 实时直播指标 + 异常断流告警 | 16 | 30s |
| J-RT-TRADE | Flink SQL | ① | 实时交易指标（GMV/漏斗） | 16 | 1min |
| J-RT-RISK | Flink CEP | ① | 实时风控（同IP多账号/秒级批量下单） | 32 | 10s |
| J-RT-BILL | Flink SQL | ① | 实时计费对账 | 8 | 1min |
| J-FS-ONLINE | Flink | ①/⑦ | 在线特征计算→Redis/TiKV | 32 | 30s |
| J-ARCHIVE | Spark | ④ | 冷热归档（TiDB→Iceberg） | - | 批 |
| J-DW-RETENTION | Spark | ⑤ | 留存报表 T+1 | - | 批 |
| J-FS-OFFLINE | Spark | ⑤/⑦ | 离线特征+训练数据集 | - | 批 |
| J-RECONCILE | Spark | ⑦ | 在线/离线特征对账 | - | 批 |

---

## 2. CDC pipeline 详细设计（ADR-D01）

### 2.1 TiCDC changefeed 配置

```sql
-- 创建 changefeed: 订单分库分表组
CREATE CHANGEFEED FOR TABLE cell_0.order_0000, cell_0.order_0001, ..., cell_0.order_1023
  INTO kafka_cluster('kafka-broker:9092', 'cdc.order_group')
  WITH (
    sink_uri='kafka://kafka-broker:9092/cdc.order_group?protocol=avro&schema_registry=http://schema-registry:8081',
    -- 分区路由: 按 order_id hash, 保证同订单顺序
    partition_policy='hash',
    -- GC safepoint 保护: TiCDC 自管, 不依赖下游
    gc_safepoint_interval='30s',
    -- 忽略 DDL 直连(DDL 走 Registry 灰度)
    ignore_ineligible_table=true
  );
```

### 2.2 Avro schema 示例（订单 CDC）

```json
{
  "type": "record",
  "name": "OrderCDC",
  "namespace": "com.edu.cdc",
  "fields": [
    {"name": "op_type", "type": {"type": "enum", "name": "OpType", "symbols": ["c","u","d","r"]}},
    {"name": "ts_ms", "type": "long", "doc": "CDC 提交时间戳(ms)"},
    {"name": "source_cell", "type": "string", "doc": "来源单元标识"},
    {"name": "table", "type": "string"},
    {"name": "before", "type": ["null", "string"], "default": null, "doc": "变更前 JSON"},
    {"name": "after", "type": ["null", "OrderPayload"], "default": null, "doc": "变更后"},
    {"name": "order_id", "type": "long"},
    {"name": "user_id", "type": "long"},
    {"name": "amount", "type": {"type":"bytes","logicalType":"decimal","precision":10,"scale":2}},
    {"name": "status", "type": "string"},
    {"name": "created_at", "type": {"type":"long","logicalType":"timestamp-millis"}},
    {"name": "updated_at", "type": {"type":"long","logicalType":"timestamp-millis"}}
  ]
}
```

Schema Registry 兼容性策略：`BACKWARD_TRANSITIVE`（新增字段需有默认值，删除字段需兼容历史消费者）。

### 2.3 Flink CDC 3.x YAML pipeline（J-CDC-ORDER）

```yaml
# pipeline: cdc-order-to-iceberg.yaml
source:
  type: kafka
  topic: cdc.order_group
  properties:
    bootstrap.servers: kafka-broker:9092
  value.format: avro
  value.avro-schema-registry-url: http://schema-registry:8081
  value.fields-include: ALL

route:
  - source-table: order_0000
    sink-table: iceberg_jdbc.ods.ods_order
  - source-table: order_0001
    sink-table: iceberg_jdbc.ods.ods_order
  # ... order_0002..order_1023 同路由
  - source-table: order_1023
    sink-table: iceberg_jdbc.ods.ods_order
  transform:
    - source-table: order_.*
      projection: "*, 'cell_0' AS source_cell"
      filter: "op_type <> 'd' OR __op_ts > '2026-01-01'"

sink:
  type: iceberg
  name: iceberg-sink
  catalog:
    type: jdbc
    url: jdbc:postgresql://catalog-pg:5432/iceberg
    warehouse: s3://edu-iceberg/warehouse
  table: ods.ods_order
  primary-keys: order_id
  partition.keys: days(created_at)
  # ADR-D02: checkpoint=commit
  commit.mode: checkpoint
  write.format.default: parquet
  write.parquet.compression-codec: zstd
  target-file-size: 256MB

# 链式 compaction (SinkV2)
maintenance:
  enabled: true
  rewrite_data_files:
    target_file_size: 256MB
    min_input_files: 5
    trigger.interval: 1h
  expire_snapshots:
    retain_last: 5
    older_than: 7d
  remove_orphan_files:
    older_than: 1d
  convert_equality_deletes:
    enabled: true

# 保留 flink-job-id 快照
flink:
  job-name: cdc-order-to-iceberg
  parallelism: 32
  checkpoint:
    interval: 5min
    mode: EXACTLY_ONCE
    timeout: 10min
    min_pause: 1min
    max_concurrent: 1
    externalized_checkpoints: RETAIN_ON_CANCELLATION
    state_backend: rocksdb
    rocksdb.localdir: /mnt/ssd/flink/rocksdb
  savepoint:
    retain: true
    tag_with_job_id: true
  restart_strategy: exponential_delay
    initial_backoff: 10s
    max_backoff: 5min
```

### 2.4 J-CDC-LEARN / J-CDC-USER
结构与 J-CDC-ORDER 一致，差异：
- J-CDC-LEARN：source topic=`cdc.learning_event_group`，sink=`ods.ods_learning_event`，parallelism=64（高频表），partition=days(created_at)+hour(created_at)
- J-CDC-USER：source topic=`cdc.user_group`，sink=`ods.ods_user`，parallelism=8（低频表）

---

## 3. Flink 实时指标 Job 详细设计

### 3.1 J-RT-LEARN 实时学习指标

| 项 | 设计 |
|----|------|
| Source | Kafka `cdc.learning_event_group`（Avro） |
| 算子 | ① filter（op_type=r/c/u）② keyBy(user_id) ③ tumble window(1min) ④ aggregate(在线人数/完播/提交速率) |
| Sink | Iceberg `dws.dws_learning_metric_min`（checkpoint=1min）+ Redis Stream（实时大屏） |
| 并行度 | 16 |
| checkpoint | 1min，EXACTLY_ONCE，RocksDB |
| 状态 | 窗口状态 RocksDB，TTL 24h |

```sql
-- Flink SQL 作业
INSERT INTO dws_learning_metric_min
SELECT
  window_start, window_end,
  COUNT(DISTINCT user_id) AS online_users,
  SUM(CASE WHEN event_type='video_complete' THEN 1 ELSE 0 END) AS completes,
  SUM(CASE WHEN event_type='submit' THEN 1 ELSE 0 END) AS submits,
  CURRENT_TIMESTAMP AS processed_at
FROM TABLE(TUMBLE(TABLE learning_event, DESCRIPTOR(event_ts), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end;

-- 同时推 Redis Stream 供大屏秒级刷新
INSERT INTO redis_stream_learning_dash
SELECT 'dash' AS key, online_users, completes, submits FROM dws_learning_metric_min_materialized;
```

### 3.2 J-RT-LIVE 实时直播指标 + CEP 告警

| 项 | 设计 |
|----|------|
| Source | Kafka `cdc.live_event_group` + `cdc.live_room` |
| 算子 | ① 直播房间在线人数（滑动窗口 10s）② 卡顿率（事件流聚合）③ CEP 异常断流（房间 30s 无心跳） |
| Sink | Iceberg `dws.dws_live_metric` + 告警 topic `alert.live_anomaly` |
| 并行度 | 16 |
| checkpoint | 30s |

```java
// CEP 异常断流伪代码
Pattern<LiveEvent, ?> brokenStream = Pattern.<LiveEvent>begin("heartbeat")
    .where(e -> e.type == EventType.HEARTBEAT)
    .timesOrMore(1)
    .within(Time.seconds(30))
    .notFollowedBy("stop")
    .where(e -> e.type == EventType.STOP);

CEP.pattern(liveStream.keyBy(e -> e.roomId), brokenStream)
    .select(events -> new LiveAnomalyAlert(events.get(0).roomId, "BROKEN_STREAM"))
    .sinkTo(kafkaSink("alert.live_anomaly"));
```

### 3.3 J-RT-TRADE 实时交易指标

```sql
-- GMV 分钟级 + 漏斗
INSERT INTO dws_trade_metric_min
SELECT
  window_start,
  SUM(amount) FILTER (WHERE status='paid') AS gmv,
  COUNT(*) FILTER (WHERE status='created') AS orders_created,
  COUNT(*) FILTER (WHERE status='paid') AS orders_paid,
  COUNT(*) FILTER (WHERE status='refunded') AS orders_refunded
FROM TABLE(TUMBLE(TABLE order_cdc, DESCRIPTOR(event_ts), INTERVAL '1' MINUTE))
GROUP BY window_start;
```
并行度 16，checkpoint 1min，Sink Iceberg `dws.dws_trade_metric_min` + Redis Stream。

### 3.4 J-RT-RISK 实时风控（CEP）

| 规则 | CEP 模式 | 输出 |
|------|---------|------|
| 同 IP 多账号（5账号/1min） | keyBy(ip) 1min 窗口 distinct(user_id)≥5 | `alert.risk.multi_account` |
| 秒级批量下单（10单/10s） | keyBy(user_id) 10s 窗口 count≥10 | `alert.risk.batch_order` |
| 异常登录（异地+新设备） | 双流 join login+device | `alert.risk.abnormal_login` |

并行度 32，checkpoint 10s（亚秒诉求），Sink Kafka alert topic + Iceberg `dws.dws_risk_event`。

### 3.5 J-RT-BILL 实时计费对账
```sql
-- 计费流水 vs 订单差异
INSERT INTO dws_billing_diff
SELECT o.order_id, o.amount AS order_amount, b.amount AS bill_amount,
       (o.amount - b.amount) AS diff
FROM order_cdc o LEFT JOIN billing_cdc b ON o.order_id = b.order_id
WHERE o.status='paid' AND (b.amount IS NULL OR o.amount <> b.amount);
```
并行度 8，checkpoint 1min，差异实时 flag。

---

## 4. Feature Store 特征定义（ADR-D03）

### 4.1 特征 DSL 示例（learner_activity.yaml）

```yaml
feature_group: learner_activity
owner: ai-platform
online_store: redis  # 也支持 tikv
offline_store: iceberg
schema_registry: http://schema-registry:8081

features:
  - name: user_30d_login_count
    type: INT
    transform:
      source: ods_learning_event
      agg: count
      filter: "event_type='login'"
      window: 30d
    online_ttl: 3600s

  - name: user_7d_video_complete_rate
    type: DOUBLE
    transform:
      source: ods_learning_event
      agg: "sum(case when event_type='video_complete' then 1 else 0 end) / count(*)"
      window: 7d
    online_ttl: 3600s

  - name: user_avg_session_duration_30d
    type: DOUBLE
    transform:
      source: ods_learning_event
      agg: avg(session_duration)
      window: 30d
    online_ttl: 3600s

  - name: user_course_completion_30d
    type: INT
    transform:
      source: ods_learning_event
      agg: count_distinct(course_id)
      filter: "event_type='course_complete'"
      window: 30d
    online_ttl: 3600s
```

### 4.2 编译器输出
- **Spark 离线作业**：读 Iceberg `ods_learning_event`，按 window 聚合，写 Iceberg 离线 store `feature_offline.learner_activity`（带版本号 + snapshot-id）。
- **Flink 在线作业**：读 Kafka `cdc.learning_event_group`，增量更新窗口，写 Redis `feat:learner_activity:{user_id}`。
- **Avro schema**：自动生成并注册到 Registry，在线/离线共用，防 skew。

### 4.3 在线 store key 设计
```
key:   feat:{group}:{user_id}
value: Avro bytes(同离线 schema)
TTL:   feature.online_ttl
```
查询：AI 推理时 `MGET feat:learner_activity:{user_id}` 返回 Avro，按 Registry schema 反序列化。

### 4.4 一致性对账作业（J-RECONCILE）
```sql
-- 每日抽样对账：在线 vs 离线同 snapshot
WITH online_sample AS (
  SELECT user_id, feature_value FROM redis_scan('feat:learner_activity:*') TABLESAMPLE(1%)
),
offline_sample AS (
  SELECT user_id, feature_value FROM feature_offline.learner_activity
  WHERE snapshot_id = ${target_snapshot}
)
SELECT
  COUNT(*) AS total,
  SUM(CASE WHEN ABS(o.feature_value - f.feature_value) > 0.001 * ABS(f.feature_value)
           THEN 1 ELSE 0 END) AS skew_count
FROM online_sample o JOIN offline_sample f USING(user_id);
-- skew_rate = skew_count/total, 阈值 0.1% 告警
```

---

## 5. Iceberg 表 DDL 全集

### 5.1 ODS 层

```sql
-- 订单 CDC
CREATE TABLE iceberg_jdbc.ods.ods_order (
  op_type STRING,
  ts_ms BIGINT,
  source_cell STRING,
  order_id BIGINT,
  user_id BIGINT,
  amount DECIMAL(10,2),
  status STRING,
  created_at TIMESTAMP(3),
  updated_at TIMESTAMP(3),
  PRIMARY KEY(order_id) NOT ENFORCED
) PARTITIONED BY (days(created_at))
WITH (
  'format-version'='2',
  'write.format.default'='parquet',
  'write.parquet.compression-codec'='zstd',
  'write.target-file-size-bytes'='268435456'
);

-- 学习事件 CDC
CREATE TABLE iceberg_jdbc.ods.ods_learning_event (
  op_type STRING, ts_ms BIGINT, source_cell STRING,
  event_id BIGINT, user_id BIGINT, course_id BIGINT,
  event_type STRING, event_ts TIMESTAMP(3),
  session_duration INT, video_id BIGINT,
  PRIMARY KEY(event_id) NOT ENFORCED
) PARTITIONED BY (days(event_ts), hours(event_ts))
WITH ('write.parquet.compression-codec'='zstd', 'write.target-file-size-bytes'='268435456');

-- 用户 CDC
CREATE TABLE iceberg_jdbc.ods.ods_user (
  op_type STRING, ts_ms BIGINT, source_cell STRING,
  user_id BIGINT, nickname STRING, region STRING,
  created_at TIMESTAMP(3), updated_at TIMESTAMP(3),
  status STRING,
  PRIMARY KEY(user_id) NOT ENFORCED
) PARTITIONED BY (days(created_at))
WITH ('write.parquet.compression-codec'='zstd');
```

### 5.2 DWS 层

```sql
-- 实时学习指标(分钟)
CREATE TABLE iceberg_jdbc.dws.dws_learning_metric_min (
  window_start TIMESTAMP(3), window_end TIMESTAMP(3),
  online_users BIGINT, completes BIGINT, submits BIGINT,
  processed_at TIMESTAMP(3)
) PARTITIONED BY (days(window_start))
WITH ('write.parquet.compression-codec'='zstd', 'write.target-file-size-bytes'='134217728');

-- 实时交易指标(分钟)
CREATE TABLE iceberg_jdbc.dws.dws_trade_metric_min (
  window_start TIMESTAMP(3), gmv DECIMAL(15,2),
  orders_created BIGINT, orders_paid BIGINT, orders_refunded BIGINT
) PARTITIONED BY (days(window_start))
WITH ('write.parquet.compression-codec'='zstd');

-- 实时直播指标
CREATE TABLE iceberg_jdbc.dws.dws_live_metric (
  window_start TIMESTAMP(3), room_id BIGINT,
  online_count INT, stall_rate DOUBLE,
  alert_flag BOOLEAN
) PARTITIONED BY (days(window_start))
WITH ('write.parquet.compression-codec'='zstd');

-- 风控事件
CREATE TABLE iceberg_jdbc.dws.dws_risk_event (
  event_ts TIMESTAMP(3), rule_id STRING, user_id BIGINT,
  ip STRING, detail STRING, severity STRING
) PARTITIONED BY (days(event_ts))
WITH ('write.parquet.compression-codec'='zstd');

-- 计费差异
CREATE TABLE iceberg_jdbc.dws.dws_billing_diff (
  order_id BIGINT, order_amount DECIMAL(10,2),
  bill_amount DECIMAL(10,2), diff DECIMAL(10,2), detected_at TIMESTAMP(3)
) PARTITIONED BY (days(detected_at));
```

### 5.3 Feature 离线 store

```sql
CREATE TABLE iceberg_jdbc.feature_offline.learner_activity (
  user_id BIGINT,
  snapshot_id BIGINT,
  feature_version STRING,
  user_30d_login_count INT,
  user_7d_video_complete_rate DOUBLE,
  user_avg_session_duration_30d DOUBLE,
  user_course_completion_30d INT,
  computed_at TIMESTAMP(3),
  PRIMARY KEY(user_id, snapshot_id) NOT ENFORCED
) PARTITIONED BY (days(computed_at))
WITH ('write.parquet.compression-codec'='zstd');
```

### 5.4 冷热归档表（与 ODS 同构，独立 namespace）

```sql
CREATE TABLE iceberg_jdbc.archive.archive_learning_event
LIKE iceberg_jdbc.ods.ods_learning_event
PARTITIONED BY (days(event_ts));
```

---

## 6. 冷热归档作业设计（J-ARCHIVE）

```python
# Spark 归档作业伪代码
def archive_table(table_name, retention_days=90, batch_size=100000):
    cutoff = current_date() - retention_days
    while True:
        rows = spark.read.format("jdbc") \
            .option("url", tidb_url) \
            .option("dbtable", f"(SELECT * FROM {table_name} WHERE created_at < '{cutoff}' ORDER BY id LIMIT {batch_size}) t") \
            .load()
        if rows.count() == 0:
            break
        # 写 Iceberg 归档表
        rows.write.format("iceberg") \
            .mode("append") \
            .saveAsTable(f"iceberg_jdbc.archive.archive_{table_name}")
        # 删 TiDB 行(限速,避免对在线库压力)
        ids = [r.id for r in rows.collect()]
        spark.sql(f"DELETE FROM tidb.{table_name} WHERE id IN ({','.join(ids)})")
        sleep(1)  # 限速
```

调度：Airflow 每日 02:00 执行，按 BU/表分别配置 retention。

---

## 7. 监控指标设计

### 7.1 Flink 作业指标（Prometheus exporter）

| 指标 | 来源 | 告警阈值 |
|------|------|---------|
| `flink_jobmanager_numRestarts` | JM | >3/min |
| `flink_taskmanager_job_numRestarts` | TM | >3/min |
| `flink_jobmanager_job_lastCheckpointDuration` | JM | >300s（5min checkpoint 超时） |
| `flink_jobmanager_job_numberOfFailedCheckpoints` | JM | >0 累计 5min |
| `flink_taskmanager_job_throughput` | TM | <阈值 50% 持续 5min |
| `kafka_consumer_lag` | Kafka | >10000 持续 5min |
| `iceberg_commit_duration` | Flink sink | >60s |
| `iceberg_small_file_count` | compaction | >50 触发告警 |

### 7.2 CDC 链路指标

| 指标 | 测量 | 告警 |
|------|------|------|
| CDC 入湖端到端延迟 | `iceberg_commit_ts - tidb_commit_ts` | P95>5min（G-09） |
| TiCDC lag | `tikv_cdc_min_resolved_ts` | >60s |
| Schema Registry 兼容失败 | registry API | >0 |

### 7.3 Iceberg 表健康指标

| 指标 | 来源 | 告警 |
|------|------|------|
| 小文件数（<256MB） | `metadata` 扫描 | >50/分区 |
| 快照数 | `snapshots` 表 | >20 |
| orphan files | `list_orphan_files` | >0 持续 1d |
| 表大小增长率 | `files` 聚合 | 异常突增 |

### 7.4 Feature Store 指标

| 指标 | 测量 | 告警 |
|------|------|------|
| 在线特征查询 P99 | Redis client 计时 | >5ms |
| 在线/离线 skew 率 | 对账作业 | >0.1% |
| 特征新鲜度（last_update） | Redis TTL | >online_ttl |

### 7.5 数据质量指标

| 指标 | 测量 | 告警 |
|------|------|------|
| Avro schema 校验失败 | Flink deserializer | >0 |
| 行数漂移（vs 历史） | DQ 作业 | ±20% |
| 空值率 | DQ 作业 | >阈值 |
| 枚举漂移（status 新值） | DQ 作业 | 新值未登记 |

---

## 8. 容错与恢复设计

### 8.1 Flink 作业恢复
- **checkpoint 失败**：`restart_strategy=exponential_delay`（10s→5min），最多重启 5 次；超限告警 + 自动 savepoint 恢复
- **flink-job-id 快照保留**（ADR-D02）：Iceberg 快照 tag = flink-job-id，`expire_snapshots` 跳过该快照，保证状态恢复不断裂
- **savepoint 恢复流程**：`flink savepoint -> cancel -> start from savepoint`，RTO<60s（NFR-06）

### 8.2 CDC 断点续传
- Kafka offset 由 Flink checkpoint 管理（EXACTLY_ONCE）
- TiCDC changefeed 自带 GC safepoint 保护，下游宕机不丢
- Schema Registry 故障：Flink 作业 fallback 缓存 schema，继续运行

### 8.3 Iceberg 表恢复
- 写失败 checkpoint 不 commit，无脏数据（ACID）
- compaction 失败：原数据文件保留，重试 compaction
- 对象存储跨区不一致：用强一致 S3 后端；MinIO 启用 erasure-coded + versioning

---

## 9. 安全与权限

| 维度 | 设计 |
|------|------|
| Kafka ACL | topic 级 ACL，CDC topic 仅 Flink 消费组可读 |
| Iceberg catalog | JDBC catalog 用户隔离，表级权限 |
| Trino | 资源组 + 行级权限（per BU），Kerberos/LDAP 认证 |
| Feature Store | 在线 store key 含 user_id，应用层不可越权；离线 store 按 BU namespace |
| Schema Registry | 写权限仅平台工程师，读权限全员 |
| 审计 | 所有 DDL/compaction/归档操作落审计日志 |

---

## 10. 与其他中台的接口契约

| 中台 | 接口 | 契约 |
|------|------|------|
| 技术中台 SP-01 | 复用 Kafka/Pulsar/可观测 | topic 命名 `cdc.*`/`alert.*`；Prometheus 指标 |
| 业务中台 SP-03 | 提供 TiDB 源表 | 表结构 + DDL 变更通知 |
| AI 中台 SP-04 | Feature Store 在线/离线查询 | Redis MGET + Iceberg snapshot-id 切片 |
| IT后端 SP-05 | 实时指标看板数据 | Redis Stream + Iceberg DWS |

---

## 11. 设计追溯

| SRS 需求 | SDD 章节 |
|---------|---------|
| FR-CDC-01..03 | §2 CDC pipeline |
| FR-LH-01..06 | §5 Iceberg DDL + §3 写入 + §8 容错 |
| FR-RT-01..05 | §3 实时 Job |
| FR-FS-01..05 | §4 Feature Store |
| FR-CS-01..04 | §6 归档作业 |
| NFR-01 (G-09) | §3 checkpoint 5min + §7.2 延迟监控 |
| NFR-06 恢复 | §8.1 |

---

**文档结束**。本 SDD 为编码与部署的直接输入，变更需同步 SAD ADR 与 SRS 追溯矩阵。
