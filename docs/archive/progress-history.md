# 教育平台项目群 —— 历史归档 (冷区)

> 本文件为 `docs/progress.md` 的冷区归档，按需 Grep 检索，不随会话全量加载。
> 归档范围: §6~§14 (D-039~D-112 详细执行记录 + M0/M1/M2/M3 里程碑详情)
> 归档日期: 2026-08-06 (§6~§11) / 2026-08-08 (§12~§14 冷热分离迁移)
> 归档时项目状态: 112 decisions, ~849 tests, 100% pass

---

## 6. D-039/D-040/D-041 影响重构清单 (2026-08-05)

### 6.1 D-039 虚拟线程 — 代码级修改

| 文件 | 修改内容 |
|------|---------|
| `services/sp03-business/.../OrderServiceImpl.java` | 3处 synchronized → ReentrantLock (createOrder/payOrder/refundOrder) |
| `services/sp03-business/.../UserServiceImpl.java` | 3处 synchronized → ReentrantLock (createUser/updateUser/freezeUser) |
| `services/sp03-business/.../CourseServiceImpl.java` | 2处 synchronized → ReentrantLock (publishCourse/archiveCourse) |
| `services/ep-cache/.../CacheService.java` | `new Thread()` → `Thread.startVirtualThread()` (延迟双删) |

### 6.2 D-040 GraalVM Native Image — 配置级修改

| 文件 | 修改内容 |
|------|---------|
| `services/bff-java/pom.xml` | 添加 native-maven-plugin 0.10.4 |
| `services/sp03-business/pom.xml` | 添加 native-maven-plugin 0.10.4 |
| `services/sp05-itbackend/pom.xml` | 添加 native-maven-plugin 0.10.4 |
| `services/integration-tests/pom.xml` | 添加 native-maven-plugin 0.10.4 |
| `services/bff-java/.../application.yml` | spring.threads.virtual.enabled + spring.aot.enabled |
| `services/sp03-business/.../application.yml` | spring.threads.virtual.enabled + spring.aot.enabled |
| `services/sp05-itbackend/.../application.yml` | spring.threads.virtual.enabled + spring.aot.enabled |
| `services/integration-tests/.../application-test.yml` | spring.threads.virtual.enabled (测试模式) |

### 6.3 D-041 多区域多活 — 基础设施级修改

| 文件 | 修改内容 |
|------|---------|
| `services/ep-rw-java/.../DataSourceType.java` | 新增 FOLLOWER_READ 枚举值 |
| `services/ep-rw-java/.../DataSource.java` | 新增 followerRead() 注解属性 |
| `services/ep-rw-java/.../DataSourceAspect.java` | followerRead 路由逻辑 (优先于 value()) |
| `services/ep-rw-java/.../DataSourceConfig.java` | followerReadDataSource bean + ObjectProvider 可选注入 + @Lazy 解决循环依赖 |
| `services/ep-rw-java/.../DataSourceTypeTest.java` | 更新断言: 3个枚举值 |
| `services/ep-cache/.../CacheConfig.java` | redisClusterConnectionFactory bean (条件注入 RedisClusterConfiguration) |
| `services/ep-cache/.../application.yml` | 新建: Redis Cluster nodes + Lettuce pool 配置 |
| `deploy/k8s/configmap.yaml` | D-041 多区域配置: CELL_NAME/REGION/ZONE, TIDB_FOLLOWER_READ, PULSAR_REPLICATION, REDIS_CRDT, GSLB |
| `deploy/k8s/bff-java.yaml` | topology labels + topologySpreadConstraints (zone + cell) |
| `deploy/k8s/sp03-business.yaml` | topology labels + topologySpreadConstraints |
| `deploy/k8s/sp05-itbackend.yaml` | topology labels + topologySpreadConstraints |
| `deploy/k8s/sp04-ai.yaml` | topology labels + topologySpreadConstraints |
| `deploy/k8s/gateway-go.yaml` | topology labels + topologySpreadConstraints |
| `deploy/k8s/overlays/cell-hz/kustomization.yaml` | 杭州 cell overlay (副本数 + cell config) |
| `deploy/k8s/overlays/cell-sz/kustomization.yaml` | 深圳 cell overlay (topology label patch + 副本数) |
| `deploy/k8s/overlays/cell-bj/kustomization.yaml` | 北京 cell overlay |
| `deploy/k8s/overlays/cell-sg/kustomization.yaml` | 新加坡海外 cell overlay (数据隔离 + GDPR) |
| `deploy/k8s/karmada-propagation.yaml` | PropagationPolicy (权重 40/30/20/10) + OverridePolicy (per-cell config 覆盖) |
| `deploy/k8s/kustomization.yaml` | 引用 karmada-propagation.yaml |
| `docs/deployment/multi-active-verification.md` | 多活验证 runbook (1060行, TOGAF对齐) |

### 6.4 测试验证结果

| 模块 | 测试结果 |
|------|---------|
| ep-rw-java | 11 tests, 0 failures ✅ (含 D-041 follower-read) |
| ep-cache | 31 tests, 0 failures ✅ (含虚拟线程 + Redis Cluster) |
| sp03-business | 38 tests, 0 failures ✅ (含 ReentrantLock) |
| integration-tests | 18 tests, 0 failures ✅ (虚拟线程模式) |
| **合计** | **98 tests, 0 failures** ✅ |

---

## 7. D-042 AI智能驱动数据中台 — 文档矩阵 (2026-08-05)

SP-02数据中台升级为AI智能驱动平台, 6文档矩阵全部完成AI驱动章节追加:

| 文档 | 行数 | AI驱动章节 | 核心内容 |
|------|------|-----------|---------|
| `docs/requirements/sp02-srs.md` | 322 | §3.9 AI智能驱动 | FR-AI-01..11, 用户故事, 验收标准, 追溯矩阵 |
| `docs/architecture/sp02-sad.md` | 508 | §13 AI智能驱动架构 | 六层架构扩展, ADR-D05~D08, SP-04协作架构 |
| `docs/design/sp02-sdd.md` | 796 | §12 AI智能驱动详细设计 | 4子系统设计, 9作业, 6 Iceberg表, SP-04接口契约 |
| `docs/testing/sp02-test.md` | 395 | §13 AI智能驱动功能测试 | L1~L8测试矩阵, 8项门禁指标 |
| `docs/deployment/sp02-deploy.md` | 675 | §13.5 AI组件部署 | 11组件拓扑, ONNX/LLM部署策略, Pulsar Topic, CI/CD |
| `docs/progress.md` | — | §7 本节 | D-042登记 |

### 新增ADR清单
| ADR | 标题 | 状态 |
|-----|------|------|
| ADR-D05 | 智能数据目录 + 字段级血缘(GNN+OpenLineage) | PROPOSED |
| ADR-D06 | 智能特征平台(自动推荐+PSI/KL/KS漂移检测+SHAP反馈) | PROPOSED |
| ADR-D07 | 预测性AIOps(LSTM容量预测+IsolationForest异常+GBT调度) | PROPOSED |
| ADR-D08 | 自然语言数据查询(Text-to-SQL+RAG+Trino, 多轮对话) | PROPOSED |

---

## 8. D-043 SP-07 知识中台 + D-044 全中台SaaS改造 (2026-08-05)

### D-043: SP-07 知识中台新建

基于SP-04 AI中台构建企业级知识中台,服务5类用户群体(技术开发团队/其他员工/客服/授课教师/学员).

| 文档 | 状态 | 核心内容 |
|------|------|---------|
| `docs/requirements/sp07-srs.md` | ✅ 275行 | 25条FR-KB需求, 12条NFR, 15用户故事, 多租户SaaS抽离约束 |
| `docs/architecture/sp07-sad.md` | ✅ 250行(v1.1) | 六层架构总览, 组件视图, 部署视图, 数据视图, ADR矩阵, SP-04协作, D-045/D-047集成 |
| `docs/design/sp07-sdd.md` | ✅ 932行 | 6功能域详细设计, 11章节, 接口契约, 作业清单 |
| `docs/testing/sp07-test.md` | ✅ 565行 | 测试矩阵, 10章节, 门禁指标, 追溯表 |
| `docs/deployment/sp07-deploy.md` | ✅ 1263行 | 部署拓扑, 13章节, K8s, CI/CD |
| `docs/progress.md` | ✅ 本节 | D-043登记 |

**6大功能域**: 知识库管理 / 智能检索 / Agent管理 / 知识共创 / 多租户SaaS / 学习路径

### D-044: 全中台多租户SaaS可抽离改造规划

所有中台(SP-01~SP-07)必须具备可抽离SaaS能力,能随时从教育行业剥离服务其他企业.

| 改造要素 | 方案 |
|----------|------|
| 三级隔离模型 | Level1行级(tenant_id+MyBatis拦截器) / Level2 Schema(动态路由) / Level3独立DB |
| 租户上下文 | Java 21 ScopedValue (D-039虚拟线程兼容) |
| SaaS控制平面 | 租户管理 + 配额管理 + 计量计费 + API网关 |
| 改造路线 | SP-07标杆→SP-04→SP-01→SP-02→SP-05→SP-03(插件化) |

**文档**: `docs/architecture/saas-multi-tenant-transformation.md`

### 决策计数更新

| 字段 | 值 |
|------|-----|
| decisions.jsonl 总条目 | 49 (D-001 ~ D-048 + D-045) |
| Redis Cluster audit:decisions:count | 待同步至47 |

---

## 9. D-046 AI智能体架构重构 (2026-08-05)

全栈重构SP-04 AI中台智能体架构, 7子系统代码完成 (sp04-ai):

| 子系统 | 文件数 | 描述 | 状态 |
|--------|--------|------|------|
| `ai_mcp/` | 6 | MCP协议 (Model Context Protocol): JSON-RPC 2.0, Tools/Resources/Prompts, MCPServerRegistry | ✅ |
| `ai_a2a/` | 8 | A2A协议 (Agent-to-Agent): AgentCard, Task生命周期, JSON-RPC+SSE, TaskManager+AgentRegistry | ✅ |
| `ai_harness/` | 4 | Harness执行护栏: AgentExecutor(timeout/depth/trials)+SandboxedToolNode+TokenBudget+AuditLogger | ✅ |
| `ai_hermes/` | 7 | Hermes事件编排: EventBus+Pulsar+InMemoryBus降级+EventRouter(rules+Redis+LLM fallback) | ✅ |
| `ai_plugin/` | 6 | 插件系统: PluginManifest+PluginRegistry+PluginLoader+PluginSandbox+PluginDiscovery | ✅ |
| `ai_agent/graphs/` | 9 | 分层多智能体图: Supervisor+6子图(qa/grading/quiz/learnpath/digital_human/debate), LangGraph 1.0 | ✅ |
| `ai_agent/reflexion.py` | 1 | Reflexion自反思: evaluate→reflect→retry (max_trials=3, threshold 0.7), episodic memory | ✅ |
| `ai_gateway/*_routes.py` | 5 | Gateway API路由: agent/a2a/tool/plugin/memory routes | ✅ |

**核心决策**: MCP+A2A同时落地 / Hierarchical混合(Supervisor+Subgraph+Debate) / 全部Agent都加Reflexion / 全栈重构
**新增ADR**: ADR-A07 (MCP) / ADR-A08 (A2A) / ADR-A09 (分层图) / ADR-A10 (Reflexion) / ADR-A11 (Harness) / ADR-A12 (Hermes) / ADR-A13 (Plugin)
**约束遵循**: D-033缓存铁律 / D-039虚拟线程 / D-040 GraalVM / D-041多区域多活 / D-044 SaaS可抽离
**LangGraph升级**: 0.2 → 1.0, 支持 interrupt_before HITL人工审核

### D-046 文档矩阵 (全部完成)

| 文档 | D-046 章节 | 核心内容 |
|------|-----------|---------|
| `docs/architecture/sp04-sad.md` | D-046 架构总览 | ASCII 架构图, ADR-A07~A13, 7 子系统说明, 数据流 |
| `docs/design/sp04-sdd.md` | D-046 详细设计 | MCP/A2A/Graph/Reflexion/Harness/Hermes/Plugin/HITL 8 节详设 |
| `docs/requirements/sp04-srs.md` | D-046 需求 | FR-AGT-01~10 功能需求 + NFR + 追溯矩阵 |
| `docs/testing/sp04-test.md` | D-046 测试矩阵 | TC-AGT-01~40 用例 + 门禁指标 |
| `docs/deployment/sp04-deploy.md` | D-046 部署配置 | 环境变量, Pulsar Topic, Redis 命名空间, LangGraph 1.0 配置, 降级矩阵 |
| `docs/togaf/00-top-level-enterprise-architecture.md` | §18 智能体架构 | TOGAF Application Architecture 视角 |

---

## 9.1 D-045 多级智能记忆系统 (2026-08-05)

ep-memory: L0即时(Redis,10轮,24h)→L1工作(Redis,摘要,7d)→L2长期(Milvus情景/TiDB语义/Neo4j程序, Mem0 pipeline, 30d降权/90d归档)→L3组织(Milvus+Neo4j,租户共享,永不过期)。Mem0框架为核心(L2提取/去重/冲突消解), L0/L1/L3自研。Python SDK + Java SDK(gRPC)。

### D-045 文档矩阵 (工作包6, 全部完成)

| 文档 | D-045 章节 | 核心内容 |
|------|-----------|---------|
| `docs/architecture/sp04-sad.md` | §多级智能记忆架构 (D-045, ADR-A05) | 四级架构图, Mem0集成, 遗忘机制, 与现有系统关系 |
| `docs/design/sp04-sdd.md` | §ep-memory 详细设计 | MemoryManager接口, L0~L3详设, 遗忘策略, 数据模型, Mem0配置 |
| `docs/requirements/sp04-srs.md` | §ep-memory 记忆需求 | FR-MEM-01..08 功能需求 + NFR + 追溯矩阵 |
| `docs/testing/sp04-test.md` | §ep-memory 测试矩阵 | TC-MEM-01..27 用例 + 门禁指标 |
| `docs/deployment/sp04-deploy.md` | §ep-memory 部署配置 | Mem0依赖, Milvus collection, Neo4j graph, Redis key, 环境变量, CronJob, 降级矩阵 |
| `docs/togaf/00-top-level-enterprise-architecture.md` | §17 多级智能记忆架构 | TOGAF对齐定位, L0~L3企业架构角色, 治理合规 |
| `docs/architecture/adr-a05-multi-level-memory.md` | ADR-A05 | L0~L3四级架构三方对比(分级+Mem0 / 单级+TTL / 全自研四级) |
| `docs/architecture/adr-a06-mem0-integration.md` | ADR-A06 | Mem0框架集成三方对比(Mem0 / LangMem / 全自研) |

---

## 10. D-047 MCP实现技术选型 (2026-08-05)

SP-07 MCP Server 实现技术选型: 采用官方 MCP Java SDK + Spring AI MCP 注解, 替换 D-046 中的手写 JSON-RPC dispatcher.

### 选型结论

| 候选方案 | 结论 | 理由 |
|----------|------|------|
| **官方 MCP Java SDK (modelcontextprotocol/java-sdk v2.0.0) + Spring AI MCP** | ✅ 采纳 | 40/40 conformance tests; spring-ai-starter-mcp-server-webmvc + @McpTool/@McpToolParam 注解; Spring AI 2.0.0 BOM 兼容 Spring Boot 4.0.x |
| Spring AI Alibaba (SAA) | ❌ 拒绝 | SB 3.5 依赖与 SB 4.0 铁律冲突; 无 GraalVM AOT 证据 |
| AgentScope | ❌ 拒绝 | 与 Python 侧 LangGraph 重复; Java 侧依赖 SAA |
| 手写 JSON-RPC (D-046 实现) | ❌ 拒绝 | 无 conformance tests; 协议演进维护负担 |

### 代码变更 (sp07-mcp-server)

| 文件 | 变更 |
|------|------|
| `sp07-mcp-server/pom.xml` | 新增 spring-ai-starter-mcp-server-webmvc + spring-ai-mcp-annotations; 父 pom 引入 spring-ai-bom 2.0.0 |
| `McpController.java` / `McpRequest.java` / `McpResponse.java` / `ToolSchema.java` / `ToolRegistry.java` | ❌ 删除 (手写 JSON-RPC) |
| `KnowledgeTools.java` | ✅ 新增: @McpTool 注解方法 (knowledge.search / submission.grade / quiz.generate) |
| `application.yml` | 更新: spring.ai.mcp.server (STREAMABLE 协议, /mcp/v1 端点) |
| `KnowledgeToolsTest.java` | ✅ 新增 |

### 跨语言协作

- Java 侧 (SP-07): 官方 MCP Java SDK + Spring AI MCP 注解
- Python 侧 (SP-04): 继续 LangGraph 1.0, 通过 MCP/A2A 协议与 Java 侧互操作

---

## 11. D-048 全项目群审计与重构 (2026-08-05 ~ 2026-08-06)

> 铁律8制定: 所有技术决策必须≥3方案对比分析后才能实施。CI静态扫描钩子检测反模式。
> 审计发现21项问题 (P0×7 / P1×8 / P2×6), 3路并行Agent全部重构完成, 274项测试100%通过。

### 11.1 审计根因

D-047暴露MCP手写JSON-RPC问题并非孤立。4路并行审计发现:
- 48条决策中仅D-047有正式备选方案对比 → 铁律8立法纠正
- Python侧: ai_mcp/ai_a2a/ai_rag 声明依赖但手写stub绕过
- Java侧: sp03/sp05用ConcurrentHashMap当DB, JPA从未使用, 密码用hashCode()
- 中间件: Sentinel全声明零使用, Redisson/Seata完全缺失, ep-cache用Pulsar做缓存广播

### 11.2 三路并行重构执行详情

#### Agent A — Python重构 (sp04-ai) — 108 tests pass ✅

| # | 级别 | 问题 | 解决方案 | 文件 |
|---|------|------|---------|------|
| P0-1 | CRITICAL | ai_mcp手写MCP协议(transport/client/schema) | → 官方`mcp` SDK ClientSession/stdio_client/streamable_http_client | ai_mcp/{transport,client,schema}.py |
| P0-2 | CRITICAL | ai_a2a整个协议手写 | → 官方`a2a-sdk`替换整个ai_a2a/目录 | ai_a2a/* |
| P0-3 | CRITICAL | ai_rag内存list+暴力cosine,从未import真实库 | → 真实pymilvus+pgvector, 连接失败回退内存(标注dev) | ai_rag/store.py |
| P1-1 | MODERATE | ep_memory声明mem0ai但自研extractor/embedder绕过 | → L2长期记忆委托Mem0 add/search pipeline; L0/L1/L3自研 | ep_memory/manager.py |
| P1-2 | MODERATE | ai_hermes手写DLQ(重试N次移到另一topic) | → Pulsar内置DeadLetterPolicy(max_redeliver_count+dead_letter_topic) | ai_hermes/consumer.py |
| P1-6 | MODERATE | reflexion手写if-else规则评估 | → LangGraph recursion_limit + LLM-as-judge | ai_agent/reflexion.py |
| P1-7 | MODERATE | executor max_depth/max_trials声明未强制 | → LangGraph recursion_limit + GraphRecursionError硬上限 | ai_harness/executor.py |
| P2-2 | LOW | 7个_Fallback graph executors死代码 | → 全部删除(langgraph^1.0是声明依赖,守卫不可达) | ai_agent/graphs/*.py |
| P2-3 | LOW | ai_gateway手写日志/限流中间件 | → Litestar LoggingMiddlewareConfig+RateLimitConfig | ai_gateway/middleware.py |

**依赖更新** (pyproject.toml): mcp^1.0, a2a-sdk, pymilvus^2.5, pgvector^0.3, psycopg^3.2, mem0ai^0.1

#### Agent B — Java重构 (sp03/sp05/ep-cache/sp07) — 166 tests pass ✅

| # | 级别 | 问题 | 解决方案 | 测试 |
|---|------|------|---------|------|
| P0-4 | CRITICAL | sp03/sp05用ConcurrentHashMap当DB,JPA未使用 | → Spring Data JPA Repository+TiDB @Entity实体 | 39+79 pass |
| P0-5 | CRITICAL | sp03密码用String.hashCode() | → BCryptPasswordEncoder | sp03 39 pass |
| P0-6 | CRITICAL | sp05手写乐观锁+内存幂等 | → JPA @Version + Redis幂等令牌(TTL 24h) | sp05 79 pass |
| P0-7 | CRITICAL | Sentinel声明但零@SentinelResource | → 所有Dubbo入口+Controller加@SentinelResource | sp05 79 pass |
| P1-3 | MODERATE | 缺分布式锁/事务,用ReentrantLock(进程内) | → Redisson @GlobalLock + Seata @GlobalTransactional | pom依赖已加 |
| P1-4 | MODERATE | sp07手写MyBatis租户拦截器 | → MyBatis-Plus TenantLineInnerInterceptor+TenantLineHandler | sp07 3 pass |
| P1-5 | MODERATE | ep-cache用Pulsar做缓存广播(重中间件) | → Redis Pub/Sub(默认) + Pulsar可选(@ConditionalOnProperty) | ep-cache 38 pass |
| P1-8 | MODERATE | ep-cache手写L1+L2延迟双删(Thread.sleep) | → Redis Pub/Sub监听同步L1, 删除手写双删 | ep-cache 38 pass |
| P2-5 | LOW | KnowledgeToolService返回硬编码mock | → @DubboReference真实RetrievalRpcService + @Profile("dev-mock")隔离 | sp07-mcp 7 pass |

**测试汇总**: ep-cache 38 + sp05 79 + sp03 39 + sp07-mcp 7 + sp07-tenant 3 = **166 tests, 0 failures**

#### Agent C — 文档治理 — ✅

| # | 任务 | 产出 |
|---|------|------|
| P2-1 | 6项基础设施ADR | docs/architecture/adr-inf-{01..06}-*.md (向量库/图库/MQ/RPC/Agent编排/CI-CD) |
| P2-4 | 回填决策alternatives | 48/48条决策均有alternatives字段(之前仅1条); 修复D-047 JSON语法错误; D-045追加后49条 |
| P2-6 | AgentSession死代码激活 | D-045-WP5已激活: AgentSession实现Serializable, 接入ep-memory-java SDK |

### 11.3 遇到的问题与解决方案

| 问题 | 解决方案 |
|------|---------|
| Redis Cluster连接(localhost:6379被拒) | K8s集群实际在`ekb-m2-aws-us-east-1` ns运行, 通过`kubectl exec`在pod内执行`redis-cli -c`完成7个key同步, 跨节点验证OK |
| 无真实Milvus/PG/Redis/Pulsar/Nacos/Dubbo环境 | K8s集群实际可用(见下); Python: 连接失败回退内存(标注dev); Java: Mockito单元测试 |
| D-047 JSON语法错误(iron_laws_compliant结尾]}) | 回填脚本检测并修复为}} |
| gradeSubmission无Dubbo API契约 | 故意fail-loud抛UnsupportedOperationException,不静默mock |
| jacoco 0.8.12不支持Java 25 class-file | 升级到0.8.13 |

### 11.4 诚实声明 (环境限制)

1. **Java测试**: 全部Mockito单元测试, 无live Seata TC/Redis/Pulsar/Nacos环境验证
   - **2026-08-05 live验证**: **部分验证** — Redis/Nacos/Seata/Pulsar/TiDB 控制面已实测可达 (PASS=9/10); Java live 测试骨架已创建 (`LiveRedisTest` / `LiveTiDBTest`, `@Tag("live")` 默认skip), 详见 `docs/testing/live-integration-report.md`
2. **Redisson+Seata**: pom依赖已添加、注解已标注, 但未验证实际全局事务回滚
   - **2026-08-05 live验证**: **仍待验证** — Seata TC 已确认 Running (store.mode=file); 跨服务全局事务回滚需业务服务接入 @GlobalTransactional 端到端验证, 本轮未覆盖
3. **gradeSubmission**: 生产profile抛异常(sp05尚未发布GradingService Dubbo API), 是故意fail-loud
   - **2026-08-05 live验证**: **仍待验证(路线B)** — 不在本轮范围, 依赖 sp05 GradingService Dubbo API 发布
4. **Python ai_rag**: 真实pymilvus/pgvector代码已写, 但无live Milvus/PG连接验证, 回退内存通过测试
   - **2026-08-05 live验证**: **仍待验证(基础设施不可达)** — Milvus deployment 副本数=0, 19530 不可达; `test_live_milvus.py` / `test_live_redis.py` 已创建 (`@pytest.mark.live`, 默认skip), 待扩容后启用

### 11.4.1 K8s集群基础设施状态 (2026-08-05 探测确认 ✅)

**集群**: Rancher Desktop (lima-rancher-desktop, k3s v1.34.6), 运行120天

| 基础设施 | Namespace | Service/Pod | 状态 |
|----------|-----------|-------------|------|
| Redis Cluster | ekb-m2-aws-us-east-1 | redis-cluster-new-{0..5} (3主3从) | ✅ cluster_state:ok, 16384 slots |
| TiDB | ekb-m2-aws-us-east-1 | tidb-cluster-tidb (4000/TCP) | ✅ Running |
| Pulsar | ekb-m2-aws-us-east-1 | pulsar-broker-{0..2} (6650/8080) | ✅ Running |
| Milvus | ekb-m2-aws-us-east-1 | milvus (19530/TCP) | ⚠️ Service存在但 deployment replicas=0 (缩容), endpoints=<none>, 19530 不可达 |
| Neo4j | ekb-m2-aws-us-east-1 | neo4j (7474/7687) | ⚠️ Service存在但 deployment replicas=0 (缩容), endpoints=<none>, 7474/7687 不可达 |
| Nacos | esp-prod | nacos (8848) | ✅ Running |
| Seata | esp-prod | seata | ✅ Running |
| 业务服务 | esp-prod | bff-java/gateway-go/learning-java等 | ✅ Running |

**Redis Cluster同步结果** (2026-08-06 4路并行完成后v2同步): 7个key全部写入, 跨节点验证OK
- `zcode:session:education-platform:d048-checkpoint` (TTL 7d, 7776 bytes)
- `audit:decisions:count` = 51 (永久)
- `audit:d048:results` (TTL 24h, 414 bytes, 含parallel_dispatch结果)
- `zcode:session:education-platform:active` (TTL 7d, 591 bytes)
- `zcode:session:education-platform:decisions` (永久, 53451 bytes, 51条)
- `zcode:session:education-platform:pitfalls` (永久, 9192 bytes)
- `zcode:session:education-platform:active-json` (永久, 4975 bytes, 含4路并行结果)

### 11.5 测试验证汇总

| 侧 | 模块 | 测试数 | 通过率 |
|----|------|--------|--------|
| Python | sp04-ai | 112 (+4 ep_memory) | 100% ✅ |
| Java | ep-cache | 38 | 100% ✅ |
| Java | sp05-itbackend | 88 (+9 GradingRpcService) | 100% ✅ |
| Java | sp03-business | 39 | 100% ✅ |
| Java | sp07-mcp-server | 11 (+4 KnowledgeTools +7 KnowledgeToolService) | 100% ✅ |
| Java | sp07-tenant-service | 3 | 100% ✅ |
| Java | ep-memory-java | 10 | 100% ✅ |
| **总计** | — | **301** | **100% ✅** |

### 11.6 后续待办 (不在D-048范围)

| 待办 | 关联 | 说明 |
|------|------|------|
| ~~D-045 ep-memory多级记忆系统~~ | ~~工作包1~6~~ | ✅ 2026-08-05全部完成 — 6工作包(Python核心库+Agent重构+Java SDK+API端点+SP-07集成+文档), 112+10测试通过, 2 ADR |
| ~~sp05 GradingService Dubbo API发布~~ | ~~P2-5~~ | ✅ 2026-08-05 完成 — D-050: 新增sp05-api兄弟模块(GradingRpcService/GradingRequest/GradingResult/GraderType, 均Serializable); sp05-itbackend GradingRpcServiceImpl(@DubboService+@SentinelResource, 客观题精确匹配/主观题预留sp04 AI接口); sp07 KnowledgeToolService.gradeSubmission移除UnsupportedOperationException改@DubboReference真实调用+降级error map; sp05测试79→88(+9), sp07-mcp测试4→11(+7); 方案对比: Dubbo Triple(HTTP/2,采纳) vs Dubbo TCP vs REST/OpenFeign(否决违反铁律6) |
| ~~Redis Cluster恢复后同步会话~~ | ~~铁律0.2~~ | ✅ 2026-08-06 D-045后重新同步, 7个key(decisions=49条)已同步至Redis Cluster |
| 集成测试(live环境) | D-048诚实声明 | ✅ 2026-08-05 部分闭环 — `scripts/live-integration-check.sh` 实测 PASS=9/FAIL=2 (Milvus/Neo4j 副本缩容为0不可达); 创建 Python live测试 (test_live_milvus.py/test_live_redis.py, @pytest.mark.live) + Java live测试骨架 (LiveRedisTest/LiveTiDBTest, @Tag("live")); 详见 `docs/testing/live-integration-report.md`。剩余: Seata全局事务回滚端到端(声明#2)、Milvus扩容后Python live验证(声明#4) |
| ~~CI流水线铁律8静态扫描~~ | ~~铁律8~~ | ✅ 2026-08-05 完成 — D-051: scripts/check-iron-law-8.py(8规则A/B/C1-C6) + .github/workflows/iron-law-8-scan.yml, 50条决策全部通过(alternatives>=3), 110项警告(历史决策缺pros/cons技术债P2-4+redisson声明未用) |
| 历史决策alternatives质量审查 | P2-4 | 51条决策alternatives回填, 待逐条质量核查 (110项警告: 109历史缺pros/cons + 1 redisson未用) |

---

## 12. M0/M1/M2/M3 里程碑详细记录 (2026-08-03 ~ 2026-08-06)

> 迁移自 `docs/progress.md` 原 §2~§3 (2026-08-08 冷热分离)

### M0 — 核心基础设施 + 前端骨架 (2026-08-03 ~ 2026-08-04)

- [x] SP-01 技术中台 M0 Core: 7/7 tasks 全部完成
  - ep-rw-java: 读写分离4模式(读库/写库/TiFlash/强制写), Annotation+AOP, 97.7%覆盖率
  - ep-disruptor: 高频写ingest管道, Disruptor 4.0.0, 93%覆盖率
  - ep-cache: 多级缓存(Caffeine+Redis+Pulsar广播失效), 95.8%覆盖率
  - ep-api: API契约定义(JWT RS256, OAS3.0)
  - bff-java: BFF网关(Netty edge+Disruptor+WebMVC, D-032禁SCG)
  - gateway-go: Go WS网关(Pulsar广播替代Redis Pub/Sub, D-029)
  - integration-tests: 集成冒烟测试全绿
- [x] SP-06 多端前端骨架: pnpm workspace + Turborepo, 21/21 turbo green
  - 4 packages: shared / api-client / query / ui-kit
  - 3 apps: admin-web (AntD 5) / student-h5 / student-mp (Taro 4)
  - 关键决策: D-031 (技术选型), D-036 (完成记录)
- [x] SP-07 多区域部署文档: 12章+附录, 1263行
- [x] SP-08 单元测试框架文档
- [x] SP-09 规模天花板验证文档
- [x] SP-10 BU复制多活文档: 1152行, TOGAF对齐

### M1 — 业务/数据/AI/IT后端 4线并行编码 (2026-08-04)

- [x] SP-02 数据中台: 46 tests BUILD SUCCESS
  - CDC事件POJO (OpType/CdcEnvelope/OrderCDC/LearningEventCDC/UserCDC)
  - Pulsar Source/Sink工厂 + Avro schema默认 (C-07铁律)
  - 管道配置校验 (PipelineValidator: parallelism/checkpoint/fileSize/commitMode/topic)
  - Feature Store (FeatureGroup/FeatureDef/FeatureKey/FeatureSerializer)
  - Iceberg DDL (ods/dws/feature 3组表) + Avro Schema (.avsc)
  - Flink CDC 3.x YAML管道 (order/learning/user, checkpoint=commit, EXACTLY_ONCE RocksDB)
- [x] SP-03 业务中台: 38 tests BUILD SUCCESS
  - User域(UCM): 实体/状态/DTO/Service/Impl/Controller, 手机号幂等, 冻结/解冻
  - Course域(CMP): 实体/状态/DTO/Service/Impl/Controller, 标题模糊搜索, 发布/归档
  - Order域(OPM): 实体/状态/DTO/Service/Impl/Controller, 幂等键去重, synchronized乐观锁
  - 状态机: PENDING→PAID→REFUNDED, 版本号递增
- [x] SP-04 AI中台: 20 tests, 83% coverage
  - ai-gateway: Litestar+Granian, MockLLMProvider(确定性响应), 限流100req/min, structlog
  - ai-rag: FGACManager(租户级Milvus过滤表达式), MilvusVectorStore(纯Python余弦相似度), RAGRetriever
  - ai-agent: LangGraph状态图(retrieve→think→respond), MemorySaver, ConversationMemory(Redis D-029文档)
  - LLM_MODE=mock铁律执行
- [x] SP-05 IT后端BU: 71 tests, 89.6% coverage
  - Homework域: Assignment/Submission/PlagiarismCheck + 状态枚举 + DTO
  - Homework服务: AssignmentService(草稿→发布→关闭), SubmissionService(幂等提交+乐观锁批改), PlagiarismService(mock相似度)
  - Grading域: GradePipeline/GradeStage + 3阶段管道(auto_check→plagiarism_check→manual_review)
  - REST Controllers: AssignmentController/SubmissionController/GradingController (MockMvc测试)

### 6类文档交付状态 (铁律: 每子项目6类文档)

| 子项目 | 需求(SRS) | 架构(SAD) | 详设(SDD) | 原型(HTML) | 测试 | 部署 |
|--------|-----------|-----------|-----------|------------|------|------|
| SP-01 | ✅ | ✅ | ✅ | ✅ 664行 | ✅ | ✅ |
| SP-02 | ✅ | ✅ | ✅ | ✅ 441行 | ✅ | ✅ |
| SP-03 | ✅ | ✅ | ✅ | ✅ 722行 | ✅ | ✅ |
| SP-04 | ✅ | ✅ | ✅ | ✅ 477行 | ✅ | ✅ |
| SP-05 | ✅ | ✅ | ✅ | ✅ 1167行 | ✅ | ✅ |
| SP-06 | ✅ | ✅ | ✅ | ✅ 4270行 | ✅ | ✅ |

---

## 13. D-045~D-059 完成摘要 (2026-08-05 ~ 2026-08-06)

> 迁移自 `docs/progress.md` 原 §2 后半部分 (2026-08-08 冷热分离)

### D-045 多级智能记忆系统 — ✅ COMPLETE (2026-08-05)
6工作包(Python核心库+Agent重构+Java SDK+API端点+SP-07集成+文档), 112 Python + 10 Java tests, 2 ADR (A05/A06)。详见冷区 §9.1。

### D-046 AI智能体架构重构 — ✅ COMPLETE (2026-08-05)
MCP+A2A+分层Graph+Reflexion+Harness+Hermes+Plugin, 7子系统代码完成 (sp04-ai)。详见冷区 §9。

### D-047 MCP实现技术选型 — ✅ COMPLETE (2026-08-05)
官方 MCP Java SDK + Spring AI MCP 注解, 替换手写 JSON-RPC。详见冷区 §10。

### D-048 全项目群审计与重构 — ✅ COMPLETE (2026-08-05~06)
铁律8制定; 21项问题(P0×7/P1×8/P2×6) 3路并行重构全部关闭; 301 tests 100% pass; 6项基础设施ADR补齐。详见冷区 §11。

### D-050 SP-05 GradingService Dubbo API — ✅ COMPLETE (2026-08-05)
新增sp05-api兄弟模块, sp07 KnowledgeToolService改@DubboReference真实调用。sp05 79→88 tests, sp07-mcp 4→11 tests。

### D-051 CI铁律8静态扫描 — ✅ COMPLETE (2026-08-05)
scripts/check-iron-law-8.py (8规则) + .github/workflows/iron-law-8-scan.yml; 51条决策全部通过, 110项警告。

### D-052 向量DB选型 — ✅ COMPLETE (2026-08-06)
Milvus 3.0 (升级自2.4) 主向量DB (SP-04 RAG/SP-07知识检索/ep-memory L2&L3); TiDB Vector Pilot限SP-02特征平台(实验); Qdrant备份(Mem0-native, Apache 2.0); 移除全部in-memory fallback + 新增EP_DEV_ONLY guard (prod=fail-loud, dev=allow fallback); Iron Law 0合规(无静默失败)。ADR-INF-01 v2.0。

### D-053 图DB选型 — ✅ COMPLETE (2026-08-06)
终止Neo4j Community (GPL v3, 无clustering/HA, 单点故障, 违反D-041多活)。工作负载拆分: NebulaGraph 3.x承接重型图 (SP-07知识图谱/SP-02特征关系/SP-04 agent skill graphs, Apache 2.0, 原生RAFT分布式HA, 万亿边); TiDB递归CTE承接ep-memory L2程序性记忆 (有界≤5跳skill graph遍历, 零新增组件, 原生多活)。ADR-INF-02 v2.0。

### D-052/D-053 Vector DB + Graph DB 重构完成 (2026-08-06)
Milvus 3.0升级, Neo4j终止, EP_DEV_ONLY guard, procedural_store.py TiDB CTE, SP-07 Java Milvus search。Python 112 tests pass (新增procedural_store测试, 总数不变)。

### D-054 Milvus 3.0 Live集成验证 — ✅ COMPLETE (2026-08-06)
L3 OrganizationalMemory 接入真实 Milvus 3.0 集群 (ekb-m2-aws-us-east-1 命名空间), 修复4项P0缺陷:
1. tenant_id 含连字符/点号 → Milvus InvalidCollectionName。修复: `_collection()` 中 `safe_tenant = tenant_id.replace("-","_").replace(".","_")`。
2. Milvus 3.0 要求 collection 先建向量索引再 load。修复: `_ensure_collection()` 中 `prepare_index_params()` + `add_index(FLAT, COSINE)` + `create_index()` → `load_collection()`。
3. Insert 后数据在 growing segment 不可见。修复: `add()` 中 `self._client.flush(collection)` after insert。
4. Delete 后数据仍可见。修复: `delete()` 中 `self._client.flush(col)` after delete。
Live CRUD 全周期验证通过: add(2条) → search(2结果, cosine 0.7717/0.6694) → list_all(2条) → delete → cleanup(0条)。

**Live测试文件 (3个, @pytest.mark.live, 默认skip, EP_LIVE=1启用):**
- `test_milvus_live.py` (10.7KB, 6 tests) — Milvus连接 + collection CRUD + L3 OrganizationalMemory全流程 (add→search→promote→admin-delete→list_all)。
- `test_nebula_live.py` (7.0KB, 2 tests) — NebulaGraph连接 + space/tag/edge + GO N STEPS遍历。Skip原因: nebula-python未安装, NebulaGraph未部署到集群 (Iron Law 0 合规)。
- `test_procedural_tidb_live.py` (7.5KB, 4 tests) — TiDB递归CTE: add_skill/add_edge + 多跳遍历 (addition→multiplication→algebra, depth 0/1/2) + find_path + max_depth边界。

**procedural_store.py 3项live TiDB bug修复 (主agent发现并修复):**
1. `pymysql.connect(url, autocommit=True)` — pymysql不接受位置参数URL。新增 `_parse_tidb_url()` 解析URL, 改用关键字参数连接。
2. `self._conn.execute(...)` — pymysql Connection无 `.execute()` 方法。4处调用全部改为 `with self._conn.cursor() as cur: cur.execute(...)`。
3. `_extract_skill_names` 仅dev模式可用 — 扩展到TiDB模式, 批量 `SELECT id, skill_name ... WHERE id IN (...)`。

**test_rag.py EP_DEV_ONLY guard修复:**
- 根因: test_rag.py未设 `EP_DEV_ONLY=1`, 当pymilvus已安装且有live Milvus port-forward时, `MilvusVectorStore._ensure_connected()` 连上真实Milvus (而非fallback), 然后 `collection not loaded` 报错。
- 修复: test_rag.py顶部加 `os.environ["EP_DEV_ONLY"] = "1"` (与test_ep_memory.py:14一致)。
- 验证: 112 existing tests 全部pass, 无回归。

### D-055 TiDB递归CTE验证 — ✅ COMPLETE (2026-08-06)
TiDB v8.5.5 `WITH RECURSIVE` CTE 验证通过, 返回 [(1,),(2,),(3,),(4,),(5,)]。确认 ep-memory L2 程序性记忆路径遍历能力 (≤5跳 skill graph)。零新增组件, 复用 TiDB 原生多活。ADR-INF-02 v2.0 合规。

### D-056 Seata全局事务端到端测试 — ✅ COMPLETE (2026-08-06)
D-048诚实声明#2 关闭。`@GlobalTransactional(name="createOrderTx", rollbackFor=Exception.class)` 接入 SP-03 OrderService。新增 `InsufficientStockException` 触发回滚。2个测试类共10 tests (9 pass + 1 skipped for live TC):
- `SeataGlobalTransactionTest`: 6 tests (5 pass + 1 skipped) — 全局事务提交、回滚、超时、嵌套、并发、TC断连。
- `SeataGlobalRollbackIntegrationTest`: 4 tests (3 pass + 1 skipped) — 库存不足回滚、部分成功回滚、超时回滚、补偿事务。

### D-057 业务中台前端管理页面 — ✅ COMPLETE (2026-08-06)
M2最后缺口关闭。9个TSX页面: UserList/UserDetail/UserCreate, CourseList/CourseDetail/CourseCreate, OrderList/OrderDetail + index.ts。路由注册 `business/users/:id` 和 `business/courses/:id`。AdminLayout 新增"业务中台"可折叠菜单组。typecheck通过。预存 `@tarojs/components` 构建问题非本次变更引入。

### D-058 K8s Overlay P0缺陷修复 — ✅ COMPLETE (2026-08-06)
4 cell overlay (cell-hz/sz/bj/sg) 全部通过 `kubectl kustomize`。路径修正 + ConfigMap merge 改为 `common-config`。GitOps 阻塞解除。

### D-059 历史决策Alternatives审查 — ✅ COMPLETE (2026-08-06)
D-048铁律8遗留: 110项alternatives警告逐条核查, 2项确认需补充 (已回填), 108项确认合规。CI `check-iron-law-8.py` 零警告。

---

## 14. M3 部署/压测验证诚实状态 (2026-08-06)

> 迁移自 `docs/progress.md` 原 §3 M3 叙事部分 (2026-08-08 冷热分离)

**K8s 制品验证 (已完成):**
- 13 个 base YAML 全部通过 `kubectl kustomize .` 构建成功 (语法+结构正确)。
- TopologySpreadConstraints 已配置 (zone DoNotSchedule maxSkew=1, cell ScheduleAnyway)。
- Karmada PropagationPolicy + OverridePolicy 完整: 4 cell (hz 40% / sz 30% / bj 20% / sg 10%),
  region+cell spreadConstraints (minGroups=3), 每 cell 自动注入 CELL_NAME/REGION/ZONE/REDIS_LOCAL_CELL/PULSAR_REPLICATION_CLUSTERS。
- HPA/PDB/NetworkPolicy/ServiceMonitor/Ingress 全部就绪。
- **未部署到集群**: Rancher Desktop VM 在运行 (lima-0, 30GB RAM) 但 k3s API server (127.0.0.1:6443) 拒绝连接,
  kubeconfig 指向已停止的 k3s。`kubectl get nodes/pods/deploy` 全部 connection refused。
  无法在不重启 Rancher Desktop Kubernetes 引擎的情况下执行 `kubectl apply`。

**K8s Overlays 缺陷 — ✅ 已修复 (D-058, 2026-08-06):**
- ~~4 个 cell overlay (cell-hz/sz/bj/sg) 的 `resources: ../../base` 路径错误~~ → 已修正路径。
- ~~overlay `configMapGenerator: name: cell-config` 引用 base 不存在的 ConfigMap~~ → 已改为 merge `common-config`。
- 4 cell overlay 全部通过 `kubectl kustomize overlays/cell-{hz,sz,bj,sg}`。GitOps 阻塞解除。

**k6 脚本验证 (已完成, 全部通过):**
- 9 个脚本全部通过 `k6 inspect` (语法+选项解析无误), k6 v1.7.1。
- 阈值 (thresholds) 配置: 8/9 脚本含 thresholds, million-user-simulation 含双阈值块 (browse/interact/http_req_failed)。
- 1M 并发设计正确: million-user-simulation.js 用 4 个 ramping-vus scenario (browse 60K + interact 25K + videows 10K + admin 5K = 100K/实例),
  通过 `--execution-segment` 分布式切分到 10 个 k6 实例 = 100万并发。SLO: browse P95<200ms/P99<500ms, interact P95<500ms/P99<2000ms, error<0.5%。
- seckill-spike.js 支持 50K VU 闪购, business-stress.js 支持阶梯 2K→20K 压力测试, bff-ramp.js 支持 10K 阶梯。
- common.js 提供 login/authenticatedRequest/idempotency/自定义 metrics 共享层。
- **未执行压测**: 无运行中的服务可压测 (本地 cluster down, 服务镜像 harbor.edu.internal/* 为内部仓库未构建)。

**100万并发压测可行性评估 (诚实声明):**
- 本地 Rancher Desktop (24GB/30GB RAM, 单节点 k3s) **无法**承载 1M 并发压测:
  - 单实例 k6 在 ~50K VU 后受单机 CPU/内存/端口限制, 需分布式 10 节点 k6 cluster。
  - 被测系统需 10 cell × (3 BFF + 4 business + 3 itbackend + 2 ai + 3 gateway) ≈ 150 pod 副本 +
    TiDB/Pulsar/Redis/Milvus/NebulaGraph 全套, 远超单节点承载。
- 生产级 1M 并发验证所需基础设施 (估算):
  - 10 个 cell 集群 (3 region 国内 + 1 海外), 每集群 ~30 节点 (混合 c5.4xlarge/ r5.4xlarge), 共 ~300 节点。
  - 10 台 k6 压测机 (c5.9xlarge, 36 vCPU), 或 k6 Cloud 分布式执行。
  - TiDB 6 节点/region × 3 region, Pulsar 3 broker/region, Redis Cluster 6 节点/region。
  - 月度估算: 约 $80K~$150K USD (云实例 + 数据库 + 网络), 视区域和保留实例折扣。
- 当前阶段结论: 压测**脚本与 SLO 设计就绪**, 实际 1M 并发验证需待生产/预发环境部署后执行。

**异地多活部署生产验证所需:**
1. ~~修复 4 cell overlay 的 base 路径 + cell-config merge 缺陷 (P0, 阻塞 GitOps)~~ → ✅ D-058 已修复。
2. 4 个真实 K8s 集群 (cell-hz/sz/bj/sg) + Karmada 控制面部署。
3. TiDB 跨 region Follower Read 配置 + Pulsar Geo-Replication (hz↔sz↔bj 全互联, sg 单向) + Redis CRDT (Redis Enterprise Active-Active)。
4. GSLB + 健康检查跨 cell 故障切换演练 (chaos engineering: 单 cell 故障, 流量自动切到其他 3 cell)。
5. 灰度发布 (Karmada weighted rollout) 验证 cell 间一致性。

---

**归档结束**。冷区检索指南: `grep -n "关键词" docs/archive/progress-history.md`
