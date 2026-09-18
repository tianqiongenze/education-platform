# 教育平台项目群 — 命名规范与编码标准 (D-112)

> 最后更新: 2026-08-08 | 维护: ZCode(首席架构师) | 关联决策: D-112

---

## 1. Java 命名规范

### 1.1 包名

| 规则 | 示例 | 说明 |
|------|------|------|
| 全小写, 反域名 | `com.ep.order` | 不含下划线/连字符 |
| 避免重复段 | `com.ep.knowledge.service` ✅ / `com.ep.knowledge.service.service` ❌ | 同名段不可连续出现 |
| 层次清晰 | `com.ep.order.api` / `com.ep.order.model` / `com.ep.order.service.impl` | api→model→service→impl 分层 |

### 1.2 类名 (PascalCase)

| 类型 | 后缀/模式 | 示例 | 说明 |
|------|-----------|------|------|
| JPA 实体 (内部模型) | `Model` | `OrderModel`, `CourseModel` | 区分 API 层 Dto, 避免 `Entity` 后缀 (历史遗留) |
| API 数据传输对象 | `Dto` (非 DTO) | `OrderDto`, `AgentDto` | 统一 `Dto` 大小写, 不用全大写 `DTO` |
| 命令对象 | `Cmd` (非 Command) | `CreateOrderCmd`, `StartGradingCmd` | 统一缩写后缀, 与 `CreateOrderCmd` 对齐 |
| 查询对象 | `Query` | `OrderListQuery` | 查询参数封装 |
| 服务接口 | `Service` | `OrderService`, `GradingService` | 无 `I` 前缀 |
| 服务实现 | `ServiceImpl` | `OrderServiceImpl` | 接口 + Impl 模式 |
| RPC 服务接口 | `RpcService` | `AiGradingRpcService` | 跨服务 Dubbo RPC |
| RPC 服务实现 | `RpcServiceImpl` | `AiGradingRpcServiceImpl` | |
| Controller | `Controller` | `OrderController`, `GradingController` | REST 端点 |
| 过滤器 | `Filter` | `AuthFilter`, `CellRouterFilter` | Servlet Filter |
| 配置类 | `Config` | `OpenApiConfig`, `DevGuardConfig` | `@Configuration` 类 |
| 异常 | `Exception` | `InsufficientStockException` | 业务异常 |
| 测试类 | `*Test` (单数) | `OrderServiceTest`, `AgentServiceListTest` | 不用 `*Tests` 复数; 方法名不在类名中间嵌入 |

### 1.3 方法名 (camelCase)

| 规则 | 示例 |
|------|------|
| 动词开头 | `createOrder()`, `findByUserId()`, `startGrading()` |
| 布尔返回用 `is`/`has`/`can` | `isPublished()`, `hasPermission()` |
| 私有方法 `_` 前缀可选 | `_validateInput()` 或 `validateInput()` |

### 1.4 REST 路径 (kebab-case + 版本前缀)

| 规则 | 示例 | 说明 |
|------|------|------|
| 统一 `/api/v1/` 前缀 | `/api/v1/orders`, `/api/v1/courses` | 所有 BFF Controller 统一版本前缀 |
| kebab-case 路径段 | `/by-user-course` ✅ / `/byUserCourse` ❌ | 连字符分隔, 不用 camelCase |
| 资源名复数 | `/api/v1/orders`, `/api/v1/users` | 集合用复数 |
| 子资源嵌套 | `/api/v1/orders/{orderId}/items` | 层级关系清晰 |
| 路径变量 camelCase | `{orderId}`, `{courseId}` | URL 变量用 camelCase |

### 1.5 数据库命名 (snake_case)

| 规则 | 示例 | 说明 |
|------|------|------|
| 表名 `ep_` 前缀 + snake_case | `ep_payment`, `ep_refund`, `ep_order` | 项目统一前缀 |
| 列名 snake_case | `tenant_id`, `created_at`, `api_key` | 不用 camelCase |
| 避免 MySQL 保留字 | `api_key` ✅ / `key` ❌ | `key`, `order`, `group` 等保留字必须避开 |
| 注释与 DDL 一致 | 注释引用 `ep_payment` 而非 `t_opm_payment` | 代码注释中的表名必须与 DDL 一致 |

---

## 2. Go 命名规范

### 2.1 Module 路径

| 规则 | 示例 | 说明 |
|------|------|------|
| 统一 `github.com/education-platform/` 前缀 | `github.com/education-platform/gateway-go` | 所有 Go module 统一路径 |
| 共享包 | `github.com/education-platform/ep-go-common` | 跨服务复用的工具包 |

### 2.2 包名与文件名

| 规则 | 示例 |
|------|------|
| 包名全小写单词 | `jwt`, `httpresp`, `health` |
| 文件名 snake_case | `validator.go`, `response.go` |
| 测试文件 `_test.go` | `validator_test.go` |

---

## 3. Python 命名规范

| 类型 | 规则 | 示例 |
|------|------|------|
| 模块名 | snake_case | `procedural_store.py`, `config.py` |
| 类名 | PascalCase | `JsonFormatter`, `MemoryManager` |
| 函数/变量 | snake_case | `get_env()`, `health_response()` |
| 常量 | UPPER_SNAKE | `DEFAULT_TIMEOUT` |
| 包名 | 全小写 | `ep_common` |

---

## 4. TypeScript / 前端命名规范

### 4.1 文件与目录

| 类型 | 规则 | 示例 |
|------|------|------|
| 组件文件 | PascalCase.tsx | `HomePage.tsx`, `PortalLayout.tsx` |
| 非组件文件 | camelCase.ts / kebab-case | `mockCourses.ts`, `useCourseList.ts` |
| 目录 | kebab-case | `portal-web/`, `api-client/` |
| 测试文件 | `*.test.tsx` / `*.spec.ts` | `HomePage.test.tsx` |

### 4.2 标识符

| 类型 | 规则 | 示例 |
|------|------|------|
| 组件 | PascalCase | `CourseCatalogPage`, `PortalLayout` |
| 函数/变量 | camelCase | `useCourseList`, `apiClient` |
| 类型/接口 | PascalCase | `UseCourseListParams`, `ApiClient` |
| 常量 | UPPER_SNAKE | `PAGE_SIZE`, `NAV_ITEMS`, `MOCK_COURSES` |
| CSS 类名 | kebab-case | `portal-footer`, `hero-section` |
| 环境变量 | `VITE_` 前缀 + UPPER_SNAKE | `VITE_API_BASE_URL`, `VITE_ADMIN_WEB_URL` |

### 4.3 Workspace 包

| 规则 | 示例 | 说明 |
|------|------|------|
| `@ep/` scope 前缀 | `@ep/api-client`, `@ep/query`, `@ep/shared`, `@ep/ui-kit` | 所有 workspace 包统一 scope |
| 包名 kebab-case | `api-client`, `ui-kit` | 不用 camelCase 或 PascalCase |

### 4.4 exactOptionalPropertyTypes 适配

项目启用 `exactOptionalPropertyTypes: true`，可选属性不接受 `undefined` 值：

```typescript
// ❌ 错误: 传 undefined 给可选属性
const params = { page: 1, keyword: keyword || undefined };

// ✅ 正确: 条件构建对象
const params: { page: number; keyword?: string } = { page: 1 };
if (keyword) params.keyword = keyword;
```

---

## 5. API 版本与路径规范

| 规则 | 说明 |
|------|------|
| 版本前缀 | 所有 REST API 统一 `/api/v1/` 前缀 |
| 认证白名单 | AuthFilter/CellRouterFilter 白名单需同时包含 `/api/auth/` 和 `/api/v1/auth/` |
| 路径风格 | kebab-case (`/by-user-course`, `/api/v1/orders`) |
| HTTP 方法语义 | GET 查询, POST 创建, PUT 全量更新, PATCH 部分更新, DELETE 删除 |

---

## 6. K8s 资源命名

| 类型 | 规则 | 示例 |
|------|------|------|
| Deployment | kebab-case | `frontend-portal`, `bff-java`, `gateway-go` |
| Service | 与 Deployment 同名 | `frontend-portal`, `bff-java` |
| Ingress host | 域名分用途 | `www.edu.com` (前端), `api.edu.com` (API) |
| ConfigMap | kebab-case + 用途 | `common-config`, `cell-config` |
| Namespace | kebab-case | `education-platform` |

---

## 7. 端口分配

| 服务 | 端口 | 类型 |
|------|------|------|
| portal-web | 3004 | Vite dev |
| admin-web | 3000 | Vite dev |
| student-h5 | 3001 | Vite dev |
| student-mp | 3002 | Taro dev |
| knowledge-admin | 3003 | Vite dev |
| bff-java | 8090 | Spring Boot |
| gateway-go | 8081 | Go HTTP |
| data-platform | 8082 | Spring Boot |
| core-business | 8083 | Spring Boot |
| order | 8084 | Spring Boot |
| grading-service | 8085 | Spring Boot |
| ai-platform | 8086 | Python (Litestar/Granian) |
| payment | 8087 | Spring Boot |
| portal-bff | 8070 | Spring Boot |
| knowledge-platform | 8071 | Spring Boot |
| knowledge-retrieval | 8072 | Spring Boot |
| knowledge-agent | 8073 | Spring Boot |
| knowledge-tenant | 8074 | Spring Boot |

---

## 8. 违规检测 (CI 门禁规划)

| 工具 | 范围 | 状态 |
|------|------|------|
| `check-iron-law-8.py` | decisions.jsonl ≥3 alternatives | ✅ 已部署 |
| ESLint | TypeScript 命名 | ✅ 已配置 (@ep/lint-config) |
| ArchUnit (规划中) | Java 包/类命名约束 | 规划中 |
| OpenAPI validate-api-contracts.py | API 路径合规 | ✅ 已部署 |

> 本文档作为后续 CI 命名规范门禁的依据。新增代码必须遵循以上规范, 违规应在 PR 阶段被拦截。
