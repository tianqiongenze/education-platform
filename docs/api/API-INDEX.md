# Education Platform API 文档索引

本项目的所有 Spring Boot 服务均使用 springdoc-openapi 自动生成 OpenAPI 3.0 文档。

## 统一访问入口 (D-106/D-107/D-111)

### 前端入口 (D-111)

**生产环境前端门户**: `https://www.edu.com`

| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | 首页 | Hero banner + 精选课程 + 事业群入口 (无需登录) |
| `/courses` | 公开课目录 | 搜索/分类筛选/分页 (无需登录) |
| `/courses/:id` | 课程详情 | 课程预览, 登录后显示报名按钮 |
| `/about` | 关于平台 | 平台特性 + 技术亮点 |
| `/bu` | 事业群导航 | 3大事业群卡片式入口 |
| `/login` | 统一登录 | 登录后跳转到对应应用 |

> 前端门户 (portal-web) 使用 Vite + React 19 + AntD 5, 复用 `@ep/api-client` / `@ep/query` / `@ep/shared` / `@ep/ui-kit` workspace 包。Dev port 3004。

### API 入口

**生产环境单一 API 入口**: `https://api.edu.com`

| 路径 | 后端 | 说明 |
|------|------|------|
| `/api/v1/users/**`, `/api/v1/courses/**` | sp03-business (Dubbo) | 课程/用户 |
| `/api/v1/orders/**` | sp03-order (Dubbo) | 订单中台 |
| `/proxy/data/**` | sp02-data (HTTP代理) | 数据中台 |
| `/proxy/ai/**` | sp04-ai (HTTP代理) | AI中台 |
| `/proxy/itbackend/**` | sp05-itbackend (HTTP代理) | IT后端 |
| `/proxy/knowledge/**` | sp07-bff (HTTP代理) | 知识中台 |
| `/proxy/order/**` | sp03-order-service (HTTP代理) | 订单REST |
| `/proxy/payment/**` | sp03-payment-service (HTTP代理) | 支付REST |
| `/ws/**` | gateway-go (WebSocket) | WS实时推送 |
| `/swagger-ui.html` | bff-java (聚合) | **统一API在线测试** |

**聚合 Swagger UI** (D-107): 访问 `http://api.edu.com/swagger-ui.html` → 顶部下拉框选择任意服务 → 在线测试全部 API。

> 注: HTTP 代理默认禁用 (EP_DEV_ONLY guard)。开发环境设置 `EP_PROXY_ENABLED=true` 启用。

## 各服务独立访问

每个服务启动后，也可通过各自端口独立访问：

| 服务 | Swagger UI | OpenAPI JSON | 端口 |
|------|-----------|--------------|------|
| BFF Java | http://localhost:8090/swagger-ui.html | http://localhost:8090/v3/api-docs | 8090 |
| Portal Web | — | — | 3004 |
| SP03 Business | http://localhost:8083/swagger-ui.html | http://localhost:8083/v3/api-docs | 8083 |
| SP03 Order | http://localhost:8084/swagger-ui.html | http://localhost:8084/v3/api-docs | 8084 |
| SP03 Payment | http://localhost:8087/swagger-ui.html | http://localhost:8087/v3/api-docs | 8087 |
| SP05 IT-Backend | http://localhost:8085/swagger-ui.html | http://localhost:8085/v3/api-docs | 8085 |
| SP07 BFF (Gateway) | http://localhost:8070/swagger-ui.html | http://localhost:8070/v3/api-docs | 8070 |
| SP07 Knowledge | http://localhost:8071/swagger-ui.html | http://localhost:8071/v3/api-docs | 8071 |
| SP07 Retrieval | http://localhost:8072/swagger-ui.html | http://localhost:8072/v3/api-docs | 8072 |
| SP07 Agent | http://localhost:8073/swagger-ui.html | http://localhost:8073/v3/api-docs | 8073 |
| SP07 Tenant | http://localhost:8074/swagger-ui.html | http://localhost:8074/v3/api-docs | 8074 |
| SP02 Data | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs | 8082 |

> 注：SP02-Data 的 `application.yml` 当前未在仓库内提交 `server.port`，端口 `8082` 为约定值。若实际部署不同，请以运行时 `server.port` 为准。

## 安全认证

所有 API 使用 `X-Tenant-Id` Header 进行租户隔离认证（SecurityScheme: `TenantAuth`，APIKEY in HEADER）。
- BFF Java / SP03 Business / SP05 IT-Backend 使用 `bearerAuth`（JWT / HTTP Bearer）。
- SP07 系列服务与 SP02-Data 使用 `TenantAuth`（`X-Tenant-Id` Header）。
- 部分管理 API 额外使用 `X-Role` Header 进行 RBAC 控制（D-068）。

## API 分组

### BFF 层
- **bff-java**: 前端聚合 BFF，统一入口（含 Dubbo 透传 Controller、JWKS）
- **sp07-bff**: 知识中台 BFF（Gateway + 聚合，调用 retrieval / agent）

### 业务中台
- **sp03-business**: 课程管理、用户管理（D-104/D-105 瘦身后保留 Course/User 域）
- **sp03-order**: 订单中台（独立，D-104 抽取 — Order 聚合根、状态机、Dubbo RPC 调 Payment）
- **sp03-payment**: 支付中台（独立，D-105 抽取 — Payment/Refund、PaymentChannelSpi 策略、Mock Channel）
- **sp05-itbackend**: IT 后端 BU 用户管理、学习记录、作业 / 提交 / 评分

### 知识中台
- **sp07-knowledge-service**: 文档上传/查询/分类（@Tag「知识库管理」）
- **sp07-agent-service**: Agent 编排 ROUTER / PIPELINE / DEBATE（@Tag「智能体管理」）
- **sp07-tenant-service**: 多租户管理、配额、API Key（@Tag「租户管理」）
- **sp07-retrieval-service**: 混合检索 + RAG 问答（@Tag「知识检索」）

### 数据中台
- **sp02-data**: 元数据查询、数据目录（@Tag「元数据管理」）

## 前端对接

前端项目可从 `http://<host>:<port>/v3/api-docs` 获取 OpenAPI JSON，
使用 [openapi-typescript](https://github.com/drwpow/openapi-typescript) 或 [orval](https://orval.dev/) 自动生成 TypeScript 客户端代码。

```bash
# 示例：基于 BFF Java 生成 TS 类型
npx openapi-typescript http://localhost:8090/v3/api-docs -o src/api/bff.d.ts
```

## Contract Generation（D-094）

D-094 建立了跨语言 OpenAPI 契约生成流水线：把各服务运行时产出的
`/v3/api-docs` JSON 统一拉到 `frontend/packages/api-client/schemas/`，
校验后用 `openapi-generator-cli` 生成 TypeScript 客户端，从而消除
"手工一次性导出 → 前端拷贝" 的漂移风险。

### 现状

- `schemas/bff-openapi.json` 是 D-070 时从 `bff-java` 手工导出的，目前是
  **仓库内唯一的 spec**。
- 其他服务的 spec 需要服务**运行**后才能由下面的脚本拉取。
- `frontend/packages/api-client/openapi-generator.config.yaml` 当前硬编码
  `inputSpec: schemas/bff-openapi.json`；切换到其他服务时需要改这一行
  （或后续把 `gen:api` 改成遍历 `schemas/` 多次调用）。

### 脚本

| 脚本 | 作用 |
|------|------|
| `scripts/generate-openapi-specs.sh [base-url]` | 拉取所有运行中服务的 `/v3/api-docs` 到 `schemas/`。服务未启动时优雅跳过，退出码恒为 0，可安全放进 CI / pre-commit。默认 `base-url=http://localhost`。 |
| `scripts/validate-api-contracts.py` | 校验 `schemas/` 下所有 spec：JSON 合法性、跨服务 `operationId` 无重复、路径遵循 `/api/...` 约定、每个操作都有 `operationId`。无 spec 时打印告警并退出 0。 |

### 端口映射

脚本内置的端口取自本文件上方的「访问方式」表（与各服务
`application.yml` 的 `server.port` 一致）：

| 服务 | 端口 |
|------|------|
| bff-java | 8090 |
| sp03-business | 8083 |
| sp03-order | 8084 |
| sp03-payment | 8087 |
| sp05-itbackend | 8085 |
| sp07-knowledge | 8071 |
| sp07-bff (Gateway) | 8070 |
| sp07-retrieval | 8072 |
| sp07-agent | 8073 |
| sp07-tenant | 8074 |
| sp02-data | 8082 |

> 注：`sp07-mcp-server` 的 `application.yml` 默认端口也是 **8090**，与
> `bff-java` 冲突，二者不能在同一主机同时运行。默认按 `bff-java`
> 占用 8090 处理；若实际是 `sp07-mcp-server` 在 8090，请用
> `BFF_PORT=<其他端口> ./scripts/generate-openapi-specs.sh` 覆盖。

### 推荐工作流

```bash
# 1. 启动需要的服务（至少 bff-java）。

# 2. 拉取所有运行中服务的 OpenAPI spec。
./scripts/generate-openapi-specs.sh

# 3. 校验 spec（operationId 唯一、路径合规、JSON 合法）。
python3 scripts/validate-api-contracts.py

# 4. 生成 TypeScript 客户端到 frontend/packages/api-client/src/generated/。
cd frontend/packages/api-client && pnpm gen:api
```

CI 集成建议：把第 2、3 步放进 PR 检查；当服务未运行时脚本会优雅跳过，
不会让 CI 误报红。spec 有变更时（diff 非空）再触发第 4 步 codegen。
