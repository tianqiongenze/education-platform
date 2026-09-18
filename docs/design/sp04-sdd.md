# SP-04 AI中台 软件详细设计(SDD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp04` for reference continuity. Per D-137, sp04 → ai-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-SDD | v1.0 | 2026-08-03 | 评审通过 | AI中台架构组 | 架构委员会 |

> 关联：SRS `sp04-srs.md`、SAD `sp04-sad.md`；ADR-A01..A04；D-010/018/019/020

## 1. 设计范围与约定
- 主语言 Python 3.11（D-009）；Web 框架 Litestar 2.x + Granian 1.x（Rust ASGI，承接 ESP ADR-09 替代 FastAPI/Uvicorn）。
- Agent 编排：LangGraph ≥1.0（对齐 ESP 验证版本 1.2.10，含 interrupt_before + Command(resume) HITL），checkpointer = RedisClusterSaver（修复 ESP RedisSaver 未实现遗留）。
- 模型网关：Kong AI 主，LiteLLM sidecar 起步（D-018）。
- 向量库：Milvus 2.5+（对齐 ESP 验证 2.5.0，知识库层）、pgvector 0.7+（租户小集合）。
- Python pulsar-client：optional extra，需检查 arm64/cp311 wheel 可用性（ESP 踩坑：无 arm64 wheel）；`PULSAR_ENABLED=false` 降级开关必须实现。
- 命名：Redis key 前缀 `zcode:aigraph:*`；Milvus collection `aigraph_*`。
- 状态序列化：JSON + schema 版本号字段 `_v`。

## 2. Agent 详细设计

### 2.1 批改 Agent（LangGraph）

**状态图**

```
START
  │
  ▼
ingest(解析提交, 注入 tenant_id/user_id/assignment_id)
  │
  ▼
rubric_decompose(按 rubric 拆子维度: 正确性/规范/可读性/边界/安全)
  │
  ▼
llm_grade(每子维度 CoT + self-consistency k=5, 取众数/均值)
  │
  ▼
risk_classify(权重≥40% 或 MAE_est>0.6 → high; 否则 low/mid)
  │
  ├─[low/mid]──► emit(写结果, mid 抽样复核)
  │
  └─[high]────► interrupt_before(teacher_review)
                    │  状态落 Redis: thread_id=(tid,uid,aid)
                    │
                    ▼
                 teacher_review(改分/驳回/标注误判)
                    │
                    ▼
                 Command(resume=payload) ─► finalize
                    │
                    ▼
                  END
```

**状态 schema（节选）**
```python
class GradingState(TypedDict):
    _v: int                      # schema 版本
    tenant_id: str               # 由网关注入, 只读
    user_id: str
    assignment_id: str
    submission: dict
    rubric: list[dict]
    sub_scores: list[dict]       # [{dim, score, cot, samples}]
    risk: Literal["low","mid","high"]
    teacher_action: dict | None  # resume payload
    audit: list[str]             # 审计事件
```

**关键代码骨架**
```python
graph = StateGraph(GradingState, checkpointer=redis_saver)
graph.add_node("ingest", ingest_fn)
graph.add_node("rubric_decompose", rubric_fn)
graph.add_node("llm_grade", llm_grade_fn)          # 调网关
graph.add_node("risk_classify", risk_fn)
graph.add_node("teacher_review", teacher_fn)
graph.add_node("emit", emit_fn)
graph.add_edge(START, "ingest")
graph.add_edge("ingest", "rubric_decompose")
graph.add_edge("rubric_decompose", "llm_grade")
graph.add_edge("llm_grade", "risk_classify")
graph.add_conditional_edges("risk_classify", route_by_risk,
                            {"low":"emit","mid":"emit","high":"teacher_review"})
graph.add_edge("teacher_review", "emit")
graph.add_edge("emit", END)
compiled = graph.compile(interrupt_before=["teacher_review"])
```

**resume 流程**
```python
# 教师复核动作到达
cfg = {"configurable":{"thread_id": thread_id}}
state = compiled.get_state(cfg)
if state.next == ("teacher_review",):
    compiled.update_state(cfg, {"teacher_action": action})
    compiled.invoke(None, cfg)   # resume
```

### 2.2 出题 Agent
- 流程：`spec(知识点/难度/题型) → retrieve(RAG 找来源) → gen(LLM 出题) → dedup(BM25+向量去重 >0.85 拒) → irt_calibrate(预估 a/b/c, |δ|<0.3) → teacher_audit(interrupt_before) →入库`。
- 轻量状态机部分：dedup / irt_calibrate 为固定流程，自研实现，避免 LangGraph 开销。

### 2.3 答疑 Agent
- 流程：`retrieve(RAG) → gen(LLM) → guard(安全+幻觉兜底) → emit`。
- 多轮：上下文窗口 8k，超出走摘要压缩；checkpointer 跨设备续聊。

### 2.4 学习路径 Agent
- 流程：`profile(学情+Feature) → graph_reason(知识图谱) → plan(LLM 生成 ≤10 节点) → review?(可选 HITL) → emit`。
- 重算触发：每 N 次练习或掌握度变化 >阈值。

### 2.5 数字人

> **ESP 经验吸收**：ESP 仅做了 TTS + 口型脚本的事件驱动骨架，未引入实时渲染。EP 在此基础上补全渲染技术栈，使数字人可用于直播讲解、AI 答疑视频生成等场景。

**技术栈**：
| 层 | 技术 | 版本 | 说明 |
|----|------|------|------|
| 文本驱动 | LLM + TTS | / | 业务中台直播事件 → LLM 生成文本 → TTS 合成语音 |
| 语音合成 | CosyVoice / Edge-TTS | 2.x | 中文情感 TTS，SSML 标注停顿/语速/情感；备选 Edge-TTS 低成本 |
| 口型同步 | Wav2Lip / SadTalker | 1.x | 音频→口型帧映射；SadTalker 适合照片驱动（低成本虚拟教师） |
| 实时渲染 | Unity + uOSC | 2022 LTS+ | 3D 数字人模型渲染，OSC 协议接收口型/表情参数（Live2D 备选） |
| 推流 | SRS v7 + LL-HLS | 7.x | 渲染帧 → SRS RTMP 推流 → LL-HLS CDN 分发（对齐 D-021 直播两层混合） |
| 模板引擎 | Jinja2 + JSON | / | 脚本模板 ≥20 套（讲解/答疑/公告/鼓励...），JSON 参数化 |

**事件驱动流程**：
```
业务中台直播事件 → Agent(LLM 生成讲解文本)
    → TTS(语音合成 + SSML 标注)
    → Wav2Lip(口型帧生成)
    → Unity(渲染 + 背景合成)
    → SRS(RTMP 推流)
    → CDN(LL-HLS 分发)
    → 学员端(HLS 播放器)
```

- 脚本模板 ≥20 套；多语言切换 ≤2s（TTS 引擎热切换，不重建渲染管线）。
- 降级：TTS 不可用时 → 预录音频 + 文字字幕；渲染不可用时 → PPT + 语音（退化为图文直播）。
- 成本控制：非直播时段用照片驱动（SadTalker），直播时段用 3D 模型（Unity）。

## 3. 模型网关 API 设计

### 3.1 统一调用接口
```
POST /v1/ai/invoke
Headers:
  Authorization: Bearer <JWT>
  X-Agent: grading|quiz|qa|path|digital
Body:
  {"prompt": "...", "params": {...}, "trace_id": "..."}
Response:
  {"content": "...", "model": "...", "provider": "...",
   "cache_hit": bool, "route": "small|flagship", "cascade": bool,
   "tokens": {"in": n, "out": m}, "cost": 0.0012, "trace_id": "..."}
```

### 3.2 路由规则表（热更新）
| 规则 ID | 条件（agent + 意图 + 难度） | 目标模型 | 级联阈值 τ |
|--------|---------------------------|----------|-----------|
| R01 | grading + 简单文本 | qwen-small | 0.7 |
| R02 | grading + 代码 + 高权重 | gpt4-class | 不级联 |
| R03 | qa + 闲聊 | qwen-small | 0.6 |
| R04 | quiz + 生成 | gpt4-class | 0.65 |

### 3.3 缓存 key 设计
```
key = "zcode:aigraph:cache:" + sha256(
        tenant_id + "|" + agent + "|" + semantic_embed(prompt) + "|"
        + kb_version + "|" + model_family)
TTL = 3600s；知识库更新时按 kb_version 失效旧 key。
```

### 3.4 成本栈伪代码
```python
def invoke(req, tenant_id, agent):
    # ① cache
    key = build_cache_key(tenant_id, agent, req.prompt, kb_version, model_family)
    if cached := langcache.get(key):
        metrics.cache_hit.inc(); return cached

    # ② intent route
    model = route_llm.classify(agent, req.prompt)   # small | flagship

    # ③ confidence cascade
    if model == "small":
        out = call(model, req)
        if confidence(out) < tau(model):
            model = "flagship"
            out = call(model, req)
            metrics.cascade.inc()
    else:
        out = call(model, req)

    # ④ multi-provider fallback
    out = fallback_call(model, req, providers=priority_list(model))

    # 计费 + 限流 + 写缓存
    bill(tenant_id, agent, model, out.tokens, out.cost)
    langcache.set(key, out, ttl=3600)
    return out

def fallback_call(model, req, providers):
    for p in providers:
        try: return p.call(model, req, timeout=3)
        except (Timeout, 5xx): metrics.fallback.inc(); continue
    raise AllProvidersDown
```

### 3.5 LLM_MODE=mock
- 环境变量 `LLM_MODE=mock` 时，网关 Ingress 短路成本栈，按 `X-Agent` 返回脚本化桩数据。
- 桩数据按 `fixtures/mock/<agent>/*.json` 组织，支持 trace_id 路由到特定用例。
- CI 默认 `LLM_MODE=mock`，无外部 provider 依赖。

## 4. RAG 检索 pipeline

```
query ──► BM25(Elasticsearch, tenant filter)
       ├─► 向量(Milvus/pgvector, tenant namespace)
       └─► metadata filter(课程/章节/难度)
              │
              ▼
        RRF 融合(top-50)
              │
              ▼
        Reranker(cross-encoder, GPU 或 API 降级) → top-10
              │
              ▼
        返回 chunks(含 chunk_id / score / source)
```

**RRF 融合公式**：`score(d) = Σ 1/(k + rank_i(d))`，k=60。

**Reranker 降级**：GPU 节点池满载或故障 → 调 provider API rerank → 仍失败则返回 RRF top-10 不重排，标记 `rerank_skipped=true`。

## 5. 多租户 FGAC 实现

### 5.1 JWT → namespace 映射
```python
def inject_tenant(request, jwt):
    tid = jwt.verify().claims["tid"]          # 仅网关解析
    request.headers["X-Tenant-Id"] = tid      # 下游只读
    # 静态扫描: app 代码不得出现 tenant_id= 拼接

def rag_query(query, tenant_id, kb):
    if kb == "shared":
        return milvus.search(
            collection="aigraph_kb",
            expr=f"tenant_id == {tenant_id}",   # 强制 filter
            ns=f"tenant_{tenant_id}")
    else:
        return pgvector.query(schema=f"t_{tenant_id}", ...)
```

### 5.2 隔离不变量校验（CI 静态扫描）
- 规则 `no-tenant-concat`：禁止源码出现 `tenant_id =` 赋值或字符串拼接。
- 规则 `milvus-expr-required`：Milvus search 调用必须含 `tenant_id ==` 表达式。
- 违规阻断 CI。

## 6. Milvus collection 设计

### 6.1 知识库 collection `aigraph_kb`
| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 PK | 自增 |
| chunk_id | varchar | 业务唯一 |
| tenant_id | int64 | FGAC filter |
| kb_id | int64 | 知识库 |
| course_id | int64 | 课程 |
| embedding | float_vector(1024) | bge-large |
| content | varchar | 原文 |
| metadata | json | 章节/难度/版本 |

- 索引：HNSW（M=16, efC=200），metric IP。
- namespace：`tenant_{tenant_id}` 物理隔离。
- 分区：按 `kb_id` partition，加速过滤。

### 6.2 租户小集合（pgvector）
- schema `t_{tenant_id}`，表 `chunks(embedding vector(1024), content, metadata)`。
- 池化：单库 ≤500 租户，超限迁移到 Milvus。

## 7. 特征对接接口

```
订阅: pulsar topic = persistent://public/event/data-middle.features.ai
消息 schema:
{
  "tenant_id": "...",
  "user_id": "...",
  "features": {
     "mastery_vector": [...],   // 掌握度向量
     "recent_errors": [...],    // 近 N 错题
     "study_velocity": 0.7,
     "ts": 1730000000
  }
}
落: Feature Store(Redis) + Iceberg(训练)
读: 学习路径 Agent 通过 FeatureClient.get(user_id, tenant_id)
```

Feature Store P95 <2s（NFR-01 上游）。

## 8. 计费与审计

- 每次网关调用产出计费事件：`(ts, tenant_id, agent, model, provider, tokens_in, tokens_out, cost, cache_hit, trace_id)`。
- 实时写 Pulsar `persistent://public/event/ai.billing` → Flink → Iceberg `ai_billing` 表，T+1 对账。
- 审计：HITL 动作、FGAC 拒绝、fallback 触发均写审计日志（不可变，附 hash 链）。

## 9. 错误处理与降级

| 场景 | 降级策略 |
|------|----------|
| 全 provider 故障 | 返回 503 + 降级话术；批改走"仅确定性评分，LLM 维度标待评" |
| Milvus 故障 | RAG 仅 BM25，标记 `vector_skip` |
| Rerank GPU 满 | 降级 API rerank；再失败返回 RRF top-10 |
| Redis LangCache 故障 | 旁路缓存，直进 route，告警 |
| Feature Store 延迟 | 学习路径用缓存画像（≤24h） |

## 10. 接口清单

| 接口 | 方法 | 说明 |
|------|------|------|
| /v1/ai/invoke | POST | 统一 LLM 调用（经成本栈） |
| /v1/agent/grade | POST | 批改 Agent 入口 |
| /v1/agent/quiz | POST | 出题 Agent |
| /v1/agent/qa | POST | 答疑 Agent |
| /v1/agent/path | POST | 学习路径 |
| /v1/rag/index | POST | 文档入库 |
| /v1/rag/search | POST | 检索测试 |
| /v1/admin/route | GET/PUT | 路由表查询/热更新 |
| /v1/admin/cache/invalidate | POST | 缓存失效 |
| /v1/admin/eval | GET | 评测看板数据 |

---

（全文完，约 260 行有效内容）

---

## D-046: 智能体架构详细设计

### 1. MCP 集成设计

**传输层**：MCPClient 同时支持 stdio（本地子进程）与 Streamable HTTP（远程服务）两种传输。传输方式由 MCPServerInfo.transport 字段决定，注册时声明。

**注册表**：MCPServerRegistry 维护 `mcp:servers:{tenant}` Redis 哈希，每个条目为 MCPServerInfo 的 JSON 序列化。Redis 不可用时回退到本地 `mcp_servers.yaml`。注册/注销/列表接口：

| 操作 | 方法 | 说明 |
|------|------|------|
| 注册 | `register(info: MCPServerInfo)` | 写 Redis + YAML 双写 |
| 注销 | `unregister(name, tenant)` | 删 Redis，YAML 标记 disabled |
| 列表 | `list(tenant)` | Redis 优先，YAML 兜底 |

**工具 Schema**：每个 MCP 工具遵循 `{"name","description","inputSchema":JSONSchema}` 结构。SandboxedToolNode 在调用前用 inputSchema 做参数校验，拒绝不合规输入。

### 2. A2A 协议设计

**Agent Card**：每个 A2A Server 在 `/.well-known/agent.json` 暴露 AgentCard，字段含 `id, name, description, endpoint, capabilities, skills, version`。AgentRegistry 拉取并缓存至 `a2a:agents:{tenant}`。

**Task 生命周期状态机**：

```
submitted ──► working ──► completed
                │   ▲
                ▼   │
        input-required (HITL)
                │
   canceled / failed / rejected
```

- `submitted`：任务已接收，未开始。
- `working`：Agent 正在执行。
- `input-required`：需要人工或外部输入（HITL）。
- `completed`/`canceled`/`failed`/`rejected`：终态。

**JSON-RPC 端点**：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/a2a/tasks/send` | JSON-RPC | 创建/续接 Task |
| `/a2a/tasks/get` | JSON-RPC | 查询 Task 状态 |
| `/a2a/tasks/sendSubscribe` | JSON-RPC + SSE | 流式订阅 Task 进度 |
| `/.well-known/agent.json` | GET | 获取 AgentCard |

TaskManager 持久化 `a2a:task:{id}` TTL 7d。NotificationManager 在 Task 状态变更时向 Hermes 发布事件。

### 3. 分层多智能体图设计

**Supervisor 路由逻辑**：root_graph 的 Supervisor 节点接收用户意图，使用 LLM 从 `{qa, grading, quiz, learnpath, digital_human, debate, FINISH}` 中选择下一节点。一旦选择具体业务子图，设置 `_round_done=True`，Supervisor 不再重新路由，子图执行完毕直接进入 FINISH，防止死循环。

**子图组合**：6 个子图各自是独立的 StateGraph，通过 `add_node(subgraph)` 作为节点嵌入父图。子图内部共享 AgentState（state.py 定义），包含 `messages, memories, reflections, trial_count, artifacts, task_id, tool_calls, evaluation_passed, feedback, final_answer`。

**Redis Checkpointer**：使用 LangGraph 1.0 的 RedisSaver，键格式 `langgraph:checkpoint:{thread_id}`，TTL 7d。Redis 不可用时降级为 MemorySaver（进程内，重启丢失，仅用于开发）。

### 4. Reflexion 设计

**流程**：
```
... → evaluate_node ──pass──► respond/emit
                │
              fail (score < 0.7)
                ▼
          reflect_node (生成反思，写入 reflections + episodic memory)
                │
          should_retry?
           ├─ trial_count < max_trials(3) → 回到 think/gen 节点
           └─ trial_count >= max_trials → respond (grounded answer 兜底)
```

**evaluate_node 规则**：空答案 / 长度 < 阈值 / 含占位符 / 关键词覆盖率不足，任一命中即 fail，综合分 0.7 为阈值。

**episodic memory**：键 `mem:episodic:{tenant}:{agent}:{user}`，存储历史反思，注入到 prompt 供后续 trial 参考，TTL 30d。

**收敛保证**：max_trials=3 硬上限 + 达上限后强制 respond，保证图必然终止。

### 5. Harness 设计

**AgentExecutor 限制**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| timeout | 120s | 单次执行超时 |
| max_depth | 50 | 图节点递归深度上限 |
| max_trials | 3 | Reflexion 最大重试 |

超限抛出 `AgentExecutionError`，被网关捕获返回 408/409。

**SandboxedToolNode**：在工具调用前后执行：
1. 权限校验：当前 Agent 是否持有该工具所需 Permission。
2. 输入清洗：检测 prompt 注入模式（`ignore previous` 等）与路径遍历（`../`、绝对路径）。
3. 预算扣减：向 TokenBudget 查询并扣减预估 token，超额拒绝。
4. 输出截断：超过 max_output_tokens 截断并标记。
5. 审计落账：写 AuditLogger。

**TokenBudget**：Redis 键 `budget:{tenant}:{agent}:{model}`，ZSET 按时间戳记录消耗，滚动 24h 窗口求和。

**AuditLogger**：Redis Stream `audit:agent:events`（XADD），异步消费者批写 Iceberg `audit.agent_events` 表；Redis 写失败时追加到 `/tmp/ep_audit_fallback.jsonl`。

### 6. Hermes 设计

**EventBus**：统一 `publish(topic, event)` / `subscribe(topic, handler)` 接口。底层实现可切换：
- `PULSAR_ENABLED=true`：PulsarProducer/Consumer，3 次重试，失败入 DLQ `persistent://public/hermes/dlq`。
- `PULSAR_ENABLED=false`（默认）：InMemoryBus，进程内 pub/sub，开发与降级用。

**EventRouter**：消费事件后按规则路由到下游 Agent。规则表存 Redis `hermes:routes`（JSON 数组），支持热更新无需重启。规则未命中时可选 LLM 兜底路由（成本高，默认关闭）。

**事件链**：`submission.created` → Grading Agent → `grading.completed` → Quiz Agent → `quiz.generated` → LearnPath Agent → `learnpath.updated`。

### 7. 插件设计

**PluginManifest Schema**：
```python
@dataclass
class PluginManifest:
    plugin_id: str
    name: str
    mcp_server_url: str
    transport: Literal["stdio", "http"]
    skills: list[str]
    required_permissions: list[PluginPermission]
    tenant_whitelist: list[str] | None  # None = 全租户
    version: str
    description: str
```

**权限模型**：PluginPermission 枚举 6 类（KNOWLEDGE_READ/WRITE、SUBMISSION_GRADE、QUIZ_GENERATE、LEARNING_PATH、NETWORK_EGRESS、FILESYSTEM）。PluginSandbox 在每次工具调用前校验 manifest.required_permissions ⊆ Agent 持有权限。

**租户隔离**：`tenant_whitelist` 为空表示仅白名单租户可用；为 None 表示全租户。注册表键 `plugin:registry:{tenant}`，YAML 兜底。

**Discovery**：PluginDiscovery 将 manifest.description 向量化，按用户查询做向量检索 top-k 推荐。

### 8. HITL 设计

Grading / Quiz / LearnPath 子图在审核节点设置 `interrupt_before=["teacher_review"]`（或 `teacher_audit` / `review`）。LangGraph 在进入该节点前暂停，状态持久化到 RedisSaver，A2A Task 进入 `input-required` 态。教师通过 `POST /v1/a2a/tasks/send` 提交审核结果后，图 resume 继续。超时（默认 24h）自动 reject。

---

（D-046 章节完）

---

## §ep-memory 详细设计 (D-045)

> 关联：ADR-A05（L0~L3 四级架构）；ADR-A06（Mem0 集成）；D-048 审计 P0-5。

### 1. MemoryManager 接口

```python
class MemoryManager:
    """ep-memory 编排层，自研。负责 L0~L3 分级召回与写入编排。"""

    async def remember(
        self,
        tenant_id: str,
        user_id: str,
        session_id: str,
        turn: dict,            # {role, content, metadata}
    ) -> None:
        """每轮对话后调用。同步写 L0，异步触发 L1 摘要与 L2 Mem0 提取。"""
        # 1. L0 写入 Redis list, LPUSH + LTRIM 保留 10 轮, EXPIRE 24h
        # 2. 异步: 若 L0 长度达阈值或会话切换 → L1 摘要(Reflexion 评估)
        # 3. 异步: Mem0 client.add(messages, user_id, tenant_id) → L2 提取

    async def recall(
        self,
        tenant_id: str,
        user_id: str,
        session_id: str,
        query: str,
        top_k: int = 5,
    ) -> list[MemoryItem]:
        """分级召回: L0 → L1 → L2(RRF 融合三路) → L3(授权后)。"""
        # L0: Redis 最近 10 轮原文
        # L1: Redis 会话摘要 + 任务上下文
        # L2: Mem0 client.search(query, user_id) 三路(情景/语义/程序) RRF 融合
        # L3: 若有组织授权 → 查 Milvus mem_org + Neo4j 图谱

    async def forget(
        self,
        tenant_id: str,
        user_id: str,
        memory_id: str | None = None,
    ) -> None:
        """显式遗忘。L0/L1 删 Redis; L2 调 Mem0 delete; L3 仅治理员可调。"""

    async def promote_to_org(
        self,
        tenant_id: str,
        user_id: str,
        memory_id: str,
        reviewer_ids: list[str],
    ) -> None:
        """L2 → L3 晋升。需双人审核，审计留痕。"""
```

**Java 侧**：`MemoryManagerClient`（ep-memory-java SDK）通过 gRPC stub 调用 Python 侧同名 RPC，封装为 Dubbo 服务供 sp07 `AgentSession` 使用（D-045 工作包 5 激活点）。

### 2. 四级记忆详细设计

#### 2.1 L0 即时记忆

- **存储**：Redis Cluster, key = `mem:imm:{tenant}:{user}:{session}`, 类型 LIST。
- **窗口**：`LPUSH` + `LTRIM 0 9` 保留最近 10 轮（user+assistant 各算 1 项）。
- **TTL**：`EXPIRE 86400`（24h），每轮 remember 时刷新。
- **降级**：Redis 不可用 → MemorySaver（D-046 已有）兜底当前会话，L0 丢失可接受。

#### 2.2 L1 工作记忆

- **存储**：Redis Cluster, key = `mem:work:{tenant}:{user}`, 类型 HASH。
- **内容**：`session_summary`（LLM 摘要，Reflexion 评估阈值 0.7）+ `task_context`（当前任务实体/槽位）+ `last_session_id`。
- **TTL**：`EXPIRE 604800`（7d）。
- **摘要触发**：L0 长度达 8 轮 / 会话切换 / 用户主动 `summarize`。
- **降级**：7d 到期由后台 job 提取摘要写入 L2 后删除 key。

#### 2.3 L2 长期记忆（Mem0 pipeline）

| 子类 | 后端 | collection/table/graph | 召回 |
|------|------|------------------------|------|
| 情景记忆 | Milvus | `mem_episodic`（dim=1536, partition_key=tenant_id） | 向量相似度 top-k |
| 语义记忆 | TiDB | `mem_semantic`（user_id, tenant_id, fact_key, fact_value, confidence, status） | SQL 精确查询 |
| 程序记忆 | Neo4j | `mem_procedural` 图（节点: User/Course/Skill/Goal; 边: 学习/掌握/目标） | Cypher 图遍历 |

- **提取**：`Mem0 client.add(messages, user_id, tenant_id)` → LLM 抽取事实/偏好/实体关系 → 写入对应后端。
- **去重**：向量相似度 > 0.95 视为重复，合并而非新增。
- **冲突消解**：新旧事实矛盾时，按时间戳 + confidence 取新值，旧值标记 `superseded`。
- **召回融合**：三路召回结果经 RRF（Reciprocal Rank Fusion）重排序后返回 top_k。

#### 2.4 L3 组织记忆

- **存储**：Milvus `mem_org`（partition_key=tenant_id）+ Neo4j `mem_org_graph`。
- **内容**：租户级共享知识沉淀（如学校教学大纲、企业培训规范、常见问题库）。
- **晋升**：L2 → L3 需 `promote_to_org` + 双人审核 + 审计留痕。
- **召回**：仅当用户持 `mem:org:read` 权限时，recall 流程才追加 L3 查询。
- **遗忘**：永不自动过期，仅治理员 `forget` API 显式删除，删除前需再次审核。

### 3. 遗忘策略

| 级别 | 策略 | 实现 |
|------|------|------|
| L0 | TTL 24h 淘汰 | Redis EXPIRE |
| L1 | 7d 降级到 L2 | 后台 job `mem:work:*` 扫描，到期提取摘要写 L2 后 DEL |
| L2 情景 | 30d 降权（score×0.5）/ 90d 归档（移冷存） | Milvus 标记 `archive=true` + 查询 filter 排除 |
| L2 语义 | 30d 降权 / 90d 归档 | TiDB `status` 字段: active/decayed/archived |
| L2 程序 | 30d 边权重×0.5 / 90d 归档 | Neo4j 边属性 `weight` 衰减 |
| L3 | 不自动遗忘 | 仅治理员 API |

后台 job `mem-forget-job`（CronJob, 每日 02:00）执行 L1 降级与 L2 降权/归档扫描。

### 4. 数据模型

```python
@dataclass
class MemoryItem:
    memory_id: str          # ULID
    tenant_id: str
    user_id: str
    level: Literal["L0", "L1", "L2", "L3"]
    sub_type: Literal["immediate", "working", "episodic", "semantic", "procedural", "org"] | None
    content: str
    embedding: list[float] | None   # L2 情景/L3 有
    metadata: dict                   # confidence, status, source_session_id, ...
    created_at: datetime
    updated_at: datetime
    expires_at: datetime | None      # L0/L1 有, L2/L3 None
```

**TiDB `mem_semantic` 表**：
```sql
CREATE TABLE mem_semantic (
  memory_id VARCHAR(32) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  fact_key VARCHAR(255) NOT NULL,
  fact_value TEXT NOT NULL,
  confidence DECIMAL(3,2) DEFAULT 1.00,
  status ENUM('active','decayed','archived','superseded') DEFAULT 'active',
  source_session_id VARCHAR(64),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_tenant_user (tenant_id, user_id, status),
  INDEX idx_updated (updated_at)
);
```

### 5. Mem0 集成配置

`mem0_config.yaml`（路径由 `MEM0_CONFIG_PATH` 指定）：

```yaml
vector_store:
  provider: milvus
  config:
    collection_name: mem_episodic
    dim: 1536
    uri: ${MILVUS_URI}
graph_store:
  provider: neo4j
  config:
    graph_name: mem_procedural
    url: ${NEO4J_URI}
    username: ${NEO4J_USER}
    password: ${NEO4J_PASSWORD}
kv_store:
  provider: tidb
  config:
    dsn: ${TIDB_DSN}
embedder:
  provider: openai
  config:
    model: text-embedding-3-small
    api_base: ${AI_GATEWAY_URL}/v1
llm:
  provider: openai
  config:
    model: gpt-4o-mini
    api_base: ${AI_GATEWAY_URL}/v1
```

### 6. 与 D-046 智能体的集成

- LangGraph 节点在 `before` 钩子调 `MemoryManager.recall()` 注入记忆到 prompt 上下文。
- 节点在 `after` 钩子调 `MemoryManager.remember()` 写入本轮。
- HITL 暂停期间，L0 仍保留，resume 后上下文连续。
- Reflexion 评估失败的输出不写入 L2（避免污染长期记忆）。

---

（D-045 章节完）

---

## 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | 研发组 |
| v1.1 | 2026-08-08 | D-094: ep-web-common 共享JAR — sp04 AI中台 Java组件复用 RoleChecker/GlobalExceptionHandler (基础设施共享, 非领域逻辑共享); D-091: AiGrading Dubbo Provider shim — Java @DubboService 桥接 SP-04 Python HTTP /v1/chat/completions, EP_DEV_ONLY guard, 跨语言契约断裂修复 | 研发组 |
