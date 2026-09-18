# SP-04 AI中台 软件架构设计(SAD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp04` for reference continuity. Per D-137, sp04 → ai-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-SAD | v1.0 | 2026-08-03 | 评审通过 | AI中台架构组 | 架构委员会 |

> 关联：顶层 spec §6；决策 D-010 / D-018 / D-019 / D-020；ADR-A01..A04

## 1. 架构目标与原则
### 1.1 目标
- 全栈 AI 能力：模型网关 / RAG / 5 Agent / 数据底座一站式（D-010）。
- 成本最优：成本栈 cache→route→cascade→fallback，单调用成本降 ≥45%（ADR-A01）。
- 多租户零泄漏：namespace + FGAC，禁 app 拼 tenant_id（ADR-A02）。
- 编排可恢复：LangGraph + Redis checkpointer，中断可 resume（ADR-A04）。
- 可离线研发：LLM_MODE=mock 全链路桩（D-018）。

### 1.2 原则
1. **能力下沉，Agent 上浮**：通用能力（路由/缓存/检索/隔离）沉到网关与 RAG 层，Agent 只编业务逻辑。
2. **FGAC 强制单点**：tenant_id 仅由网关从 JWT 解析注入，应用层不可见。
3. **成本栈顺序不可逆**：cache 命中即返回，不进 route；route 决定模型后进 cascade；cascade 低置信才 fallback。
4. **状态外化**：LangGraph 状态走 Redis，进程无状态。
5. **复用优先**：Milvus / 图库 / Redis Cluster 复用集群，不另起栈。

## 2. TOGAF 四域对齐

| TOGAF 域 | AI中台对应 | 关键能力 |
|----------|-----------|----------|
| 业务架构 | 5 Agent + 事业群接入 | 批改/出题/答疑/学习路径/数字人 |
| 数据架构 | RAG + Feature Store + Iceberg | 混合检索 / 特征订阅 / 训练湖 |
| 应用架构 | 模型网关 + Agent 编排 + 管理控制台 | 成本栈 / LangGraph / 控制台 |
| 技术架构 | K8s + Kong AI + Milvus + Redis + GPU 池 | 部署 / 限流 / 向量 / 缓存 / 推理 |

## 3. 4 层架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│ ① AI应用层(Agent, LangGraph 多步)                                    │
│  ┌────────┬────────┬────────┬──────────┬─────────┐                 │
│  │批改Agent│出题Agent│答疑Agent│学习路径Agent│数字人   │                 │
│  │HITL(A03)│IRT校准 │RAG引用 │图谱+特征  │TTS+口型 │                 │
│  └────────┴────────┴────────┴──────────┴─────────┘                 │
│  编排: LangGraph(interrupt_before + Redis checkpointer + resume)     │
│  轻量状态机: 固定流程(去重/校准)避免跨语言RPC                          │
├─────────────────────────────────────────────────────────────────────┤
│ ② 模型网关(Kong AI 主 / LiteLLM 起步)                                │
│  成本栈(顺序不可逆, ADR-A01):                                        │
│   cache(LangCache) → intent route(RouteLLM) →                       │
│   confidence cascade → multi-provider fallback                      │
│  + 限流(tenant×agent×model) + 计费(token→Iceberg) + LLM_MODE=mock   │
├─────────────────────────────────────────────────────────────────────┤
│ ③ RAG层                                                              │
│  混合检索: BM25 + 向量(Milvus/pgvector) + metadata → RRF 融合        │
│  → Reranker(cross-encoder) → top-k                                   │
│  多租户: namespace + pool + JWT-FGAC(D-019), 禁 app 拼 tenant_id     │
│  Milvus=知识库层(共享) | pgvector=租户小集合                          │
├─────────────────────────────────────────────────────────────────────┤
│ ④ 数据底座                                                           │
│  Feature Store(数据中台喂) | Iceberg(训练数据) | 向量库 | 图库         │
└─────────────────────────────────────────────────────────────────────┘
        ▲ FGAC 单点: JWT→tenant_id 由网关注入, 下游全链路透传
        ▲ 状态外化: Redis Cluster(zcode:aigraph:*)
```

## 4. Agent 编排架构（LangGraph）

```
                 ┌───────────────────────────────────┐
                 │  Agent Orchestrator (LangGraph)   │
                 │  checkpointer = RedisClusterSaver │
                 └───────────────┬───────────────────┘
                                 │ thread_id = (tenant,user,session)
   ┌─────────────────────────────┼─────────────────────────────┐
   ▼                             ▼                             ▼
批改Agent                      答疑Agent                    学习路径Agent
 graph:                         graph:                       graph:
  ingest→rubric→llm             retrieve→gen→guard→emit      profile→graph→plan→review?
  →risk?                        (固定流, 轻量状态机)           (interrupt_before review)
  →[low:emit]                                                  │
  →[high: interrupt_before(teacher_review)]                   │
  →Command(resume) ← teacher                                   │
```

**关键机制（ADR-A04）**：
- `interrupt_before(["teacher_review"])` 暂停图执行，状态落 Redis。
- 教师复核动作触发 `Command(resume=...)`，图从断点恢复。
- 进程重启后 `graph.get_state(thread_id)` 重载，无状态丢失。
- 固定流程（答疑、去重、校准）用自研轻量状态机，避免 LangGraph 跨语言 RPC 开销。

## 5. 模型网关架构（成本栈）

```
                   ┌───────────────────┐
   Agent 请求 ───► │  Gateway Ingress  │ ── JWT 解析 → tenant_id 注入
                   └────────┬──────────┘
                            ▼
              ┌─────────────────────────┐  命中?  ┌──────────┐
              │ ① 语义缓存 LangCache    │ ───Y──► │ 直接返回 │
              │   key = hash(sembed+tenant+agent)│ └──────────┘
              └────────┬────────────────┘
                       │ N
                       ▼
              ┌─────────────────────────┐
              │ ② 意图路由 RouteLLM      │  简单→小模型, 复杂→旗舰
              │   classifier + 规则      │  成本降 45-85%
              └────────┬────────────────┘
                       ▼
              ┌─────────────────────────┐
              │ ③ 置信度级联 cascade     │  小模型置信<τ → 升级旗舰
              └────────┬────────────────┘
                       ▼
              ┌─────────────────────────┐
              │ ④ 多 provider fallback  │  A故障→B→C, RTO≤3s
              └────────┬────────────────┘
                       ▼
              ┌─────────────────────────┐
              │ 计费 + 限流 + 写缓存      │
              └─────────────────────────┘
```

**成本栈不变量（ADR-A01）**：
- 顺序固定，禁止配置化重排，避免误调导致成本爆炸。
- 每阶段独立可观测（命中率/路由分布/级联率/fallback率）。
- LangCache 命中 60-73%；RouteLLM 路由降本 45-85%（调研基线）。

## 6. RAG 多租户隔离架构（ADR-A02）

```
┌─────────────────────────────────────────────────────────────┐
│  Gateway 注入 tenant_id (来自 JWT, 不可被 app 覆盖)          │
│        │                                                      │
│        ▼                                                      │
│  RAG Router: tenant 路由                                      │
│   ├─ 大租户/知识库层 → Milvus namespace = tenant_id           │
│   └─ 小租户集合   → pgvector pool (tenant_id 隔离 schema)     │
│                                                              │
│  FGAC: 每条查询强制带 tenant_id filter, 网关侧注入            │
│        app 层 tenant_id 字段为只读, 静态扫描禁止拼接           │
└─────────────────────────────────────────────────────────────┘
```

**隔离不变量**：
- 共享 Milvus index，按 namespace 物理隔离；pgvector 按 schema 池化。
- 跨 namespace 查询在 SDK 层被拒（无 tenant_id 即拒）。
- 渗透测试：持 A 的 JWT 查 B，0 返回。

## 7. 关键 ADR

### ADR-A01 模型网关分层 + 成本栈
- **决策**：成本栈顺序 cache→intent route→confidence cascade→multi-provider fallback，顺序不可逆。
- **理由**：语义缓存 60-73% 命中直接省掉后续；RouteLLM 类路由再降 45-85%；cascade 仅在低置信升级；fallback 兜底可用性。
- **影响**：网关为核心成本中心，需独立 SLO 与看板；路由表热更新。

### ADR-A02 RAG 多租户隔离 = namespace + FGAC
- **决策**：共享 index + JWT 驱动 tenant_id 强隔离，禁 app 层拼接。
- **理由**：跨租户泄漏是 #1 事故类；DB-per-tenant >10k 租户不可行。
- **影响**：所有 RAG SDK 调用必须经网关注入；静态扫描卡 CI。

### ADR-A03 IT 代码批改 human-in-the-loop
- **决策**：LLM 做形成性反馈（rubric 分解 CoT），高风险评分教师复核。
- **理由**：StepGrade 2025 研究 MAE 0.44-0.62 未达人类一致性。
- **影响**：批改 Agent 必须 interrupt_before teacher_review；审计日志必填。

### ADR-A04 LangGraph ≥1.0 用于多步 Agent
- **决策**：批改/答疑/学习路径用 LangGraph ≥1.0（对齐 ESP 验证版本 1.2.10：interrupt_before + Redis checkpointer + Command(resume)）；固定流程用轻量状态机。
- **理由**：ESP 验证 LangGraph 1.x HITL 模式可行；避免固定流程跨语言 RPC 延迟；修复 ESP 遗留的 RedisSaver 未实现问题（EP 直接用 RedisClusterSaver）。
- **影响**：Python 主语言，Web 框架用 Litestar 2.x + Granian ≥1.6.0（承接 ESP ADR-09，Rust ASGI 替代 FastAPI/Uvicorn；ESP 实际解析至 2.7.9，EP 锁定 ≥1.6.0 下限）；状态序列化 schema 需版本化。
- **ESP 踩坑对策**：Python pulsar-client 需检查 arm64 wheel 可用性（ESP 无 arm64/cp313 wheel），实现 `PULSAR_ENABLED=false` 降级开关。

## 8. 部署拓扑

```
┌──────────────────── 国内主集群(K8s, 异地多活) ────────────────────┐
│  namespace: ai-platform                                           │
│   ├─ agent-svc (Python, gunicorn, HPA=cpu70%)                     │
│   ├─ gateway-svc (Kong AI / LiteLLM sidecar)                      │
│   ├─ rag-svc (Python)                                             │
│   ├─ rerank-svc (Python, GPU nodePool, 可降级 API)                │
│   └─ admin-console (静态托管)                                     │
│ 依赖:                                                             │
│   ├─ Milvus cluster (集群共享, namespace 隔离)                    │
│   ├─ Redis Cluster (LangCache + checkpointer, zcode:aigraph:*)    │
│   ├─ pgvector pool (租户小集合)                                   │
│   ├─ Iceberg (数据中台湖, 训练数据)                               │
│   └─ Feature Store (数据中台 Flink 喂)                            │
└───────────────────────────────────────────────────────────────────┘
┌──────────── 海外独立集群(PIPL) ────────────┐
│  同构部署, 独立 LLM provider, 数据不出境    │
└────────────────────────────────────────────┘
```

## 9. 安全设计

### 9.1 FGAC（细粒度访问控制）
- tenant_id 来源：JWT claim `tid`，网关 Ingress 解析后注入请求头 `X-Tenant-Id`。
- 下游服务（RAG / Agent / 计费）只读该头，禁止 app 层覆盖；静态扫描规则 `no-tenant-concat`。
- Milvus 查询表达式强制 `tenant_id == {injected}`，无该表达式查询被 SDK 拒绝。

### 9.2 数据安全
- 训练数据落 Iceberg 带 tenant_id，湖查询经 FGAC 中间件。
- 缓存 key 含 tenant_id，杜绝跨租户缓存串。
- LLM 请求/响应脱敏后落评测集（PII 过滤）。

### 9.3 内容安全
- 答疑 / 出题输入经安全策略过滤（政治/色情/暴力/作弊）。
- 输出经二次审核，命中策略转人工。

## 10. 可观测性

| 维度 | 指标 | 采集 |
|------|------|------|
| 网关 | cache命中率 / route分布 / cascade率 / fallback率 / P95延迟 | Prometheus |
| Agent | 各 Agent 调用量 / interrupt次数 / resume成功率 / MAE | OpenTelemetry |
| RAG | 召回@10 / nDCG@10 / 索引入库速率 / 跨租户拒绝次数 | Prometheus + 审计 |
| 成本 | 单调用成本 / tenant成本 / agent成本 | Iceberg T+1 |
| 安全 | FGAC 绕过尝试 / 安全过滤命中 | 审计日志 |

## 11. 与其他中台接口

| 对端 | 接口 | 方向 |
|------|------|------|
| 技术中台 | K8s / Redis Cluster / Milvus / 图库 | 依赖 |
| 数据中台 | Feature Store topic / Iceberg / Flink | 入：特征；出：训练样本 |
| 业务中台 | 课程目录 / 作业 / 学情 / 直播事件 | 入：业务上下文 |
| 安全合规 | JWT / FGAC 策略 / 内容安全策略 | 入：策略 |

## 12. 风险与对策（架构级）
- R1 provider 单点：多 provider fallback + 健康探针 + 熔断。
- R2 缓存脏数据：版本 key（知识库 version）+ 主动失效 + TTL。
- R3 GPU 争抢：rerank 限流 + 降级到 API rerank + 节点池隔离。
- R4 LangGraph 状态膨胀：checkpointer TTL 7d + 冷存归档。

---

（全文完，约 240 行有效内容）

---

## D-046: 智能体架构重构 (MCP+A2A+Graph+Reflexion+Harness+Hermes+Plugin)

### 1. 架构总览

D-046 对 AI 中台智能体层进行重构，引入 7 个互相解耦的子系统，统一由 LangGraph 1.0 编排。整体仍遵循 §3 的 4 层架构，本节聚焦智能体层（L3）与网关层（L2）的扩展。

```
                              ┌────────────────────────────────────┐
   User / BFF ──HTTP──►       │  ai_gateway (Litestar)             │
                              │  agent_routes / a2a_routes /       │
                              │  tool_routes / plugin_routes /     │
                              │  memory_routes                     │
                              └───────────────┬────────────────────┘
                                              │ invoke
                          ┌───────────────────▼───────────────────┐
                          │  ai_agent/graphs/root_graph.py        │
                          │  Supervisor (LLM intent router)        │
                          │  _round_done 终止保护                  │
                          │  RedisSaver checkpointer (TTL 7d)      │
                          └───┬────┬────┬────┬────┬────┬────┬─────┘
                              │    │    │    │    │    │    │
                 ┌────────────▼┐┌───▼┐┌──▼──┐┌──▼──┐┌──▼──┐┌──▼───┐
                 │ qa_agent    ││grad││quiz ││learn││digit││debate│
                 │ (Reflexion) ││ing ││     ││path ││human││      │
                 └─────┬───────┘└─┬──┘└─┬───┘└─┬───┘└─┬───┘└──────┘
                       │          │     │      │      │
            ┌──────────┴──────────┴─────┴──────┴──────┴──────────┐
            │  ai_harness: AgentExecutor / SandboxedToolNode      │
            │  TokenBudget / AuditLogger (Redis Stream → Iceberg)  │
            └──────────┬─────────────────────────────────────────┘
                       │ events
            ┌──────────▼─────────────────────────────────────────┐
            │  ai_hermes: EventBus → Pulsar (3 retries + DLQ)     │
            │  EventRouter (rule + Redis hot-update + LLM)        │
            │  InMemoryBus fallback (PULSAR_ENABLED=false)        │
            └──────────┬─────────────────────────────────────────┘
                       │
   ┌───────────────────┴────────────────────────────────────────┐
   │  ai_mcp (Tools/Resources/Prompts, stdio + Streamable HTTP)  │
   │  ai_a2a  (AgentCard @ /.well-known/agent.json, Task lifecycle)│
   │  ai_plugin (Manifest + Permission + Sandbox + Discovery)    │
   └─────────────────────────────────────────────────────────────┘
```

### 2. ADR 矩阵

| ADR | 标题 | 决策摘要 |
|-----|------|---------|
| ADR-A07 | MCP 协议接入 | 采用 Model Context Protocol (JSON-RPC 2.0) 作为外部工具/资源/Prompt 统一接入协议，支持 stdio + Streamable HTTP 双传输；注册表 Redis 优先、YAML 兜底 |
| ADR-A08 | A2A 协议支持 | 采用 Agent-to-Agent Protocol 实现跨 Agent 互操作；AgentCard 发布于 `/.well-known/agent.json`，Task 生命周期 6 态机，JSON-RPC over HTTPS + SSE |
| ADR-A09 | 分层多智能体图 | Supervisor + 6 子图（QA/Grading/Quiz/LearnPath/DigitalHuman/Debate），子图作为节点嵌入父图，RedisSaver checkpointer 持久化，`_round_done` 防死循环 |
| ADR-A10 | Reflexion 自反思 | 每个业务子图内置 evaluate→reflect→retry 循环，max_trials=3，阈值 0.7，episodic memory Redis TTL 30d |
| ADR-A11 | Harness 执行护栏 | AgentExecutor 超时 120s / 深度 50 / 试验 3；SandboxedToolNode 权限+注入清洗+预算+截断+审计；AuditLogger Redis Stream → Iceberg，本地 JSONL 兜底 |
| ADR-A12 | Hermes 事件编排 | EventBus 统一 pub/sub，Pulsar 主路径 3 重试 + DLQ，InMemoryBus 降级；EventRouter 规则 + Redis 热更 + LLM 兜底 |
| ADR-A13 | 插件系统 | PluginManifest 声明式注册，6 类 Permission 枚举，tenant_whitelist 隔离，PluginSandbox 强制权限校验，向量检索发现 |

### 3. 子系统说明

**ai_mcp** — MCP Server/Client/Registry。MCPServerInfo 数据类描述服务元信息；MCPServerRegistry 以 `mcp:servers:{tenant}` 为键持久化，YAML 兜底；MCPClient 支持 stdio 与 Streamable HTTP；EPMCPServer 暴露平台内置工具集。

**ai_a2a** — AgentCard、TaskManager、A2AClient、A2AServer、AgentRegistry、NotificationManager。Task 状态机：`submitted→working→input-required→completed`（或 `canceled/failed/rejected`）。TaskManager Redis 键 `a2a:task:{id}` TTL 7d。NotificationManager 将 Task 事件转发至 Hermes。

**ai_harness** — AgentExecutor 统一封装超时/深度/试验上限；SandboxedToolNode 在工具调用前做权限校验、prompt 注入与路径遍历清洗、Token 预算扣减、输出截断、审计落账；TokenBudget 按 `tenant×agent×model` 在 Redis 维护滚动 24h 窗口；AuditLogger 写入 Redis Stream `audit:agent:events`，异步落 Iceberg，失败回退 `/tmp/ep_audit_fallback.jsonl`。

**ai_hermes** — EventBus 统一发布/订阅抽象；PulsarProducer/Consumer 默认 3 次重试 + DLQ；`PULSAR_ENABLED=false`（默认）时自动降级为 InMemoryBus；EventRouter 规则匹配优先，Redis 键 `hermes:routes` 支持热更新，未命中规则时可选 LLM 兜底。事件链：`submission.created→grading.completed→quiz.generated→learnpath.updated`。

**ai_plugin** — PluginManifest 声明 plugin_id、mcp_server_url、transport、skills、required_permissions、tenant_whitelist、version；PluginPermission 枚举（KNOWLEDGE_READ/WRITE、SUBMISSION_GRADE、QUIZ_GENERATE、LEARNING_PATH、NETWORK_EGRESS、FILESYSTEM）；PluginRegistry 异步加载，Redis 键 `plugin:registry:{tenant}` + YAML 兜底；PluginSandbox 在调用前校验权限与租户白名单；PluginDiscovery 基于向量检索推荐插件。

**ai_agent/graphs** — root_graph 构建父图：Supervisor 节点使用 LLM 做意图路由，设置 `_round_done` 标志位在意图明确后终止循环避免死循环。6 个子图：QA（retrieve→think→evaluate→[reflect→think]→respond）、Grading（ingest→rubric→llm_grade→evaluate→[reflect]→risk_classify→[teacher_review HITL]→emit）、Quiz（spec→retrieve→gen→dedup→irt_calibrate→evaluate→[reflect]→[teacher_audit HITL]→emit）、LearnPath（profile→graph_reason→plan→evaluate→[reflect]→[review HITL]→emit）、DigitalHuman（script→tts→lip_sync→render→stream）、Debate（proposition↔opposition→synthesis）。Checkpointer 使用 RedisSaver，MemorySaver 作为兜底。

**ai_agent/reflexion.py** — evaluate_node 基于规则（空/过短/占位符/关键词覆盖）评分，阈值 0.7；reflect_node 生成可操作的反思；should_retry 决策：通过→respond / 失败且未超上限→reflect / 失败且达上限→respond；episodic memory 键 `mem:episodic:{tenant}:{agent}:{user}` TTL 30d。

### 4. 数据流

```
User → BFF → ai_gateway POST /v1/agents/run
  → Supervisor 路由到子图（如 Grading）
  → 子图执行：ingest → rubric → llm_grade
  → Harness 校验：权限 / 预算 / 注入清洗 / 审计
  → Reflexion evaluate：未达阈值 → reflect → 重试（≤3）
  → teacher_review 节点 interrupt_before（HITL 暂停）
  → 教师审核通过 → resume → emit
  → Hermes 发布 grading.completed 事件
  → 下游子图（Quiz/LearnPath）订阅触发
  → 最终结果经 ai_gateway 返回用户
```

### 5. 与其他中台接口更新

| 中台 | 接口 | 说明 |
|------|------|------|
| 业务中台 | submission.created 事件 | 经 Hermes 触发 Grading Agent |
| 知识中台 | MCP 工具 / Plugin | 知识检索作为 MCP Tool 暴露 |
| 数据中台 | audit:agent:events → Iceberg | 审计落湖供分析 |

### 6. 风险与对策（D-046 增补）

- R5 Supervisor 路由抖动：`_round_done` 标志 + 最大轮次硬上限。
- R6 Reflexion 不收敛：max_trials=3 硬截断 + grounded answer 兜底。
- R7 Pulsar 不可用：InMemoryBus 降级 + 告警。
- R8 插件逃逸：PluginSandbox 强制权限 + 租户白名单 + 网络隔离。

---

（D-046 章节完）

---

## §多级智能记忆架构 (D-045, ADR-A05)

> 关联：D-045 多级智能记忆系统；ADR-A05（L0~L3 四级架构）；ADR-A06（Mem0 框架集成）；D-048 审计 P0-5（ep_memory 手写 stub 重构）；D-033 缓存铁律；ADR-A02 多租户 FGAC。

### 1. 定位

ep-memory 是 SP-04 AI 中台的独立平台级共享记忆库，为 D-046 智能体架构（Supervisor + 5 业务 Agent）提供跨会话、跨设备、跨用户的记忆能力。Python SDK（SP-04 内置）+ Java SDK（sp07 `AgentSession` 激活，见 D-045 工作包 5）双语言可用。

基础选型：引入 **Mem0 开源框架** 为 L2 长期记忆核心（提取/去重/冲突消解），L0/L1/L3 自研。详见 ADR-A06。

### 2. 四级记忆架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    MemoryManager (编排层, 自研)                  │
│   recall(user, query) → [L0] → [L1] → [L2] → [L3]  分级召回     │
│   remember(user, turn) → L0 写入 → 异步 L1 摘要 → L2 Mem0 提取  │
└─────────────────────────────────────────────────────────────────┘
   │              │              │                  │
   ▼              ▼              ▼                  ▼
┌─────────┐  ┌─────────┐  ┌──────────────────┐  ┌────────────┐
│ L0 即时 │  │ L1 工作 │  │ L2 长期记忆       │  │ L3 组织记忆│
│ Redis   │  │ Redis   │  │ Milvus+TiDB+Neo4j│  │ Milvus     │
│         │  │         │  │ Mem0 pipeline    │  │ +Neo4j     │
│ 最近10轮│  │ 会话摘要│  │ 情景/语义/程序   │  │ 租户级共享 │
│ TTL 24h │  │ TTL 7d  │  │ 30d降权/90d归档  │  │ 永不过期   │
└─────────┘  └─────────┘  └──────────────────┘  └────────────┘
   │ 24h淘汰      │ 7d降级L2     │ 降权/归档          │ 仅手动治理
```

### 3. Mem0 集成方式

- **职责边界**：Mem0 仅负责 L2 的提取（LLM 抽取事实/偏好/实体）、去重（向量相似度阈值）、冲突消解（新旧事实合并）pipeline；L0/L1（Redis 层）与 L3（治理层）由自研 MemoryManager 实现。
- **后端适配**：Mem0 配置 `vector_store=milvus`（情景记忆 `mem_episodic`，dimension=1536）、`graph_store=neo4j`（程序记忆 `mem_procedural`）、`kv_store=tidb`（语义记忆 `mem_semantic`），与 ADR-INF-01/02 及 TiDB 选型对齐。
- **服务形态**：Python 侧 ep-memory 服务以 gRPC `:50057` 暴露 `Remember`/`Recall`/`Forget` RPC；Java 侧 ep-memory-java SDK 通过 gRPC stub 调用，不内嵌 Mem0，符合 D-040 GraalVM AOT。
- **LLM/Embedder**：Mem0 的 LLM 与 Embedder 走项目 `ai_gateway`，不直连外部 provider。

### 4. 遗忘机制

| 级别 | 策略 | 触发 | 实现 |
|------|------|------|------|
| L0 | TTL 淘汰 | 24h 未更新 | Redis EXPIRE |
| L1 | 降级到 L2 | TTL 7d 到期 | 后台 job 提取摘要写入 L2 后删除 |
| L2 | 降权 → 归档 | 30d 降权（召回分 ×0.5）/ 90d 归档（冷存） | Milvus 标记 + TiDB 状态字段 + Neo4j 边权重衰减 |
| L3 | 不自动遗忘 | 仅手动治理 | 管理员 API 显式删除 |

### 5. 与现有系统的关系

| 系统 | 关系 |
|------|------|
| D-046 智能体架构 | MemoryManager 作为 LangGraph 节点工具注入，每轮对话前后调用 recall/remember |
| D-033 缓存铁律 | L0/L1 即 ep-memory 的缓存层，遵循 Redis Cluster + 本地兜底铁律 |
| ADR-A02 多租户 | 所有记忆键含 tenant_id 段；L3 跨用户共享需显式授权 |
| ADR-INF-01/02 | Milvus/Neo4j 已选型，ep-memory 直接复用 collection/graph |
| D-048 审计 P0-5 | 本节解决 `ep_memory` 手写 stub 接入 Mem0 问题 |
| D-046 Reflexion | L1 摘要与 L2 提取结果走 Reflexion 评估，阈值 0.7 |

### 6. 风险与对策

- R9 Mem0 社区版 3 亿用户扩展性未验证：L2 无状态可水平扩展 + SP-09 压测验证。
- R10 无官方 Java SDK：自研 gRPC 客户端，契约驱动，与 D-047 模式一致。
- R11 L1 摘要质量不稳定：Reflexion 评估 + 失败兜底保留原文。
- R12 L3 组织记忆污染：晋升需双人审核 + 审计留痕 + 可回滚。

---

（D-045 章节完）

---

## 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | 架构组 |
| v1.1 | 2026-08-08 | D-091: AiGrading Dubbo Provider shim — Java @DubboService 桥接 SP-04 Python HTTP /v1/chat/completions (跨语言契约修复); D-094: ep-web-common 共享JAR — AI中台Java组件复用统一基础设施(安全/异常处理) | 架构组 |
