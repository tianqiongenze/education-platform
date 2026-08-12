| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-A06 | v1.0 | 2026-08-05 | 已批准 | AI 中台架构组 | 架构委员会 |

# ADR-A06: Mem0 框架集成 — L2 长期记忆提取与召回

> 关联：D-045 多级智能记忆系统；ADR-A05 四级记忆架构；铁律 8（决策对比分析）；D-048 审计 P0-5（ep_memory 手写 stub 重构）。

## 1. 问题陈述与约束条件

ADR-A05 确立 L0~L3 四级记忆架构，其中 L2 长期记忆需要从对话中**提取**事实/偏好/实体关系、**去重**与**冲突消解**、并支持向量/结构化/图谱三路**召回**。这部分能力是否自研、采用何种开源框架，是本 ADR 的决策对象。

约束条件：

1. **Python + Java 双 SDK**：SP-04 为 Python，SP-07/SP-03 等业务侧为 Java，记忆 API 需双语言可用。
2. **后端可定制**：必须支持 Milvus（向量）+ TiDB（关系）+ Neo4j（图谱）三后端，不能锁定单一存储。
3. **3 亿用户规模**：框架核心逻辑需可水平扩展，不能有单点瓶颈。
4. **铁律 8**：必须三方对比后定论。
5. **D-040 GraalVM**：Java 侧通过 gRPC/HTTP 调用 Python 侧 Mem0 服务，不内嵌，AOT 无障碍。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Mem0 开源框架为核心 + 自研 L0/L1/L3**（已选） | Mem0 提供 L2 提取/去重/冲突消解 + 多后端适配，L0/L1/L3 场景特化层自研 |
| B | **LangMem (LangChain 记忆)** | LangChain 生态的记忆模块，与 LangGraph 深度集成 |
| C | **全自研 L2 记忆系统** | 提取/去重/冲突消解/召回全部自研，不依赖任何框架 |

## 3. 对比维度表

| 维度 | Mem0（A） | LangMem（B） | 全自研（C） |
|------|-----------|--------------|-------------|
| **成熟度** | 2024 GA，GitHub 20k★+，生产落地多 | LangChain 子模块，随主版本演进 | 无 |
| **后端支持** | Milvus/Qdrant/Pinecone/PG/Neo4j 等可插拔，与项目栈对齐 | 主要 PG/Redis，Milvus 支持弱 | 自行对接全部后端 |
| **Java SDK** | 无官方 Java SDK，需自研 Java 客户端调 Python gRPC | 无 Java SDK | 需自研双语言 SDK |
| **提取/去重/冲突消解** | 内置 LLM 提取 + 向量去重 + 冲突消解 pipeline | 仅基础对话窗口记忆，无提取 pipeline | 需从零实现 |
| **与 LangGraph 集成** | 框架无关，可作为工具节点注入 | 原生集成 | 自行集成 |
| **社区支持** | 活跃，版本迭代快 | 依赖 LangChain 社区 | 无 |
| **3 亿用户扩展性** | 无状态服务，可水平扩展；后端 Milvus/TiDB/Neo4j 各自分布式 | 单进程为主，扩展性弱 | 与 A 等价 |
| **开发成本** | 中（自研 Java SDK + L0/L1/L3） | 低但能力残缺 | 最高（重复造轮子，违反铁律 8） |
| **可替换性** | Mem0 仅负责 L2，可替换；L0/L1/L3 自研不受影响 | 与 LangChain 强耦合 | — |

## 4. 结论与理由

**采纳方案 A：Mem0 开源框架为核心 + 自研 L0/L1/L3**。

理由：

1. **后端与项目栈完全对齐**：Mem0 支持 Milvus + Neo4j + 关系库（TiDB 走 PG 兼容层适配），与 ADR-INF-01/02 及 TiDB 选型一致，无新增组件。
2. **L2 核心能力开箱即用**：提取（LLM 抽取事实/偏好）、去重（向量相似度 + 阈值）、冲突消解（新旧事实合并）pipeline 已成熟，自研属重复造轮子（铁律 8）。
3. **框架无关可替换**：Mem0 仅负责 L2，通过 gRPC 暴露服务；若未来社区版能力不足，可替换为自研 pipeline 而不影响 L0/L1/L3。
4. **Java SDK 缺口可控**：虽无官方 Java SDK，但 Java 侧通过 gRPC 调用 Python 侧 Mem0 服务即可，与 D-047 MCP Java SDK 通过 Streamable HTTP 调用 Python 侧的模式一致，不违反 D-040。
5. **LangMem 能力残缺**：仅基础对话窗口记忆，无 L2 提取 pipeline，不满足 L2 长期记忆需求。
6. **全自研违反铁律 8**：L2 提取/去重/冲突消解是成熟问题，自研无社区支持且开发量大。

## 5. 集成方式

### 5.1 Python 侧（SP-04 ep-memory 服务）

```
┌──────────────────────────────────────────────────┐
│           ep-memory Python Service               │
│  ┌────────────────────────────────────────────┐  │
│  │  MemoryManager (自研编排层)                │  │
│  │  recall() / remember() / forget()          │  │
│  └────────────┬───────────────────────────────┘  │
│               │                                  │
│   ┌───────────┼───────────┬────────────┐        │
│   ▼           ▼           ▼            ▼        │
│ L0 Redis   L1 Redis   L2 Mem0       L3 自研     │
│ (自研)     (自研)     (框架)        (治理)      │
│                        │                        │
│               ┌────────┼────────┐               │
│               ▼        ▼        ▼               │
│           Milvus    TiDB     Neo4j              │
│           (情景)   (语义)   (程序)              │
└──────────────────────────────────────────────────┘
```

- Mem0 配置文件 `mem0_config.yaml` 指定 Milvus collection / TiDB DSN / Neo4j URI。
- `MemoryManager` 编排四级召回：L0 → L1 → L2（Mem0 三路 RRF 融合）→ L3（授权后）。
- gRPC 端口 `:50057` 暴露 `Remember`/`Recall`/`Forget` RPC 供 Java 侧调用。

### 5.2 Java 侧（ep-memory-java SDK）

- gRPC stub 调用 Python 侧 `ep-memory` 服务，不内嵌 Mem0。
- `MemoryManagerClient` 封装为 Dubbo 服务（D-046 sp07 `AgentSession` 激活点，见 D-045 工作包 5）。
- Java SDK 不感知 Mem0 存在，仅依赖 gRPC 契约，符合 D-040 GraalVM AOT。

### 5.3 Mem0 配置要点

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `vector_store.provider` | `milvus` | 情景记忆向量存储 |
| `vector_store.collection` | `mem_episodic` | dimension=1536 |
| `graph_store.provider` | `neo4j` | 程序记忆图谱 |
| `graph_store.graph_name` | `mem_procedural` | — |
| `kv_store.provider` | `tidb` | 语义记忆结构化事实 |
| `embedder.provider` | `openai` | 与 SP-04 RAG 一致 |
| `llm.provider` | 项目 LLM 网关 | 走 ai_gateway，不直连外部 |

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| Mem0 社区版对 3 亿用户的扩展性未验证 | L2 无状态服务可水平扩展；后端 Milvus/TiDB/Neo4j 各自分布式；压测验证（SP-09） |
| 无官方 Java SDK | 自研 gRPC 客户端，契约驱动，与 D-047 模式一致 |
| Mem0 版本迭代快可能引入破坏性变更 | 锁定 minor 版本；升级走 D-047 式变更评审 |
| LLM 提取质量不稳定 | 提取结果走 Reflexion 评估（D-046），阈值 0.7；失败兜底不写入 L2 |
| Mem0 与 TiDB 适配（PG 兼容层）风险 | PoC 验证 TiDB PG 兼容层覆盖 Mem0 所用 SQL 子集；不行则自研 TiDB adapter |

## 7. 与 ADR-A05 的关系

- ADR-A05 确立 L0~L3 四级架构与遗忘机制（架构层）。
- 本 ADR 确立 L2 采用 Mem0 框架实现（技术选型层），是 ADR-A05 的细化。
- L0/L1/L3 由自研实现，不在本 ADR 范围内。

---

**文档结束**。本 ADR 满足铁律 8 三方对比要求，解决 D-048 审计 P0-5（ep_memory 手写 stub 接入 Mem0）。
