# SP-02 数据中台 软件架构设计（SAD）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-02-SAD | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | EP-SPEC-001 §4 + SP-02-SRS v1.0 |

> 本 SAD 兑现深度：TOGAF 四域对齐、6 层架构图、CDC 数据流、ADR-D01..D-04 详述、Iceberg 表设计、Feature Store 架构、Trino 查询层、部署拓扑。承接顶层 SAD §4，细化到 SDD 可直接落地的契约级。

---

## 1. 引言

### 1.1 目的
定义数据中台的架构视图、关键决策、组件契约与部署拓扑，作为 SDD 与部署文档的架构基线。

### 1.2 范围
覆盖 SRS 中 FR-RT/CDC/LH/CS/SV/QY/FS/OPS 全部功能模块的架构落地。

### 1.3 架构原则
1. **CDC 解耦**：TiCDC→Kafka→Flink→Iceberg，非直连（ADR-D01）
2. **checkpoint 即提交**：Flink→Iceberg 用 checkpoint 界定事务（ADR-D02）
3. **冷热强制分层**：在线只留热数据，温冷归 Iceberg（ADR-D04）
4. **特征一次定义双作业**：Feature Store 编译为 Spark+Flink（ADR-D03）
5. **湖仓一体**：Iceberg 作为离线/训练/归档统一落点
6. **复用基础设施**：不新建 OLAP 库，TiDB+TiFlash+Trino+Iceberg 各司其职

---

## 2. TOGAF 四域对齐

| TOGAF 域 | 数据中台对应 | 关键组件 |
|----------|-------------|---------|
| 业务架构(BA) | 数据消费者：分析师/AI工程师/运营/风控 | 看板/Feature Store/Trino/实时大屏 |
| 数据架构(DA) | 6 层湖仓一体 | Iceberg 表/CDC pipeline/Feature Store |
| 应用架构(AA) | Flink jobs/Spark jobs/Trino/Feature Store 服务 | 作业编排/查询服务 |
| 技术架构(TA) | K8s+Operator/Kafka/TiDB/S3/MinIO/Prometheus | 基础设施层 |

---

## 3. 6 层架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│ ① 实时层(Flink)                                                   │
│   Flink SQL/CEP 实时指标(学习/直播/交易/风控/计费)                  │
│   实时特征工程→AI中台 Feature Store                                │
│   窗口: 分钟/秒级; CEP 规则引擎; 双流 join                          │
├──────────────────────────────────────────────────────────────────┤
│ ② CDC 总线                                                        │
│   TiDB ─TiCDC changefeed─▶ Kafka(Avro+SchemaRegistry)             │
│   Flink CDC 3.x YAML pipeline: 分库分表合并→ODS Iceberg            │
│   不直连 Flink-CDC-TiDB(避免 GC safefence 阻塞全库)               │
├──────────────────────────────────────────────────────────────────┤
│ ③ 湖仓层(Apache Iceberg + S3/MinIO)                              │
│   Flink CDC 实时写 Iceberg(checkpoint=commit,5min,256MB,zstd)     │
│   SinkV2 链式 compaction(rewrite+expire+orphan+convertEquality)   │
│   保留 flink-job-id 快照供状态恢复                                 │
│   Time-travel / Schema evolution EVOLVE / ACID                    │
├──────────────────────────────────────────────────────────────────┤
│ ④ 冷热分层                                                        │
│   热: TiDB/TiFlash 在线 HTAP(近90天+活跃用户)                     │
│   温/冷: Iceberg + 对象存储(成本数量级下降)                       │
│   归档作业: 超期数据 TiDB→Iceberg, 删 TiDB 行                     │
├──────────────────────────────────────────────────────────────────┤
│ ⑤ 服务层                                                          │
│   离线数仓: Spark + Iceberg(留存/画像/复杂报表 T+1)               │
│   AI训练数据: Iceberg→Spark/Flink→模型训练 pipeline               │
│   跨BU联邦: Iceberg 统一落点, Trino 跨BU查询, 行级权限             │
├──────────────────────────────────────────────────────────────────┤
│ ⑥ 查询层                                                          │
│   在线: TiDB/TiFlash HTAP(P95<500ms)                             │
│   湖仓交互: Trino/Presto on Iceberg(ad-hoc/数据科学, P95<10s)     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. CDC 数据流图（ADR-D01 落地）

```
┌──────────┐   DDL/DML    ┌─────────┐  changefeed   ┌────────────────────┐
│  TiDB    │─────────────▶│ TiCDC   │──────────────▶│ Kafka              │
│ (分库分表)│              │ (集群)  │               │ (topic=tbl_group)  │
└──────────┘              └─────────┘               └─────────┬──────────┘
                                                              │ Avro(Registry)
                                                              ▼
                                                    ┌────────────────────┐
                                                    │ Schema Registry    │
                                                    │ (Confluent/Apicurio)│
                                                    └─────────┬──────────┘
                                                              │ EVOLVE
                                                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  Flink CDC 3.x YAML pipeline                                             │
│  source: Kafka(topic=tbl_group, Avro)                                    │
│  route:  order_0000..order_1023 → ods_order                              │
│  sink:   Iceberg(catalog=iceberg_jdbc, table=ods_order)                  │
│  checkpoint=5min, target=256MB, zstd, SinkV2 链式 compaction             │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │ checkpoint=commit
                                     ▼
                            ┌────────────────────┐
                            │ Iceberg (S3/MinIO) │
                            │ ods_order          │
                            │ partition: dt/h    │
                            └─────────┬──────────┘
                                      │
                ┌─────────────────────┼─────────────────────┐
                │                     │                     │
                ▼                     ▼                     ▼
        ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
        │ DWD/DWS Spark│      │ Trino ad-hoc │      │ AI 训练切片  │
        │ (T+1 数仓)   │      │ (查询层)     │      │ (time-travel)│
        └──────────────┘      └──────────────┘      └──────────────┘
```

**关键设计点**：
- **不直连 Flink-CDC-TiDB**：直连会持 GC safefence，慢表阻塞全库（ADR-D01）
- **按表分组 changefeed**：高频表（订单/学习事件）独立 changefeed，避免互相影响
- **Avro+SchemaRegistry**：DDL 变更经 Registry EVOLVE，下游 Flink 作业自动适配
- **YAML pipeline 声明式**：分库分表合并（order_0000..1023 → ods_order）路由在 YAML 声明

---

## 5. 关键 ADR 详述

### ADR-D01 CDC=TiCDC→Kafka→Flink→Iceberg（非直连）

| 项 | 内容 |
|----|------|
| 背景 | 早期方案 Flink-CDC 直连 TiDB，慢表 CDC 会持 GC safefence，阻塞全库 GC，导致在线业务 OOM |
| 决策 | CDC 链路 = TiCDC changefeed → Kafka(Avro+SchemaRegistry) → Flink CDC 3.x YAML pipeline → Iceberg |
| 备选 | A. Flink-CDC 直连 TiDB（否：GC 阻塞）B. Canal→Kafka（否：TiDB 用 TiCDC 更稳） C. Debezium（否：MySQL 向，TiDB 适配弱） |
| 收益 | 解耦上下游；Schema Registry 演化；Kafka 削峰；多消费方共享 CDC 流 |
| 代价 | 多一跳 Kafka（延迟+1~2s）；Avro+Registry 运维成本 |
| 依据 | TiCDC FAQ + Flink CDC YAML pipeline 文档 |

### ADR-D02 Flink→Iceberg checkpoint 即提交

| 项 | 内容 |
|----|------|
| 背景 | Flink 写 Iceberg 需界定事务边界，频繁 commit 产生小文件，稀疏 commit 增延迟 |
| 决策 | checkpoint=commit：checkpoint interval=5min，target file=256MB，zstd 压缩；SinkV2 后置链式 compaction（rewrite+expire+orphan+convertEquality）；保留 flink-job-id 标记快照，过期时跳过它 |
| 备选 | A. 事务型 sink 独立提交（否：与 checkpoint 解耦难恢复） B. 1min commit（否：小文件爆炸） C. 10min commit（否：超 G-09 5min SLO） |
| 收益 | 5min 延迟达标；256MB 文件读优；链式 compaction 无独立维护作业；flink-job-id 快照支持状态恢复 |
| 代价 | 5min 延迟下限（实时大屏需另走 Redis Stream 秒级）；compaction 资源消耗 |
| 依据 | Iceberg 官方 Flink writes + LakeOps 生产调优 + 字节 StreamShield |

### ADR-D03 Feature Store 在线/离线一致性

| 项 | 内容 |
|----|------|
| 背景 | AI 推理用在线特征（Redis），训练用离线特征（Iceberg），双份代码易 skew |
| 决策 | 特征 DSL 定义一次，编译为 Spark 离线作业 + Flink 在线作业（Hopsworks/Feast 模式）；Avro schema 防在线/离线 skew；定时对账 skew<0.1% |
| 备选 | A. 双份手写（否：skew 必现） B. 仅在线（否：训练无历史） C. 仅离线（否：推理延迟不达标） |
| 收益 | 一次定义双作业；在线亚 ms + 离线可回溯；Avro 强一致 |
| 代价 | DSL 编译器开发；Avro Registry 运维；对账作业开销 |
| 依据 | Hopsworks + Feast |

### ADR-D04 30亿历史数据冷热分层强制

| 项 | 内容 |
|----|------|
| 背景 | 30亿用户历史数据全压 TiDB 在线库，成本与性能双崩 |
| 决策 | 热（近90天+活跃用户）留 TiDB/TiFlash；温冷归 Iceberg+对象存储；AI训练/历史报表/归档审计走 Iceberg，不回压在线库 |
| 备选 | A. 全量 TiDB（否：成本数量级超标） B. 全量 Iceberg（否：在线 P95 不达标） C. HBase 冷存（否：无 SQL/无 time-travel） |
| 收益 | 在线库容量稳定；冷数据成本<热 1/10；Iceberg time-travel 支持回查 |
| 代价 | 冷热查询需 Trino 联邦；归档迁移期对在线库压力 |
| 依据 | D-013 |

---

## 6. Iceberg 表设计

### 6.1 分层模型
```
ODS(原始CDC) → DWD(明细) → DWS(汇总) → ADS(应用)
   ↓              ↓            ↓          ↓
 Iceberg        Iceberg     Iceberg    TiDB/TiFlash+Redis
```

### 6.2 表设计规范

| 层 | 表命名 | 分区 | 格式 | compaction | 保留期 |
|----|--------|------|------|-----------|--------|
| ODS | ods_\<tbl\> | dt(天) + h(小时) | parquet+zstd | 256MB rewrite, 1h | 90天 |
| DWD | dwd_\<domain\>_\<event\> | dt | parquet+zstd | 256MB rewrite, 1d | 1年 |
| DWS | dws_\<metric\>_\<granu\> | dt | parquet+zstd | 128MB rewrite, 1d | 3年 |
| ADS | ads_\<app\> | - | (TiDB) | - | 按业务 |

### 6.3 关键表 DDL 范式（详见 SDD §3）

```sql
-- ODS: 订单 CDC 入湖
CREATE TABLE iceberg_jdbc.ods.ods_order (
  `op_type` STRING,          -- c/u/d/r
  `ts_ms` BIGINT,            -- CDC 时间戳
  `source_cell` STRING,      -- 来源单元
  `before` STRING,           -- 变更前(JSON)
  `after` STRING,            -- 变更后(JSON)
  -- 业务字段展开(从 after 解)
  `order_id` BIGINT,
  `user_id` BIGINT,
  `amount` DECIMAL(10,2),
  `status` STRING,
  `created_at` TIMESTAMP(3),
  `updated_at` TIMESTAMP(3),
  PRIMARY KEY(order_id) NOT ENFORCED
) PARTITIONED BY (days(created_at))
WITH ('format'='parquet','write.format.default'='parquet',
      'write.parquet.compression-codec'='zstd',
      'flink.max-continuous-num-commits'='...');
```

### 6.4 compaction 策略
- **rewrite_data_files**：target 256MB（ODS）/128MB（DWS），binpack 策略，触发阈值小文件数>50
- **expire_snapshots**：ODS 保留 7 天快照，DWS 30 天
- **remove_orphan_files**：每日清理
- **convertEquality**：等值 delete 转 position delete，提升读性能

---

## 7. Feature Store 架构（ADR-D03）

```
┌─────────────────────────────────────────────────────────────┐
│ 特征定义层(Feature DSL)                                      │
│  feature_def.yaml: name/type/transform/window/online_offline │
└──────────────────────────┬──────────────────────────────────┘
                           │ 编译器
            ┌──────────────┴──────────────┐
            ▼                             ▼
  ┌──────────────────┐          ┌──────────────────┐
  │ Spark 离线作业    │          │ Flink 在线作业    │
  │ 读 Iceberg 历史特征│          │ 读 Kafka 实时流   │
  │ 写回 Iceberg(离线 │          │ 写 Redis/TiKV     │
  │ store)+训练数据集 │          │ (在线 store)      │
  └────────┬─────────┘          └────────┬─────────┘
           │                             │
           ▼                             ▼
  ┌──────────────────┐          ┌──────────────────┐
  │ Iceberg 离线 store│          │ Redis/TiKV 在线  │
  │ (特征历史+版本)   │          │ store(亚ms)      │
  └────────┬─────────┘          └────────┬─────────┘
           │                             │
           └──────────┬──────────────────┘
                      ▼
            ┌──────────────────┐
            │ 一致性对账作业    │
            │ (Avro schema 校验│
            │  skew<0.1%)       │
            └──────────────────┘
```

**关键点**：
- 特征 DSL 一次定义，编译器生成 Spark + Flink 双作业，杜绝双份代码
- 在线/离线均用 Avro schema 序列化，Registry 强制版本对齐
- 对账作业每日跑，对比同特征在线/离线值，skew>0.1% 告警

---

## 8. Trino 查询层

```
┌──────────────────────────────────────────────────┐
│ Trino Coordinator + Workers (K8s)                │
│  - Hive/Iceberg catalog → S3/MinIO + Glue/JDBC   │
│  - 资源组(per BU/数据分析师) 限流                  │
│  - 物化视图(Iceberg) 加速重复查询                  │
└────────────────────┬─────────────────────────────┘
                     │ 查询
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   ┌─────────┐  ┌─────────┐  ┌─────────┐
   │ Iceberg │  │ Iceberg │  │ TiDB    │
   │ ODS/DWD │  │ DWS/ADS │  │ (JDBC)  │
   └─────────┘  └─────────┘  └─────────┘
```

- **资源隔离**：每 BU 独立资源组，防止大查询拖垮他人
- **联邦查询**：Trino 跨 Iceberg + TiDB（JDBC connector）冷热联合
- **加速**：热点查询建 Iceberg 物化视图，Trino 自动改写

---

## 9. 部署拓扑

```
┌──────────────── 国内多活(华东/华北/华南)────────────────┐
│                                                         │
│  每 cell 内:                                            │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│   │ TiDB+TiCDC   │  │ Kafka(local) │  │ Flink K8s    │ │
│   │ (本cell分片) │─▶│ (CDC topic)  │─▶│ Operator     │ │
│   └──────┬───────┘  └──────────────┘  └──────┬───────┘ │
│          │                                   │         │
│          │ TiFlash(HTAP)                     ▼         │
│          │                          ┌────────────────┐ │
│          │                          │ Iceberg        │ │
│          │                          │ (S3/MinIO 跨区)│ │
│          │                          └──────┬─────────┘ │
│          │                                 │           │
│          │                          ┌──────▼─────────┐ │
│          │                          │ Trino集群(共享)│ │
│          │                          │ + Spark(数仓)  │ │
│          │                          └────────────────┘ │
│          │                                            │
│  Schema Registry(华北主)+只读副本                    │
│  跨cell聚合: 各cell Kafka → 中心 Flink → 中心 Iceberg │
└────────────────────────┬────────────────────────────────┘
                         │ 海外独立(数据不回流)
┌────────────────────────▼─────────────────────────────┐
│ 海外cell: 独立 TiDB/Kafka/Flink/Iceberg, 数据隔离    │
└──────────────────────────────────────────────────────┘
```

**关键拓扑决策**：
- **每 cell 独立 Kafka**：CDC 流本地消费，避免跨区 RTT
- **Iceberg 共享对象存储**：S3/MinIO 跨区，但 catalog 集中（JDBC）
- **Trino 共享**：跨 cell 查询统一入口，资源组隔离
- **Schema Registry 集中**：华北主 + 只读副本，保证 schema 一致

---

## 10. 组件契约（摘要，详见 SDD）

| 组件 | 输入 | 输出 | 契约 |
|------|------|------|------|
| TiCDC changefeed | TiDB 表变更 | Kafka Avro 消息 | topic=tbl_group, key=PK, Avro schema |
| Flink CDC pipeline | Kafka Avro | Iceberg 表 | YAML 声明，checkpoint=5min |
| Flink 实时指标 job | Kafka CDC 流 | Iceberg DWS + Redis Stream | SQL/CEP，分钟窗口 |
| Feature Store 编译器 | 特征 DSL | Spark+Flink 作业 | Avro schema |
| Trino 查询服务 | SQL | 结果集 | Iceberg+TiDB JDBC |
| 归档作业 | TiDB 超期行 | Iceberg ODS + 删 TiDB | 按 dt 分批 |

---

## 11. 架构决策追溯

| ADR | 来源决策 | SRS 需求 | SDD 章节 |
|-----|---------|---------|---------|
| ADR-D01 | D-016 | FR-CDC-01..03 | §2 CDC pipeline |
| ADR-D02 | D-017 | FR-LH-02..03 | §3 Flink Job |
| ADR-D03 | D-011 | FR-FS-01..04 | §4 特征定义 |
| ADR-D04 | D-013 | FR-CS-01..02 | §5 归档作业 |

---

## 12. 风险与对策

| ID | 风险 | 对策 |
|----|------|------|
| AR-01 | Flink checkpoint 失败率超阈值 | 监控 + 自动 savepoint 恢复（NFR-06） |
| AR-02 | Iceberg 小文件爆炸 | 链式 compaction + 小文件数告警 |
| AR-03 | Schema 演化破坏下游 | 灰度流程 + Pact 契约测试 |
| AR-04 | 跨 cell 聚合延迟 | 本地消费 + 中心异步聚合，不阻塞单元 |
| AR-05 | Trino 大查询拖垮 | 资源组限流 + 物化视图 |
| AR-06 | 对象存储跨区一致性 | 用强一致 S3 后端；MinIO 启用 erasure-coded |

---

**文档结束**。本文档为 SP-02-SDD 的架构基线，变更需经架构评审并同步 ADR 登记。
