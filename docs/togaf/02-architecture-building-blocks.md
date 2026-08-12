# TOGAF 架构构建块登记表 (Architecture Building Blocks Registry)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-ABB | v1.0 | 2026-08-12 | Approved | ZCode(架构治理) | D-157 / 00-顶层§5 |

> **文档定位**：TOGAF ADM Phase C/D 产物。登记 education-platform 项目群全部架构构建块(ABB)，作为 Solution Building Blocks(03-SBB)与 Architecture Requirements Specification(05-ARS)的锚点。每个 ABB 在 00-顶层§5 应用架构 / §6 技术架构 中均有对应位置。
> **裁剪说明**：仅列"架构级"构建块(跨子项目、有契约、有 ADR 锚定)；具体 CRUD 实现类不入此表。

---

## 1. ABB 登记表

| ABB-ID | 名称 | 类型 | 功能描述 | 接口 | 依赖 | 所属域 | TOGAF分类 |
|--------|------|------|---------|------|------|--------|----------|
| ABB-01 | BFF Edge Gateway (portal-bff) | 接入层 | HTTP 反向代理 + JWT RS256 签发(JWKS) + hop-by-hop header 剥离 + Sentinel 限流 + 灰度路由 | REST/OpenAPI 3, HTTP/1.1 | ABB-18 缓存, ABB-03 CellRouter, Nacos | 接入域 | Application/Governance |
| ABB-02 | WebSocket Gateway (ws-gateway) | 接入层 | RFC6455 长连接 + fail-closed JWT 鉴权 + Pulsar 跨 pod 广播 + WS 心跳 | WS/JSON | Pulsar, Redis, ABB-23 OTel | 接入域 | Application |
| ABB-03 | CellRouter | 接入层 | 多单元路由 hash(user_id)%N + misroute 自动纠正(上限3) + 路由表外置 | Filter SPI, HTTP header `x-ep-cell` | Nacos(路由表), ABB-01 | 接入域 | Application/Technology |
| ABB-04 | Course Service (core-business/course) | 业务域 | 课程/章节/目录 CRUD + 课程上下架 + 缓存读 | REST, Dubbo Triple | ABB-18 缓存, ABB-25 TiDB, ABB-24 Pulsar | 业务域 | Business/Application |
| ABB-05 | User Service (core-business/user) | 业务域 | 用户/凭证/会话/画像 + 强单元化(user_id 分片) | REST, Dubbo Triple | ABB-25, ABB-26 Redis, ABB-20 TenantFilter | 业务域 | Business/Application |
| ABB-06 | Order Service (order-platform) | 业务域 | 订单创建/状态机 + Saga participant + 幂等键 + 乐观锁 | REST, Saga 协议 | ABB-17 Saga, ABB-07 Payment, Pulsar | 业务域 | Business/Application |
| ABB-07 | Payment Service (payment-platform) | 业务域 | 支付下单/回调/对账 + Saga participant + 补偿回滚 | REST, Saga 协议, 支付通道 SDK | ABB-17 Saga, ABB-06 Order, ABB-25 | 业务域 | Business/Application |
| ABB-08 | Grading Service (grading-service) | BU 域 | 作业评分 pipeline + LLM rubric + human-in-the-loop + Firecracker Judge | REST, Dubbo Triple (grading-api 独立模块) | ABB-13 Agent, ABB-15 AI Gateway, Firecracker | BU 域 | Business/Application |
| ABB-09 | Senior Care Service (senior-care) | BU 域 | 康养课程/健康打卡/适老化学习路径 | REST | ABB-04, ABB-05, ABB-25 | BU 域 | Business |
| ABB-10 | Board Game Service (boardgame-service) | BU 域 | 棋牌对弈房间 + 匹配 + 棋谱回放 | REST, WS (via ABB-02) | ABB-02, ABB-25, ABB-26 | BU 域 | Business/Application |
| ABB-11 | Knowledge Service (knowledge-platform/knowledge-service) | 知识域 | 知识库/文档/分块 + 上传/索引 + 多租户隔离 | REST, Dubbo Triple | ABB-12 Retrieval, ABB-14 Tenant, ABB-27 Milvus | 知识域 | Application/Data |
| ABB-12 | Retrieval Service (knowledge-platform/retrieval-service) | 知识域 | RAG 检索 + 向量召回 + 重排 + namespace+FGAC | REST, Dubbo Triple | ABB-27 Milvus, ABB-26 Redis(语义缓存) | 知识域 | Application/Data |
| ABB-13 | Agent Service (knowledge-platform/agent-service) | AI 域 | LangGraph 多步 Agent + 工具调用 + 多级记忆 L0~L3 | REST, LangGraph | ABB-15 AI Gateway, ABB-12, ep-memory | AI 域 | Application |
| ABB-14 | Tenant Service (knowledge-platform/tenant-service) | 知识域 | 租户生命周期 + 配额 + JWT FGAC 上下文 | REST, Filter SPI | ABB-20 TenantFilter, ABB-25 | 知识域 | Application/Governance |
| ABB-15 | AI Gateway (ai-platform/ai_gateway) | AI 域 | LLM 路由 + 语义缓存 + 计费 + 模型路由(mock 压测) | REST, OpenAI 兼容 | ABB-18 缓存, LiteLLM→Kong AI | AI 域 | Application |
| ABB-16 | Data Platform (data-platform) | 数据域 | Flink CDC 入湖 + 实时指标 + Feature Store + Iceberg | Pulsar ns=cdc://, Flink SQL | ABB-24 Pulsar, ABB-25 TiDB, Iceberg, S3 | 数据域 | Data |
| ABB-17 | Saga State Machine | 技术中台 | 分布式事务编排 + 幂等键 + at-least-once + 补偿(不引入 Seata, ADR-T06) | Saga 协议, Pulsar | ABB-24 Pulsar, ABB-25 | 技术中台 | Application |
| ABB-18 | Multi-tier Cache (ep-cache) | 技术中台 | L1 Caffeine + L2 Redis + Cache-Aside + 惊群防护 + EMPTY_SENTINEL | SDK (Java) | ABB-26 Redis | 技术中台 | Technology |
| ABB-19 | Snowflake ID Generator | 技术中台 | 分布式 ID + TiDB AUTO_RANDOM + worker id 分配 | SDK | ABB-25 TiDB | 技术中台 | Technology |
| ABB-20 | Tenant Context Filter | 技术中台 | 多租户隔离 + JWT 解析注入 tenant_id + 禁 app 拼 tenant_id(P-03) | Filter SPI | ABB-21 RoleChecker | 技术中台 | Application/Security |
| ABB-21 | Role Checker | 技术中台 | RBAC + ABAC(资源级) + 注入权限上下文 | Filter SPI, 注解 | ABB-01 | 技术中台 | Application/Security |
| ABB-22 | Global Exception Handler | 技术中台 | 统一错误响应 + 错误码规范 + traceId 透出 | @RestControllerAdvice | ABB-23 OTel | 技术中台 | Application |
| ABB-23 | OpenTelemetry Tracing | 技术中台 | W3C traceparent 注入/透传 + OTLP 导出 + tail-sampling | OTel SDK, OTLP | Edge Collector→Tempo | 技术中台 | Technology |
| ABB-24 | Pulsar Event Bus | 技术中台 | CDC(ns=cdc://) + 领域事件 + geo-rep + Avro Schema Registry | Pulsar protocol, Avro | ABB-25 TiCDC | 技术中台 | Technology |
| ABB-25 | TiDB HTAP Storage | 数据中台 | 在线 Primary/Follower + TiFlash 列存 + AUTO_RANDOM + RANGE 分区 | JDBC, SQL | — | 数据中台 | Data/Technology |
| ABB-26 | Redis Cluster | 数据中台 | L2 缓存 + 会话 + 路由表 + 特征 + CRDT 多活 | RESP3 | — | 数据中台 | Technology |
| ABB-27 | Milvus Vector DB | AI 中台 | 向量索引 + namespace + FGAC + 语义检索 | gRPC, REST | — | AI 中台 | Data/AI |
| ABB-28 | Frontend Monorepo (portal-web / admin-web / student-h5) | 应用域 | 三端共 API 契约 + W3C traceparent 注入 + @ep/* scope | HTTPS→ABB-01 | ABB-01 BFF | 应用域 | Application |

---

## 2. ABB 关系矩阵(关键依赖)

```
ABB-28 Frontend ──HTTP──► ABB-01 BFF ──► ABB-03 CellRouter ──► 业务域(ABB-04..07)
                                          │                          │
                              ABB-18 Cache┘                          │
                              ABB-20 TenantFilter ─► ABB-21 RoleChecker
                                                                       │
   BU 域(ABB-08..10) ◄── 业务域 ◄── ABB-17 Saga ─► Pulsar(ABB-24) ◄───┘
        │                              │                  │
        ▼                              ▼                  ▼
   ABB-13 Agent ─► ABB-15 AI GW   ABB-25 TiDB        ABB-16 Data Platform
        │                              ▲                  │
        ▼                              │                  ▼
   ABB-12 Retrieval ─► ABB-27 Milvus  ABB-26 Redis     Iceberg/S3
```

## 3. 与 TOGAF 分类对齐

- **Application/Governance**：ABB-01/03/20/21 — 接入与治理组件
- **Business/Application**：ABB-04..10 — 业务能力承载
- **Application/Data**：ABB-11/12/16 — 跨数据/应用
- **Technology**：ABB-18/19/23/24/25/26 — 基础设施与技术中台
- **Data/AI**：ABB-27 — AI 专用数据基础设施

## 4. 变更管理

- 新增 ABB：提交 ADR + 本表登记，经架构委员会批准
- 废弃 ABB：状态置 Deprecated，关联 SBB 同步标记，00-顶层§5 更新
- ABB 接口变更：触发 Pact 契约测试(P-06)，向后兼容窗口 2 个 minor

---

**关联文档**：00-顶层§5 应用架构 / 03-SBB Registry / 05-ARS / 06-Governance Log
