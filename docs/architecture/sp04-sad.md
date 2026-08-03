| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-SAD | v1.0 | 2026-08-03 | 评审通过 | AI中台架构组 | 架构委员会 |

# SP-04 AI中台 软件架构设计(SAD)

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
- **影响**：Python 主语言，Web 框架用 Litestar 2.x + Granian 1.x（承接 ESP ADR-09，Rust ASGI 替代 FastAPI/Uvicorn）；状态序列化 schema 需版本化。
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
