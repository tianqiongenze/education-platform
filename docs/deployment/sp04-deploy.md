| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-DEPLOY | v1.0 | 2026-08-03 | 评审通过 | AI中台SRE组 | 架构委员会 |

# SP-04 AI中台 部署方案

> 关联：SAD `sp04-sad.md`、SDD `sp04-sdd.md`；D-004/009/018；ADR-A01..A04

## 1. 部署总览

```
┌────────────── 国内主集群(异地多活 2 cell) ──────────────┐
│ K8s namespace: ai-platform                              │
│  ├─ agent-svc (Python/gunicorn)  ┐                      │
│  ├─ gateway-svc (Kong AI + LiteLLM sidecar)             │
│  ├─ rag-svc (Python)                                    │
│  ├─ rerank-svc (Python, GPU nodePool)                   │
│  ├─ admin-console (Nginx 静态托管)                      │
│  └─ cron-eval (CronJob 评测回归)                        │
│ 依赖: Milvus cluster / Redis Cluster / pgvector pool    │
│       / Iceberg(数据中台湖) / Feature Store(Pulsar)      │
└─────────────────────────────────────────────────────────┘
┌──────────── 海外独立集群(PIPL) ────────────┐
│ 同构, 独立 LLM provider, 数据不出境        │
└────────────────────────────────────────────┘
```

## 2. Python 服务 K8s 部署

### 2.1 镜像规范
- 基础镜像 `python:3.11-slim`，多阶段构建。
- Web 框架 Litestar 2.x + Granian ≥1.6.0（Rust ASGI，承接 ESP ADR-09 替代 FastAPI/Uvicorn；ESP 实际解析至 2.7.9，EP 锁定 ≥1.6.0 下限）。
- ASGI 入口 `granian app:app --interface asgi --workers $(nproc)*2+1`（替代 gunicorn/uvicorn）。
- 健康检查：`/healthz`（liveness）、`/readyz`（readiness，含 Redis/Milvus 探活）。
- **ESP 踩坑对策**：`pulsar-client` Python 包需在目标架构(arm64/cp311)预检 wheel 可用性；Dockerfile 用 `pip install pulsar-client || echo "PULSAR disabled"` + 运行时 `PULSAR_ENABLED=false` 降级开关（ESP 无 arm64 wheel 导致 import 失败）。

### 2.2 Deployment 模板（agent-svc 节选）
```yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: agent-svc, namespace: ai-platform}
spec:
  replicas: 4
  strategy: {type: RollingUpdate, rollingUpdate: {maxSurge: 1, maxUnavailable: 0}}
  template:
    spec:
      affinity:
        podAntiAffinity: {preferredDuringScheduling: [{weight:100, podAffinityTerm:{labelSelector:{matchLabels:{app:agent-svc}}}}]}
      containers:
      - name: app
        image: registry.example.com/ai/agent-svc:1.0.0
        resources:
          requests: {cpu: "1", memory: "2Gi"}
          limits:   {cpu: "2", memory: "4Gi"}
        env:
        - {name: LLM_MODE, value: "real"}        # CI 为 mock
        - {name: REDIS_URL, valueFrom: {secretKeyRef: {name: ai-secrets, key: redis_url}}}
        - {name: MILVUS_HOST, value: "milvus.ai-data.svc"}
        - {name: JWT_JWKS_URL, valueFrom: {secretKeyRef: {name: ai-secrets, key: jwks_url}}}
        readinessProbe: {httpGet: {path: /readyz, port: 8000}, periodSeconds: 5}
        livenessProbe:  {httpGet: {path: /healthz, port: 8000}, periodSeconds: 10}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: agent-svc, namespace: ai-platform}
spec:
  scaleTargetRef: {kind: Deployment, name: agent-svc}
  minReplicas: 4
  maxReplicas: 40
  metrics:
  - type: Resource
    resource: {name: cpu, target: {type: Utilization, averageUtilization: 70}}
```

### 2.3 GPU 节点池（rerank-svc）
- nodePool `gpu-rerank`，标签 `nvidia.com/gpu.present=true`。
- 资源请求 `nvidia.com/gpu: 1`，节点池最小 2 节点，最大 8（cluster-autoscaler）。
- 限流：每节点并发 ≤32，超限降级到 API rerank。

## 3. Kong AI Gateway 部署

### 3.1 部署模式
- Kong AI Gateway 作为 `gateway-svc` 的 sidecar 或独立 Deployment（推荐独立，便于扩容）。
- 起步阶段用 LiteLLM sidecar 旁路，逐步切 Kong AI 主路径（D-018）。

### 3.2 关键插件配置
| 插件 | 用途 | 关键参数 |
|------|------|----------|
| ai-proxy | 多 provider 路由 | providers: [openai, azure, qwen] |
| ai-rate-limiting | 限流 | dimension: {tenant, agent, model}, window: 60s |
| ai-semantic-cache | LangCache | redis: {cluster}, ttl: 3600, similarity: 0.92 |
| ai-token-meter | 计费 | sink: pulsar `persistent://public/event/ai.billing` |
| ai-prompt-template | 模板 | per-agent 模板 |
| request-transformer | 注入 X-Tenant-Id | 来自 JWT claim tid |

### 3.3 路由表热更新
- 路由表存于 ConfigMap `ai-route-table`，通过 admin API `PUT /v1/admin/route` 触发 reload，<5s 生效。

## 4. Milvus 集群配置

### 4.1 复用集群
- 使用集群已有 Milvus（D-010/C-01），AI 中台建独立 collection `aigraph_kb`。
- 资源配额：`aigraph_kb` 最多 50 节点 shard，单独 resource group 隔离。

### 4.2 collection 创建
```python
from pymilvus import CollectionSchema, FieldSchema, Collection, utility
fields = [
  FieldSchema("id", DataType.INT64, is_primary=True, auto_id=True),
  FieldSchema("chunk_id", DataType.VARCHAR, max_length=64),
  FieldSchema("tenant_id", DataType.INT64),
  FieldSchema("kb_id", DataType.INT64),
  FieldSchema("course_id", DataType.INT64),
  FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=1024),
  FieldSchema("content", DataType.VARCHAR, max_length=8192),
  FieldSchema("metadata", DataType.JSON),
]
schema = CollectionSchema(fields, enable_dynamic_field=False)
col = Collection("aigraph_kb", schema)
col.create_index("embedding", {"index_type":"HNSW","metric_type":"IP","params":{"M":16,"efConstruction":200}})
col.create_index("tenant_id", {"index_type":"INVERTED"})
col.set_properties({"partition.tenant.strategy":"namespace"})
# 按 tenant 创建 namespace: tenant_{tid}
```

### 4.3 备份与恢复
- 每日快照到对象存储；跨 cell 备份 RPO ≤24h。
- 恢复演练每季度一次。

## 5. Redis LangCache 配置

### 5.1 部署
- 复用 Redis Cluster（D-006），独立 DB 或 key 前缀 `zcode:aigraph:cache:*` 隔离。
- LangCache 模块（Kong ai-semantic-cache 或自研）以 sidecar 部署。

### 5.2 关键参数
| 参数 | 值 | 说明 |
|------|----|------|
| 嵌入模型 | bge-small-zh | 缓存 query 向量化 |
| 相似度阈值 | 0.92 | 余弦，低于不命中 |
| TTL | 3600s | 兜底失效 |
| 失效 | kb_version 变更触发 `DEL zcode:aigraph:cache:*{kb_version}*` | 主动失效 |
| checkpointer | `zcode:aigraph:state:*` TTL 7d | LangGraph 状态 |

### 5.3 LangGraph checkpointer
```python
from langgraph.checkpoint.redis import RedisClusterSaver
saver = RedisClusterSaver(url=REDIS_URL, key_prefix="zcode:aigraph:state:")
# TTL 7d, 冷存归档到 Iceberg
```

## 6. GPU 资源管理

- 仅 rerank-svc 与可选本地小模型使用 GPU；旗舰 LLM 走 provider API。
- 节点池 `gpu-rerank` 独立 taint `gpu=true:NoSchedule`，仅 rerank-svc 容忍。
- 驱逐策略：节点 GPU 利用率 >90% 持续 5min 触发 autoscaler 扩容。
- 故障降级：GPU 节点全故障 → rerank-svc 切 API rerank（环境变量 `RERANK_MODE=api`）。

## 7. LLM_MODE=mock 在 CI 中的配置

### 7.1 环境变量
- CI 流水线默认 `LLM_MODE=mock`，全量单元/集成/E2E 用桩数据。
- 真实 provider 调用仅在夜班评测 Job 显式 `LLM_MODE=real` + 密钥注入。

### 7.2 桩数据组织
```
fixtures/mock/
  grading/*.json
  quiz/*.json
  qa/*.json
  path/*.json
  digital/*.json
```
- 按 `X-Agent` 路由到目录；`trace_id` 命中特定用例（如 `trace_id=mock-grade-high-01` → `grading/high_risk.json`）。

### 7.3 CI 闸门
- 静态扫描 `no-tenant-concat` / `milvus-expr-required` 必过。
- mock 模式回归必过；真实 provider 夜班指标劣化 >5% 阻断发布。

## 8. CI/CD pipeline

```
代码提交(PR)
  ├─ lint(ruff/black) + 静态扫描(自定义规则)
  ├─ 单元测试(pytest, LLM_MODE=mock)
  ├─ 集成测试(testcontainers: Milvus/pgvector/Redis)
  ├─ E2E(mock)
  ├─ 镜像构建 + 漏洞扫描(trivy)
  └─ 部署到 staging(自动)
staging:
  ├─ 冒烟用例
  └─ 人工审批 → prod
prod:
  ├─ 滚动更新(maxSurge=1, maxUnavailable=0)
  └─ 金丝雀 10% → 50% → 100%(基于错误率/P95)
夜班:
  ├─ 评测集回归(real provider, 灰度)
  ├─ 压测(mock)
  └─ 安全渗透
```

### 8.1 GitOps
- ArgoCD 同步 `ai-platform` manifest 仓库；分支 `prod` → prod cell。
- 配置 drift 检测，告警到 SRE。

## 9. 运维 Runbook

### 9.1 LLM 降级
- 现象：provider 错误率 >5% 或 P95 >5s。
- 处置：
  1. 网关自动 fallback 至备用 provider（RTO ≤3s）。
  2. 若全 provider 故障，启用降级模式：批改仅确定性评分（LLM 维度标"待评"），答疑返回兜底话术并转人工。
  3. SRE 在控制台切 `LLM_DEGRADE=1`，公告业务方。
  4. provider 恢复后逐步取消降级，观察 30min。

### 9.2 缓存失效
- 知识库更新触发：`POST /v1/admin/cache/invalidate {kb_version}` → 按 key 模式批量 DEL。
- 脏数据疑似：控制台单条 `DEL zcode:aigraph:cache:<key>`，并查审计。
- 全量 flush：仅 SRE 二人审批，记录工单。

### 9.3 模型切换
- 路由表热更新：`PUT /v1/admin/route`，5s 生效。
- 灰度：先 10% 流量新模型，观察 MAE/引用率/成本 30min，再全量。
- 回滚：路由表回退前一版本，秒级生效。

### 9.4 跨租户泄漏疑似
- 立即隔离：相关 tenant 路由切 mock，审计日志保全。
- 排查：FGAC 拒绝日志、Milvus 查询表达式、静态扫描最近变更。
- 复盘：必须根因 + 改进静态扫描规则。

### 9.5 GPU 节点故障
- rerank-svc 自动切 `RERANK_MODE=api`，质量略降但可用。
- SRE 排查节点，恢复后切回。

## 10. 监控告警

| 指标 | 阈值 | 动作 |
|------|------|------|
| 网关 P95 | >3s 持续 5min | 告警 SRE |
| 缓存命中率 | <50% 持续 15min | 告警算法 |
| fallback 率 | >10% 持续 5min | 告警 SRE |
| 跨租户拒绝次数 | 突增 >2σ | 告警安全 |
| 批改 MAE | >0.4（周评测） | 阻断发布 |
| GPU 利用率 | >90% 5min | 扩容 |
| Redis 内存 | >80% | 扩容/淘汰 |

## 11. 容量规划
- 天花板 100 万并发（G-01）：网关 HPA max=200，Milvus shard=50，Redis Cluster 30 节点。
- M1 阶段 1 万 CCU：网关 4 副本起步，按 CPU70% 扩容。
- 训练数据 Iceberg 年增 50TB，湖冷热分层，热区 SSD 7d，冷区对象存储。

## 12. 灾备
- 异地多活：两 cell 同构，DNS 按用户域路由；故障切 cell RTO ≤5min。
- 海外独立：数据不出境，provider 独立。
- 演练：季度进行 cell 切换 + Milvus 恢复演练。

---

（全文完，约 250 行有效内容）

---

## D-046: 智能体架构部署配置

### 1. 新增环境变量

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| MCP_ENABLED | true | 启用 MCP 协议接入 |
| A2A_ENABLED | true | 启用 A2A 协议与 AgentCard 端点 |
| HERMES_ENABLED | true | 启用 Hermes 事件总线 |
| PULSAR_ENABLED | false | Pulsar 主路径开关；false 时降级 InMemoryBus |
| REFLEXION_MAX_TRIALS | 3 | Reflexion 最大重试次数 |
| REFLEXION_THRESHOLD | 0.7 | evaluate 通过阈值 |
| REDIS_CHECKPOINTER_URL | redis://redis-cluster:6379/0 | LangGraph RedisSaver 连接串 |
| AUDIT_REDIS_STREAM | audit:agent:events | 审计 Redis Stream 名 |

### 2. Pulsar Topic 预配

| Topic | 用途 | 分区 | 保留 |
|-------|------|------|------|
| `persistent://public/a2a/task-events` | A2A Task 状态变更通知 | 6 | 7d |
| `persistent://public/hermes/agent-events` | Hermes 智能体事件链 | 6 | 7d |
| `persistent://public/hermes/dlq` | Hermes 死信队列 | 3 | 30d |

预配命令示例：
```bash
bin/pulsar-admin topics create persistent://public/a2a/task-events -p 6
bin/pulsar-admin topics create persistent://public/hermes/agent-events -p 6
bin/pulsar-admin topics create persistent://public/hermes/dlq -p 3
bin/pulsar-admin namespaces set-retention public -r 7d -s 10G
```

### 3. Redis 键命名空间表

| 键模式 | 类型 | TTL | 用途 |
|--------|------|-----|------|
| `mcp:servers:{tenant}` | Hash | — | MCP Server 注册表 |
| `a2a:agents:{tenant}` | Hash | — | A2A Agent 注册表 |
| `a2a:task:{id}` | String(JSON) | 7d | A2A Task 状态 |
| `plugin:registry:{tenant}` | Hash | — | Plugin 注册表 |
| `budget:{tenant}:{agent}:{model}` | ZSET | 24h 滚动 | Token 预算 |
| `audit:agent:events` | Stream | — | 审计事件流（异步落 Iceberg） |
| `hermes:routes` | String(JSON) | — | 事件路由规则热更新 |
| `mem:episodic:{tenant}:{agent}:{user}` | List | 30d | Reflexion episodic memory |
| `langgraph:checkpoint:{thread_id}` | Hash | 7d | LangGraph 状态检查点 |

### 4. LangGraph 1.0 Checkpointer 配置

```yaml
langgraph:
  checkpointer:
    type: redis
    url: ${REDIS_CHECKPOINTER_URL}
    ttl: 7d
    fallback: memory  # Redis 故障降级为 MemorySaver
```

依赖升级（pyproject.toml 已对齐）：
- `langgraph ^0.2 → ^1.0`
- 新增 `mcp ^1.0`、`langchain-mcp-adapters ^0.1`、`langgraph-sdk ^1.0`、`mem0ai ^0.1`

### 5. K8s 部署补充

sp04-ai Deployment 新增环境变量（通过 ConfigMap `sp04-ai-config` 注入）：

```yaml
env:
  - name: MCP_ENABLED
    valueFrom: { configMapKeyRef: { name: sp04-ai-config, key: mcp_enabled } }
  - name: A2A_ENABLED
    valueFrom: { configMapKeyRef: { name: sp04-ai-config, key: a2a_enabled } }
  - name: HERMES_ENABLED
    valueFrom: { configMapKeyRef: { name: sp04-ai-config, key: hermes_enabled } }
  - name: PULSAR_ENABLED
    valueFrom: { configMapKeyRef: { name: sp04-ai-config, key: pulsar_enabled } }
  - name: REFLEXION_MAX_TRIALS
    value: "3"
  - name: REFLEXION_THRESHOLD
    value: "0.7"
  - name: REDIS_CHECKPOINTER_URL
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: redis_url } }
  - name: AUDIT_REDIS_STREAM
    value: "audit:agent:events"
```

### 6. 审计落湖配置

AuditLogger 消费 `audit:agent:events` Stream 批写 Iceberg 表 `audit.agent_events`。消费组 `audit-iceberg-sink`，批次大小 500，提交间隔 10s。Redis 写失败回退 `/tmp/ep_audit_fallback.jsonl`，由 sidecar `audit-replay` 容器重放。

### 7. 降级矩阵

| 故障 | 降级路径 | 告警 |
|------|---------|------|
| Redis 不可用 | MCP/Plugin 注册表→YAML；Checkpointer→MemorySaver；TokenBudget→本地限流 | CRITICAL |
| Pulsar 不可用 | Hermes→InMemoryBus | WARNING |
| Iceberg 不可用 | AuditLogger→本地 JSONL，待重放 | WARNING |
| Milvus 不可用 | PluginDiscovery→跳过向量检索 | WARNING |

---

（D-046 章节完）

---

## §ep-memory 部署配置 (D-045)

> 关联：ADR-A05；ADR-A06；D-048 审计 P0-5。

### 1. Mem0 依赖

Python 侧 `sp04-ai/requirements.txt` 追加：

```
mem0ai==0.1.30
pymilvus==2.4.6
neo4j==5.24.0
tidb-vector==0.0.4
```

### 2. Milvus collection 配置

`mem_episodic`（L2 情景记忆）：

```python
from pymilvus import CollectionSchema, FieldSchema, DataType

fields = [
    FieldSchema("memory_id", DataType.VARCHAR, max_length=32, is_primary=True),
    FieldSchema("tenant_id", DataType.VARCHAR, max_length=64, is_partition_key=True),
    FieldSchema("user_id", DataType.VARCHAR, max_length=64),
    FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=1536),
    FieldSchema("content", DataType.VARCHAR, max_length=4096),
    FieldSchema("archive", DataType.BOOL, default=False),
    FieldSchema("created_at", DataType.INT64),  # epoch ms
]
schema = CollectionSchema(fields, enable_dynamic_field=True)
# index: HNSW on embedding, M=16, efConstruction=200
# partition_key=tenant_id (千级租户走 partition_key 隔离)
```

`mem_org`（L3 组织记忆）：同构，`tenant_id` partition_key 隔离。

### 3. Neo4j graph 配置

`mem_procedural`（L2 程序记忆）与 `mem_org_graph`（L3）：

```cypher
// 节点标签
// :User, :Course, :Skill, :Goal, :OrgKnowledge
// 关系类型
// :LEARNS(User->Course), :MASTERS(User->Skill), :TARGETS(User->Goal), :BELONGS_TO(*->OrgKnowledge)

// 边权重衰减 (遗忘机制)
// weight 属性默认 1.0, 30d ×0.5, 90d 归档(archive=true)
```

### 4. Redis key 命名空间

| 级别 | key 模式 | 类型 | TTL |
|------|---------|------|-----|
| L0 即时 | `mem:imm:{tenant}:{user}:{session}` | LIST | 24h |
| L1 工作 | `mem:work:{tenant}:{user}` | HASH | 7d |
| L3 授权 | `mem:org:auth:{tenant}:{user}` | SET（授权的 org_memory_id） | — |

### 5. TiDB 表

`mem_semantic`（L2 语义记忆）建表 SQL 见 SDD §4 数据模型。索引 `idx_tenant_user(tenant_id, user_id, status)` 支撑召回查询。

### 6. 环境变量

```yaml
env:
  - name: MEM0_CONFIG_PATH
    value: "/app/config/mem0_config.yaml"
  - name: MILVUS_COLLECTION_MEM
    value: "mem_episodic"
  - name: MILVUS_COLLECTION_ORG
    value: "mem_org"
  - name: NEO4J_GRAPH_MEM
    value: "mem_procedural"
  - name: NEO4J_GRAPH_ORG
    value: "mem_org_graph"
  - name: EP_MEMORY_GRPC_PORT
    value: "50057"
  - name: TIDB_DSN
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: tidb_dsn } }
  - name: MILVUS_URI
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: milvus_uri } }
  - name: NEO4J_URI
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: neo4j_uri } }
  - name: NEO4J_USER
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: neo4j_user } }
  - name: NEO4J_PASSWORD
    valueFrom: { secretKeyRef: { name: sp04-ai-secret, key: neo4j_password } }
  - name: AI_GATEWAY_URL
    valueFrom: { configMapKeyRef: { name: sp04-ai-config, key: ai_gateway_url } }
```

### 7. 遗忘 CronJob

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: mem-forget-job
  namespace: sp04-ai
spec:
  schedule: "0 2 * * *"   # 每日 02:00
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: mem-forget
            image: sp04-ai:latest
            command: ["python", "-m", "sp04_ai.memory.forget_job"]
          restartPolicy: OnFailure
```

`forget_job` 执行：L1 7d 降级扫描 + L2 30d 降权 + L2 90d 归档标记。

### 8. 降级矩阵（增补）

| 故障 | 降级路径 | 告警 |
|------|---------|------|
| Redis Cluster 不可用 | L0/L1 → MemorySaver 兜底当前会话；L2 直接召回 | CRITICAL |
| Milvus 不可用 | L2 情景跳过；L1 摘要兜底 | WARNING |
| Neo4j 不可用 | L2 程序跳过；情景/语义兜底 | WARNING |
| TiDB 不可用 | L2 语义跳过；情景兜底 | WARNING |
| Mem0 服务不可用 | L2 提取暂停（消息入 Pulsar DLQ 待重放）；L0/L1 不受影响 | WARNING |

---

（D-045 章节完）
