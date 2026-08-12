| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-01 | v2.0 | 2026-08-06 | 已批准 | 基础设施架构组 | 架构委员会 |

> 关联：D-052 决策；取代 v1.0（2026-08-05）；铁律 8（决策对比分析）；SP-04 AI 中台 RAG 子系统；SP-07 知识中台知识检索域；SP-02 智能特征平台；D-028 Pulsar 统一消息总线；D-041 多活架构；ADR-A02 FGAC 单点注入。

> **版本变更**：v1.0 选取 Milvus 2.4 LTS（对比 Qdrant / Weaviate）。v2.0 因 Milvus 3.0 GA（2026-07-29）与 TiDB Vector 候选浮现而重审，对比三方后调整为「Milvus 3.0 为主 + TiDB Vector 试点」的双轨策略。v1.0 被本版本取代。

# ADR-INF-01: 向量数据库选型 v2.0 — Milvus 3.0 vs Qdrant vs TiDB Vector

## 1. 问题陈述与约束条件

教育平台需支撑 **30 亿用户规模 / 100 万并发** 的语义检索与 RAG 召回，向量库承担以下职责：

- SP-04 AI 中台 `ai_rag`：Embedding 向量存储、相似度召回、多租户 FGAC 过滤。
- SP-07 知识中台：企业知识库语义检索（5 类用户群体，跨租户隔离）。
- ep-memory L2/L3：Mem0 长期记忆的向量检索后端。
- SP-02 智能特征平台：文本类特征的向量检索。

**自 v1.0 以来的变化**：

1. **Milvus 3.0 于 2026-07-29 GA**，带来重大架构升级：Woodpecker 独立 WAL（解耦消息存储，摆脱对 Pulsar/Kafka 的硬依赖）、lake-native 架构（对象存储为主存）、稀疏索引全面重构（SINDI + Block-Max WAND，大幅提升 BM25/稀疏检索性能与资源效率）。
2. **TiDB Vector 作为新候选浮现**：项目已采用 TiDB 作为关系型主库（D-041 多活架构基于 TiDB 多 PD 区域部署），TiDB Vector 复用同一栈即可获得向量检索能力，且天然具备多活能力；但其仍标记为 experimental，且不在 Mem0 官方支持 provider 列表中。
3. Weaviate 因国内生态弱、GraphQL 与 Dubbo 体系不匹配，在 v1.0 已被排除，本轮不再纳入对比；由 TiDB Vector 替补进入三方对比，满足铁律 8。

约束条件：

1. **多租户隔离**：必须支持租户级 collection / partition 隔离，禁止跨租户泄漏。
2. **规模天花板**：设计到 30 亿用户 × 千级租户 × 亿级文档 chunk，单集群需水平分片。
3. **多活合规（D-041）**：向量库需支持多区域 active-active 或可基于解耦 WAL 构建单元化多活。
4. **与 Pulsar 生态集成（D-028）**：向量库写入链路需经 Pulsar（或等价解耦 WAL）。
5. **GraalVM 兼容性**：Python 侧（SP-04）不涉及 AOT；Java 侧（SP-07）通过 gRPC/HTTP 客户端访问，不内嵌向量库，AOT 无障碍。
6. **FGAC 单点注入（ADR-A02）**：租户过滤在数据层单点注入，向量库必须支持过滤表达式。
7. **Mem0 集成**：ep-memory L2/L3 基于 Mem0，向量库需在 Mem0 支持列表内或可适配。
8. **铁律 8**：必须三方对比后才能定论。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Milvus 3.0**（由 2.4 升级） | 分布式 lake-native 向量库，Woodpecker 独立 WAL，SINDI 稀疏索引，10 亿级原生分布式 |
| B | **Qdrant 1.x** | Rust 实现，Apache 2.0，单机 QPS 高，Mem0 原生支持，Filter DSL 灵活 |
| C | **TiDB Vector** | 复用现有 TiDB 栈，原生多活（D-041），但仍 experimental，非 Mem0 官方支持 |

## 3. 对比维度表

| 维度 | Milvus 3.0（A） | Qdrant 1.x（B） | TiDB Vector（C） |
|------|------------------|------------------|--------------------|
| **成熟度 / GA 状态** | 生产级，3.0 GA 于 2026-07-29；2.x 国内大规模落地（小米/搜狐/知乎） | 2021 GA，海外中等规模落地多，国内少 | experimental（尚未 GA），生产案例稀缺 |
| **社区活跃度** | LF AI & Data 基金会毕业，CNCF 生态，1.5w★+ | 2w★+，海外活跃，Apache 2.0 | TiDB 生态（2w★+ on pingcap/tidb），Vector 子模块独立活跃度低 |
| **规模天花板（3B 用户 / 1M 并发）** | 原生分布式分片，DiskANN 磁盘索引，10 亿级向量，满足 30 亿用户设计天花板 | HNSW 内存索引为主，10 亿级靠分片，内存成本高 | 受限于 TiDB 集群规模，向量检索为大单行/列存扩展，10 亿级未验证 |
| **多区域 active-active（D-041 合规）** | 无 turnkey 多活；需基于 Woodpecker 解耦 WAL + 单元化部署构建 | 无原生多活，需自建复制 | 原生多活（TiDB 多 PD 区域 + D-041 已验证），向量数据随 TiDB 多活 |
| **多租户 / FGAC** | collection + partition_key 多级隔离；`expr="tenant_id == 'x'"` 过滤，与 ADR-A02 对齐 | collection 分租户 + payload filter，语法灵活 | 依赖 TiDB 行级权限 + WHERE 过滤，FGAC 成熟但非向量原生 |
| **Mem0 集成（ep-memory L2 关键）** | Mem0 官方支持 provider，开箱即用 | Mem0 官方支持 provider，开箱即用 | **不在 Mem0 官方支持列表**，需自研 adapter |
| **Pulsar 集成（D-028）** | 原生 Pulsar Source/Sink；3.0 Woodpecker WAL 可与 Pulsar 解耦并存，`Embed → Pulsar → Milvus` 已验证 | 需自研 Sink | 无 Pulsar 集成；写入走 TiDB SQL 链路 |
| **GraalVM AOT 兼容** | N/A（Python/Java 走 gRPC 客户端，不内嵌） | N/A（同） | N/A（JDBC/Go 驱动，不内嵌） |
| **运维复杂度（所需组件）** | etcd + MinIO/S3 + Woodpecker WAL + MixCoord/QueryNode/IndexNode，组件多但与项目栈重叠 | 单二进制，运维简单 | 复用现有 TiDB 集群，零新增组件（试点场景优势） |
| **稀疏检索 / 混合检索** | 3.0 SINDI + Block-Max WAND，BM25 稀疏检索性能大幅提升 | 支持稀疏向量，性能中等 | 支持有限，非重点特性 |
| **分层存储** | lake-native，对象存储为主存，热/温/冷分层 | 支持，以内存为主 | 随 TiDB 行存/列存，向量无独立分层 |
| **许可证** | Apache 2.0 | Apache 2.0 | Apache 2.0（TiDB） |

## 4. 结论

采用**双轨策略**：

1. **主选 — Milvus 3.0**（方案 A）：用于 SP-04 RAG 子系统、SP-07 知识检索域、ep-memory L2/L3。
   - 由 v1.0 的 2.4 LTS 升级至 3.0，享受 Woodpecker WAL、lake-native、SINDI 稀疏索引升级。
   - Mem0 官方支持，ep-memory L2/L3 无适配风险。
   - Pulsar 链路（D-028）保持不变，3.0 Woodpecker WAL 为后续解耦提供演进路径。

2. **试点 — TiDB Vector**（方案 C）：仅用于 SP-02 智能特征平台，范围受限。
   - 复用现有 TiDB 栈，零新增组件，且原生享 D-041 多活能力，适合特征平台的多区域写入场景。
   - 因仍 experimental 且非 Mem0 支持，**禁止用于关键路径**（SP-04/SP-07/ep-memory），试点周期内评估稳定性与性能，若 GA 后成熟可扩大范围。

3. **备份 — Qdrant**（方案 B）：作为 Milvus 3.0 迁移出现阻塞性问题时的备选。
   - Mem0 原生支持、Apache 2.0、单二进制运维简单，切换成本可控。
   - 文档化为应急方案，不立即部署。

## 5. 理由

1. **规模与分布式能力**：Milvus 3.0 是三方中唯一原生面向 10 亿级 + 分布式分片的向量库，DiskANN 磁盘索引满足 30 亿用户设计天花板；Qdrant 在 10 亿级主要靠内存 HNSW，成本与扩展性劣势；TiDB Vector 10 亿级未验证。
2. **多租户隔离与 FGAC**：Milvus `partition_key` + `expr` 过滤表达式天然契合 ADR-A02 的 tenant_id 单点注入，与 SP-04 已实现的 `FGACManager` 一致；TiDB Vector 行级权限成熟但非向量原生，Qdrant payload filter 灵活但缺少 partition_key 级物理隔离。
3. **生态重叠度**：Milvus 依赖 etcd/MinIO/Pulsar，与 SP-01 技术中台已选组件重叠，运维栈不增加新负担；TiDB Vector 复用 TiDB，在 SP-02 试点场景零新增组件；Qdrant 引入新组件。
4. **Pulsar 集成**：D-028 的 `Embed → Pulsar → Milvus` 链路已在 SP-04 RAG 子系统跑通，3.0 Woodpecker WAL 进一步解耦消息存储；Qdrant/TiDB Vector 需自研适配。
5. **Mem0 兼容性**：ep-memory L2/L3 依赖 Mem0，Milvus 与 Qdrant 均为官方支持 provider，开箱即用；TiDB Vector 不在支持列表，故仅用于不依赖 Mem0 的 SP-02 试点。

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| Milvus 3.0 较新（2026-07 GA），生产稳定性待验证 | 保持 2.5.x 客户端兼容；分阶段灰度（SP-04 先行，SP-07 跟进）；保留回滚至 2.4 LTS 能力 |
| TiDB Vector 仍 experimental，存在稳定性与特性变更风险 | 仅 SP-02 试点，禁止用于 SP-04/SP-07/ep-memory 关键路径；设定试点退出标准，GA 前不扩范围 |
| Milvus 无 turnkey 多区域 active-active | 基于 Woodpecker 解耦 WAL + 单元化部署构建跨区域多活；每个 cell 独立 Milvus 集群，WAL 异步复制 |
| Mem0 不支持 TiDB Vector | SP-02 试点不依赖 Mem0；若未来需接入 ep-memory，待 TiDB Vector GA 后自研 adapter 并贡献上游 |
| partition_key 数量上限（默认 1024） | 千级租户走 collection 隔离，万级租户走 partition_key |
| 3.0 稀疏索引重构带来回归风险 | SINDI + Block-Max WAND 在灰度环境做召回率回归基准测试后再切换生产 |

## 7. 铁律 8 合规说明

本 ADR 对比三方候选：Milvus 3.0（A）、Qdrant 1.x（B）、TiDB Vector（C），满足铁律 8「≥3 备选方案对比」要求。✓

---

**文档结束**。本 ADR（D-052）取代 v1.0，记录 Milvus 3.0 升级与 TiDB Vector 试点双轨决策，满足铁律 8 三方对比要求。
