# 项目命名规范审计 + 重构方案 (D-137)

> **日期**: 2026-08-09  
> **评估范围**: 教育平台项目群全量目录/模块/包命名  
> **原则**: 命名应 **表意准确**（见名知意）、**风格统一**（同层一致）、**层次清晰**（领域 > 子域 > 模块）

---

## 一、当前命名问题诊断

### 1.1 服务层命名问题

| 当前目录 | 问题 | 严重度 |
|---------|------|--------|
| `sp02-data` | "data" 过于泛化，无法区分是数据中台还是数据接入 | 🟡 |
| `sp03-business` | "business" 过于泛化，实际只有课程+用户，不表意 | 🔴 |
| `sp03-order` | ✅ 表意准确 | — |
| `sp03-payment` | ✅ 表意准确 | — |
| `sp04-ai` | "ai" 过于泛化，实际是 LLM Gateway + RAG + Agent 平台 | 🟡 |
| `sp05-api` | 命名为 "api" 但实际是 Dubbo 契约包，应归入共享库 | 🔴 |
| `sp05-itbackend` | "itbackend" 非表意命名，实际是作业批改服务 | 🔴 |
| `sp07-knowledge` | ✅ 表意准确，但 7 子模块命名冗长(sp07-knowledge-service) | 🟡 |
| `sp08-elder` | "elder" 不够正式，应表意为"银发关怀"或"长者服务" | 🟡 |
| `sp09-boardgame` | ✅ 表意准确但非教育核心域 | 🟢 |
| `bff-java` | 缺少领域前缀，应表明是哪个 BFF | 🟡 |
| `gateway-go` | 缺少功能描述，应表意为 WebSocket 网关 | 🟡 |
| `ep-api` | 与 sp05-api 功能重复（都是 Dubbo 契约） | 🔴 |
| `ep-disruptor` | 已废弃但仍占目录位 | 🟡 |
| `ep-rw-java` | 已废弃但仍占目录位 | 🟡 |
| `ep-go-common` | 命名风格不一致（其他共享库是 ep-xxx-java/python） | 🟡 |
| `ep-python-common` | 同上，应为 ep-common-python | 🟡 |
| `ep-memory-java` | ✅ 表意准确 | — |
| `ep-web-common` | ✅ 表意准确 | — |
| `ep-cache` | ✅ 表意准确 | — |

### 1.2 前端命名问题

| 当前目录 | 当前 name | 问题 |
|---------|-----------|------|
| `apps/admin-web` | `admin-web` | 缺少 `@ep/` 前缀，与其他 app 不一致 |
| `apps/portal-web` | `portal-web` | 同上 |
| `apps/student-h5` | `student-h5` | 同上 |
| `apps/student-mp` | `student-mp` | 同上 |
| `apps/knowledge-admin` | `@ep/knowledge-admin` | ✅ |
| `apps/knowledge-h5` | `@ep/knowledge-h5` | ✅ |
| `apps/knowledge-mp` | `@ep/knowledge-mp` | ✅ |

### 1.3 包名问题

| 当前包名 | 问题 |
|---------|------|
| `com.ep.itbackend` | "itbackend" 非表意，应为 `com.ep.grading` |
| `com.ep.game` | 应为 `com.ep.boardgame` 更准确 |
| `com.ep.elder` | 应为 `com.ep.senior` 或 `com.ep.eldercare` 更正式 |

---

## 二、改名方案

### 2.1 服务层改名方案

#### 原则
1. **SP 编号保留**：SP 编号是项目群追踪锚点，保留但不作为目录名前缀
2. **领域表意优先**：目录名 = 领域名，不含 SP 编号和技术栈后缀
3. **技术栈后缀仅用于共享库**：共享库用 `ep-<功能>-<语言>` 格式
4. **废弃项目移入 archive/**

#### 改名映射表

| 当前目录 | → 新目录 | 理由 |
|---------|---------|------|
| `sp02-data` | `data-platform` | 表意：数据中台 |
| `sp03-business` | `core-business` | 表意：核心业务（课程+用户） |
| `sp03-order` | `order-platform` | 表意：订单中台 |
| `sp03-payment` | `payment-platform` | 表意：支付中台 |
| `sp04-ai` | `ai-platform` | 表意：AI 中台 |
| `sp05-api` | **合并到 `ep-contract`** | Dubbo 契约包统一 |
| `sp05-itbackend` | `grading-service` | 表意：作业批改服务 |
| `sp07-knowledge` | `knowledge-platform` | 表意：知识中台 |
| `sp08-elder` | `senior-care` | 表意：长者关怀服务 |
| `sp09-boardgame` | `boardgame-service` | 表意：桌游服务 |
| `bff-java` | `portal-bff` | 表意：门户 BFF |
| `gateway-go` | `ws-gateway` | 表意：WebSocket 网关 |
| `ep-api` | **合并到 `ep-contract`** | Dubbo 契约包统一 |
| `ep-disruptor` | `archive/ep-disruptor` | 废弃项目归档 |
| `ep-rw-java` | `archive/ep-rw-java` | 废弃项目归档 |
| `ep-go-common` | `ep-common-go` | 统一格式：ep-common-<lang> |
| `ep-python-common` | `ep-common-python` | 统一格式：ep-common-<lang> |
| `ep-memory-java` | `ep-memory-java` | ✅ 不变 |
| `ep-web-common` | `ep-web-common` | ✅ 不变 |
| `ep-cache` | `ep-cache` | ✅ 不变 |
| `integration-tests` | `integration-tests` | ✅ 不变 |

#### sp07 子模块精简命名

| 当前子模块 | → 新子模块 | 理由 |
|-----------|-----------|------|
| `sp07-api` | `knowledge-api` | 去掉 SP 编号 |
| `sp07-bff` | `knowledge-bff` | 去掉 SP 编号 |
| `sp07-knowledge-service` | `knowledge-service` | 去掉冗余前缀 |
| `sp07-retrieval-service` | `retrieval-service` | 去掉冗余前缀 |
| `sp07-agent-service` | `agent-service` | 去掉冗余前缀 |
| `sp07-tenant-service` | `tenant-service` | 去掉冗余前缀 |
| `sp07-mcp-server` | `mcp-server` | 去掉冗余前缀 |

### 2.2 前端改名方案

| 当前 name | → 新 name | 理由 |
|-----------|-----------|------|
| `admin-web` | `@ep/admin-web` | 统一 `@ep/` 前缀 |
| `portal-web` | `@ep/portal-web` | 统一 `@ep/` 前缀 |
| `student-h5` | `@ep/student-h5` | 统一 `@ep/` 前缀 |
| `student-mp` | `@ep/student-mp` | 统一 `@ep/` 前缀 |

### 2.3 Java 包名改名方案

| 当前包名 | → 新包名 | 理由 |
|---------|---------|------|
| `com.ep.itbackend` | `com.ep.grading` | 表意：批改域 |
| `com.ep.game` | `com.ep.boardgame` | 表意：桌游域 |
| `com.ep.elder` | `com.ep.senior` | 表意：长者域 |

---

## 三、改名影响分析

### 3.1 影响范围

| 变更类型 | 影响文件数 | 风险 |
|---------|-----------|------|
| 目录重命名（services/） | 21 个项目目录 | 中 — 需更新所有引用路径 |
| Maven artifactId 变更 | ~15 个 pom.xml | 中 — 需更新依赖引用 |
| Java package 变更 | ~80 个 .java 文件 | 高 — 需更新 import + 目录结构 |
| 前端 package.json name 变更 | 4 个 app | 低 — 需更新 import 别名 |
| Dubbo 契约合并 | 2 个项目 → 1 个 | 中 — 需更新所有 Dubbo 引用 |

### 3.2 改名策略

采用 **渐进式改名**：
1. **Phase 1**: 目录重命名 + pom.xml artifactId + 前端 package.json name（低风险，不影响编译）
2. **Phase 2**: Java package 重命名（中风险，需更新 import）
3. **Phase 3**: Dubbo 契约合并（高风险，需全量回归）

---

## 四、架构优化方案（与改名同步进行）

### 4.1 P0 修复

| # | 问题 | 方案 | 影响文件 |
|---|------|------|---------|
| P0-2 | gateway-go OTel 未导出 OTLP span | 补全 OTel Go SDK 导出器（离线降级为 W3C 透传，CI 环境导出 OTLP） | `gateway-go/src/middleware/otel.go` |
| P0-3 | sp07-agent-service HikariCP=10 | 调整为 20（与 knowledge/tenant 一致） | `sp07-agent-service application.yml` |

### 4.2 P1 修复

| # | 问题 | 方案 | 影响文件 |
|---|------|------|---------|
| P1-2 | 无 mTLS | 新增 mTLS 配置文档 + Dubbo SSL 配置模板 | `deploy/k8s/base/mtls.yaml`(新增) |
| P1-3 | 契约包重复 | 合并 ep-api + sp05-api → ep-contract | `services/ep-contract/` |
| P1-4 | Saga 无 E2E 测试 | 新增 Saga 端到端集成测试 | `integration-tests/src/test/` |
| P1-5 | sp04-ai 无 API 鉴权 | 新增 JWT 校验中间件 | `sp04-ai/src/` |
| P1-6 | 虚拟线程无 Semaphore | 新增 DB 访问 Semaphore 限流 | `ep-web-common/` |

### 4.3 P2 改进

| # | 问题 | 方案 |
|---|------|------|
| P2-1 | 废弃项目占目录 | 移入 `services/archive/` |
| P2-4 | 覆盖率门禁非硬失败 | CI 配置 `--fail-under 80` |
| P2-5 | JWT 无 refresh token | bff-java 新增 refresh token 端点 |
