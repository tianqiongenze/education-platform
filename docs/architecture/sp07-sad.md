# SP-07 知识中台 软件架构设计(SAD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp07` for reference continuity. Per D-137, sp07 → knowledge-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP07-SAD | v1.1 | 2026-08-06 | 正式 | 知识中台架构组 | 架构委员会 |

> 关联：顶层 spec §6；决策 D-019 / D-033 / D-039 / D-040 / D-041 / D-042 / D-043 / D-045 / D-047；ADR-A02 / ADR-A05 / ADR-A06 / ADR-A07
> 依据：SP-07-SRS v1.0 + SP-07-SDD v1.0 + TOGAF 顶层架构 v1.4

---

## 目录
1. [架构概述](#1-架构概述)
2. [六层架构总览](#2-六层架构总览)
3. [组件视图](#3-组件视图)
4. [部署视图](#4-部署视图)
5. [数据视图](#5-数据视图)
6. [ADR矩阵](#6-adr矩阵)
7. [SP-04 协作架构](#7-sp-04-协作架构)
8. [D-047: MCP实现技术选型](#8-d047-mcp实现技术选型)
9. [D-045: 多级智能记忆集成](#9-d045-多级智能记忆集成)
10. [非功能属性](#10-非功能属性)
11. [风险与对策](#11-风险与对策)

---

## 1. 架构概述

### 1.1 定位

SP-07 知识中台是 education-platform 项目群的企业级 AI 知识基础设施，向上为 SP-05 各业务线（考公/考研/养生事业群）提供知识检索、智能问答、Agent 编排能力，向下调用 SP-04 AI 中台的 LLM/Embedding/Reranker 模型服务。

### 1.2 设计目标

| 目标 | 量化指标 |
|------|----------|
| 知识统一治理 | 文档入池延迟 P95 < 30s |
| 高精度检索 | Top-5 命中率 ≥ 92% |
| 智能体编排 | 端到端 P95 < 4s |
| 多租户 SaaS | 1000+ 租户，10w QPS |
| 弹性扩展 | RTO < 60s，RPO < 5s |
| 低成本运行 | 启动 < 1s（GraalVM native），缓存命中率 ≥ 80% |

### 1.3 架构约束

| 约束 ID | 内容 | 来源 |
|---------|------|------|
| D-033 | Redis 缓存：热门问答/Embedding/会话上下文；双写一致性 via Pub/Sub | 铁律3 |
| D-039 | 虚拟线程：IO 密集型全程 `Thread.ofVirtual()`，禁用 `synchronized` | 铁律7 |
| D-040 | GraalVM Native Image：sp07-bff / sp07-api 强制 native；service 兼容 | 铁律7 |
| D-041 | 多区域多活：三中心单元化部署，租户级路由 + 异步复制 | — |
| D-042 | AI 驱动决策：检索/推荐/编排全面 AI 化 | — |
| D-045 | ep-memory 多级记忆系统接入 | ADR-A05/A06 |
| D-047 | 官方 MCP Java SDK + Spring AI MCP 注解 | ADR-A07 |
| 版本对齐 | Spring Boot 4.0.7、SCA 2025.1.0.0、Dubbo 3.3.6、JDK 21 | 全局基线 |

---

## 2. 六层架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                     SP-07 知识中台 六层架构                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ⑥ 接入层 (sp07-bff / sp07-api)                                     │
│     Spring Cloud Gateway | GraalVM Native | REST + WebSocket         │
│     职责: 路由/限流/鉴权/协议转换                                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ⑤ 智能体编排层 (sp07-agent-service)                                 │
│     LangGraph编排(调SP-04) | AgentSession管理 | ep-memory接入        │
│     职责: 多模式Agent(路由/流水线/辩论) | 会话记忆 | 工具调用          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ④ MCP 工具层 (sp07-mcp-server)                                      │
│     官方MCP Java SDK 2.0.0 | @McpTool注解 | Streamable HTTP          │
│     职责: 知识检索工具暴露 | MCP 2.0协议合规 | 工具自动注册            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ③ 检索引擎层 (sp07-retrieval-service)                               │
│     混合检索(向量+BM25) | Reranker | RRF融合 | RAG Pipeline          │
│     职责: 召回→重排→引用溯源 | 语义缓存(Redis)                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ② 知识库管理层 (sp07-knowledge-service)                             │
│     文档管道(解析→分块→向量化) | 版本管理 | 知识图谱(Neo4j)          │
│     职责: 多格式解析 | Iceberg冷存 | 增量更新 | 图谱构建              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ① 多租户基座层 (sp07-tenant-service)                                │
│     MyBatis-Plus租户拦截器 | 配额/计费 | 开放API | 三级隔离           │
│     职责: 行级/Schema/独立DB隔离 | 租户路由 | 资源配额                │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1 模块清单

| 层 | 模块 | 职责 | 文件数 | 测试数 |
|----|------|------|--------|--------|
| 接入层 | sp07-bff | Web/H5/MP 聚合网关 | 2 | 1 |
| 接入层 | sp07-api | 对外开放API (Dubbo契约) | 12 | 0 |
| 智能体 | sp07-agent-service | Agent编排+会话+记忆 | 9 | 1 |
| MCP工具 | sp07-mcp-server | MCP 2.0协议工具暴露 | 5 | 2 |
| 检索引擎 | sp07-retrieval-service | 混合检索+RAG | 7 | 2 |
| 知识库 | sp07-knowledge-service | 文档管道+图谱 | 8 | 1 |
| 多租户 | sp07-tenant-service | 隔离+配额+计费 | 12 | 1 |

---

## 3. 组件视图

### 3.1 核心交互序列 — RAG 问答

```
用户提问
   │
   ▼
sp07-bff (Gateway) ──── 鉴权+租户路由 ────▶ sp07-agent-service
                                                  │
                    ┌─────────────────────────────┤
                    │                             │
                    ▼                             ▼
          ep-memory (L0即时记忆)         sp07-mcp-server (MCP工具)
          Redis: 最近10轮对话            @McpTool: knowledge_search
                    │                             │
                    │ 注入上下文                    │ 调用
                    ▼                             ▼
          sp07-agent-service ◀─── 检索结果 ── sp07-retrieval-service
                    │                             │
                    │                             ├─ Redis语义缓存 (D-033)
                    │                             ├─ Milvus向量召回
                    │                             ├─ BM25关键词召回
                    │                             └─ Reranker (调SP-04)
                    │
                    │ LLM生成 (Dubbo→SP-04)
                    ▼
          sp07-bff ──── 引用溯源回答 ────▶ 用户
                    
                    │ 异步
                    ▼
          ep-memory: L0→L1摘要压缩→L2长期记忆提取(Mem0)
```

### 3.2 核心交互序列 — 文档入池

```
文档上传
   │
   ▼
sp07-knowledge-service
   ├─ 1. 格式解析 (PDF/Word/Markdown/PPT)
   ├─ 2. 分块 (语义分块, overlap 10%)
   ├─ 3. 向量化 (Dubbo→SP-04 Embedding)
   ├─ 4. 存入 Milvus (collection: kb_{tenant}_{doc_id})
   ├─ 5. 知识图谱构建 (Neo4j: 实体+关系抽取)
   ├─ 6. Iceberg冷存 (原始文档+分块元数据)
   └─ 7. Redis缓存刷新 (D-033 Pub/Sub通知)
```

---

## 4. 部署视图

### 4.1 多区域多活 (D-041)

```
         ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
         │  北京 Cell    │     │  上海 Cell    │     │  深圳 Cell    │
         │  (主)         │     │  (主)         │     │  (主)         │
         ├──────────────┤     ├──────────────┤     ├──────────────┤
         │ sp07-bff     │     │ sp07-bff     │     │ sp07-bff     │
         │ sp07-agent   │     │ sp07-agent   │     │ sp07-agent   │
         │ sp07-mcp     │     │ sp07-mcp     │     │ sp07-mcp     │
         │ sp07-retriev │     │ sp07-retriev │     │ sp07-retriev │
         │ sp07-knowled │     │ sp07-knowled │     │ sp07-knowled │
         │ sp07-tenant  │     │ sp07-tenant  │     │ sp07-tenant  │
         ├──────────────┤     ├──────────────┤     ├──────────────┤
         │ Milvus       │     │ Milvus       │     │ Milvus       │
         │ Neo4j        │     │ Neo4j        │     │ Neo4j        │
         │ Redis Cluster│     │ Redis Cluster│     │ Redis Cluster│
         └──────┬───────┘     └──────┬───────┘     └──────┬───────┘
                │                    │                    │
                └────────────────────┼────────────────────┘
                                     │
                           异步复制 (Pulsar跨cell)
                           RTO < 60s, RPO < 5s
```

### 4.2 K8s部署拓扑 (当前)

| 组件 | Namespace | 副本数 | 资源限制 |
|------|-----------|--------|----------|
| sp07-bff | esp-prod | 3 | 2C/4Gi |
| sp07-agent-service | esp-prod | 3 | 4C/8Gi |
| sp07-mcp-server | esp-prod | 2 | 2C/4Gi |
| sp07-retrieval-service | esp-prod | 3 | 4C/8Gi |
| sp07-knowledge-service | esp-prod | 2 | 2C/4Gi |
| sp07-tenant-service | esp-prod | 3 | 2C/4Gi |
| Milvus | ekb-m2-aws-us-east-1 | 3 | 8C/16Gi |
| Neo4j | ekb-m2-aws-us-east-1 | 1 | 4C/8Gi |
| Redis Cluster | ekb-m2-aws-us-east-1 | 6 (3主3从) | 4C/8Gi |

---

## 5. 数据视图

### 5.1 存储分工

| 存储引擎 | 用途 | 数据特征 | 隔离方式 |
|----------|------|----------|----------|
| **TiDB** | 业务关系数据(租户/配额/计费/社区/学习) | ACID, 结构化 | 行级 (tenant_id) |
| **Milvus** | 文档向量(分块embedding) | 高维向量, ANN检索 | collection命名 (kb_{tenant}_*) |
| **Neo4j** | 知识图谱(实体+关系) | 图结构, 多跳查询 | graph property (tenant_id) |
| **Redis Cluster** | L0/L1记忆 + 语义缓存 + 热门问答 | KV, 低延迟 | key前缀 (mem:{tenant}:*) |
| **Iceberg** | 原始文档+分块冷存 | 列式, 批量分析 | partition (tenant_id) |
| **Pulsar** | 事件流(文档变更/Agent事件) | 日志, 削峰 | topic (persistent://{tenant}/...) |

### 5.2 ep-memory 多级记忆数据流 (D-045)

| 层级 | 存储 | Key/Collection | TTL | 用途 |
|------|------|----------------|-----|------|
| L0 即时 | Redis Cluster | `mem:imm:{tenant}:{session}` | 24h | 最近10轮对话 |
| L1 工作 | Redis Cluster | `mem:work:{tenant}:{user}` | 7d | 会话摘要+任务上下文 |
| L2 情景 | Milvus | `mem_episodic` | 30d降权/90d归档 | 经验记忆向量检索 |
| L2 语义 | TiDB | `mem_facts` | 30d降权/90d归档 | 结构化事实 |
| L2 程序 | Neo4j | `mem_procedural` | 30d降权/90d归档 | 实体关系图谱 |
| L3 组织 | Milvus+Neo4j | `mem:org:{tenant}` | 永不过期 | 租户级知识沉淀 |

---

## 6. ADR矩阵

| ADR | 标题 | 决策 | 状态 |
|-----|------|------|------|
| ADR-A02 | FGAC 单点租户注入 | MyBatis-Plus TenantLineInnerInterceptor | ✅ 已落地 |
| ADR-A05 | 多级智能记忆系统 | L0~L3 四级架构, Redis/Milvus/TiDB/Neo4j | ✅ 已落地(D-045) |
| ADR-A06 | Mem0 框架集成 | L2长期记忆委托Mem0, 自定义后端 | ✅ 已落地(D-045) |
| ADR-A07 | MCP 协议接入 | 官方MCP Java SDK 2.0.0 + Spring AI MCP | ✅ 已落地(D-047) |
| ADR-INF-01 | 向量库选型 | Milvus 2.5+ | ✅ 已落地 |
| ADR-INF-02 | 图库选型 | Neo4j Community | ✅ 已落地 |
| ADR-INF-03 | 消息队列 | Pulsar 3.x | ✅ 已落地 |
| ADR-INF-04 | RPC框架 | Dubbo 3.3.6 | ✅ 已落地 |
| ADR-INF-05 | Agent编排 | LangGraph 1.0 (Python侧, 通过Dubbo调用) | ✅ 已落地 |
| ADR-INF-06 | CI/CD铁律8扫描 | GHA + Python脚本 | 🔄 实现中 |

---

## 7. SP-04 协作架构

### 7.1 调用关系

```
SP-07 知识中台                         SP-04 AI中台
─────────────                         ─────────────
sp07-agent-service ─── Dubbo ────────▶ AI Gateway (Litestar)
  │                                     ├─ LLM生成 (chat/completions)
  │                                     ├─ Embedding (embeddings)
  │                                     └─ Reranker (rerank)
  │
sp07-retrieval-service ── Dubbo ─────▶ AI Gateway
  │                                     └─ Reranker
  │
sp07-knowledge-service ── Dubbo ─────▶ AI Gateway
  │                                     └─ Embedding
  │
sp07-agent-service ─── ep-memory ────▶ Memory Gateway (Litestar)
  │   (Java SDK)                        ├─ POST /v1/memory/add
  │                                     ├─ GET  /v1/memory/search
  │                                     └─ GET  /v1/memory/context
  │
sp07-mcp-server ──── MCP 2.0 ────────▶ SP-04 Python Agent
  │   (Streamable HTTP)                 └─ LangGraph tools互调
```

### 7.2 接口契约

| SP-07 调用方 | SP-04 提供方 | 协议 | 用途 |
|--------------|-------------|------|------|
| sp07-agent-service | AI Gateway | Dubbo Triple | LLM生成/Embedding/Reranker |
| sp07-retrieval-service | AI Gateway | Dubbo Triple | Reranker重排 |
| sp07-knowledge-service | AI Gateway | Dubbo Triple | 文档向量化 |
| sp07-agent-service | Memory Gateway | HTTP REST | ep-memory记忆CRUD |
| sp07-mcp-server | SP-04 LangGraph | MCP 2.0 | 工具互调 |

---

## 8. D-047: MCP实现技术选型

> 2026-08-05 追加。决策编号 D-047，记录 SP-07 MCP Server 实现技术选型，替换 D-046 中手写 JSON-RPC dispatcher 的方案。

### 8.1 决策摘要

SP-07 MCP Server 采用 **官方 MCP Java SDK (modelcontextprotocol/java-sdk v2.0.0) + Spring AI MCP 注解 (@McpTool/@McpToolParam)** 实现工具暴露与协议处理，替换原 D-046 中基于手写 JSON-RPC dispatcher 的 `McpController` / `McpRequest` / `McpResponse` / `ToolSchema` / `ToolRegistry` 五件套。

| 项 | 决策 |
|----|------|
| MCP 协议实现 | 官方 MCP Java SDK v2.0.0（modelcontextprotocol/java-sdk） |
| 注解与自动装配 | Spring AI MCP（`spring-ai-starter-mcp-server-webmvc` + `spring-ai-mcp-annotations`） |
| 工具注册方式 | `@McpTool` / `@McpToolParam` 声明式注解，Spring 容器扫描自动注册 |
| 传输层 | Streamable HTTP（MCP 2.0 spec），端点 `/mcp/v1` |
| BOM 对齐 | Spring AI 2.0.0 BOM（与 Spring Boot 4.0.x 兼容） |
| 模块 | sp07-mcp-server |

### 8.2 选型理由

1. **协议合规经过验证**：官方 SDK 通过 MCP 官方 conformance tests（40/40），自带协议版本协商、Streamable HTTP、SSE、cancellation 等完整语义。
2. **与 Spring AI 共维护**：Spring AI MCP 模块由 Spring AI 团队与官方 SDK 共同维护，版本随 Spring AI BOM 发布。
3. **GraalVM AOT 友好**：Spring AI MCP 模块在 Spring Boot 4.0 的 AOT 管线下可生成原生镜像元数据，满足 D-040 铁律。
4. **降低维护负担**：协议演进由上游负责，应用层只关注 `@McpTool` 业务实现。
5. **与 Python 侧（SP-04）解耦**：Java 侧用官方 SDK 暴露工具，Python 侧继续 LangGraph 通过 MCP/A2A 协议互操作，两侧无源码耦合。

### 8.3 方案对比 (铁律8)

| 备选方案 | 结论 | 关键拒绝理由 |
|----------|------|-------------|
| **Spring AI Alibaba (SAA)** | ❌ 拒绝 | 稳定版 v1.1.2.2 强依赖 Spring Boot 3.5，与项目 SB 4.0 铁律冲突；2.0.0-M1 为预发布版不可用于生产；无 GraalVM AOT 证据，违反 D-040 |
| **AgentScope**（阿里开源 Agent 框架） | ❌ 拒绝 | Java 侧依赖 SAA StateGraph，等价于直接用 SAA；Python 侧与已采用的 LangGraph 重复；无 Pulsar 集成 |
| **手写 JSON-RPC dispatcher**（D-046 实现） | ❌ 拒绝 | 缺失协议版本协商、Streamable HTTP、SSE、cancellation 语义；无 conformance tests 覆盖 |
| **官方 MCP Java SDK + Spring AI MCP** | ✅ 采纳 | 40/40 conformance tests 通过；与 Spring AI 共维护；GraalVM AOT 友好 |

### 8.4 架构影响

- **新增模块依赖**：`sp07-mcp-server/pom.xml` 引入 `spring-ai-starter-mcp-server-webmvc` + `spring-ai-mcp-annotations`；父 pom 引入 `spring-ai-bom 2.0.0`。
- **删除手写层**：`McpController` / `McpRequest` / `McpResponse` / `ToolSchema` / `ToolRegistry` 五个类整体删除。
- **传输端点**：MCP Server 暴露于 `/mcp/v1`，使用 Streamable HTTP（MCP 2.0 spec）。
- **租户上下文**：MCP 工具调用通过 Spring AI 的 `ToolContext` 透传 `tenant_id`，遵循 ADR-A02 FGAC 单点注入原则。

---

## 9. D-045: 多级智能记忆集成

> 2026-08-05 追加。决策编号 D-045，SP-07 作为 ep-memory 的主要消费方。

### 9.1 集成方式

sp07-agent-service 通过 **ep-memory-java SDK** 接入多级记忆系统：

```java
// AgentRpcServiceImpl.chat()
@Autowired
private MemoryManager memoryManager;  // ep-memory-java SDK

public ChatResponse chat(ChatRequest request) {
    // 1. 预加载上下文 (L0即时 + L1工作记忆)
    ContextWindow ctx = memoryManager.getContext(
        request.getTenantId(), request.getUserId(), request.getSessionId());
    
    // 2. 注入记忆到Agent编排
    AgentSession session = agentSessionRepo.findBySessionId(request.getSessionId());
    session.setMemorySnapshot(ctx);  // transient字段, 不持久化
    
    // 3. 执行Agent编排 (记忆在编排中使用)
    AgentResponse response = orchestrator.orchestrate(session, request);
    
    // 4. 异步持久化新记忆
    memoryManager.addAsync(response.getNewMessages(),
        request.getTenantId(), request.getUserId(), request.getSessionId());
    
    return response;
}
```

### 9.2 AgentSession 激活

D-048 P2-6 标注的 AgentSession 死代码已于 D-045 WP5 激活：
- 实现 `Serializable` (GraalVM AOT 兼容)
- 新增 `transient ContextWindow memorySnapshot` (内存态, 不持久化)
- AgentRpcServiceImpl 注入 MemoryManager 依赖

---

## 10. 非功能属性

| 属性 | 目标 | 实现方式 |
|------|------|----------|
| **性能** | 检索 P95 < 500ms, Agent P95 < 4s | Redis语义缓存 + Milvus ANN + 虚拟线程 |
| **可用性** | 99.95% | 多副本 + 健康检查 + 自动重启 |
| **可扩展性** | 水平扩展无状态服务 | K8s HPA + Dubbo负载均衡 |
| **多活** | RTO < 60s, RPO < 5s | 三中心单元化 + Pulsar异步复制 |
| **安全** | 租户隔离100% | FGAC + collection命名隔离 + key前缀隔离 |
| **可观测** | 全链路追踪 | OpenTelemetry + Pulsar事件审计 |
| **成本** | 启动 < 1s | GraalVM Native Image (BFF/API) |

---

## 11. 风险与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| Spring AI 2.0.0 与 Spring Boot 4.0.x 小版本兼容窗口 | M | 锁定 `spring-ai-bom 2.0.0`，CI 跑 `mvn dependency:tree` 校验 |
| GraalVM AOT 元数据缺失导致 native 构建失败 | M | sp07-mcp-server 仅做 service 兼容，BFF/API 强制 native；CI 增加冒烟测试 |
| 官方 MCP SDK API 在 2.x 有破坏性变更 | L | 锁定 v2.0.0，升级走 D-047 变更评审 |
| Milvus 单collection超100w chunks性能下降 | M | 按租户分collection，超出独立Schema |
| Neo4j Community版无集群 | M | 单节点+定期备份，生产可升级Enterprise |
| ep-memory L2遗忘策略误删关键记忆 | L | L3组织记忆永不自动过期，管理员可恢复 |

---

**文档结束**。本 SAD 覆盖六层架构、组件视图、部署视图、数据视图、ADR矩阵、SP-04协作、D-047/D-045决策、非功能属性与风险。编码输入见 `docs/design/sp07-sdd.md`，需求追溯见 `docs/requirements/sp07-srs.md`，测试矩阵见 `docs/testing/sp07-test.md`，部署配置见 `docs/deployment/sp07-deploy.md`。
