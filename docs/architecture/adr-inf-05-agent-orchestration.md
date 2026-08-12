| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-05 | v1.0 | 2026-08-05 | 已批准 | AI 架构组 | 架构委员会 |

# ADR-INF-05: Agent 编排框架选型 — LangGraph vs AutoGen vs CrewAI

> 关联：SP-04 AI 中台（D-046 Agent 架构重构）、D-048 审计 P2-1、铁律 8；与 ADR-A09（分层混合多智能体图）。

## 1. 问题陈述与约束条件

SP-04 AI 中台的 Agent 编排层负责：

- **分层多智能体图**（D-046 / ADR-A09）：Supervisor + 6 子图（qa/grading/quiz/learnpath/digital_human/debate）。
- **Reflexion 自反思循环**（ADR-A10）：evaluate → reflect → retry。
- **HITL 人工审核**：`interrupt_before` 节点暂停等待人工。
- **持久化 checkpointer**：会话状态恢复。
- **多租户**：租户级 Agent 实例隔离。
- **与 Mem0 集成**：长期记忆（D-045 工作包 5）。

约束条件：

1. **图结构编排**：需显式表达 Supervisor → Subgraph 拓扑。
2. **Reflexion 支持**：需支持条件边/循环。
3. **持久化 checkpointer**：会话级状态恢复。
4. **多租户**：thread_id 维度隔离。
5. **与 Mem0 集成**：Python 侧，需可注入 Mem0 client。
6. **铁律 8**：三方对比后定论。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **LangGraph 1.0**（已选，SP-04） | 显式图结构，条件边/循环，checkpointer，interrupt_before |
| B | **AutoGen 0.4**（微软） | 多 Agent 对话（GroupChat），事件驱动，Agent 可对话 |
| C | **CrewAI 0.x** | 角色扮演（Crew + Agent + Task），声明式简洁 |

## 3. 对比维度表

| 维度 | LangGraph 1.0（A） | AutoGen 0.4（B） | CrewAI 0.x（C） |
|------|----------------------|------------------|------------------|
| **成熟度** | 2024 GA，1.0 稳定，LangChain 系 | 2023 GA，0.4 重构（actor 模型），生产案例中 | 2024 起步，社区增长快，生产案例少 |
| **社区活跃度** | LangChain 9w★+ 生态，LangGraph 独立 1w★+ | 4w★+，微软背书 | 2w★+，增长快 |
| **编排模型** | 显式状态图（StateGraph），节点 + 条件边 + 循环 | GroupChat + 对话消息流，隐式编排 | Crew → Task → Agent 角色链，声明式 |
| **图结构表达** | 原生节点/边拓扑，Supervisor+Subgraph 天然适配 | 隐式对话流，显式图结构弱 | 无显式图，靠 Task 顺序 |
| **Reflexion 支持** | 条件边 + 循环原生，`recursion_limit` 控制 | 需自实现对话循环 | 需自实现 |
| **持久化 checkpointer** | `MemorySaver`/`SqliteSaver`/`PostgresSaver` 原生 | 无原生 checkpointer，需自实现 | 无原生，需自实现 |
| **HITL interrupt_before** | 1.0 原生支持 `interrupt_before` | 需自实现 | 需自实现 |
| **多租户隔离** | `thread_id` 维度隔离，每个会话独立图实例 | GroupChat 实例隔离 | Crew 实例隔离 |
| **与 Mem0 集成** | Python 原生，可在节点注入 Mem0 client | Python 原生 | Python 原生 |
| **与 MCP/A2A 集成** | 工具节点可包装 MCP client（D-046 已实现） | Agent 可调 MCP，需封装 | Task 可调 MCP，需封装 |
| **可观测性** | LangSmith 原生追踪 | 无原生，需 OTel | 无原生，需 OTel |
| **状态管理** | 显式 State（TypedDict），可控 | 隐式消息历史 | 隐式任务状态 |
| **调试** | 图可视化 + 节点级 trace | 对话 trace | 任务 trace |

## 4. 结论与理由

**采纳方案 A：LangGraph 1.0**（与 SP-04 现状一致，记录对比理由）。

理由：

1. **显式图结构与分层拓扑**：ADR-A09 的 Supervisor + 6 子图分层混合架构需显式节点/边表达，LangGraph 的 `StateGraph` 天然适配；AutoGen 的 GroupChat 是隐式对话流，分层拓扑需绕弯；CrewAI 无显式图。
2. **Reflexion 原生支持**：ADR-A10 的 evaluate → reflect → retry 循环用条件边 + `recursion_limit` 直接实现，D-046 已落地 `ai_agent/reflexion.py`；AutoGen/CrewAI 需自实现循环逻辑（铁律 8 反对造轮子）。
3. **checkpointer 原生**：`MemorySaver`/`PostgresSaver` 满足会话状态恢复；AutoGen/CrewAI 无原生方案，会话级持久化需自研。
4. **HITL interrupt_before**：1.0 原生支持，D-046 已规划人工审核节点；AutoGen/CrewAI 需自实现暂停/恢复。
5. **LangSmith 可观测性**：Agent 链路追踪原生，与 SP-04 的 structlog/OTel 互补。
6. **与 MCP/A2A 已集成**：D-046 的工具节点已包装 MCP client，LangGraph 工具节点 API 稳定。

**取舍**：放弃 AutoGen 的多 Agent 对话简洁性与 CrewAI 的声明式简洁，换取显式图结构 + 持久化 + HITL 的工程可控性。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| LangGraph 1.0 API 仍有演进 | 锁定 1.0，升级走 ADR 评审；隔离 `ai_agent/graphs/` 内的 LangGraph 直接依赖 |
| 复杂图调试难 | LangSmith + 节点级 trace + 图可视化 |
| 多租户 thread_id 隔离需严格 | checkpointer 按 `tenant_id:session_id` 复合 thread_id |

---

**文档结束**。本 ADR 补齐 D-048 审计 P2-1 的 Agent 编排框架选型对比缺失，记录 LangGraph 已选理由，满足铁律 8 三方对比要求。
