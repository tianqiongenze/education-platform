# SP-02 数据中台 软件详细设计（SDD）

> **D-200 Naming Note:** This document's filename retains the legacy code `sp02` for reference continuity. Per D-137, sp02 → data-platform. The subproject has been renamed in all source code, configs, and directory structure.

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
  INTO pulsar_cluster('pulsar://pulsar-broker:6650', 'cdc/order_group')
  WITH (
    sink_uri='pulsar://pulsar-broker:6650/cdc/order_group?protocol=avro&schema-registry=pulsar-builtin',
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

> 注：使用 Pulsar 内置 Schema Registry（`pulsar-builtin`），无需独立部署 Apicurio。Schema 随 topic 自动注册并按兼容性策略校验。

### 2.3 Flink CDC 3.x YAML pipeline（J-CDC-ORDER）

```yaml
# pipeline: cdc-order-to-iceberg.yaml
source:
  type: pulsar
  topic: persistent://public/cdc/order_group
  namespace: cdc
  properties:
    service-url: pulsar://pulsar-broker:6650
  value.format: avro
  value.avro-schema-registry-url: pulsar-builtin
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
- J-CDC-LEARN：source topic=`persistent://public/cdc/learning_event_group`，sink=`ods.ods_learning_event`，parallelism=64（高频表），partition=days(created_at)+hour(created_at)
- J-CDC-USER：source topic=`persistent://public/cdc/user_group`，sink=`ods.ods_user`，parallelism=8（低频表）

---

## 3. Flink 实时指标 Job 详细设计

### 3.1 J-RT-LEARN 实时学习指标

| 项 | 设计 |
|----|------|
| Source | Pulsar `persistent://public/cdc/learning_event_group`（Avro, 内置 Schema Registry） |
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
| Source | Pulsar `persistent://public/cdc/live_event_group` + `persistent://public/cdc/live_room` |
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
    .sinkTo(pulsarSink("persistent://public/alert/live_anomaly"));
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

并行度 32，checkpoint 10s（亚秒诉求），Sink Pulsar alert topic + Iceberg `dws.dws_risk_event`。

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
schema_registry: pulsar-builtin (Pulsar 内置)

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
- **Flink 在线作业**：读 Pulsar `persistent://public/cdc/learning_event_group`，增量更新窗口，写 Redis `feat:learner_activity:{user_id}`。
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
| `pulsar_backlog` | Pulsar | backlog >10000 持续 5min |
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
- Pulsar cursor/ack 由 Flink checkpoint 管理（EXACTLY_ONCE）
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
| Pulsar ACL | namespace+topic 级 ACL，CDC namespace 仅 Flink 订阅可读 |
| Iceberg catalog | JDBC catalog 用户隔离，表级权限 |
| Trino | 资源组 + 行级权限（per BU），Kerberos/LDAP 认证 |
| Feature Store | 在线 store key 含 user_id，应用层不可越权；离线 store 按 BU namespace |
| Schema Registry | 写权限仅平台工程师，读权限全员 |
| 审计 | 所有 DDL/compaction/归档操作落审计日志 |

---

## 10. 与其他中台的接口契约

| 中台 | 接口 | 契约 |
|------|------|------|
| 技术中台 SP-01 | 复用 Pulsar/可观测 | topic 命名 `persistent://public/cdc/*`/`persistent://public/alert/*`；Prometheus 指标 |
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

## 12. AI智能驱动详细设计 (D-042)

> **实现状态 (D-081+D-085~D-087, 2026-08-08)**: ADR-D05 PARTIAL — D-074 MetadataCollector + D-081 规则层字段血缘(LineageParser ~70%) + LineageGraph(BFS) + 数据资产画像API + 6张Iceberg DDL; GNN血缘仍 PROPOSED。ADR-D06 PARTIAL+ — D-081 PSI漂移检测(PSICalculator+DriftDetector) + D-087 SHAP反馈闭环(FeatureFeedbackConsumer) + IV计算器 + 三路推荐引擎(SHAP/COLLAB/IV) + DSL草稿生成 + DriftAlertPublisher + CacheWeightAdjuster。ADR-D07 PARTIAL — D-085 纯Java AIOps: IsolationForest异常检测 + EWMA/线性回归容量预测 + 拓扑根因分析 + 决策树调度优化。ADR-D08 PARTIAL — D-086 Text-to-SQL pipeline: JDK HttpClient→SP-04 /v1/chat/completions + 正则SQL白名单 + 多轮上下文 + 审计日志。

### 12.1 智能数据目录详细设计

#### 12.1.1 自动元数据发现管道
- 触发源: Flink CDC schema变更事件 (Pulsar topic: `cdc-schema-change`)
- 处理流程:
  1. Schema Change Consumer (Flink Job) 消费变更事件
  2. Metadata Extractor 抽取: 表名/字段名/类型/注释/数据分布(min/max/null_ratio/cardinality)
  3. Semantic Tagger (LLM调用SP-04 AI网关): 推断字段语义(如user_id→用户ID, amount→金额)
  4. 写入: Iceberg Catalog(结构化) + Elasticsearch 8.x(全文搜索)
  5. 通知: 发布 `metadata-updated` 事件到 Pulsar
- 数据分布采样: Iceberg snapshot → 随机采样 10000 行 → 统计算子计算分布
- 性能目标: schema变更到元数据可搜索 < 60s

#### 12.1.2 字段级血缘推断
- 采集源: OpenLineage集成Flink SQL Parser + Trino Query Logger
- 规则解析层(Druid SQL Parser): 解析 INSERT INTO ... SELECT ... 提取字段映射 → 覆盖率~70%
- AI推断层(GNN模型): 
  - 输入: 表关系图(节点=表/字段, 边=已知的表级血缘) + 字段名相似度 + 数据分布相似度
  - 模型: GraphSAGE 2层, 隐藏维度128
  - 输出: 字段级血缘边 + 置信度分数
  - 补全: 置信度>0.8的边自动加入血缘图; 0.5-0.8 标记待人工确认
- 存储: Neo4j(血缘图) + Iceberg表 `meta.column_lineage`
- 目标: 字段级血缘覆盖率 ≥95%, 准确率 ≥90%

#### 12.1.3 数据资产360°画像
- 质量评分: 行数完整率 + 空值率 + 唯一性 + 枚举漂移 → 加权评分 0-100
- 热度: Trino查询日志统计(近7/30/90天查询次数 + 去重用户数)
- 成本: Iceberg表存储大小 × 单价 + 关联Flink作业计算资源 × 单价
- AI关联推荐: 同主题域表 + 相同上游血缘 → 推荐相似数据资产
- 可视化: Grafana Dashboard + 自研数据资产目录UI

### 12.2 智能特征平台详细设计

#### 12.2.1 自动特征推荐引擎
- 输入信号:
  1. SP-04 AI中台模型训练完成后, 通过Pulsar topic `feature-feedback` 发送特征SHAP values
  2. Feature Store历史特征使用统计(被查询次数/被训练集引用次数)
  3. Iceberg ODS/DWS层数据分布(候选字段统计)
- 推荐算法:
  - 基于SHAP: 特征重要性Top-K → 已有特征中排名靠前的优先缓存
  - 基于协同过滤: 相似模型使用的特征 → 推荐给新模型
  - 基于统计: 高IV(Information Value) > 0.3 的候选字段 → 推荐为新特征
- 输出: 候选特征列表(排序) + 自动生成的Feature DSL草稿(YAML) → 人工审核后注册

#### 12.2.2 特征漂移检测作业
- 在线检测Flink Job (J-DRIFT-ONLINE):
  - 输入: Feature Store在线查询日志(特征值实时流)
  - 算法: 滑动窗口(1h) PSI计算, 对比基准窗口(7天前同时段)
  - 阈值: PSI > 0.2 → 告警; PSI > 0.4 → 严重告警
  - 输出: Pulsar topic `drift-alert` (含特征名/PSI值/基准分布/当前分布)
  - 联动: 告警事件 → SP-04 触发模型重训练流水线
- 离线检测Spark Job (J-DRIFT-OFFLINE):
  - 调度: 每日 02:00 Cron
  - 输入: Iceberg feature_offline表 vs 最新快照
  - 算法: KL Divergence + KS Test
  - 输出: 漂移报告写入 Iceberg表 `meta.feature_drift_report`

#### 12.2.3 特征重要性反馈闭环
- 事件流:
  1. SP-04模型训练完成 → 计算SHAP values → 发送Pulsar event到`feature-feedback`
  2. SP-02 Feature Feedback Consumer (Flink Job) 消费事件
  3. 更新Feature Store元数据: 特征重要性排名 → 调整Caffeine L1缓存权重
  4. 低重要性特征(排名Bottom 20%) → 降级为懒加载, 释放Redis内存

### 12.3 预测性运维(AIOps)详细设计

#### 12.3.1 容量预测作业
- 数据采集: Prometheus metrics → 每5min采样 → 写入Iceberg表 `ops.metrics_history`
  - 指标: Iceberg表存储增量, Flink TM CPU/Memory, Pulsar backlog, MinIO bucket size
- 预测模型: LSTM (PyTorch)
  - 输入: 过去90天时序数据(2592个点, 5min粒度)
  - 输出: 未来30天预测值 + 置信区间(95%)
  - 模型更新: 每日全量重训练, 滑动窗口增量更新
- 动作规则:
  - 预测存储增长>80%容量 → 提前7天创建MinIO扩容工单
  - 预测Flink资源不足 → 提前触发K8s HPA扩容
- 部署: ONNX Runtime本地推理, 延迟<50ms

#### 12.3.2 异常检测与根因分析
- 异常检测:
  - 多维度: Isolation Forest(输入: CDC延迟/Flink checkpoint耗时/compaction频率/Trino P99, 4维)
  - 单维度: 3-Sigma规则(各指标独立)
  - 检测延迟: <1min (Flink实时流处理)
- 根因分析:
  - 拓扑图: CDC→Pulsar→Flink→Iceberg→Trino 依赖链
  - 算法: 最短路径分析 — 找到异常节点链路上的根因候选
  - 关联规则: 历史故障库匹配(如"CDC延迟+Flink checkpoint失败"→"TiDB GC停滞")
  - 输出: 根因报告(置信度+建议修复动作) → PagerDuty通知

#### 12.3.3 智能调度优化
- 数据: Flink作业历史执行画像(Iceberg表 `ops.flink_job_profile`)
  - 特征: 输入数据量, 作业耗时, 并行度, TM核数, TM内存, 背压时间占比
- 模型: Gradient Boosted Trees (scikit-learn)
  - 目标: 预测最优(并行度, TM资源)组合, 最小化成本同时满足SLA
  - 训练: 每周全量重训练
- 效果验证: A/B测试 — 50%作业用AI推荐配置, 50%用人工配置, 对比计算成本

### 12.4 自然语言数据查询详细设计

#### 12.4.1 Text-to-SQL Pipeline
- 步骤1: 用户输入自然语言 → 意图识别(LLM分类: 查询/统计/趋势/对比)
- 步骤2: RAG上下文构建:
  - 从Elasticsearch检索相关表schema(Iceberg Catalog索引)
  - 注入: 表名/字段/注释/样例数据(3行)/外键关系
  - 上下文Token预算: 4000 tokens
- 步骤3: LLM SQL生成(via SP-04 AI网关):
  - Prompt: "根据以下表结构生成Trino SQL: {schema_context}\n用户问题: {question}\n只生成SELECT语句"
  - 模型: GPT-4级别LLM
- 步骤4: SQL校验:
  - Antlr语法检查(Trino dialect)
  - 权限检查: 只允许SELECT, 禁止INSERT/DELETE/DDL
  - 查询超时: 30s硬限制
- 步骤5: Trino执行 → 结果返回
- 步骤6: 可视化推荐(LLM): 根据结果列类型推荐图表(折线/柱状/饼图/表格)

#### 12.4.2 多轮对话上下文管理
- Context Window: 维护最近5轮对话(问题+SQL+结果摘要)
- 追问类型:
  - 维度变换: "换个维度看" → 修改GROUP BY
  - 过滤追加: "加上时间范围XXX" → 追加WHERE条件
  - 下钻: "展开XX的明细" → 关联子表查询
- 上下文存储: Redis (key=user_session_id, TTL=30min)

#### 12.4.3 安全与权限
- SQL白名单: 只允许SELECT + WITH子句, 禁止DML/DDL
- 行级权限: Trino + Apache Ranger, 按用户角色过滤可见行
- 审计日志: 所有NL Query记录到Iceberg表 `audit.nl_query_log`
- 速率限制: 每用户每分钟最多10次查询

### 12.5 新增作业清单

| 作业ID | 作业名称 | 类型 | 调度 | 目标 |
|--------|---------|------|------|------|
| J-META-DISCOVER | 元数据自动发现 | Flink Streaming | 持续(事件驱动) | schema变更→元数据可搜索<60s |
| J-LINEAGE-GNN | 字段血缘推断 | Spark Batch | 每日01:00 | 血缘覆盖率≥95% |
| J-DRIFT-ONLINE | 在线特征漂移检测 | Flink Streaming | 持续 | PSI>0.2告警<5min |
| J-DRIFT-OFFLINE | 离线特征漂移检测 | Spark Batch | 每日02:00 | KL/KS批量检测 |
| J-FEEDBACK-CONSUMER | 特征重要性反馈消费 | Flink Streaming | 持续 | SHAP反馈→缓存策略调整 |
| J-CAPACITY-PREDICT | 容量预测 | Python+Cron | 每日06:00 | 30天预测+扩容工单 |
| J-ANOMALY-DETECT | 异常检测 | Flink Streaming | 持续 | 异常检测<1min |
| J-SCHEDULE-OPTIMIZE | 调度优化 | Spark Batch | 每周日03:00 | Flink作业资源配置优化 |
| J-NL-QUERY | 自然语言查询服务 | REST API | 按需 | P95<8s |

### 12.6 新增Iceberg表DDL

```sql
-- 元数据表
CREATE TABLE meta.column_lineage (
    source_table STRING, source_column STRING,
    target_table STRING, target_column STRING,
    confidence DOUBLE, inferred_by STRING,  -- 'rule' or 'gnn'
    created_at TIMESTAMP(6)
) USING iceberg PARTITIONED BY (days(created_at));

CREATE TABLE meta.data_asset_profile (
    table_name STRING, quality_score INT,
    query_count_7d INT, query_count_30d INT,
    storage_bytes BIGINT, cost_monthly DECIMAL(10,2),
    updated_at TIMESTAMP(6)
) USING iceberg;

-- 特征漂移报告
CREATE TABLE meta.feature_drift_report (
    feature_name STRING, drift_type STRING,  -- 'psi'/'kl'/'ks'
    drift_value DOUBLE, threshold DOUBLE,
    baseline_window STRING, current_window STRING,
    detected_at TIMESTAMP(6)
) USING iceberg PARTITIONED BY (days(detected_at));

-- AIOps指标历史
CREATE TABLE ops.metrics_history (
    metric_name STRING, metric_value DOUBLE,
    labels MAP<STRING,STRING>, recorded_at TIMESTAMP(6)
) USING iceberg PARTITIONED BY (days(recorded_at));

CREATE TABLE ops.flink_job_profile (
    job_id STRING, input_records BIGINT, duration_ms BIGINT,
    parallelism INT, tm_cores INT, tm_memory_mb INT,
    backpressure_ratio DOUBLE, executed_at TIMESTAMP(6)
) USING iceberg PARTITIONED BY (days(executed_at));

-- NL查询审计
CREATE TABLE audit.nl_query_log (
    user_id STRING, question STRING, generated_sql STRING,
    executed BOOLEAN, result_rows INT, latency_ms INT,
    queried_at TIMESTAMP(6)
) USING iceberg PARTITIONED BY (days(queried_at));
```

### 12.7 与SP-04 AI中台接口契约补充

| 接口 | 方向 | 协议 | 描述 |
|------|------|------|------|
| Feature Feedback | SP-04→SP-02 | Pulsar `feature-feedback` | SHAP特征重要性, JSON: {model_id, feature_importance: [{name, shap}]} |
| Drift Alert | SP-02→SP-04 | Pulsar `drift-alert` | 特征漂移事件, JSON: {feature_name, psi, baseline, current} |
| NL Query LLM | SP-02→SP-04 | HTTP REST | Text-to-SQL请求, POST /v1/chat/completions (D-086修正: 原文档/ai-gateway/llm/completions与SP-04实际实现不符) |
| Model Inference | SP-02→SP-04 | HTTP REST | LSTM/IsolationForest推理, POST /v1/chat/completions (D-085: AIOps改用纯Java本地推理, 不走SP-04 REST) |

### 12.8 设计追溯补充

| 设计项 | 关联需求 | 关联ADR |
|--------|---------|---------|
| 智能数据目录 | FR-AI-01..03 | ADR-D05 |
| 智能特征平台 | FR-AI-04..06 | ADR-D06 |
| 预测性运维 | FR-AI-07..09 | ADR-D07 |
| 自然语言查询 | FR-AI-10..11 | ADR-D08 |

---

**文档结束**。本 SDD 为编码与部署的直接输入，变更需同步 SAD ADR 与 SRS 追溯矩阵。
