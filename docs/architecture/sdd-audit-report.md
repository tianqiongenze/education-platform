# SDD 契约成熟度审计报告

> **报告编号**: D-205
> **报告日期**: 2026-08-12
> **关联评分**: architecture-scorecard.md v5（维度 4: 9.0/10, A-）
> **审计视角**: Spec-Driven Development（SDD）契约成熟度评估
> **审计范围**: education-platform（智教云）全量 13 服务 + 20+ 领域事件 + 7 Pulsar topic
> **参考标准**: OpenAPI 3.1 规范 · AsyncAPI 3.0 规范 · Spectral linter · contract-first design · microservices.io API design · Stoplight API Design Best Practices · Springdoc OpenAPI · Apache Pulsar schema registry

---

## 1. Executive Summary

教育平台采用 **"documentation-first + code-first OpenAPI"** 模式——拥有行业顶级的设计文档纪律（SRS/SDD/SAD 全覆盖、131+ ADRs 每条含三方案对比、TOGAF 7 制品、Iron Law 8/9/10 立法），但**并非真正的 Spec-Driven Development（契约驱动开发）**。

**核心发现**：项目的 OpenAPI/AsyncAPI spec 是代码的**副产物**（generated artifacts），而非 source-of-truth（authored contracts）。

- OpenAPI 由 springdoc 从 `@RestController` 注解生成，`scripts/generate-openapi-specs.sh` 从运行中服务的 `/v3/api-docs` 端点拉取 JSON——这是 **code-first**，不是 **contract-first**。
- 20+ 领域事件横跨 7 个 Pulsar topic，**零 AsyncAPI spec 文件**。事件消费者无法从 schema 推导 payload 结构。
- 仅 1 个 proto 文件（boardgame Stockfish），其余 RPC 用 Dubbo Triple 但无显式 proto 契约抽取。
- 无 Spectral linter，OpenAPI 质量未 CI 强制。
- CI 无契约漂移检测（contract drift detection）。

**当前 SDD 成熟度**：**Level 2**（code-first OpenAPI，设计文档顶级但 spec 是代码副产物）。
**目标 SDD 成熟度**：**Level 3**（contract-first，spec 是 source-of-truth，CI lint 强制）。
**升级路径**：D-206（契约优先 OpenAPI + Spectral）+ D-207（AsyncAPI 规格）。

**关键信息**：项目有卓越的设计纪律（131+ ADRs、TOGAF、Iron Laws），但缺失契约优先执行层。升级到 Level 3 需将 OpenAPI/AsyncAPI spec 视为 source-of-truth，而非生成产物。

---

## 2. SDD Maturity Assessment

### 2.1 SDD 成熟度模型

| Level | 名称 | 特征 | 典型表现 |
|-------|------|------|---------|
| **Level 0** | No Specs | 无任何 API/事件规格文档 | 代码即契约，文档靠口口相传 |
| **Level 1** | Documentation-first | 有设计文档（SRS/SDD/SAD）、ADR，但 spec 与代码无强制关联 | 设计文档质量高，但 spec 是"写给人看的"非"机器可执行的" |
| **Level 2** | Code-first OpenAPI | OpenAPI 从代码生成（springdoc/swagger），文档随代码走 | spec 是代码的副产物，非 source-of-truth |
| **Level 3** | Contract-first | spec 先于代码编写，CI lint 强制，可选 codegen | spec 是 source-of-truth，代码须符合 spec |
| **Level 4** | Contract-first + Drift Detection + Auto-generate | spec 先于代码 + CI 漂移检测 + 客户端/服务端 codegen | spec 驱动全链路，漂移即 CI fail |

### 2.2 逐 Level 评估

#### Level 0: No Specs — 项目已**远超**此 Level

项目拥有完整的设计文档体系：SRS、SDD、SAD 每子项目 6-7 类制品，131+ ADRs，TOGAF Phase A-H 全覆盖。**不存在"无规格"的情况**。

#### Level 1: Documentation-first — 项目已**满足并超越**此 Level

| 证据 | 状态 |
|------|------|
| SRS（软件需求规格） | ✅ 全子项目覆盖 |
| SDD（软件设计文档） | ✅ 全子项目覆盖 |
| SAD（软件架构文档） | ✅ TOGAF Phase A-H 对齐 |
| ADR（架构决策记录） | ✅ 131+ 条，每条 ≥3 方案对比 + 选择理由 + 影响分析（Iron Law 8） |
| Iron Law 8 CI 强制 | ✅ `check-iron-law-8.py` 静态扫描三方案门禁 |
| Iron Law 10 | ✅ 设计-实现可追溯性强制律（D-196 立法） |

**结论**：项目在 Level 1（文档优先）上达行业顶级水平。但 Level 1 的 spec 是"写给人看的"文档，不是"机器可执行的契约"。

#### Level 2: Code-first OpenAPI — **当前状态**

| 证据 | 现状 | 判定 |
|------|------|------|
| OpenAPI 生成方式 | `scripts/generate-openapi-specs.sh` 从**运行中服务**的 `/v3/api-docs` 端点拉取 springdoc 生成的 JSON | 🔴 code-first |
| springdoc 依赖 | springdoc-openapi-starter-webmvc-ui 2.6，从 `@RestController` + `@Operation` 注解生成 spec | 🔴 spec 是代码副产物 |
| spec 存储位置 | `frontend/packages/api-client/schemas/*-openapi.json`（生成产物，非手工编写） | 🔴 非源文件 |
| 前端 codegen | `pnpm gen:api` 从生成的 OpenAPI JSON 生成 TS 客户端 | ⚠️ 半自动（依赖 code-first 产物） |
| AsyncAPI | 20+ 领域事件，**零 AsyncAPI spec** | 🔴 低于 Level 2 |
| Spectral linter | 无 `.spectral.yaml` | 🔴 缺失 |
| 契约漂移检测 | CI 无 spec-vs-code diff | 🔴 缺失 |

**结论**：项目处于 **Level 2**（code-first OpenAPI）。设计文档纪律顶级（超越 Level 1），但 OpenAPI 是代码副产物，未达 Level 3 契约优先标准。

#### Level 3: Contract-first — **目标状态**

| 特征 | 目标 |
|------|------|
| spec 先于代码 | OpenAPI/AsyncAPI YAML 手工编写，存入 `docs/contracts/openapi/` 和 `docs/contracts/asyncapi/`，作为设计评审一部分 |
| CI lint 强制 | Spectral linter 校验命名规范、必填字段、错误响应、security scheme、版本管理 |
| source-of-truth | spec 是契约源头，代码须符合 spec，而非 spec 跟随代码 |
| 客户端 codegen | 前端 TS 客户端从 spec 文件 codegen，而非从运行服务拉取 |
| 事件契约 | AsyncAPI spec 覆盖全部领域事件，消费者从 spec 推导 payload |

#### Level 4: Contract-first + Drift Detection + Auto-generate — **未来愿景**

| 特征 | 未来 |
|------|------|
| CI 漂移检测 | `oasdiff`/`openapi-diff` 比较 spec 与代码生成的 spec，drift 即 CI fail |
| 全链路 codegen | 客户端 + 服务端 stub 均从 spec 生成 |
| 契约测试自动化 | Pact/SCC 从 spec 生成 contract test |

---

## 3. Service-by-Service Spec Audit Matrix

### 3.1 审计范围（13 服务）

| # | 服务名 | 端口 | 语言 | 模块路径 |
|---|--------|------|------|---------|
| 1 | portal-bff | 8090 | Java | `services/portal-bff/` |
| 2 | core-business | 8083 | Java | `services/core-business/` |
| 3 | order-platform | 8084 | Java | `services/order-platform/order-platform-service/` |
| 4 | payment-platform | 8087 | Java | `services/order-platform/payment-platform-service/`（端口推断） |
| 5 | grading-service | 8085 | Java | `services/grading-service/` |
| 6 | data-platform | 8082 | Java | `services/data-platform/` |
| 7 | ai-platform | 8086 | Python | `services/ai-platform/` |
| 8 | ws-gateway | 8081 | Go | `services/ws-gateway/` |
| 9 | knowledge-bff | 8070 | Java | `services/knowledge-platform/knowledge-bff/` |
| 10 | knowledge-service | 8071 | Java | `services/knowledge-platform/knowledge-service/` |
| 11 | retrieval-service | 8072 | Java | `services/knowledge-platform/retrieval-service/` |
| 12 | agent-service | 8073 | Java | `services/knowledge-platform/agent-service/` |
| 13 | tenant-service | 8074 | Java | `services/knowledge-platform/tenant-service/` |

> **注**：boardgame-service（附加模块，proto + 事件）和 senior-care（附加模块，事件）在事件审计中单列。

### 3.2 逐服务规格审计

| # | 服务 | 端口 | 语言 | OpenAPI | 生成方式 | AsyncAPI | Proto | JSON Schema |
|---|------|------|------|---------|---------|----------|-------|-------------|
| 1 | portal-bff | 8090 | Java | ✅ springdoc | 🔴 code-first | ❌ 无 | ❌ 无 | ❌ 无 |
| 2 | core-business | 8083 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（有 3 事件，见第 4 节） | ❌ 无 | ❌ 无 |
| 3 | order-platform | 8084 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（有 2 事件） | ❌ 无 | ❌ 无 |
| 4 | payment-platform | 8087 | Java | ✅ springdoc | 🔴 code-first | ❌ 无 | ❌ 无 | ❌ 无 |
| 5 | grading-service | 8085 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（有 2 事件） | ❌ 无 | ❌ 无 |
| 6 | data-platform | 8082 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（CDC 消费者） | ❌ 无 | ❌ 无 |
| 7 | ai-platform | 8086 | Python | ⚠️ FastAPI auto | 🔴 code-first | ❌ 无（内部 Hermes topic） | ❌ 无 | ❌ 无 |
| 8 | ws-gateway | 8081 | Go | ❌ 无 | N/A | ❌ 无（Redis Streams broadcast） | ❌ 无 | ❌ 无 |
| 9 | knowledge-bff | 8070 | Java | ✅ springdoc | 🔴 code-first | ❌ 无 | ❌ 无 | ❌ 无 |
| 10 | knowledge-service | 8071 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（有 2 事件） | ❌ 无 | ❌ 无 |
| 11 | retrieval-service | 8072 | Java | ✅ springdoc | 🔴 code-first | ❌ 无 | ❌ 无 | ❌ 无 |
| 12 | agent-service | 8073 | Java | ✅ springdoc | 🔴 code-first | ❌ 无 | ❌ 无 | ❌ 无 |
| 13 | tenant-service | 8074 | Java | ✅ springdoc | 🔴 code-first | ❌ 无（有 3 事件） | ❌ 无 | ❌ 无 |

### 3.3 审计汇总

| 规格类型 | 覆盖数 | 覆盖率 | 生成方式 |
|---------|--------|--------|---------|
| OpenAPI | 11/13 | 84.6% | **全部 code-first**（springdoc/FastAPI auto-gen） |
| AsyncAPI | 0/13 | **0%** | **完全缺失** |
| Proto | 1/13 | 7.7% | 仅 boardgame Stockfish（附加模块） |
| JSON Schema | 0/13 | 0% | 完全缺失 |
| Spectral lint | 0/13 | 0% | 无 linter |
| 契约漂移检测 | 0/13 | 0% | 无 drift detection |

**关键发现**：
1. **OpenAPI 覆盖率 84.6%**（11/13 服务），但**全部是 code-first**。ws-gateway（Go）和 ai-platform（Python，FastAPI auto-gen）无独立 OpenAPI spec 文件。
2. **AsyncAPI 覆盖率 0%**——这是最严重的 gap。13 个服务中有多达 20+ 领域事件，但零 AsyncAPI spec。
3. **Proto 仅 1 个**（boardgame Stockfish），其余 RPC 用 Dubbo Triple 但无显式 proto 契约。
4. **无 Spectral linter，无 JSON Schema，无契约漂移检测**——契约质量与一致性无 CI 强制。

---

## 4. Event-Driven Contract Gap

### 4.1 领域事件清单（从代码库提取）

项目共有 **20+ 领域事件类**，分布在 7 个服务中，全部通过 Pulsar 发布，**零 AsyncAPI spec**。

#### 4.1.1 core-business（3 事件）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `CoursePublishedEvent` | `services/core-business/src/main/java/com/ep/business/course/CoursePublishedEvent.java` | 课程发布事件 |
| `CourseArchivedEvent` | `services/core-business/src/main/java/com/ep/business/course/CourseArchivedEvent.java` | 课程归档事件 |
| `UserFrozenEvent` | `services/core-business/src/main/java/com/ep/business/user/UserFrozenEvent.java` | 用户冻结事件 |

#### 4.1.2 grading-service（2 事件）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `AssignmentPublishedEvent` | `services/grading-service/src/main/java/com/ep/grading/homework/event/AssignmentPublishedEvent.java` | 作业发布事件 |
| `AssignmentClosedEvent` | `services/grading-service/src/main/java/com/ep/grading/homework/event/AssignmentClosedEvent.java` | 作业关闭事件 |

#### 4.1.3 knowledge-service（2 事件）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `DocumentParsedEvent` | `services/knowledge-platform/knowledge-service/src/main/java/com/ep/knowledge/service/event/DocumentParsedEvent.java` | 文档解析完成事件 |
| `DocumentFailedEvent` | `services/knowledge-platform/knowledge-service/src/main/java/com/ep/knowledge/service/event/DocumentFailedEvent.java` | 文档解析失败事件 |

#### 4.1.4 tenant-service（3 事件）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `TenantActivatedEvent` | `services/knowledge-platform/tenant-service/src/main/java/com/ep/knowledge/tenant/event/TenantActivatedEvent.java` | 租户激活事件 |
| `TenantSuspendedEvent` | `services/knowledge-platform/tenant-service/src/main/java/com/ep/knowledge/tenant/event/TenantSuspendedEvent.java` | 租户暂停事件 |
| `TenantReactivatedEvent` | `services/knowledge-platform/tenant-service/src/main/java/com/ep/knowledge/tenant/event/TenantReactivatedEvent.java` | 租户重新激活事件 |

#### 4.1.5 order-platform（2 事件）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `OrderPaidEvent` | `services/order-platform/order-platform-service/src/main/java/com/ep/order/event/OrderPaidEvent.java` | 订单支付完成事件 |
| `OrderRefundedEvent` | `services/order-platform/order-platform-service/src/main/java/com/ep/order/event/OrderRefundedEvent.java` | 订单退款事件 |

#### 4.1.6 senior-care（5 事件，附加模块）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `CarePlanActivatedEvent` | `services/senior-care/src/main/java/com/ep/senior/care/CarePlanActivatedEvent.java` | 照护计划激活事件 |
| `CarePlanCompletedEvent` | `services/senior-care/src/main/java/com/ep/senior/care/CarePlanCompletedEvent.java` | 照护计划完成事件 |
| `ElderActivatedEvent` | `services/senior-care/src/main/java/com/ep/senior/profile/ElderActivatedEvent.java` | 长者激活事件 |
| `ElderDischargedEvent` | `services/senior-care/src/main/java/com/ep/senior/profile/ElderDischargedEvent.java` | 长者出院事件 |
| `HealthAbnormalDetectedEvent` | `services/senior-care/src/main/java/com/ep/senior/health/HealthAbnormalDetectedEvent.java` | 健康异常检测事件 |

#### 4.1.7 boardgame-service（3 事件，附加模块）

| 事件类 | 文件 | 说明 |
|--------|------|------|
| `RoomStartedEvent` | `services/boardgame-service/src/main/java/com/ep/boardgame/room/RoomStartedEvent.java` | 房间开始事件 |
| `RoomFinishedEvent` | `services/boardgame-service/src/main/java/com/ep/boardgame/room/RoomFinishedEvent.java` | 房间结束事件 |
| `MatchFinishedEvent` | `services/boardgame-service/src/main/java/com/ep/boardgame/room/MatchFinishedEvent.java` | 对局完成事件 |

**事件总计：20 个领域事件类**（core 3 + grading 2 + knowledge 2 + tenant 3 + order 2 + senior-care 5 + boardgame 3），**全部零 AsyncAPI spec**。

### 4.2 Pulsar Topic 清单（从代码库提取）

项目共有 **7+ 个 Pulsar topic**，全部无 AsyncAPI spec。

| Topic | 生产者服务 | 消费者 | 事件数 | AsyncAPI spec |
|-------|----------|--------|--------|--------------|
| `persistent://public/default/ep-business-domain-events` | core-business | data-platform（CDC）| 3（CoursePublished/CourseArchived/UserFrozen）| ❌ 无 |
| `persistent://public/default/senior-care-events` | senior-care | （订阅方未审计）| 5（CarePlan*/Elder*/HealthAbnormal）| ❌ 无 |
| `persistent://public/default/boardgame-service-events` | boardgame-service | （订阅方未审计）| 3（RoomStarted/RoomFinished/MatchFinished）| ❌ 无 |
| `persistent://public/default/ep-cache-invalidate` | 全部 9 服务（Java/Go/Python 缓存 SDK）| 全部 9 服务 | 1（缓存失效广播）| ❌ 无 |
| `persistent://public/cdc/user_group` | TiCDC | data-platform（Flink）| CDC 流 | ❌ 无 |
| `persistent://public/cdc/learning_event_group` | TiCDC | data-platform（Flink）| CDC 流 | ❌ 无 |
| `persistent://public/cdc/order_group` | TiCDC | data-platform（Flink）| CDC 流 | ❌ 无 |

> **注**：ai-platform 内部另有 Hermes/LLM topic（`persistent://public/hermes/events`、`persistent://public/default/llm-request`、`persistent://public/default/llm-response`、`persistent://public/a2a/task-events`），均为内部事件总线，非跨服务领域事件，亦无 AsyncAPI spec。

### 4.3 事件契约 Gap 分析

| Gap | 影响 | 严重度 |
|-----|------|--------|
| 零 AsyncAPI spec | 事件消费者无法从 schema 推导 payload 结构，事件契约无强制 | 🔴 High |
| 事件 payload 仅存在于 Java 类（继承 `DomainEvent` 基类） | 非 Java 消费者（Go ws-gateway、Python ai-platform）需手工对齐字段 | 🟡 Medium |
| 无 Pulsar Schema Registry | 事件序列化无 schema 校验，生产者/消费者字段漂移无感知 | 🟡 Medium |
| 跨语言缓存失效消息无 schema | `ep-cache-invalidate` topic 的 `{cacheName,key}` JSON 格式仅在代码中定义（`EventTopics.java`），跨语言消费者靠口头约定 | 🟡 Medium |
| CDC topic 无 Avro schema | `persistent://public/cdc/*` 三组 CDC topic 虽在 Flink pipeline 中标注 `AVRO`，但无独立 schema 文件 | 🟢 Low |

---

## 5. Remediation Plan

### 5.1 D-206: 契约优先 OpenAPI + Spectral Linter（P1）

**目标**：将 9 个 Java 服务的 OpenAPI 从 code-first 升级为 contract-first，引入 Spectral linter。

**范围**：portal-bff、core-business、order-platform、payment-platform、grading-service、data-platform、knowledge-bff、knowledge-service、retrieval-service、agent-service、tenant-service（11 Java 服务，ws-gateway Go + ai-platform Python 另行处理）。

**交付物**：
1. `docs/contracts/openapi/<service>-openapi.yaml` — 11 个手工编写的 contract-first OpenAPI 3.1 YAML
2. `.spectral.yaml` — Spectral ruleset（命名规范、必填字段、错误响应、security scheme、版本管理）
3. CI job `contract-lint` — `spectral lint docs/contracts/openapi/*.yaml`，fail 即阻塞
4. `scripts/validate-contract-first.sh` — 校验 spec 先于代码（git pre-commit hook）
5. 前端 codegen 改为从 `docs/contracts/openapi/*.yaml` 生成（而非从运行服务拉取）

**验收标准**：
- 11 服务均有 contract-first OpenAPI YAML
- Spectral lint 在 CI 中通过
- 前端 `pnpm gen:api` 从 YAML 文件生成 TS 客户端
- spec 文件作为 PR 评审的必检项

### 5.2 D-207: AsyncAPI 规格（P1）

**目标**：为 8 个事件 topic 创建 AsyncAPI 3.0 spec，覆盖 20+ 领域事件。

**范围**：

| AsyncAPI spec | 覆盖 topic | 事件数 |
|--------------|-----------|--------|
| `business-domain-events.yaml` | `ep-business-domain-events` | 3（CoursePublished/CourseArchived/UserFrozen）|
| `senior-care-events.yaml` | `senior-care-events` | 5（CarePlan*/Elder*/HealthAbnormal）|
| `boardgame-service-events.yaml` | `boardgame-service-events` | 3（Room*/Match*）|
| `cache-invalidate.yaml` | `ep-cache-invalidate` | 1（缓存失效广播，跨语言）|
| `cdc-user.yaml` | `persistent://public/cdc/user_group` | CDC 流 |
| `cdc-learning-event.yaml` | `persistent://public/cdc/learning_event_group` | CDC 流 |
| `cdc-order.yaml` | `persistent://public/cdc/order_group` | CDC 流 |
| `ai-platform-events.yaml` | Hermes/LLM topic | 内部事件总线 |

**交付物**：
1. `docs/contracts/asyncapi/<spec>.yaml` — 8 个 AsyncAPI 3.0 YAML
2. 每个事件定义 message payload schema（JSON Schema inline 或 $ref）
3. `scripts/validate-asyncapi.sh` — AsyncAPI CLI 校验
4. CI job `asyncapi-lint` — 校验全部 AsyncAPI spec

**验收标准**：
- 8 个 AsyncAPI spec 覆盖全部 20+ 领域事件
- 每个事件有明确的 payload schema
- AsyncAPI lint 在 CI 中通过
- 跨语言缓存失效消息 schema 统一（消除 `ep-memory-contract.md` 手工镜像问题）

### 5.3 D-208: 前端技术债（Taro 解耦）— 并行

**目标**：前端 Taro 框架耦合技术债，与 SDD 升级并行推进。不在本报告范围内，单独 ADR 记录。

### 5.4 未来：契约漂移检测 + 客户端 Codegen（Level 4）

| 项目 | 描述 | 优先级 |
|------|------|--------|
| `oasdiff` / `openapi-diff` | CI 比较 contract-first spec 与 springdoc 生成 spec，drift 即 fail | P2 |
| 服务端 stub codegen | 从 contract-first OpenAPI 生成 Spring `@RestController` interface（可选） | P3 |
| Pulsar Schema Registry | AsyncAPI spec 注册到 Pulsar Schema Registry，生产者/消费者 schema 强制 | P2 |
| Pact from spec | 从 OpenAPI/AsyncAPI spec 自动生成 Pact 契约测试 | P3 |

---

## 6. 结论

教育平台拥有**卓越的设计纪律**——131+ ADRs（每条含三方案对比）、TOGAF Phase A-H 全制品覆盖、Iron Law 8（三方案强制律）/Iron Law 9（运行验证真相律）/Iron Law 10（设计-实现可追溯性强制律）立法、血泪教训吸收（ADR-T01~T14）。这是国内外项目中顶级的决策治理水平。

**但设计纪律不等于契约纪律**。

项目的 OpenAPI/AsyncAPI spec 是代码的**副产物**（generated artifacts），而非 source-of-truth（authored contracts）：

1. **OpenAPI 是 code-first**：`scripts/generate-openapi-specs.sh` 从运行中服务的 `/v3/api-docs` 拉取 springdoc 生成的 JSON，spec 是代码的副产物，不是契约源头。
2. **零 AsyncAPI**：20+ 领域事件横跨 7 个 Pulsar topic，全部无 AsyncAPI spec，事件消费者无法从 schema 推导 payload。
3. **无 Spectral linter，无契约漂移检测**：契约质量与一致性无 CI 强制。

**当前 SDD 成熟度：Level 2**（code-first OpenAPI，设计文档顶级但 spec 是代码副产物）。

**升级到 Level 3（contract-first）需要范式转变**：将 OpenAPI/AsyncAPI spec 从"代码生成物"转变为"代码的输入"。这意味着：
- spec 先于代码编写，作为设计评审的一部分
- CI 中 Spectral linter 强制 spec 质量
- 前端从 spec 文件 codegen，而非从运行服务拉取
- AsyncAPI spec 覆盖全部领域事件

项目已有的设计纪律（131+ ADRs、Iron Laws、TOGAF）为这一范式转变提供了坚实基础——团队已具备"设计优先"的文化，只需将"设计"从文档维度延伸到"机器可执行的契约"维度。

**关键信息**：代码架构是 ~98% 完成（A），但 SDD/契约成熟度是下一前沿（Level 2 → Level 3）。D-206（契约优先 OpenAPI + Spectral）和 D-207（AsyncAPI 规格）是升级到 Level 3 的关键路径。

---

## 附录 A: 证据索引

### A.1 OpenAPI 生成脚本

- `scripts/generate-openapi-specs.sh` — 从运行服务 `/v3/api-docs` 拉取 springdoc OpenAPI JSON（code-first 证据）
- `frontend/packages/api-client/schemas/*-openapi.json` — 生成的 OpenAPI JSON（非手工编写）
- `scripts/validate-api-contracts.py` — 契约校验脚本（校验生成物，非 contract-first）

### A.2 Proto 文件

- `services/boardgame-service/src/main/proto/StockfishProto/service.proto` — 唯一 proto 文件

### A.3 领域事件基类

- `services/core-business/src/main/java/com/ep/business/common/DomainEvent.java`
- `services/grading-service/src/main/java/com/ep/grading/common/DomainEvent.java`
- `services/order-platform/order-platform-service/src/main/java/com/ep/order/event/DomainEvent.java`
- `services/knowledge-platform/knowledge-service/src/main/java/com/ep/knowledge/service/event/DomainEvent.java`
- `services/knowledge-platform/tenant-service/src/main/java/com/ep/knowledge/tenant/event/DomainEvent.java`
- `services/senior-care/src/main/java/com/ep/senior/common/DomainEvent.java`
- `services/boardgame-service/src/main/java/com/ep/boardgame/common/DomainEvent.java`

### A.4 Pulsar 事件发布器

- `services/core-business/src/main/java/com/ep/business/event/PulsarEventPublisher.java`
- `services/order-platform/order-platform-service/src/main/java/com/ep/order/infrastructure/PulsarEventPublisher.java`
- `services/senior-care/src/main/java/com/ep/senior/event/PulsarEventPublisher.java`
- `services/boardgame-service/src/main/java/com/ep/boardgame/event/PulsarEventPublisher.java`
- `services/ep-cache/src/main/java/com/ep/cache/event/EventPublisher.java`（缓存失效广播）

### A.5 Pulsar Topic 配置

- `services/core-business/src/main/resources/application.yml` — `ep-business-domain-events`
- `services/senior-care/src/main/resources/application.yml` — `senior-care-events`
- `services/boardgame-service/src/main/resources/application.yml` — `boardgame-service-events`
- `services/ep-cache/src/main/java/com/ep/cache/CacheBroadcastConfig.java` — `ep-cache-invalidate`
- `services/data-platform/src/main/resources/flink-pipelines/cdc-*.yaml` — CDC topics

### A.6 缺失项证据

- `find . -name "*.asyncapi*" -o -name "*asyncapi*"` → **零结果**（无 AsyncAPI spec）
- `find . -name ".spectral*" -o -name "spectral*"` → **零结果**（无 Spectral linter）
- CI workflow 无 `oasdiff`/`openapi-diff` job（无契约漂移检测）

---

## 附录 B: SDD 成熟度模型参考

| Level | 名称 | 行业参考 |
|-------|------|---------|
| Level 0 | No Specs | — |
| Level 1 | Documentation-first | TOGAF ADM · C4 Model · ADR |
| Level 2 | Code-first OpenAPI | springdoc · swagger-codegen · FastAPI auto-docs |
| Level 3 | Contract-first | Stoplight · Spectral · OpenAPI 3.1 · AsyncAPI 3.0 · design-first API |
| Level 4 | Contract-first + Drift Detection + Auto-generate | oasdiff · openapi-diff · Pulsar Schema Registry · full codegen pipeline |

---

**审计人**: ZCode（SDD 契约成熟度评估视角）
**审计日期**: 2026-08-12
**关联文档**: `docs/architecture/architecture-scorecard.md` v5（维度 4: 9.0/10, A-）
**下一步**: D-206（契约优先 OpenAPI + Spectral）+ D-207（AsyncAPI 规格）
