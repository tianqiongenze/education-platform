| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-SDD | v1.0 | 2026-08-03 | 评审通过 | AI中台架构组 | 架构委员会 |

# SP-04 AI中台 软件详细设计(SDD)

> 关联：SRS `sp04-srs.md`、SAD `sp04-sad.md`；ADR-A01..A04；D-010/018/019/020

## 1. 设计范围与约定
- 主语言 Python 3.11（D-009）。
- Agent 编排：LangGraph 0.2+，checkpointer = RedisClusterSaver。
- 模型网关：Kong AI 主，LiteLLM sidecar 起步。
- 向量库：Milvus 2.4+（知识库层）、pgvector 0.7+（租户小集合）。
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
- 事件驱动：业务中台直播事件 → 文本 → TTS + 口型脚本 → 推流（直播平台）。
- 脚本模板 ≥20 套；多语言切换 ≤2s。

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
订阅: kafka topic = data-middle.features.ai
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
- 实时写 Kafka `ai.billing` → Flink → Iceberg `ai_billing` 表，T+1 对账。
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
