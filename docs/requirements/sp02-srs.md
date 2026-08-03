# SP-02 数据中台 软件需求规格说明书（SRS）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-02-SRS | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | 顶层蓝图 EP-SPEC-001 §4 + 决策 D-011/D-013/D-016/D-017 |

> 本 SRS 兑现深度：数据中台全六层功能需求与非功能需求、四类用户故事、约束、量化验收标准。承接顶层 SAD ADR-D01..D-04。

---

## 1. 引言

### 1.1 目的
本文档定义 education-platform 项目群中 **SP-02 数据中台** 子项目的功能、非功能需求与验收标准，作为后续 SAD/SDD/测试/部署文档的上游输入，支撑 SLO G-06（学习事件 ≥500,000 ev/s）与 G-09（实时入湖 <5min）的达成。

### 1.2 范围
SP-02 覆盖数据中台六大职责层：
1. 实时层（Flink SQL/CEP 实时指标、实时特征工程）
2. CDC 总线（TiDB → TiCDC → Kafka → Flink → Iceberg）
3. 湖仓层（Apache Iceberg + S3/MinIO）
4. 冷热分层（热 TiDB/TiFlash，温冷 Iceberg）
5. 服务层（离线数仓、AI 训练数据、跨 BU 联邦）
6. 查询层（在线 HTAP、湖仓交互 Trino）

不在范围：业务中台的领域数据（订单/用户/课程）、AI 中台的模型训练框架本体、技术中台的可观测采集管道。

### 1.3 术语
| 术语 | 含义 |
|------|------|
| CDC | Change Data Capture，变更数据捕获 |
| TiCDC | TiDB 官方 CDC 组件，输出 changefeed 到 Kafka |
| Schema Registry | Avro schema 注册中心，支持 EVOLVE 演化 |
| Lakehouse | 湖仓一体，对象存储 + 表格式（Iceberg）提供 ACID/Time-travel |
| SinkV2 | Flink Iceberg Sink 第二代，checkpoint 即提交 + 链式 compaction |
| Feature Store | 特征仓库，在线/离线一致性特征服务（Hopsworks/Feast 模式） |
| HTAP | Hybrid Transactional/Analytical Processing，TiDB+TiFlash 混合 |
| compaction | Iceberg 小文件合并，含 rewrite/expire/orphan/convertEquality |

### 1.4 依据
- 顶层蓝图 EP-SPEC-001 §4 数据中台 SAD
- ADR-D01 CDC=TiCDC→Kafka→Flink→Iceberg（非直连）
- ADR-D02 Flink→Iceberg checkpoint=commit（5min/256MB/zstd/SinkV2）
- ADR-D03 Feature Store 在线/离线一致性
- ADR-D04 30亿历史数据冷热分层强制
- 决策 D-011/D-013/D-016/D-017

---

## 2. 总体描述

### 2.1 系统定位
数据中台是项目群的**统一数据底座**，向上为 AI 中台提供 Feature Store 与训练数据，向事业群提供实时指标、离线数仓、跨 BU 联邦查询；向下复用技术中台的 Kafka/Pulsar/可观测，对接基础设施层的 TiDB/TiFlash、S3/MinIO。

### 2.2 用户角色与画像

| 角色 | 职责 | 关键诉求 |
|------|------|---------|
| 数据分析师 | 留存/画像/复杂报表 | 湖仓交互查询秒级响应、Schema 演化不阻塞 |
| AI 工程师 | 模型训练数据准备、特征接入 | 在线/离线特征一致、训练数据可回溯(time-travel) |
| 运营 | 实时业务看板、活动效果 | 实时指标延迟<5min、看板自助配置 |
| 风控工程师 | 实时风控规则、计费对账 | CEP 延迟亚秒、风控特征在线可查 |
| 数据平台工程师 | Pipeline 运维、表治理 | checkpoint 失败可恢复、compaction 调度自动化 |

### 2.3 约束
| ID | 约束 | 来源 |
|----|------|------|
| C-01 | 复用现有 TiDB（不另起 OLAP 库），复用技术中台 Kafka/Pulsar/可观测 | D-011/EP-SPEC §2 |
| C-02 | CDC 不得直连 Flink-CDC-TiDB（避免 GC safepoint 阻塞全库） | ADR-D01 |
| C-03 | Flink→Iceberg 必须用 checkpoint=commit（不得用事务型 sink 独立提交） | ADR-D02 |
| C-04 | 30亿历史数据强制冷热分层，在线库只留热数据 | ADR-D04 |
| C-05 | 海外集群数据不回流国内（PIPL 合规） | D-004 |
| C-06 | Flink 作业跑在 K8s Operator 之上，不裸跑 YARN | EP-SPEC §8 |
| C-07 | Avro+Schema Registry 强制，禁 JSON CDC | D-016 |

### 2.4 假设与依赖
- 假设：TiDB 7.5+ 已部署并开启 TiCDC；S3/MinIO bucket 已创建；Kafka 3.x 集群可用；K8s 1.28+ + Flink K8s Operator 1.7+ 可用。
- 依赖：SP-01 技术中台提供 Kafka/Pulsar/可观测；业务中台 SP-03 提供领域表（订单/用户/课程）；AI 中台 SP-04 消费 Feature Store。

---

## 3. 功能需求

> 编号规则：FR-<域>-<序号>。域=RT(实时)/CDC(变更捕获)/LH(湖仓)/CS(冷热)/SV(服务)/QY(查询)/FS(特征)/OPS(运维)。

### 3.1 实时层（RT）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-RT-01 | 实时学习指标 | P0 | Flink SQL 聚合：每分钟在线学习人数、视频完播率、作业提交速率，落 Iceberg + 推 Redis 实时看板 |
| FR-RT-02 | 实时直播指标 | P0 | 直播房间在线人数、卡顿率、弹幕 QPS，CEP 检测异常断流告警 |
| FR-RT-03 | 实时交易指标 | P0 | 下单/支付/退款金额、转化漏斗，分钟级窗口 |
| FR-RT-04 | 实时风控指标 | P0 | CEP 规则：同 IP 多账号、秒级批量下单、异常登录，命中即推风控引擎 + 落 Iceberg |
| FR-RT-05 | 实时计费指标 | P1 | 课程消耗/退款流水，与订单对账，差异实时 flag |
| FR-RT-06 | 实时大屏 | P1 | 运营大屏：GMV/CCU/学习时长，秒级刷新（推 Redis Stream） |

### 3.2 CDC 总线（CDC）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-CDC-01 | TiCDC changefeed 管理 | P0 | 按表分组创建 changefeed，支持启停/过滤/路由，元数据登记到 catalog |
| FR-CDC-02 | Avro 编码 + Schema Registry | P0 | CDC 消息 Avro 编码，schema 注册并 EVOLVE 演化，禁 JSON |
| FR-CDC-03 | Flink CDC 3.x YAML pipeline | P0 | 分库分表合并（如 order_0000..order_1023 → ods_order），YAML 声明路由 |
| FR-CDC-04 | 跨单元 changefeed 聚合 | P1 | 多 cell TiDB changefeed 聚合到统一 ODS 表，标注 source_cell |
| FR-CDC-05 | Schema 演化通知 | P1 | 上游 DDL 变更经 Schema Registry 通知下游作业，自动 EVOLVE 或挂起人工 |

### 3.3 湖仓层（LH）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-LH-01 | Iceberg 表创建 | P0 | DDL 建表（分区/格式/属性），catalog=JDBC/Hive |
| FR-LH-02 | Flink 实时写入 | P0 | checkpoint=commit（5min/256MB/zstd），保留 flink-job-id 快照 |
| FR-LH-03 | 链式 compaction | P0 | SinkV2 后置：rewrite_data_files + expire_snapshots + remove_orphan_files + convertEquality |
| FR-LH-04 | Time-travel | P0 | 支持 `snapshot-id` / `as-of-timestamp` 查询，AI 训练数据可回溯 |
| FR-LH-05 | Schema evolution | P0 | ADD COLUMN/RENAME/EVOLVE 类型，不重写历史数据 |
| FR-LH-06 | ACID 保证 | P0 | 写入隔离，读不阻塞写，失败 checkpoint 不产生脏数据 |

### 3.4 冷热分层（CS）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-CS-01 | 热数据界定 | P0 | 热数据=近 90 天 + 活跃用户（30 天内有行为），留 TiDB/TiFlash |
| FR-CS-02 | 温冷归档 | P0 | 超期数据从 TiDB 归档到 Iceberg（partition by event_date） |
| FR-CS-03 | 冷数据回查 | P1 | 通过 Trino 查询 Iceberg 冷数据，SLA 秒~十秒级 |
| FR-CS-04 | 归档策略可配 | P1 | 按 BU/表分别配置保留期与归档目标 |

### 3.5 服务层（SV）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-SV-01 | 离线数仓 | P0 | Spark + Iceberg：留存/画像/复杂报表，T+1 调度 |
| FR-SV-02 | AI 训练数据导出 | P0 | Iceberg → Spark/Flink → 训练 pipeline，支持 time-travel 切片 |
| FR-SV-03 | 跨 BU 联邦 | P1 | Iceberg 统一落点，Trino 跨 BU 查询，行级权限 |
| FR-SV-04 | 数据血缘 | P1 | 表/字段级血缘，来源 TiDB → Kafka → Iceberg → 报表 |

### 3.6 查询层（QY）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-QY-01 | 在线 HTAP | P0 | TiDB/TiFlash 提供 OLTP+实时 OLAP，P95<500ms |
| FR-QY-02 | 湖仓交互查询 | P0 | Trino on Iceberg，ad-hoc/数据科学，P95<10s（TB 级扫描） |
| FR-QY-03 | 查询加速 | P1 | Trino 物化视图/Iceberg 元数据缓存 |

### 3.7 特征工程（FS）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-FS-01 | 特征定义 | P0 | 特征 DSL 定义一次，编译为 Spark 离线 + Flink 在线双作业 |
| FR-FS-02 | 在线 store | P0 | Redis/TiKV 亚 ms 查询，服务 AI 推理 |
| FR-FS-03 | 离线 store | P0 | Iceberg 存储历史特征，训练数据生成 |
| FR-FS-04 | 一致性校验 | P0 | Avro schema 防在线/离线 skew，定时对账 |
| FR-FS-05 | 特征版本化 | P1 | 特征 schema 版本 + 数据版本，回滚支持 |

### 3.8 运维（OPS）

| ID | 需求 | 优先级 | 描述 |
|----|------|--------|------|
| FR-OPS-01 | Flink 作业管理 | P0 | 部署/启停/savepoint/恢复，flink-job-id 快照保留 |
| FR-OPS-02 | 监控告警 | P0 | Prometheus Flink exporter：吞吐/延迟/checkpoint 失败率 |
| FR-OPS-03 | Compaction 调度 | P0 | 自动 + 手动触发，小文件数阈值告警 |
| FR-OPS-04 | Schema 演化流程 | P0 | DDL 提交→Schema Registry→下游作业适配→灰度 |
| FR-OPS-05 | 数据质量监控 | P1 | Avro schema 校验 + 行数/空值/枚举漂移监控 |

---

## 4. 非功能需求

| ID | 类别 | 指标 | 目标 | 测量方法 | 关联 SLO |
|----|------|------|------|---------|---------|
| NFR-01 | 延迟 | CDC 入湖端到端 | <5min（P95） | Flink checkpoint→commit 时间戳 - TiDB 提交时间戳 | G-09 |
| NFR-02 | 吞吐 | 学习事件 ingestion | ≥500,000 ev/s | Flink source throughput + Kafka lag | G-06 |
| NFR-03 | 延迟 | 实时指标看板刷新 | <30s | Redis Stream 消费延迟 | — |
| NFR-04 | 延迟 | 在线特征查询 | <5ms（P99） | Redis/TiKV 客户端计时 | — |
| NFR-05 | 可用性 | 数据中台核心链路 | 99.9% | Prometheus up + 故障注入 | G-07 |
| NFR-06 | 可恢复 | checkpoint 失败恢复 | <60s RTO | savepoint 恢复演练 | — |
| NFR-07 | 一致性 | 在线/离线特征 skew | <0.1% | 定时对账任务 | ADR-D03 |
| NFR-08 | 容量 | 历史数据存储 | 30亿用户全量 | Iceberg 表容量监控 | D-013 |
| NFR-09 | 成本 | 冷数据单位成本 | <热数据 1/10 | 对象存储 vs TiDB 单位成本 | ADR-D04 |
| NFR-10 | 合规 | 海外数据不回流 | 100% | 网络流量审计 | D-004 |
| NFR-11 | 可观测 | 全链路 trace 覆盖 | 100% 核心链路 | W3C traceparent 采样 | EP-SPEC §3 |
| NFR-12 | 演化 | Schema 演化零停机 | 是 | DDL 灰度发布演练 | ADR-D01 |

---

## 5. 用户故事

### 5.1 数据分析师
- US-DA-01：作为数据分析师，我希望通过 Trino 直接查询 Iceberg 湖仓表，无需提工单，以便 10 分钟内完成自助分析。
- US-DA-02：作为数据分析师，我希望 Schema 演化（ADD COLUMN）不阻塞我的查询，以便上游加字段时我的报表不中断。
- US-DA-03：作为数据分析师，我希望能 time-travel 查询某历史快照，以便复盘活动当天数据。

### 5.2 AI 工程师
- US-AI-01：作为 AI 工程师，我希望通过 Feature Store 一次性定义特征并自动生成离线训练 + 在线推理双作业，以便避免双份代码 skew。
- US-AI-02：作为 AI 工程师，我希望训练数据可按 snapshot-id 切片，以便模型复现实验。
- US-AI-03：作为 AI 工程师，我希望在线特征查询 P99<5ms，以便推荐推理不被特征拉慢。

### 5.3 运营
- US-OP-01：作为运营，我希望实时大屏秒级刷新 GMV/CCU/学习时长，以便活动期间实时决策。
- US-OP-02：作为运营，我希望自助配置实时看板指标，无需找开发，以便快速响应业务变化。
- US-OP-03：作为运营，我希望指标异常（如 GMV 突跌）自动告警，以便及时介入。

### 5.4 风控工程师
- US-RK-01：作为风控工程师，我希望 CEP 规则秒级命中异常行为并推风控引擎，以便拦截欺诈。
- US-RK-02：作为风控工程师，我希望实时计费与订单对账差异实时 flag，以便追漏算。
- US-RK-03：作为风控工程师，我希望风控特征在线可查（亚 ms），以便规则引擎实时取用。

### 5.5 数据平台工程师
- US-PE-01：作为平台工程师，我希望 checkpoint 失败后能从 savepoint 自动恢复，以便不丢数据。
- US-PE-02：作为平台工程师，我希望 compaction 自动调度且小文件数告警，以便避免读性能退化。
- US-PE-03：作为平台工程师，我希望 Schema 演化走灰度流程（DDL→Registry→下游适配→全量），以便不破坏消费方。

---

## 6. 验收标准

### 6.1 功能验收
| 验收项 | 标准 | 关联 |
|--------|------|------|
| 实时指标链路 | 学习/直播/交易/风控/计费 5 类指标 Flink job 全部运行，看板可见 | FR-RT-01..05 |
| CDC 入湖 | TiDB → TiCDC → Kafka → Flink → Iceberg 全链路打通，行数一致 | FR-CDC-01..03, ADR-D01 |
| Iceberg 表 | DDL 建表成功，Flink 实时写入，time-travel/schema evolution 验证通过 | FR-LH-01..06 |
| 冷热分层 | 超 90 天数据归档 Iceberg，TiDB 在线库容量稳定 | FR-CS-01..02, ADR-D04 |
| 离线数仓 | Spark 留存/画像报表 T+1 产出，数据准确 | FR-SV-01 |
| Trino 查询 | Trino on Iceberg ad-hoc 查询 P95<10s（TB 级） | FR-QY-02 |
| Feature Store | 特征定义→双作业生成→在线/离线一致（skew<0.1%） | FR-FS-01..04, ADR-D03 |

### 6.2 非功能验收
| 验收项 | 标准 | 关联 |
|--------|------|------|
| 入湖延迟 | CDC 入湖 P95<5min（压测环境实测） | NFR-01, G-09 |
| 吞吐 | 学习事件 ≥10,000 ev/s（M1），扩容验证 ≥500,000 ev/s（M3） | NFR-02, G-06 |
| 恢复 | checkpoint 失败后 60s 内从 savepoint 恢复，无数据丢失 | NFR-06 |
| 可观测 | 核心链路 100% trace 覆盖，Prometheus 指标可见 | NFR-11 |
| 合规 | 海外集群数据无回流国内（流量审计） | NFR-10 |

### 6.3 场景验收
- 场景 A：上游订单表 ADD COLUMN（`coupon_id`），Schema Registry 通知下游 Flink 作业自动 EVOLVE，查询不中断。
- 场景 B：Flink job 因 TaskManager OOM 失败，从 savepoint 恢复，无数据丢失，恢复时间<60s。
- 场景 C：90 天前学习记录从 TiDB 归档到 Iceberg，Trino 查询可关联冷热数据。
- 场景 D：AI 工程师按 snapshot-id 切片训练数据，模型实验可复现。

---

## 7. 需求追溯矩阵（摘要）

| 需求 | 来源 ADR/决策 | SAD 章节 | SDD 章节 | 测试章节 |
|------|--------------|---------|---------|---------|
| FR-CDC-01..03 | ADR-D01, D-016 | §3.2 CDC 总线 | §2 CDC pipeline | §2 集成测试 |
| FR-LH-02..03 | ADR-D02, D-017 | §3.3 湖仓层 | §3 Flink Job | §3 Iceberg 测试 |
| FR-FS-01..04 | ADR-D03 | §5 Feature Store | §4 特征定义 | §4 一致性测试 |
| FR-CS-01..02 | ADR-D04 | §4 冷热分层 | §5 归档作业 | §5 端到端 |
| NFR-01 (G-09) | EP-SPEC §1.2 | §3.3 | §3 checkpoint | §5 延迟测试 |

---

## 8. 风险与开放问题

| ID | 风险/问题 | 缓解 |
|----|----------|------|
| R-D01 | Iceberg+TiDB Cloud Lake 部分特性未 GA | 不依赖未 GA 特性，用成熟 SinkV2+对象存储（R-05） |
| R-D02 | Flink checkpoint 失败导致入湖延迟超 5min | 监控 checkpoint 失败率 + 自动 savepoint 恢复 |
| R-D03 | Schema 演化破坏下游消费方 | 灰度流程 + Pact 契约测试 |
| R-D04 | 30亿历史数据归档迁移期对在线库压力 | 分批迁移 + 限速 |
| R-D05 | Feature Store 在线/离线 skew | Avro schema 强制 + 定时对账（NFR-07） |
| O-D01 | 冷热分层具体保留期（90 天 vs 180 天）待各 BU 确认 | M2 评审定 |

---

**文档结束**。本文档与 SP-02-SAD/SDD/测试/部署文档双向追溯，变更需同步更新追溯矩阵。
