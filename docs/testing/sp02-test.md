# SP-02 数据中台 测试方案

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-02-TEST | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-02-SRS/SAD/SDD v1.0 |

> 本测试方案覆盖：Flink job 单元测试、CDC pipeline 集成测试、Iceberg 表测试、端到端延迟测试、数据质量测试、压测。目标验证 SLO G-06/G-09 与 ADR-D01..D-04 落地。

---

## 1. 测试策略总览

| 层级 | 范围 | 工具 | 通过标准 |
|------|------|------|---------|
| L1 单元测试 | Flink 算子/UDF/CEP 模式 | JUnit5 + Flink MiniCluster + flink-testing | 覆盖率≥80% |
| L2 集成测试 | CDC pipeline 全链路（TiDB→Kafka→Flink→Iceberg） | Testcontainers + flink-cdc-pipeline-test | 数据一致 100% |
| L3 表测试 | Iceberg time-travel/schema evolution/compaction | Iceberg API + JUnit | 全通过 |
| L4 端到端延迟 | 入湖 P95<5min（G-09） | 计时探针 + Prometheus | P95<5min |
| L5 数据质量 | Avro schema/行数/空值/枚举 | Great Expectations + 自研 DQ | 0 critical |
| L6 压测 | 吞吐≥10,000 ev/s（M1）/≥500,000 ev/s（M3） | 分布式 Locust + Kafka 压测工具 | 达标 |
| L7 混沌 | checkpoint 失败/JM OOM/Kafka 分区迁移 | Chaos Mesh | 自动恢复 RTO<60s |
| L8 安全 | Kafka ACL/Trino 行级权限/Feature 越权 | 渗透 + 配置审计 | 0 高危 |

---

## 2. Flink Job 单元测试（L1）

### 2.1 测试目标
验证每个 Flink job 的算子逻辑（filter/keyBy/window/aggregate/CEP）正确性。

### 2.2 测试框架
- JUnit5 + `flink-test-utils`（MiniCluster）
- `ProcessFunctionTestHarnesses`（算子单测）
- `CEPPatternUtils`（CEP 模式验证）

### 2.3 测试用例

#### 2.3.1 J-RT-LEARN 学习指标算子
```java
@Test
void testLearningMetricAggregate() throws Exception {
    // given
    List<LearningEvent> events = List.of(
        event("u1", "video_complete", "2026-08-03 10:00:01"),
        event("u1", "submit", "2026-08-03 10:00:30"),
        event("u2", "video_complete", "2026-08-03 10:00:59")
    );
    // when
    TestHarness<LearningEvent> harness = ProcessFunctionTestHarnesses
        .forFunction(new LearningMetricAggregate(1min));
    for (LearningEvent e : events) harness.processElement(e);
    // then
    LearningMetric result = harness.extractOutput();
    assertEquals(2, result.online_users);     // u1,u2
    assertEquals(2, result.completes);        // 2 video_complete
    assertEquals(1, result.submits);          // 1 submit
}
```

#### 2.3.2 J-RT-RISK CEP 模式
| 用例 | 输入 | 期望 |
|------|------|------|
| 同 IP 5 账号 1min | 5 不同 user_id 同 ip 60s 内 | 触发 multi_account alert |
| 同 IP 4 账号 1min | 4 不同 user_id | 不触发 |
| 秒级批量 10 单 10s | 1 user 10 单 10s 内 | 触发 batch_order alert |
| 9 单 10s | 1 user 9 单 | 不触发 |
| 异常登录 | 异地+新设备双流 | 触发 abnormal_login |

#### 2.3.3 J-RT-LIVE CEP 断流
| 用例 | 输入 | 期望 |
|------|------|------|
| 30s 无心跳 | 心跳后 30s 无 stop | 触发 BROKEN_STREAM |
| 正常 stop | 心跳 + stop | 不触发 |
| 持续心跳 | 心跳每 5s | 不触发 |

### 2.4 覆盖率目标
- 算子逻辑 ≥80%
- CEP 模式 100%（安全核心）
- UDF ≥80%

---

## 3. CDC pipeline 集成测试（L2）

### 3.1 测试目标
验证 TiDB→TiCDC→Kafka→Flink→Iceberg 全链路数据一致性（ADR-D01）。

### 3.2 测试环境
- Testcontainers：TiDB + Kafka + Schema Registry + PostgreSQL(catalog) + MinIO
- Flink MiniCluster 运行 CDC pipeline YAML

### 3.3 测试用例

#### 3.3.1 数据一致性
| 用例 | 操作 | 验证 |
|------|------|------|
| 单行 insert | TiDB INSERT order | Iceberg ods_order 行数+1，字段一致 |
| 单行 update | TiDB UPDATE order status | Iceberg 对应行 updated_at 更新，status 更新 |
| 单行 delete | TiDB DELETE order | Iceberg 对应行（delete 标记）符合预期 |
| 批量 1000 行 | TiDB 批量插入 | Iceberg 行数=1000，无丢失 |
| 分库分表合并 | order_0000..1023 各插 100 | Iceberg ods_order 行数=102400，source_cell 标注正确 |
| Avro schema 演化 | ADD COLUMN coupon_id | Iceberg 新列可查，历史行 NULL |

#### 3.3.2 端到端行数校验
```python
def test_cdc_row_count_consistency():
    # 1. 向 TiDB 插入 N 行
    insert_rows(tidb_conn, "order", n=10000)
    # 2. 等待 Flink checkpoint commit (最多 6min, 含 1min 容差)
    wait_for_iceberg_commit("ods_order", expected_rows=10000, timeout=360)
    # 3. 校验行数
    actual = query_iceberg("SELECT COUNT(*) FROM ods_order")
    assert actual == 10000
    # 4. 校验字段抽样
    sample = query_iceberg("SELECT * FROM ods_order WHERE order_id IN (1,5000,10000)")
    assert validate_fields(sample, expected)
```

#### 3.3.3 Schema 演化集成
| 用例 | 操作 | 验证 |
|------|------|------|
| ADD COLUMN | DDL ADD coupon_id + Registry 注册 | Flink 作业自动 EVOLVE，Iceberg 表新增列，查询不中断 |
| DROP COLUMN | DDL DROP（兼容模式 BACKWARD） | 下游作业 fallback，历史数据保留 |
| 类型变更 | INT→BIGINT | Registry 拒绝不兼容变更，作业挂起告警 |

---

## 4. Iceberg 表测试（L3）

### 4.1 Time-travel
```python
def test_time_travel():
    # 1. 写入快照 S1
    insert_rows("ods_order", n=100)
    snap1 = current_snapshot("ods_order")
    # 2. 写入快照 S2
    insert_rows("ods_order", n=100)
    snap2 = current_snapshot("ods_order")
    # 3. time-travel 查询
    rows_s1 = query_iceberg("SELECT * FROM ods_order VERSION AS OF {snap1}")
    rows_s2 = query_iceberg("SELECT * FROM ods_order VERSION AS OF {snap2}")
    assert len(rows_s1) == 100
    assert len(rows_s2) == 200
    # 4. AI 训练切片复现
    assert reproduce_training(snap1) == baseline_model
```

### 4.2 Schema evolution
| 用例 | 操作 | 验证 |
|------|------|------|
| ADD COLUMN | ALTER TABLE ADD COLUMN coupon_id | 旧查询不报错，新查询可见新列 |
| RENAME COLUMN | ALTER TABLE RENAME a TO b | 查询 b 正常，旧名不可用 |
| 类型 EVOLVE | INT→BIGINT | 历史数据自动转，无重写 |

### 4.3 Compaction
| 用例 | 初始 | 操作 | 验证 |
|------|------|------|------|
| rewrite 合并 | 50 个 5MB 小文件 | rewrite_data_files target=256MB | 合并为 1 个 250MB 文件 |
| expire 快照 | 20 个快照 | expire_snapshots retain_last=5 | 快照数=5，旧数据文件清理 |
| orphan 清理 | 模拟孤儿文件 | remove_orphan_files | orphan=0 |
| convertEquality | 等值 delete | convertEquality | 转 position delete，读性能提升 |
| flink-job-id 保留 | 含 job-id tag | expire 跳过 job-id 快照 | 状态恢复可用 |

### 4.4 ACID
| 用例 | 操作 | 验证 |
|------|------|------|
| 写读隔离 | 写 checkpoint 中 | 查询可见旧快照，不阻塞 |
| 失败回滚 | 模拟 checkpoint 失败 | 无脏数据，旧快照可查 |

---

## 5. 端到端延迟测试（L4，G-09）

### 5.1 测试目标
验证 CDC 入湖端到端 P95<5min（NFR-01 / G-09）。

### 5.2 测试方法
1. 在 TiDB 插入带 `mark_ts` 的探针行
2. 轮询 Iceberg 表，记录出现时间 `commit_ts`
3. 延迟 = `commit_ts - mark_ts`
4. 重复 1000 次，统计 P50/P95/P99

### 5.3 测试用例
| 用例 | 并发 | 期望 P95 |
|------|------|---------|
| 低并发 100 ev/s | 100 | <5min |
| 中并发 10,000 ev/s | 10,000 | <5min |
| 高并发 100,000 ev/s | 100,000 | <5min（M3 验证） |
| 背压（Kafka lag 100k） | 10,000 | <5min（恢复后） |

### 5.4 计时探针
```python
def measure_lag(tidb, iceberg, n=1000):
    lags = []
    for i in range(n):
        mark_ts = time.now()
        tidb.execute(f"INSERT INTO probe (id, mark_ts) VALUES ({i}, '{mark_ts}')")
        while True:
            if query_iceberg(f"SELECT 1 FROM ods_probe WHERE id={i}"):
                commit_ts = time.now()
                lags.append((commit_ts - mark_ts).seconds)
                break
            sleep(5)
    assert percentile(lags, 95) < 300  # 5min
```

---

## 6. 数据质量测试（L5）

### 6.1 Avro schema 校验
| 用例 | 输入 | 期望 |
|------|------|------|
| 合规 Avro | 符合 schema 的消息 | Flink 正常消费 |
| schema 不兼容 | 缺必填字段 | deserializer 报错，消息入 DLQ |
| Registry 不可用 | Registry 宕机 | Flink fallback 缓存 schema，继续运行 |

### 6.2 行数/空值/枚举漂移（Great Expectations）
```yaml
# dq_ods_order.yaml
suite: ods_order_daily
expectations:
  - expect_table_row_count_to_be_between:
      min_value: 10000
      max_value: 10000000
  - expect_column_values_to_not_be_null:
      column: order_id
  - expect_column_values_to_be_in_set:
      column: status
      value_set: ["created","paid","refunded","cancelled"]
  - expect_column_mean_to_be_between:
      column: amount
      min_value: 0
      max_value: 100000
```

### 6.3 特征一致性（ADR-D03）
| 用例 | 验证 | 期望 |
|------|------|------|
| 在线/离线 skew | 对账作业抽样 | skew<0.1% |
| 特征新鲜度 | Redis TTL | <online_ttl |
| 训练切片复现 | snapshot-id 切片 | 模型输出一致 |

---

## 7. 压测（L6，G-06）

### 7.1 吞吐目标
- M1：学习事件 ≥10,000 ev/s
- M3：≥500,000 ev/s

### 7.2 压测工具
- Kafka 生产端：`kafka-producer-perf-test` + 自研 Avro 生成器
- Flink 背压监控：`flink-metrics` backpressure
- Locust：模拟多 BU 数据分析师 Trino 并发查询

### 7.3 压测用例
| 用例 | 并发 | 持续 | 期望 |
|------|------|------|------|
| CDC 入湖吞吐 | 100,000 ev/s | 1h | Kafka lag<100k，Iceberg commit 正常 |
| 实时指标算子 | 100,000 ev/s | 1h | Flink throughput 稳定，checkpoint 不失败 |
| Trino 并发查询 | 50 并发 | 30min | P95<10s，资源组隔离生效 |
| Feature 在线查询 | 50,000 QPS | 30min | P99<5ms |
| 混合压测 | CDC+查询+特征 | 2h | 全链路稳定 |

### 7.4 容量推演
- 单 Flink TM（4 vCPU/8GB）吞吐约 20,000 ev/s（学习事件）
- 100,000 ev/s 需 8 TM；500,000 ev/s 需 32 TM（M3）
- Kafka 分区：每分区 10,000 ev/s，100,000 ev/s 需 10 分区

---

## 8. 混沌测试（L7）

### 8.1 故障注入（Chaos Mesh）

| 用例 | 故障 | 验证 |
|------|------|------|
| JM OOM | kill JobManager | 自动重启 + checkpoint 恢复，RTO<60s，无数据丢失 |
| TM OOM | kill TaskManager | 重启，状态从 checkpoint 恢复 |
| Kafka 分区迁移 | leader 切换 | Flink 自动重平衡，无中断 |
| Schema Registry 宕机 | 网络分区 | Flink fallback 缓存 schema，继续运行 |
| 对象存储抖动 | 延迟 5s | checkpoint 超时，自动重试 |
| 跨区网络抖动 | cell 间延迟 500ms | 本地消费不阻塞，中心聚合延迟上升但可恢复 |

### 8.2 恢复验证
- savepoint 恢复演练：每周 1 次，验证 RTO<60s（NFR-06）
- flink-job-id 快照保留验证：恢复后状态不断裂

---

## 9. 安全测试（L8）

| 用例 | 操作 | 期望 |
|------|------|------|
| Kafka ACL 越权 | 非授权消费组读 CDC topic | 拒绝 |
| Trino 行级权限 | BU-A 查询 BU-B 表 | 拒绝 |
| Feature 越权 | app 拼接他人 user_id 查特征 | 拒绝（key 强制 JWT 解析） |
| Schema Registry 写越权 | 非平台工程师注册 schema | 拒绝 |
| 审计完整性 | 任意 DDL/compaction | 审计日志可见 |

---

## 10. 测试环境与数据

### 10.1 环境矩阵
| 环境 | 用途 | 规模 |
|------|------|------|
| CI（单节点） | L1 单元测试 | MiniCluster |
| 集成测试（Testcontainers） | L2/L3 | 容器化 TiDB/Kafka/MinIO |
| 预发（3 节点） | L4/L5 | 小规模真实拓扑 |
| 压测（多节点） | L6 | 3+ Flink TM，多分区 Kafka |

### 10.2 测试数据
- 合成数据生成器：按 TiDB 表结构生成 1亿/10亿 行
- 生产数据脱敏采样：10% 抽样（PII 脱敏）用于 DQ 校准

---

## 11. 测试通过标准（验收门）

| 门 | 标准 | 关联 SRS |
|----|------|---------|
| G1 单元测试 | 覆盖率≥80%，CEP 100% | NFR |
| G2 集成测试 | CDC 一致性 100% | FR-CDC |
| G3 表测试 | time-travel/schema/compaction 全通过 | FR-LH |
| G4 延迟 | 入湖 P95<5min | NFR-01/G-09 |
| G5 数据质量 | 0 critical DQ 失败 | NFR |
| G6 压测 | M1 吞吐≥10,000 ev/s | NFR-02/G-06 |
| G7 混沌 | RTO<60s 自动恢复 | NFR-06 |
| G8 安全 | 0 高危 | NFR-10 |

---

## 12. 测试追溯

| SRS 需求 | 测试章节 |
|---------|---------|
| FR-CDC-01..05 | §3 集成测试 |
| FR-LH-01..06 | §4 Iceberg 测试 |
| FR-RT-01..06 | §2 单元测试 |
| FR-FS-01..05 | §6.3 特征一致性 |
| FR-CS-01..04 | §3.3.2 + 端到端 |
| NFR-01 (G-09) | §5 延迟测试 |
| NFR-02 (G-06) | §7 压测 |
| NFR-06 恢复 | §8 混沌 |

---

**文档结束**。本测试方案与 SDD 编码同步迭代，新增 Job/表需补对应测试用例。
