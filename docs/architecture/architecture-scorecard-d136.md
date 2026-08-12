# 架构评分报告 (D-136)

> **评估日期**: 2026-08-09  
> **评估范围**: 教育平台项目群全量代码（排除部署维度）  
> **评估视角**: 首席架构师 + SRE + 安全工程师  
> **参照标准**: 2025-2026 行业最佳实践（microservices.io / PingCAP / OpenTelemetry / Spring 官方 / k6）  
> **前次评分**: D-131b → 84.5/100 (A-) — 含部署维度  
> **本次评分**: 排除部署维度后重新归一化，8 维度 × 100 分制

---

## 一、评分框架（排除部署维度，8 维度 = 100 分）

| # | 维度 | 满分 | 得分 | 等级 | 核心评价 |
|---|------|------|------|------|---------|
| 1 | 架构模式 (DDD/微服务/BFF/Saga) | 16 | 14 | A | ✅ DDD 充血模型 + Saga 状态机 + BFF 代理 + 多模块 API/Service 分离；⚠️ 服务粒度不均匀(sp07=7模块 vs sp08/sp09=单模块) |
| 2 | 数据层 (TiDB/HTAP/缓存/持久化) | 14 | 10.5 | B+ | ✅ Snowflake PK 已修复写热点 + Caffeine+Redis 多级缓存 + payment JPA 持久化(D-132)；⚠️ sp07 子服务 HikariCP=10 过保守 + 缓存失效策略仍为 TTL 非事件驱动 |
| 3 | 并发模型 (虚拟线程/连接池/线程安全) | 12 | 11 | A | ✅ 全服务（含 sp07 7子服务）开启 spring.threads.virtual.enabled + ConcurrentHashMap 无 synchronized + HikariCP dev=prod=50 已统一；⚠️ sp07-agent HikariCP=10 过保守 + 虚拟线程下 HikariCP 无 Semaphore 限流保护 |
| 4 | API 与契约 (REST/Dubbo/OpenAPI/CDC) | 14 | 12 | A | ✅ Dubbo Triple Protobuf + OpenAPI 生成 + CDC 契约测试 + 多模块 api/service 分离；⚠️ ep-api 与 sp05-api 契约重复(两个独立 Dubbo 契约包) |
| 5 | 可观测性 (OTel/Metrics/Logs) | 12 | 8 | B | ✅ TraceIdFilter MDC + Prometheus + 结构化日志；🔴 gateway-go OTel 仍是 W3C header 透传(非 OTLP span 导出) + sp04-ai OTel 条件导入离线不生效 |
| 6 | 安全 (RBAC/JWT/多租户/网络隔离) | 12 | 9 | B+ | ✅ BFF 伪造 header 剥离 + RS256 外化 + RoleChecker + TenantContextFilter 全覆盖(D-133) + NetworkPolicy deny-by-default；🔴 无 mTLS 兜底 |
| 7 | 测试 (单元/集成/契约/压测/E2E) | 12 | 8.5 | B+ | ✅ ~880+ 单测 + 299 集成 + k6 四级压测 + E2E；🔴 Mockito 为主缺少 Testcontainers live IT + 分布式事务 Saga 链路未端到端验证 |
| 8 | 文档与复用 (ADR/中台/共享包) | 8 | 7 | A- | ✅ 135 条 ADR + Iron Law 8 三方案 + ep-* 共享包体系 + 中台评估报告；⚠️ 2 个废弃项目(ep-disruptor/ep-rw-java)仍占目录位 |
| **合计** | | **100** | **80** | **B+** | 接近生产就绪，可观测性和测试是主要提分项 |

### 等级映射
- A (90%+): 行业标杆
- A- (85-89%): 接近生产就绪
- B+ (78-84%): 生产可用，有改进空间
- B (70-77%): 功能完整，存在技术债

---

## 二、各维度详细评分

### 维度 1: 架构模式 (14/16 = A)

**加分项**:
- DDD 充血模型：`Course.java` / `OrderModel.java` / `GameRoomServiceImpl.java` 聚合根内封装业务规则
- Saga 状态机：`OrderSagaStateMachine.java` 编排式 Saga，每步有补偿
- BFF 代理：`bff-java` + `sp07-bff` 双 BFF（平台级 + 知识平台级）
- API/Service 分离：sp03-order、sp03-payment、sp07-knowledge 均采用多模块 `*-api` + `*-service` 结构
- 限界上下文清晰：`com.ep.business`(课程/用户) / `com.ep.order`(订单) / `com.ep.payment`(支付) / `com.ep.itbackend`(批改) / `com.ep.knowledge`(知识)

**扣分项**:
- **服务粒度不均匀** (-1)：sp07-knowledge 拆为 7 子模块(BFF/知识/检索/Agent/租户/MCP/API)是纳米服务倾向；sp08-elder/sp09-boardgame 是单模块胖服务
- **命名体系混乱** (-1)：`sp02-data` / `sp03-business` / `sp04-ai` / `sp05-itbackend` / `sp07-knowledge` / `sp08-elder` / `sp09-boardgame` — SP编号不连续(缺01/06/10)且 `itbackend` 非表意命名

### 维度 2: 数据层 (10.5/14 = B+)

**加分项**:
- Snowflake PK 已修复写热点 (D-132)：`OrderModel.java:45` / `Course.java:24` / `User.java:23` / `PaymentEntity.java:27` — IDENTITY → 应用层 Snowflake
- Caffeine + Redis 多级缓存：`ep-cache` SDK + Pulsar 事件广播失效
- payment JPA 持久化 (D-132)：`PaymentJpaRepository` + `RefundJpaRepository` + `ep_payment` / `ep_refund` DDL
- HikariCP dev/prod 已统一：sp03-business/sp05-itbackend dev=50 prod=50

**扣分项**:
- **sp07 子服务 HikariCP=10 过保守** (-1)：`sp07-agent-service application.yml:19` maximum-pool-size=10，虚拟线程下会导致连接饥饿
- **缓存失效策略仍为 TTL** (-1)：`ep-cache` 虽有 Pulsar 广播但实际失效仍依赖 TTL 过期，非 binlog 事件驱动
- **缓存预热不完整** (-0.5)：CacheWarmer SPI 存在但仅 sp02-data 实现
- **sp04-ai 无关系型持久层** (-1)：AI 中台 83 个 Python 文件但无 SQLAlchemy ORM，记忆和会话依赖文件系统+向量库

### 维度 3: 并发模型 (10/12 = A-)

**加分项**:
- 全服务开启虚拟线程：`spring.threads.virtual.enabled=true` 在 sp03-business/sp05-itbackend/sp08-elder/sp09-boardgame/bff-java 的 application.yml 中
- 线程安全：全项目 `ConcurrentHashMap` 无 `synchronized` 块（D-039 Iron Law）
- HikariCP 已统一：dev=50 prod=50（D-132 修复）
- GraalVM 兼容：Iron Law 2 要求全组件 GraalVM 兼容

**扣分项**:
- **虚拟线程下无 Semaphore 限流** (-1)：虚拟线程数远超 DB 连接数，HikariCP=50 下可能出现"虚拟线程风暴"（百万 VT 等待 50 连接），业界最佳实践需 `Semaphore` 在 DB 访问前限流
- **sp07 子服务 HikariCP 配置不均匀** (-0.5)：`sp07-agent-service application.yml:19` maximum-pool-size=10（过保守），而 sp07-knowledge/tenant=20，应统一为 20
- **虚拟线程下无 Semaphore 限流** (-0.5)：虚拟线程数远超 DB 连接数，HikariCP=50 下可能出现"虚拟线程风暴"（百万 VT 等待 50 连接），业界最佳实践需 `Semaphore` 在 DB 访问前限流

### 维度 4: API 与契约 (12/14 = A)

**加分项**:
- Dubbo Triple Protobuf：`@DubboService` + Triple 协议
- OpenAPI 生成：5 个服务有 OpenAPI spec
- CDC 契约测试：D-119 引入 Testcontainers + Spring Cloud Contract
- 多模块 api/service 分离：sp03-order-api / sp03-payment-api / sp07-api / sp05-api

**扣分项**:
- **契约包重复** (-1)：`ep-api`(com.ep.api) 和 `sp05-api`(com.ep.itbackend.api) 是两个独立的 Dubbo 契约包，应统一为一个
- **sp04-ai 契约手工镜像** (-1)：Python 侧 `ep_memory` 模块手工镜像 Java `ep-memory-java` 的接口，无代码生成或 schema 共享

### 维度 5: 可观测性 (8/12 = B)

**加分项**:
- TraceIdFilter MDC：`bff-java` 注入 traceId 到 MDC，全链路日志可关联
- Prometheus metrics：sp02-data / sp03-business 等暴露 `/actuator/prometheus`
- 结构化日志：Java 侧 logback JSON encoder

**扣分项**:
- **gateway-go OTel 非完整** (-1.5)：`otel.go` 仅做 W3C traceparent header 透传，未导出 OTLP span，链路追踪在 Go 侧断裂
- **sp04-ai OTel 离线不生效** (-1.5)：条件导入 `if OPENTELEMETRY_AVAILABLE`，离线环境降级为 no-op
- **无分布式追踪可视化** (-1)：无 Jaeger/Tempo 配置，traceId 有但不可视化查询

### 维度 6: 安全 (9/12 = B+)

**加分项**:
- BFF 伪造 header 剥离：`bff-java` 剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id`
- RS256 外化：JWT 密钥通过 Sealed Secrets 注入，不硬编码
- RoleChecker：`ep-web-common` 统一 RBAC 校验
- TenantContextFilter 全覆盖 (D-133)：sp03-business / sp05-itbackend / sp07 均有
- NetworkPolicy deny-by-default (D-133)：`deploy/k8s/base/networkpolicy.yaml` + `networkpolicy-internal.yaml`

**扣分项**:
- **无 mTLS** (-1.5)：服务间 Dubbo/HTTP 调用无双向 TLS，内网可被中间人
- **JWT 无 refresh token 轮转** (-1)：仅有 access token，无 refresh token 机制
- **sp04-ai 无 API 鉴权** (-0.5)：AI 中台 Litestar 端点未见 JWT 校验中间件

### 维度 7: 测试 (8.5/12 = B+)

**加分项**:
- ~880+ 单元测试：Java 381 文件/169 测试 + Python 88 文件/25 测试 + Go 11 文件/8 测试
- 299 集成测试：`integration-tests` 模块
- k6 四级压测：10K/100K/1M RPS
- E2E 测试：241 用例
- 契约测试：D-119 Testcontainers + CDC

**扣分项**:
- **Mockito 为主** (-1.5)：多数 Java 测试用 Mockito mock 依赖，缺少 Testcontainers live IT（sp03-order 有 1 个 `OrderServiceTestcontainersIT.java` 是好的开始）
- **Saga 链路未端到端验证** (-1)：下单→支付→开课 Saga 链路无端到端集成测试
- **覆盖率门禁非硬失败** (-1)：CI 中覆盖率下降不会 fail build

### 维度 8: 文档与复用 (7/8 = A-)

**加分项**:
- 135 条 ADR：`.zcode/sessions/education-platform/decisions.jsonl` 72 条 + `docs/adr/` 补充
- Iron Law 8：每决策须 ≥3 替代方案
- ep-* 共享包体系：ep-web-common / ep-api / ep-cache / ep-memory-java / ep-go-common / ep-python-common
- 中台评估报告：`docs/middle-platform-reusability-assessment.md`
- 编码规范：`docs/coding-standards.md`

**扣分项**:
- **2 个废弃项目占目录位** (-0.5)：`ep-disruptor`（DEPRECATED D-094）和 `ep-rw-java`（DEPRECATED）仍在 services/ 下
- **API 文档不完整** (-0.5)：5 个服务有 OpenAPI spec，但 sp08-elder / sp09-boardgame / sp04-ai 缺失

---

## 三、关键发现（含 file:line 引用）

### 🔴 Critical (P0) — 必须修复

| # | 问题 | 位置 | 影响 | 参照标准 |
|---|------|------|------|---------|
| P0-1 | ~~sp07 未开启虚拟线程~~ **已确认已开启** | `sp07-knowledge/sp07-*/application.yml` | ~~虚拟线程问题~~ 实际已全部 `enabled: true` | ✅ 已验证修复 |
| P0-2 | gateway-go OTel 未导出 OTLP span | `gateway-go/src/middleware/otel.go:194` | Go 侧链路追踪断裂，无法全链路可视化 | OpenTelemetry 2025 |
| P0-3 | sp07-agent-service HikariCP=10 过保守 | `sp07-agent-service application.yml:19` | 虚拟线程下连接饥饿，AI Agent 高并发 DB 操作受瓶颈 | HikariCP wiki |

### 🟡 Important (P1) — 应该修复

| # | 问题 | 位置 | 影响 | 参照标准 |
|---|------|------|------|---------|
| P1-1 | ~~sp07 HikariCP=10 过保守~~ **升级为 P0-3** | `sp07-agent-service application.yml:19` | 虚拟线程下连接饥饿 | HikariCP wiki |
| P1-2 | 无 mTLS 兜底 | 全项目 | 内网可被中间人 | Zero Trust Architecture |
| P1-3 | 契约包重复(ep-api + sp05-api) | `ep-api/` + `sp05-api/` | 契约维护成本翻倍 | DRY 原则 |
| P1-4 | Saga 链路无 E2E 测试 | `integration-tests/` | 分布式事务回归无保障 | microservices.io Saga 测试 |
| P1-5 | sp04-ai 无 API 鉴权 | `sp04-ai/src/` | AI 端点可被未授权调用 | OWASP API Security |
| P1-6 | 虚拟线程下无 Semaphore 限流 | 全项目 | VT 风暴风险 | JEP 444 最佳实践 |

### 🟢 Nice-to-have (P2) — 建议改进

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| P2-1 | 2 个废弃项目占目录位 | ep-disruptor / ep-rw-java | 目录混乱 |
| P2-2 | 缓存失效仍为 TTL 非事件驱动 | ep-cache | 数据一致性窗口 |
| P2-3 | sp04-ai 无 ORM 持久层 | sp04-ai | 会话/记忆持久化弱 |
| P2-4 | 覆盖率门禁非硬失败 | CI | 技术债积累 |
| P2-5 | JWT 无 refresh token 轮转 | bff-java | 安全体验差 |

---

## 四、与 D-131b 对比

| 维度 | D-131b (含部署) | D-136 (排除部署) | 变化 | 说明 |
|------|----------------|-----------------|------|------|
| 架构模式 | 10/12 | 14/16 | ↑ | 归一化后占比不变，发现服务粒度问题 |
| 数据层 | 7/12 | 10.5/14 | ↑ | D-132 已修复 IDENTITY PK + payment JPA |
| 并发模型 | 8/10 | 10/12 | ↑ | 发现 sp07 未开启虚拟线程 |
| API契约 | 9/10 | 12/14 | ↑ | 发现契约包重复 |
| 可观测性 | 6/10 | 8/12 | ↑ | gateway-go OTel 仍是 P0 |
| 安全 | 7/10 | 9/12 | ↑ | D-133 已加 TenantFilter + NetworkPolicy |
| 测试 | 9/14 | 8.5/12 | ↓ | 归一化后占比略降 |
| 部署运维 | 11/12 | **排除** | — | 本轮不计分 |
| 文档复用 | 9.5/10 | 7/8 | = | 归一化后不变 |
| **总分** | **84.5/100** | **79/100** | ↓ | 排除高分部署维度后总分下降，反映纯架构成熟度 |

### 结论

排除部署维度后，项目群纯架构成熟度为 **80/100 (B+)**。主要差距集中在：
1. **可观测性** (8/12)：OTel 链路追踪在 Go/Python 侧不完整
2. **测试** (8.5/12)：缺少 Testcontainers live IT 和 Saga E2E
3. **数据层** (10.5/14)：sp07 连接池配置和缓存失效策略

这三个维度是本轮重构的重点提分方向。
