# TOGAF 架构需求规格说明书 (Architecture Requirements Specification)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-ARS | v1.0 | 2026-08-12 | Approved | ZCode(架构治理) | D-157 / 00-顶层 |

> **文档定位**：TOGAF ADM Phase B/C/D 产物。将业务/数据/应用/技术需求结构化，映射 SLO G-01..G-10、Iron Law 1-9、架构原则 P-01..P-10,并提供需求追溯矩阵。是 06-Governance Log 与 07-Architecture Contract 的依据。

---

## §1 业务需求(功能)

| 需求 ID | 业务能力 | 描述 | 关联中台/BU | 映射 ABB |
|---------|---------|------|-----------|---------|
| FR-01 | 课程管理 | 课程/章节/目录 CRUD + 上下架 + 缓存读 | 业务中台(CMP) | ABB-04 |
| FR-02 | 用户管理 | 注册/登录/凭证/画像 + 强单元化 | 业务中台(UCM) | ABB-05 |
| FR-03 | 订单支付 | 下单/支付/回调/对账 + Saga 补偿 | 业务中台(OPM) | ABB-06/07/17 |
| FR-04 | 作业评分 | 提交/Judge 评测/LLM rubric/human-in-the-loop | IT 后端 | ABB-08 |
| FR-05 | 知识库 RAG | 文档上传/分块/向量索引/检索/重排 | 知识域 | ABB-11/12/27 |
| FR-06 | Agent 对话 | LangGraph 多步 Agent + 工具 + 多级记忆 | AI 域 | ABB-13 |
| FR-07 | 直播互动 | 推流/转码/录制 + WS 弹幕/连麦 + 房间单 cell | IT 后端 | ABB-02 |
| FR-08 | 棋牌对弈 | 房间/匹配/棋谱回放 + WS | BU 域 | ABB-10 |
| FR-09 | 康养管理 | 康养课程/健康打卡/适老化路径 | BU 域 | ABB-09 |
| FR-10 | 多端接入 | web/admin/h5 三端共 API 契约 | 应用域 | ABB-28/01 |

## §2 非功能需求(映射 SLO G-01..G-10)

| 需求 ID | SLO | 指标 | 天花板(M3) | MVP(M1) | 映射架构决策 | 验证方式 |
|---------|-----|------|-----------|---------|------------|---------|
| NFR-01 | G-01 | 峰值并发用户 | 1,000,000 CCU | 10,000 CCU | D-012/D-014 单元化+流量纠正 | k6 分布式压测 |
| NFR-02 | G-02 | 注册用户容量 | 3,000,000,000 | 1,000,000 | D-008/D-015 单元化+分表/D-013 Iceberg | TiDB 容量规划+压测反推 |
| NFR-03 | G-03 | 核心 API P95 | <200ms | <200ms | ADR-T02 读写分离/ADR-T04 缓存 | Prometheus histogram |
| NFR-04 | G-04 | 核心 API P99 | <500ms | <500ms | ADR-T02/ADR-T04 | Prometheus histogram |
| NFR-05 | G-05 | 直播/互动延迟 | <800ms P95 | <800ms P95 | D-021 两层混合 | WS 端到端压测 |
| NFR-06 | G-06 | 学习事件 ingestion | ≥500,000 ev/s | ≥10,000 ev/s | ADR-T03 Disruptor+Pulsar | Disruptor 基准 |
| NFR-07 | G-07 | 可用性 | 99.99% | 99.9% | D-014 跨区 Raft/ADR-T05 探针 | PromQL up + 故障注入 |
| NFR-08 | G-08 | 单元故障切换 | <30s | <60s | D-014 AppActive 模式 | 单元切换演练 |
| NFR-09 | G-09 | 数据中台实时延迟 | <5min(Iceberg) | <5min | D-016/17 TiCDC→Pulsar→Flink→Iceberg | Flink checkpoint 监控 |
| NFR-10 | G-10 | AI 推理 P95 | <3s | <3s | D-018 网关+缓存+路由 | AI 网关 trace + mock 压测 |

## §3 数据需求

| 需求 ID | 需求 | 映射决策 | 映射 ABB/SBB |
|---------|------|---------|------------|
| DR-01 | TiDB HTAP(在线 Primary/Follower + TiFlash 列存) | ADR-T-DB | ABB-25 / SBB-04 |
| DR-02 | 冷热分层(热 TiDB / 温 TiFlash / 冷 Iceberg / 归档 S3 Glacier) | ADR-D04 / P-04 | ABB-25/16 / SBB-30 |
| DR-03 | CDC 链路 TiCDC→Pulsar(ns=cdc://)→Flink→Iceberg,禁 Kafka | ADR-D01/D-016 | ABB-24/16 / SBB-06/29 |
| DR-04 | Milvus 向量库 + namespace+FGAC 多租户隔离 | ADR-A02/D-019 | ABB-27 / SBB-07 |
| DR-05 | Redis Cluster L2 缓存 + 会话 + CRDT 多活 + key 规范 | 00-顶层§4.2/§15 | ABB-26 / SBB-05 |
| DR-06 | 大表强制 RANGE 分区(防 session_turns 82M OOM) | ADR-T10 | ABB-25 |
| DR-07 | AUTO_RANDOM(5) + PRE_SPLIT_REGIONS(Snowflake 等价) | D-015 | ABB-25/19 |
| DR-08 | Avro Schema Registry(EVOLVE 模式)for CDC | 00-顶层§5.4 | ABB-24 |

## §4 应用需求

| 需求 ID | 需求 | 映射决策 | 映射 ABB |
|---------|------|---------|---------|
| AR-01 | DDD 充血模型,聚合根+实体+值对象,domain 不依赖 web/infra | 00-顶层§5/P-05 | 全部业务服务 |
| AR-02 | Saga 状态机(幂等键+at-least-once+补偿),不引入 Seata AT(用 Seata Saga, D-113) | ADR-T06/D-113 | ABB-17 |
| AR-03 | BFF 代理(Netty edge+Disruptor+WebMVC),禁 SCG | ADR-T07/D-032 | ABB-01 |
| AR-04 | 多租户隔离(JWT FGAC,禁 app 拼 tenant_id) | P-03/ADR-A02 | ABB-20/14 |
| AR-05 | 跨单元不直接 RPC,走 Pulsar 异步 | P-02/ADR-T03 | ABB-24 |
| AR-06 | 契约先行(OpenAPI/IDL)+ Pact + 2 minor 向后兼容 | P-06/00-顶层§5.4 | 全部 |
| AR-07 | LLM 评分 human-in-the-loop,高风险教师定 | P-08/D-020/ADR-I05 | ABB-08 |
| AR-08 | Agent 多级记忆 L0~L3 + 状态外化 | D-045/ADR-A05/A06 | ABB-13 |

## §5 技术需求

| 需求 ID | 需求 | 映射决策 | 映射 SBB |
|---------|------|---------|---------|
| TR-01 | 虚拟线程:`spring.threads.virtual.enabled=true` + synchronized→ReentrantLock | D-039 | SBB-01 |
| TR-02 | GraalVM Native Image 双模式(JIT 调优 + Native 生产) + native-maven-plugin 0.10.4 | D-040 | SBB-01/27 |
| TR-03 | K8s 部署(HPA/PDB/NetworkPolicy deny-by-default/探针三分离) | ADR-T05/D-154 | SBB-10 |
| TR-04 | GitOps(ArgoCD 声明式)+ JIB 无 Dockerfile 构建 | D-114/D-115 | SBB-11/12 |
| TR-05 | OTel 全链路(W3C traceparent + OTLP + tail-sampling) | D-118 | SBB-25 |
| TR-06 | 多集群多活 Karmada(4-cell: hz/sz/bj/sg) | D-041 | SBB-28 |
| TR-07 | Dubbo Triple(HTTP/2+Protobuf)契约先行,禁 STRICT | ADR-T09 | SBB-03 |
| TR-08 | Sentinel 规则走 Nacos 分布式下发,禁本地内存 | ADR-T08 | SBB-08/09 |
| TR-09 | Pulsar 3.3.x 锁定,CDC + 事件统一总线,4.x 延至 v2.0 | ADR-T11/ADR-D01 | SBB-06 |

## §6 Iron Law 1-9 合规需求

| Iron Law | 需求 | 映射决策 | 关联标准 | 验证方式 |
|---------|------|---------|---------|---------|
| 铁律1 | 新 BU 接入 = 调中台,不自建共享能力 | P-01 | SIB-C-05 | ArchUnit + 架构审查 |
| 铁律2 | 跨单元不直接 RPC,走 Pulsar 异步 | P-02/ADR-T03 | SIB-A-* | 代码扫描 + 网络策略 |
| 铁律3 | 禁 app 拼 tenant_id,用 JWT FGAC | P-03/ADR-A02 | SIB-S-08 | SAST 规则 |
| 铁律4 | 在线 TiDB 只留热数据,冷归 Iceberg | P-04/ADR-D04 | SIB-DB-03 | 容量监控 |
| 铁律5 | 公共库 ≥80% 覆盖,安全核心 100% | P-05 | SIB-T-01 | JaCoCo 硬门 |
| 铁律6 | 禁 Spring Cloud Netflix/SCG 原生组件 | D-032 | 03-SBB §3 禁用清单 | 依赖扫描 |
| 铁律7 | 多级缓存(Caffeine L1 + Redis L2)统一,禁重复造轮子 | ADR-T04/D-033 | SIB-DB-02 | ArchUnit + 审查 |
| 铁律8 | 决策需三方对比选型(不重复造轮子) | 00-顶层 §16/§17 | — | ADR 审查 |
| 铁律9 | 上下文 80% 主动压缩,状态外化 Redis | P-10/D-006 | — | 代码审查 |

## §7 需求追溯矩阵

| 需求 ID | 需求描述 | 关联 SP | 关联 ADR | 验证方式 | 状态 |
|---------|---------|--------|---------|---------|------|
| FR-01 | 课程管理 | SP-03/05 | ADR-B01 | E2E + 契约测试 | M1 已实现 |
| FR-02 | 用户管理 | SP-03 | ADR-T01/D-012 | E2E + 单元 | M1 已实现 |
| FR-03 | 订单支付 + Saga | SP-03 | ADR-T06/D-113 | SagaE2eIntegrationTest(D-152) | M1 已实现 |
| FR-04 | 作业评分 + human-in-loop | SP-05/04 | ADR-I03/I04/I05/D-020 | 单元+集成 | M1 已实现 |
| FR-05 | 知识库 RAG | SP-04 | ADR-A02/D-019 | 集成 + Milvus | M1 已实现 |
| FR-06 | Agent 对话 + 多级记忆 | SP-04 | ADR-A04/D-045/A05/A06 | 单元 + E2E | M1 已实现 |
| FR-07 | 直播互动 | SP-05 | ADR-I01/D-021 | WS 压测 | M1 已实现 |
| FR-08 | 棋牌对弈 | SP-10 | — | 单元 + E2E | M1 已实现 |
| FR-09 | 康养管理 | SP-10 | — | 单元 | M1 已实现 |
| FR-10 | 多端接入 | SP-06 | P-06 | Pact + E2E | M1 已实现 |
| NFR-01..10 | SLO G-01..G-10 | SP-01/07/09 | 见 §2 | 见 §2 | M1 部分(1万 CCU)/M3 目标 |
| DR-01..08 | 数据架构 | SP-02/07 | 见 §3 | 见 §3 | M1 部分已实现 |
| AR-01..08 | 应用架构 | 各 SP | 见 §4 | ArchUnit + 契约 | M1 已实现 |
| TR-01..09 | 技术架构 | SP-01/07 | 见 §5 | CI 门禁 | M1 已实现 |
| 铁律1..9 | Iron Law | 全部 | 见 §6 | 见 §6 | 持续合规(见 06-Governance) |

---

**关联文档**：00-顶层§1.2/§5/§6/§7 / 02-ABB / 04-SIB / 06-Governance Log
