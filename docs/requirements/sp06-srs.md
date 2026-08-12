# SP-06 多端前端骨架 —— 软件需求规格说明书(SRS)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP06-SRS-001 | v1.0 | 2026-08-04 | Approved | ZCode(文档工程师) | EP-TOGAF-001 §1/§6 / EP-SPEC-001 §8.1 SP-06 / D-031 |

> 本文是 education-platform 项目群 SP-06 多端前端骨架的**需求基线**。
> 技术选型基于 2025-2026 联网调研（D-031），所有决策有来源 URL 可追溯。

---

## 目录

1. [引言](#1-引言)
2. [角色与用户故事](#2-角色与用户故事)
3. [功能需求(逐端)](#3-功能需求逐端)
4. [非功能需求(SLO 量化表)](#4-非功能需求slo-量化表)
5. [约束条件](#5-约束条件)
6. [依赖关系](#6-依赖关系)
7. [验收标准](#7-验收标准)
8. [追溯矩阵](#8-追溯矩阵)

---

## 1. 引言

### 1.1 文档目的

为 SP-06 多端前端骨架确立**完整可测试的需求基线**，覆盖 Web 端(PC 管理后台 + 学习中心)、H5 端(移动浏览器)、微信小程序端三端共 API 契约的前端骨架。核心目标：**一次定义、三端复用、契约驱动、类型安全**。

### 1.2 范围

**In Scope**：

- **Monorepo 基础设施**：pnpm workspace + Turborepo 任务编排，分层包结构（apps/ + packages/）
- **三端应用骨架**：
  - `apps/admin-web`：PC 管理后台（React + Vite，面向运营/教师/管理员）
  - `apps/student-h5`：移动 H5 学习中心（Taro 4 编译 H5，面向学生移动端）
  - `apps/student-mp`：微信小程序学习中心（Taro 4 编译微信小程序，面向学生小程序）
- **共享 API 契约层**：`packages/api-client` —— openapi-generator 从 BFF OAS 自动生成 TS client + Zod schema
- **共享 UI 组件库**：`packages/ui-kit` —— Taro 4 跨端组件（H5 + 小程序同源）
- **共享工具库**：`packages/shared`（类型定义、常量、工具函数、Zod schema）
- **共享状态层**：`packages/query` —— TanStack Query hooks 封装（三端共享查询逻辑）
- **构建与 CI**：Vite(H5) + Taro 内置链(小程序) + Turborepo 远程缓存 + CI 契约校验门
- **类型安全全链路**：TypeScript 5.x strict + Zod 运行时边界校验

**Out of Scope**：

- IT 后端 SP-05 各子平台（直播/在线编程/作业/评分）的业务页面实现（M1 阶段）
- 后端 BFF Java 代码实现（SP-01 Task 5）
- 生产环境部署（见 SP-06 部署方案）
- 原生 App（iOS/Android）—— 三期评估

### 1.3 术语

| 术语 | 含义 |
|------|------|
| BFF | Backend For Frontend，面向前端的后端聚合层(Java Spring Boot) |
| OAS | OpenAPI Specification，API 契约规范(3.1) |
| Monorepo | 单一代码仓库管理多个包/应用 |
| Taro | 京东开源跨端框架，一套代码编译多端(H5/小程序/RN) |
| TanStack Query | 服务端状态管理库(React Query v5)，缓存/同步/更新 |
| Zod | TypeScript 优先的运行时 schema 校验库 |
| Turborepo | 基于 Rust 的 Monorepo 任务编排工具 |
| pnpm workspace | pnpm 原生 Monorepo 工作区协议 |
| openapi-generator | 从 OAS 生成多语言客户端代码的工具 |

### 1.4 参考文档

- EP-TOGAF-001 顶层企业架构文档(§1 愿景/§6 技术架构)
- EP-SPEC-001 §8.1 SP-06 子项目定义
- D-031 SP-06 前端技术栈选型（2025-2026 联网调研）
- SP-01 SRS（BFF 契约规范、JWT RS256 鉴权）
- SP-05 SRS（IT 后端三端业务需求）

### 1.5 技术选型决策摘要（D-031）

| 层 | 选型 | 版本 | 理由 | 来源 |
|---|---|---|---|---|
| 包管理+任务编排 | pnpm workspace + Turborepo | pnpm 11+, Turborepo latest | 硬链接省磁盘，Rust 内核增量构建，大厂验证 | https://github.com/pnpm/pnpm, https://github.com/vercel/turborepo |
| API 契约 | springdoc-openapi → openapi-generator-cli | OAS 3.1, generator 7.24+ | BFF 自动产 OAS，前端自动生成 TS client，契约先行 | https://springdoc.org/, https://github.com/OpenAPITools/openapi-generator |
| 跨端 UI | Taro 4 | 4.x | H5+小程序同源编译，React 语法，37.6k stars 成熟 | https://github.com/NervJS/taro |
| 服务端状态 | TanStack Query | v5+ | 自动GC/离线缓存/selectors/结构性共享，50k stars | https://github.com/TanStack/query |
| 构建工具 | Vite(H5) + Taro 内置(小程序) | Vite 7/8 | 事实标准，Rust 内核 Rolldown，82.2k stars | https://github.com/vitejs/vite |
| 类型+运行时校验 | TypeScript + Zod | TS 5.x, Zod 3/4 | 编译期+运行时双保险，z.infer 单一数据源 | https://github.com/colinhacks/zod |
| **剔除项** | tRPC(不适配 Java) / Nx(过重) / uni-app x(App红利) / SWR(功能不足) / Rsbuild(无迁移红利) / 纯TS(无运行时保护) | - | 见 D-031 逐项理由 | D-031 |

---

## 2. 角色与用户故事

### 2.1 利益相关者

| 角色 | 关注点 | SP-06 价值 |
|------|--------|-----------|
| 学生(移动端) | H5/小程序学习体验流畅 | 跨端一致体验，小程序原生体验 |
| 教师/运营(PC) | 管理后台高效操作 | PC 大屏管理后台 |
| 前端开发 | 减少三端重复开发 | Monorepo 共享层，一次编写三端复用 |
| 后端开发 | 前后端契约对齐 | OAS 自动生成，契约驱动，零手写对接 |
| 架构师 | 可维护/可扩展/类型安全 | 全链路 TS strict + Zod 运行时校验 |
| QA | 测试效率 | 共享测试工具 + 契约测试 CI 门 |

### 2.2 核心用户故事

**US-01：前端开发人员新建一个 API 调用**
> 作为前端开发，我希望从 BFF 的 OAS 自动生成 TS client，这样我不需要手写接口类型和请求函数，且类型安全有保障。

**US-02：前端开发人员实现一个三端共享页面**
> 作为前端开发，我希望用 Taro 4 写一套 React 代码同时编译到 H5 和小程序，这样减少 50%+ 重复工作。

**US-03：前端开发人员处理服务端数据**
> 作为前端开发，我希望用 TanStack Query 封装数据获取逻辑（缓存/重试/loading 状态），这样三端共享同一套查询 hooks。

**US-04：QA 验证 API 契约一致性**
> 作为 QA，我希望 CI 中自动校验前端生成的 client 与 BFF 的 OAS 一致，这样契约漂移在提交时即被拦截。

**US-05：学生通过小程序进入学习中心**
> 作为学生，我希望在微信小程序中打开学习中心，获得接近原生的流畅体验，且功能与 H5 一致。

**US-06：教师通过 PC 管理后台管理课程**
> 作为教师，我希望在 PC 浏览器中使用管理后台，操作课程/作业/学生管理，界面适配大屏。

**US-07：架构师审查类型安全**
> 作为架构师，我希望前端代码全链路 TypeScript strict + Zod 运行时校验，这样编译期和运行时双重保障类型安全。

---

## 3. 功能需求(逐端)

### 3.1 Monorepo 基础设施(FR-MONO)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-MONO-01 | pnpm workspace 初始化，`pnpm-workspace.yaml` 定义 apps/* 和 packages/* | P0 | `pnpm install` 成功，所有包可互相引用 |
| FR-MONO-02 | Turborepo 配置 `turbo.json`，定义 build/lint/test/dev 任务管道 | P0 | `turbo build` 按依赖拓扑并行构建所有包 |
| FR-MONO-03 | 共享 TS 配置 `packages/tsconfig`，三端继承 base config | P0 | `tsc --noEmit` 全项目零 error |
| FR-MONO-04 | 共享 ESLint + Prettier 配置 `packages/lint-config` | P0 | `turbo lint` 全项目零 error |
| FR-MONO-05 | Turborepo 远程缓存配置（self-hosted，数据合规） | P1 | CI 命中缓存时构建时间降 ≥60% |
| FR-MONO-06 | Changeset 版本管理（包间依赖版本联动） | P1 | `changeset version` 正确 bump 依赖包版本 |

### 3.2 共享 API 契约层(FR-API)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-API-01 | `packages/api-client`：openapi-generator-cli 从 BFF OAS 生成 TS client | P0 | `pnpm gen:api` 成功生成，类型完整 |
| FR-API-02 | 生成器配置 `typescript-fetch` 模板（轻量，适配小程序） | P0 | 生成产物无 `axios` 依赖，兼容 `Taro.request` |
| FR-API-03 | 同时生成 Zod schema（`openapi-zod-schemas` 或 `zod-openapi`） | P0 | 每个接口的 request/response 有对应 Zod schema |
| FR-API-04 | CI 契约校验门：diff 生成产物与已提交产物，不一致则 fail | P0 | BFF OAS 变更未同步生成 client 时 CI fail |
| FR-API-05 | API client 导出统一 `createClient(config)` 工厂函数 | P0 | 三端注入不同 `fetcher`（H5=fetch, 小程序=Taro.request） |
| FR-API-06 | JWT RS256 token 自动注入请求头 | P0 | 请求自动携带 `Authorization: Bearer <token>` |
| FR-API-07 | 401 自动刷新 token + 请求重放 | P1 | token 过期时透明刷新并重放原请求 |
| FR-API-08 | 请求/响应 Zod 运行时校验（开发环境全量，生产环境采样） | P1 | BFF 返回脏数据时 Zod 安全失败而非白屏 |

### 3.3 共享状态层(FR-QUERY)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-QUERY-01 | `packages/query`：TanStack Query v5 实例配置（三端共享） | P0 | QueryClient 配置 staleTime/gcTime/refetchOnWindowFocus |
| FR-QUERY-02 | 封装核心 hooks：`useCourseList`/`useCourseDetail`/`useUserProfile` 等 | P0 | hooks 可在三端直接调用 |
| FR-QUERY-03 | 查询 key 规范化：`[domain, resource, params]` 三元组 | P0 | key 可序列化，支持精确 invalidation |
| FR-QUERY-04 | 乐观更新封装：`useMutation` + onMutate optimistic + onError rollback | P1 | 提交操作即时 UI 反馈，失败回滚 |
| FR-QUERY-05 | 离线队列：离线时 mutation 入队，恢复后重放 | P2 | 小程序弱网场景提交不丢失 |
| FR-QUERY-06 | QueryDevtools 仅开发环境启用 | P1 | 生产构建不含 Devtools 代码 |

### 3.4 共享 UI 组件库(FR-UI)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-UI-01 | `packages/ui-kit`：Taro 4 跨端组件（H5+小程序同源） | P0 | 组件在 H5 和小程序渲染一致 |
| FR-UI-02 | 基础组件：Button/Input/Card/Modal/Toast/Loading/Skeleton | P0 | 每个组件有三端快照测试 |
| FR-UI-03 | 业务组件：CourseCard/LessonList/VideoPlayer/CodeEditor | P1 | 业务组件封装三端差异（如小程序 `live-player`） |
| FR-UI-04 | 主题系统：CSS Variables + Taro `pxTransform`，支持暗色模式 | P1 | `data-theme="dark"` 切换全局主题 |
| FR-UI-05 | 响应式布局：H5 端断点适配(sm/md/lg)，小程序固定 rpx | P1 | H5 在 375px/768px/1024px 断点正确响应 |
| FR-UI-06 | 组件文档：Storybook(H5 端) 自动生成 | P2 | `pnpm storybook` 可交互浏览所有组件 |

### 3.5 PC 管理后台(FR-ADMIN)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-ADMIN-01 | `apps/admin-web`：React + Vite，PC 管理后台骨架 | P0 | `pnpm dev:admin` 启动成功 |
| FR-ADMIN-02 | 布局框架：侧边栏 + 顶栏 + 内容区 + 标签页 | P0 | 侧边栏菜单可配置，标签页可关闭 |
| FR-ADMIN-03 | 权限路由：基于 JWT role 的路由守卫 | P0 | 无权限路由自动重定向 |
| FR-ADMIN-04 | 登录页：JWT RS256 登录 + token 管理 | P0 | 登录成功后 token 存储并注入 API client |
| FR-ADMIN-05 | 数据表格：分页/排序/筛选/批量操作 | P1 | 表格组件支持 ≥10000 行虚拟滚动 |
| FR-ADMIN-06 | 表单：动态表单 + Zod 校验 + 提交 | P1 | 表单字段 Zod schema 校验 |

### 3.6 移动 H5 学习中心(FR-H5)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-H5-01 | `apps/student-h5`：Taro 4 编译 H5，移动学习中心骨架 | P0 | `pnpm dev:h5` 启动成功，移动浏览器可访问 |
| FR-H5-02 | 底部 TabBar：首页/课程/我的 | P0 | TabBar 3 页切换正常 |
| FR-H5-03 | 首页：轮播图 + 推荐课程 + 学习进度 | P1 | 首页 LCP < 2s(4G) |
| FR-H5-04 | 课程详情页：视频播放 + 章节列表 + 评论区 | P1 | 视频播放器 H5 适配 |
| FR-H5-05 | 个人中心：学习记录 + 证书 + 设置 | P1 | 个人数据 TanStack Query 缓存 |
| FR-H5-06 | 分享：微信分享卡片 + 链接 | P2 | 分享卡片正确展示标题/缩略图 |

### 3.7 微信小程序学习中心(FR-MP)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-MP-01 | `apps/student-mp`：Taro 4 编译微信小程序，学习中心骨架 | P0 | `pnpm dev:mp` 编译成功，微信开发者工具可预览 |
| FR-MP-02 | 底部 TabBar：首页/课程/我的（原生 tabBar 配置） | P0 | TabBar 原生渲染，切换流畅 |
| FR-MP-03 | 小程序登录：`wx.login` → code → BFF 换 JWT | P0 | 静默登录成功获取 JWT |
| FR-MP-04 | 首页/课程/个人中心与 H5 功能一致 | P1 | 三端功能对齐率 ≥95% |
| FR-MP-05 | 小程序支付：微信支付 JSAPI 集成 | P2 | 支付流程拉起成功 |
| FR-MP-06 | 小程序直播：`live-player`/`live-pusher` 组件适配 | P2 | 直播课小程序端可观看 |
| FR-MP-07 | 小程序分包：主包 < 2MB，分包按需加载 | P1 | 主包体积 < 2MB，分包正确加载 |
| FR-MP-08 | 小程序隐私协议：隐私接口声明 + 授权弹窗 | P0 | 隐私接口调用前弹窗授权 |

### 3.8 构建与 CI(FR-CI)

| 编号 | 需求 | 优先级 | 验收标准 |
|------|------|--------|----------|
| FR-CI-01 | Turborepo `turbo.json` 定义 build/lint/test/typecheck 管道 | P0 | `turbo run build` 按依赖拓扑并行执行 |
| FR-CI-02 | CI 流水线：lint → typecheck → test → build → 契约校验 | P0 | 任一阶段 fail 则流水线 fail |
| FR-CI-03 | OAS 契约校验门：`gen:api` 产物 diff 检查 | P0 | BFF OAS 变更未同步生成 client 时 CI fail |
| FR-CI-04 | 构建产物体积监控：H5 bundle < 500KB(gzip) | P1 | CI 报告 bundle size，超标 warn |
| FR-CI-05 | 小程序预览上传：CI 可选上传微信体验版 | P2 | 手动触发上传体验版二维码 |
| FR-CI-06 | Turborepo 远程缓存（self-hosted Redis 后端） | P1 | CI 缓存命中率 ≥60% |

---

## 4. 非功能需求(SLO 量化表)

| 编号 | 维度 | 指标 | 目标值 | 来源 |
|------|------|------|--------|------|
| NFR-01 | H5 首屏加载 | LCP(4G) | < 2.0s | G-09 前端体验 |
| NFR-02 | H5 交互响应 | FID/INP | < 200ms | G-09 |
| NFR-03 | 小程序首屏 | 冷启动到可交互 | < 3.0s | 微信小程序基准 |
| NFR-04 | 小程序主包体积 | 主包大小 | < 2MB | 微信小程序限制 |
| NFR-05 | PC 首屏加载 | LCP | < 1.5s | G-09 |
| NFR-06 | API client 生成时间 | gen:api 耗时 | < 30s | 开发效率 |
| NFR-07 | Monorepo install | pnpm install 耗时 | < 60s(冷缓存) / < 10s(热缓存) | 开发效率 |
| NFR-08 | Turborepo 增量构建 | 改动1个包后 build 耗时 | < 30s(仅构建变更包+依赖) | 开发效率 |
| NFR-09 | 类型安全 | TypeScript strict | 100% 零 error | 架构基线 |
| NFR-10 | 运行时校验 | Zod 覆盖 API 边界 | 100% 接口有 Zod schema | D-031 |
| NFR-11 | 可维护性 | 三端代码复用率 | ≥60%（共享层占比） | D-031 目标 |
| NFR-12 | 可测试性 | 共享层单元测试覆盖率 | ≥80% | G-08 |
| NFR-13 | 浏览器兼容 | H5 支持范围 | Chrome 90+/Safari 14+/Edge 90+ | 2025 主流浏览器 |
| NFR-14 | 小程序基础库 | 微信基础库最低版本 | ≥ 3.0.0 | 2025 主流覆盖 |
| NFR-15 | 可观测 | 前端错误上报 | Sentry/自建，采样率 ≥10% | G-07 |

---

## 5. 约束条件

### 5.1 技术约束

| 编号 | 约束 | 来源 |
|------|------|------|
| C-01 | 后端 BFF 为 Java Spring Boot，前端不能使用 tRPC（仅适配 Node.js 后端） | D-031 |
| C-02 | API 契约必须从 BFF springdoc-openapi 自动生成，禁止手写 client | D-031 契约先行 |
| C-03 | 小程序端必须用 Taro 4 编译（不用原生 WXML），保证与 H5 代码同源 | D-031 |
| C-04 | 全项目 TypeScript strict 模式，禁止 `any`（特殊情况需 `// @ts-expect-error` 注释理由） | 架构基线 |
| C-05 | API 边界必须有 Zod schema 运行时校验，禁止裸 `as Type` 断言 API 响应 | D-031 |
| C-06 | 状态管理统一用 TanStack Query（服务端状态）+ React Context（客户端状态），禁止 Redux/Zustand | D-031 状态分层 |
| C-07 | JWT RS256 鉴权（继承 SP-01 ADR-A02），禁止 HS256 | 安全铁律 |
| C-08 | 缓存 key 格式继承 SP-01 ADR-T04：`{bu}:{svc}:{entity}:{id}` 明文格式 | 架构基线 |

### 5.2 合规约束

| 编号 | 约束 | 来源 |
|------|------|------|
| C-09 | Turborepo 远程缓存必须 self-hosted（数据不出集群），禁止用 Vercel 云缓存 | PIPL 数据合规 |
| C-10 | 前端错误上报不得包含用户 PII（手机号/身份证/邮箱），必须脱敏 | PIPL |
| C-11 | 小程序隐私协议必须声明所有隐私接口（getUserInfo/requestPayment 等），调用前弹窗 | 微信小程序合规 |
| C-12 | 海外用户 H5 不得加载国内 CDN 资源（独立海外前端栈） | D-004 数据隔离 |

### 5.3 复用约束

| 编号 | 约束 | 来源 |
|------|------|------|
| C-13 | 复用 SP-01 的 JWT RS256 + JWKS 鉴权规范，前端不自行实现鉴权 | SP-01 ADR-A02 |
| C-14 | 复用 SP-01 的 BFF API 契约（OAS），前端不自行定义接口 | SP-01 契约先行 |
| C-15 | 复用项目群可观测体系（OTel），前端埋点上报到统一后端 | G-07 |

---

## 6. 依赖关系

### 6.1 上游依赖

| 依赖 | 提供方 | 内容 | 状态 |
|------|--------|------|------|
| BFF OAS 3.1 spec | SP-01 Task 5 (bff-java) | springdoc-openapi 自动产出的 `/v3/api-docs` | ⏳ 待实现 |
| JWT RS256 鉴权 | SP-01 ADR-A02 | JWKS endpoint `/oauth/jwks` | ⏳ 待实现 |
| CellRouter 路由 | SP-01 | 前端请求需携带 cell routing header | ⏳ 待实现 |
| IT 后端业务 API | SP-05 | 课程/作业/评测/直播等业务接口 | ⏳ 待实现 |

### 6.2 下游被依赖

| 被依赖方 | 内容 |
|----------|------|
| SP-05 IT 后端 | 三端业务页面基于 SP-06 骨架开发 |
| SP-07 单元化多区域 | 前端需支持 cell 路由 header 注入 |
| SP-08 测试体系 | 前端单测/集成测/视觉回归基于 SP-06 测试规范 |

### 6.3 基础设施依赖

| 依赖 | 用途 | 状态 |
|------|------|------|
| pnpm 11+ | 包管理 | ✅ 可安装 |
| Node.js 22 LTS | 运行时 | ✅ 可安装 |
| Turborepo | 任务编排 | ✅ 可安装 |
| 微信开发者工具 | 小程序预览/上传 | ✅ 本地可用 |

---

## 7. 验收标准

### 7.1 M0 验收（骨架可用）

- [ ] pnpm workspace + Turborepo 初始化完成，`turbo build` 成功
- [ ] `packages/api-client` 能从 mock OAS 生成 TS client + Zod schema
- [ ] `packages/query` TanStack Query 实例配置完成
- [ ] `packages/ui-kit` 基础组件(Button/Input/Card/Modal/Toast)在 H5+小程序渲染一致
- [ ] `apps/admin-web` PC 管理后台骨架启动成功（登录页+布局框架）
- [ ] `apps/student-h5` H5 学习中心骨架启动成功（TabBar+首页占位）
- [ ] `apps/student-mp` 微信小程序学习中心骨架编译成功（微信开发者工具可预览）
- [ ] TypeScript strict 全项目零 error
- [ ] CI 流水线(lint→typecheck→test→build→契约校验)全绿
- [ ] 三端代码复用率 ≥40%（骨架阶段，业务页面后提升到 ≥60%）

### 7.2 M1 验收（业务可用）

- [ ] 三端核心流程跑通（登录→选课→学习→作业→评测→出分）
- [ ] BFF OAS 对接完成，API client 自动生成
- [ ] H5 LCP < 2.0s(4G)，小程序冷启动 < 3.0s
- [ ] 小程序主包 < 2MB
- [ ] 三端功能对齐率 ≥95%
- [ ] 共享层单元测试覆盖率 ≥80%

---

## 8. 追溯矩阵

| 需求 ID | 来源 | 对应 SAD 章节 | 对应 SDD 章节 | 对应测试 |
|---------|------|--------------|--------------|----------|
| FR-MONO-01..06 | D-031 | §2 Monorepo 架构 | §2 工程结构 | TC-MONO |
| FR-API-01..08 | D-031 + SP-01 契约 | §3 API 契约层 | §3 API client 实现 | TC-API |
| FR-QUERY-01..06 | D-031 | §4 状态层架构 | §4 Query hooks 实现 | TC-QUERY |
| FR-UI-01..06 | D-031 | §5 UI 组件库 | §5 组件实现 | TC-UI |
| FR-ADMIN-01..06 | EP-SPEC §8.1 | §6.1 PC 管理后台 | §6.1 Admin 实现 | TC-ADMIN |
| FR-H5-01..06 | EP-SPEC §8.1 | §6.2 H5 学习中心 | §6.2 H5 实现 | TC-H5 |
| FR-MP-01..08 | EP-SPEC §8.1 | §6.3 微信小程序 | §6.3 MP 实现 | TC-MP |
| FR-CI-01..06 | D-031 | §7 CI/CD | §7 CI 配置 | TC-CI |
| NFR-01..15 | G-07/G-08/G-09 | §8 SLO | §8 性能优化 | TC-PERF |

---

> **文档状态**：Approved v1.0
> **下一步**：SP-06 SAD 架构设计文档 → SDD 详细设计 → 原型 HTML → 测试/部署文档
