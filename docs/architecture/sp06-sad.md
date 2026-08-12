# SP-06 多端前端骨架 —— 软件架构设计文档(SAD)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP06-SAD-001 | v1.0 | 2026-08-04 | Approved | ZCode(架构师) | SP06-SRS-001 / EP-TOGAF-001 / D-031 |

> 本文是 SP-06 多端前端骨架的**架构设计基线**，遵循 TOGAF ADM 应用架构域，定义 Monorepo 拓扑、包依赖、数据流、API 契约机制、状态层、UI 组件库、三端应用架构、ADR 决策。

---

## 目录

1. [架构概览](#1-架构概览)
2. [Monorepo 拓扑与包依赖](#2-monorepo-拓扑与包依赖)
3. [API 契约层架构](#3-api-契约层架构)
4. [状态层架构](#4-状态层架构)
5. [UI 组件库架构](#5-ui-组件库架构)
6. [三端应用架构](#6-三端应用架构)
7. [数据流与鉴权](#7-数据流与鉴权)
8. [构建与 CI/CD 架构](#8-构建与-cicd-架构)
9. [可观测架构](#9-可观测架构)
10. [架构决策记录(ADR)](#10-架构决策记录adr)
11. [技术选型矩阵](#11-技术选型矩阵)
12. [风险与缓解](#12-风险与缓解)

---

## 1. 架构概览

### 1.1 设计目标

| 目标 | 量化指标 | 来源 |
|------|----------|------|
| 三端代码复用 | 共享层占比 ≥60% | NFR-11 |
| 类型安全 | TS strict 100% + Zod 运行时覆盖 100% API 边界 | NFR-09/10 |
| 契约驱动 | BFF OAS → 前端 TS client 全自动，CI 契约门 | D-031 |
| 开发效率 | Turborepo 增量构建 <30s | NFR-08 |
| 首屏性能 | H5 LCP <2.0s / 小程序冷启动 <3.0s | NFR-01/03 |

### 1.2 架构总览图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SP-06 Monorepo (pnpm workspace + Turborepo)          │
│                                                                         │
│  ┌─── apps/ ───────────────────────────────────────────────────────┐   │
│  │                                                                 │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │   │
│  │  │ admin-web    │  │ student-h5   │  │ student-mp           │  │   │
│  │  │ (React+Vite) │  │ (Taro4→H5)  │  │ (Taro4→微信小程序)   │  │   │
│  │  │ PC管理后台   │  │ 移动H5学习   │  │ 小程序学习中心       │  │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │   │
│  │         │                 │                     │              │   │
│  └─────────┼─────────────────┼─────────────────────┼──────────────┘   │
│            │                 │                     │                    │
│            ▼                 ▼                     ▼                    │
│  ┌─── packages/ (共享层) ────────────────────────────────────────┐    │
│  │                                                               │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │    │
│  │  │ api-client  │  │ query       │  │ ui-kit              │  │    │
│  │  │ OAS→TS      │  │ TanStack    │  │ Taro4跨端组件       │  │    │
│  │  │ client+Zod  │  │ Query hooks │  │ Button/Input/Card.. │  │    │
│  │  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │    │
│  │         │                │                    │              │    │
│  │         ▼                ▼                    ▼              │    │
│  │  ┌──────────────────────────────────────────────────────┐   │    │
│  │  │ shared (类型/常量/Zod schema/工具函数)               │   │    │
│  │  └──────────────────────────────────────────────────────┘   │    │
│  │                                                               │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │    │
│  │  │ tsconfig    │  │ lint-config │  │ eslint-plugin       │  │    │
│  │  │ (TS strict) │  │ (ESLint+PR) │  │ (自定义规则)        │  │    │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘  │    │
│  └───────────────────────────────────────────────────────────────┘    │
│                                                                       │
└───────────────────────────┬───────────────────────────────────────────┘
                            │
                            │ HTTPS (JWT RS256)
                            ▼
                 ┌─────────────────────┐
                 │  SP-01 BFF (Java)   │
                 │  Spring Boot 4.0.7  │
                 │  springdoc → OAS    │
                 └─────────────────────┘
```

### 1.3 设计原则

1. **契约先行**：BFF OAS 是唯一 API 真相源，前端 client 全自动生成，零手写
2. **共享最大化**：三端业务逻辑共享在 packages 层，apps 层只做端特定 UI 编排
3. **类型安全纵深**：编译期(TS strict) + 运行时(Zod) + CI 契约门，三层防御
4. **端差异隔离**：三端差异封装在 ui-kit 组件内部，不泄漏到业务层
5. **渐进增强**：M0 骨架可用 → M1 业务可用 → M2 体验优化

---

## 2. Monorepo 拓扑与包依赖

### 2.1 目录结构

```
education-platform/frontend/            ← SP-06 工作区根
├── pnpm-workspace.yaml
├── turbo.json
├── package.json                        ← root: 脚本/devDeps
├── .npmrc                              ← pnpm 配置(shamefully-hoist 等)
├── tsconfig.base.json                  ← TS strict base config
├── apps/
│   ├── admin-web/                      ← PC 管理后台 (React + Vite)
│   │   ├── src/
│   │   │   ├── main.tsx                ← 入口
│   │   │   ├── App.tsx                 ← 根组件+路由
│   │   │   ├── layouts/                ← 布局(侧边栏+顶栏+标签页)
│   │   │   ├── pages/                  ← 页面(登录/课程管理/作业管理...)
│   │   │   ├── routes/                 ← 路由定义+权限守卫
│   │   │   ├── hooks/                  ← admin 特有 hooks
│   │   │   └── vite-env.d.ts
│   │   ├── vite.config.ts              ← Vite 配置
│   │   ├── tsconfig.json               ← 继承 base
│   │   └── package.json
│   ├── student-h5/                     ← 移动 H5 (Taro 4 → H5)
│   │   ├── src/
│   │   │   ├── app.tsx                 ← Taro 入口
│   │   │   ├── app.config.ts           ← Taro 全局配置(H5)
│   │   │   ├── pages/                  ← 页面(index/course/profile...)
│   │   │   ├── components/             ← H5 特有组件
│   │   │   └── taro-env.d.ts
│   │   ├── config/                     ← Taro 编译配置
│   │   │   ├── h5.ts                   ← H5 编译配置
│   │   │   └── index.ts
│   │   ├── tsconfig.json
│   │   └── package.json
│   └── student-mp/                     ← 微信小程序 (Taro 4 → 微信)
│       ├── src/
│       │   ├── app.tsx                 ← Taro 入口(与 H5 共享或分离)
│       │   ├── app.config.ts           ← Taro 全局配置(小程序 tabBar)
│       │   ├── pages/                  ← 页面(与 H5 共享或分平台)
│       │   ├── subpackages/            ← 分包(live/quiz/assignment)
│       │   └── taro-env.d.ts
│       ├── config/
│       │   ├── weapp.ts                ← 微信小程序编译配置
│       │   └── index.ts
│       ├── project.config.json         ← 微信开发者工具配置
│       ├── tsconfig.json
│       └── package.json
├── packages/
│   ├── api-client/                     ← OAS → TS client + Zod schema
│   │   ├── src/
│   │   │   ├── generated/              ← openapi-generator 产物(gitignored or committed?)
│   │   │   ├── client.ts               ← createClient 工厂
│   │   │   ├── fetcher.ts              ← fetcher 适配(H5 fetch / MP Taro.request)
│   │   │   ├── auth.ts                 ← JWT token 注入 + 401 刷新
│   │   │   └── index.ts
│   │   ├── openapi-generator.config.yaml
│   │   ├── schemas/                    ← OAS spec 副本(CI 对比用)
│   │   ├── tsconfig.json
│   │   └── package.json
│   ├── query/                          ← TanStack Query 共享 hooks
│   │   ├── src/
│   │   │   ├── client.ts               ← QueryClient 配置
│   │   │   ├── keys.ts                 ← queryKey 规范化
│   │   │   ├── hooks/
│   │   │   │   ├── useCourseList.ts
│   │   │   │   ├── useCourseDetail.ts
│   │   │   │   ├── useUserProfile.ts
│   │   │   │   ├── useLogin.ts
│   │   │   │   └── ...
│   │   │   ├── mutations/
│   │   │   │   ├── useEnrollCourse.ts
│   │   │   │   ├── useSubmitAssignment.ts
│   │   │   │   └── ...
│   │   │   └── index.ts
│   │   ├── tsconfig.json
│   │   └── package.json
│   ├── ui-kit/                         ← Taro 4 跨端组件库
│   │   ├── src/
│   │   │   ├── components/
│   │   │   │   ├── Button/
│   │   │   │   ├── Input/
│   │   │   │   ├── Card/
│   │   │   │   ├── Modal/
│   │   │   │   ├── Toast/
│   │   │   │   ├── Loading/
│   │   │   │   ├── Skeleton/
│   │   │   │   ├── CourseCard/
│   │   │   │   ├── LessonList/
│   │   │   │   ├── VideoPlayer/        ← 三端适配(H5 video / MP live-player)
│   │   │   │   └── CodeEditor/         ← Monaco(H5) / 简化(MP)
│   │   │   ├── theme/
│   │   │   │   ├── tokens.ts           ← 设计令牌(颜色/间距/字号)
│   │   │   │   ├── dark.ts             ← 暗色主题
│   │   │   │   └── index.ts
│   │   │   └── index.ts
│   │   ├── .storybook/                 ← Storybook(仅 H5 端)
│   │   ├── tsconfig.json
│   │   └── package.json
│   ├── shared/                         ← 共享类型/常量/工具/Zod schema
│   │   ├── src/
│   │   │   ├── types/                  ← 领域类型(User/Course/Assignment...)
│   │   │   ├── constants/              ← 枚举/常量
│   │   │   ├── utils/                  ← 工具函数(日期/格式化/校验)
│   │   │   ├── schemas/                ← 手写 Zod schema(非 API 边界)
│   │   │   └── index.ts
│   │   ├── tsconfig.json
│   │   └── package.json
│   ├── tsconfig/                       ← 共享 TS 配置
│   │   ├── base.json
│   │   ├── react.json
│   │   ├── taro.json
│   │   └── package.json
│   └── lint-config/                    ← 共享 ESLint + Prettier
│       ├── eslint.config.js
│       ├── .prettierrc.js
│       └── package.json
└── tools/
    ├── openapi-gen.sh                  ← OAS → TS client 生成脚本
    └── check-contract.sh               ← CI 契约校验脚本
```

### 2.2 包依赖关系(有向无环图)

```
                    ┌──────────┐
                    │  shared  │ ← 零依赖(纯类型/工具)
                    └────┬─────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
        ┌──────────┐ ┌────────┐ ┌─────────┐
        │api-client│ │ query  │ │ ui-kit  │
        └────┬─────┘ └───┬────┘ └────┬────┘
             │           │           │
             └─────┬─────┘           │
                   │                 │
                   ▼                 │
            ┌──────────────────────┐ │
            │  apps/admin-web      │◄┘
            │  apps/student-h5     │◄┘
            │  apps/student-mp     │◄┘
            └──────────────────────┘
```

**依赖规则**（CI 强制检查）：
- `apps/*` 可依赖 `packages/*`，禁止 apps 互相依赖
- `packages/*` 之间只能依赖 `shared`，禁止 api-client ↔ query ↔ ui-kit 互相依赖（避免循环）
- `packages/shared` 零外部包依赖（纯类型/工具）
- 所有包依赖 `packages/tsconfig` 和 `packages/lint-config`

### 2.3 pnpm-workspace.yaml

```yaml
packages:
  - 'apps/*'
  - 'packages/*'
```

### 2.4 turbo.json

```json
{
  "$schema": "https://turbo.build/schema.json",
  "globalDependencies": ["tsconfig.base.json", ".env"],
  "globalEnv": ["NODE_ENV", "API_BASE_URL"],
  "tasks": {
    "build": {
      "dependsOn": ["^build"],
      "outputs": ["dist/**", "build/**", ".taro/**"],
      "cache": true
    },
    "lint": {
      "outputs": []
    },
    "typecheck": {
      "dependsOn": ["^build"],
      "outputs": []
    },
    "test": {
      "dependsOn": ["^build"],
      "outputs": ["coverage/**"],
      "cache": true
    },
    "dev": {
      "cache": false,
      "persistent": true
    },
    "gen:api": {
      "outputs": ["src/generated/**"],
      "cache": true
    }
  }
}
```

---

## 3. API 契约层架构

### 3.1 契约驱动流程

```
SP-01 BFF (Java Spring Boot 4.0.7)
  │
  │  springdoc-openapi 自动产出
  │  GET /v3/api-docs → OAS 3.1 JSON
  │
  ▼
CI Pipeline (gen:api 阶段)
  │
  │  1. 启动 BFF (或从 artifact 下载 OAS JSON)
  │  2. openapi-generator-cli generate \
  │       -i bff-openapi.json \
  │       -g typescript-fetch \
  │       -o packages/api-client/src/generated \
  │       --additional-properties=supportsES6=true,typescriptThreePlus=true
  │  3. openapi-zod-schemas (或 zod-openapi) 生成 Zod schema
  │  4. git diff --exit-code src/generated/ → 契约门(不一致则 fail)
  │
  ▼
packages/api-client/src/generated/
  ├── apis/              ← 每个接口一个类(CourseApi/UserApi/AssignmentApi...)
  ├── models/            ← 每个 DTO 一个 TS interface + Zod schema
  └── index.ts           ← 统一导出
  │
  ▼
packages/api-client/src/client.ts
  │  createClient(config) 工厂
  │  注入 fetcher + auth interceptor + Zod validator
  │
  ▼
packages/query/src/hooks/
  │  useCourseList() → apiClient.course.list() → Zod 校验 → return data
  │
  ▼
apps/*/pages/
  │  组件调用 useCourseList() → 渲染
```

### 3.2 fetcher 适配层

```typescript
// packages/api-client/src/fetcher.ts

// H5 端: 标准 fetch
export const h5Fetcher: Fetcher = async (url, init) => {
  const res = await fetch(url, init);
  if (!res.ok) throw new ApiError(res.status, await res.text());
  return res.json();
};

// 小程序端: Taro.request
import Taro from '@tarojs/taro';
export const mpFetcher: Fetcher = async (url, init) => {
  const res = await Taro.request({
    url,
    method: init.method as keyof Taro.request.Method,
    header: init.headers,
    data: init.body,
    dataType: 'json',
  });
  if (res.statusCode >= 400) throw new ApiError(res.statusCode, res.data);
  return res.data;
};
```

### 3.3 Zod 运行时校验策略

```
API 响应 → generated Zod schema .safeParse(response)
              │
              ├─ success: true → 返回校验后的数据(类型安全)
              │
              └─ success: false →
                    ├─ 开发环境: 抛出详细错误(含 schema 路径)
                    └─ 生产环境: 上报错误监控 + 返回 fallback 数据(不白屏)
```

**校验粒度**：
- 开发环境：全量校验（每个 API 响应）
- 生产环境：采样校验（10% 采样率），平衡性能与安全
- 关键接口（支付/鉴权）：全量校验（不受采样率影响）

### 3.4 ADR-F01：openapi-generator 模板选择

**背景**：openapi-generator 支持 `typescript-fetch`、`typescript-axios`、`typescript-nestjs` 等多个 TS 生成器。

**决策**：选用 `typescript-fetch` 模板。

**理由**：
- `typescript-fetch` 仅依赖 `fetch` API，零第三方依赖
- 小程序端可通过 `Taro.request` polyfill `fetch`，或通过 fetcher 适配层注入
- `typescript-axios` 引入 `axios` 依赖，增加小程序包体积
- `typescript-nestjs` 是后端模板，不适用

**状态**：Accepted

---

## 4. 状态层架构

### 4.1 状态分类

```
┌─────────────────────────────────────────┐
│           前端状态全景                    │
├──────────────┬──────────────────────────┤
│  服务端状态   │  TanStack Query 管理     │
│  (Server)    │  - 课程列表/详情          │
│              │  - 用户信息              │
│              │  - 作业列表              │
│              │  - 自动缓存/重试/失效     │
├──────────────┼──────────────────────────┤
│  客户端状态   │  React Context + useState │
│  (Client)    │  - 主题(暗色/亮色)        │
│              │  - 侧边栏折叠            │
│              │  - 当前选中标签页         │
│              │  - 表单临时状态          │
├──────────────┼──────────────────────────┤
│  持久化状态   │  Storage( localStorage /  │
│  (Persisted) │  Taro.setStorage )        │
│              │  - JWT token             │
│              │  - 用户偏好设置          │
│              │  - 离线草稿              │
└──────────────┴──────────────────────────┘
```

### 4.2 TanStack Query 配置

```typescript
// packages/query/src/client.ts
import { QueryClient } from '@tanstack/react-query';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5 * 60 * 1000,      // 5min 内不重新请求
        gcTime: 30 * 60 * 1000,         // 30min 后 GC
        retry: 3,                       // 失败重试 3 次
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 30000),
        refetchOnWindowFocus: true,     // H5 端窗口聚焦重新请求
        refetchOnReconnect: true,       // 网络恢复重新请求
      },
      mutations: {
        retry: 1,                       // mutation 重试 1 次
      },
    },
  });
}
```

### 4.3 queryKey 规范

```typescript
// packages/query/src/keys.ts
// 格式: [domain, resource, ...params] 三元组
export const queryKeys = {
  courses: {
    all: ['courses'] as const,
    list: (params: CourseListParams) => ['courses', 'list', params] as const,
    detail: (id: string) => ['courses', 'detail', id] as const,
  },
  user: {
    profile: () => ['user', 'profile'] as const,
    enrollments: () => ['user', 'enrollments'] as const,
  },
  assignments: {
    list: (courseId: string) => ['assignments', 'list', courseId] as const,
    detail: (id: string) => ['assignments', 'detail', id] as const,
  },
} as const;
```

### 4.4 乐观更新模式

```typescript
// packages/query/src/mutations/useEnrollCourse.ts
export function useEnrollCourse() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (courseId: string) => apiClient.course.enroll(courseId),
    onMutate: async (courseId) => {
      // 1. 取消正在进行的查询(防止覆盖乐观更新)
      await queryClient.cancelQueries({ queryKey: queryKeys.user.enrollments() });
      // 2. 快照当前值(用于回滚)
      const prev = queryClient.getQueryData(queryKeys.user.enrollments());
      // 3. 乐观更新
      queryClient.setQueryData(queryKeys.user.enrollments(), (old) => [...old, courseId]);
      // 4. 返回上下文(含快照)
      return { prev };
    },
    onError: (_err, _courseId, context) => {
      // 失败时回滚
      queryClient.setQueryData(queryKeys.user.enrollments(), context?.prev);
    },
    onSettled: () => {
      // 无论成功失败，重新请求确保一致
      queryClient.invalidateQueries({ queryKey: queryKeys.user.enrollments() });
    },
  });
}
```

---

## 5. UI 组件库架构

### 5.1 组件分层

```
┌─────────────────────────────────────────────────┐
│              apps/*/pages (业务页面)              │
├─────────────────────────────────────────────────┤
│  业务组件 (ui-kit/components/business)           │
│  CourseCard / LessonList / VideoPlayer /         │
│  CodeEditor / AssignmentCard / GradeBadge        │
├─────────────────────────────────────────────────┤
│  基础组件 (ui-kit/components/base)               │
│  Button / Input / Card / Modal / Toast /         │
│  Loading / Skeleton / Avatar / Badge / Tabs      │
├─────────────────────────────────────────────────┤
│  主题层 (ui-kit/theme)                           │
│  tokens.ts (设计令牌) / dark.ts / light.ts       │
├─────────────────────────────────────────────────┤
│  Taro 4 跨端运行时                               │
│  H5: React DOM    小程序: 微信原生渲染            │
└─────────────────────────────────────────────────┘
```

### 5.2 端差异隔离策略

```typescript
// ui-kit/components/VideoPlayer/index.tsx
import { View } from '@tarojs/components';
import { useEnv } from '@tarojs/taro';

export function VideoPlayer({ src, poster }: VideoPlayerProps) {
  const env = useEnv();

  // H5 端: 使用 <video> 标签
  if (env === Taro.ENV_TYPE.H5) {
    return <video src={src} poster={poster} controls />;
  }

  // 小程序端: 使用 <live-player> 或 <video> 组件
  if (env === Taro.ENV_TYPE.WEAPP) {
    return (
      <video
        src={src}
        poster={poster}
        controls
        showCenterPlayBtn
        enableProgressGesture
      />
    );
  }

  // 兜底
  return <View>视频播放不支持当前环境</View>;
}
```

### 5.3 主题系统

```typescript
// ui-kit/theme/tokens.ts
export const tokens = {
  color: {
    primary: '#1890ff',
    success: '#52c41a',
    warning: '#faad14',
    error: '#ff4d4f',
    // 语义色
    textPrimary: 'var(--color-text-primary)',
    textSecondary: 'var(--color-text-secondary)',
    bgPrimary: 'var(--color-bg-primary)',
    bgSecondary: 'var(--color-bg-secondary)',
  },
  spacing: { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 },
  fontSize: { xs: 20, sm: 24, md: 28, lg: 32, xl: 40 }, // rpx
  radius: { sm: 4, md: 8, lg: 12 },
} as const;

// CSS Variables (H5) / Taro pxTransform (小程序)
// 暗色模式: data-theme="dark" → CSS Variables 切换
```

### 5.4 ADR-F02：Taro 4 vs uni-app x

**背景**：跨端框架两大候选——Taro 4（京东，React 语法）vs uni-app x（DCloud，Vue 语法）。

**决策**：选用 Taro 4。

**理由**：
- Taro 4 兼容 React 语法，与 TanStack Query（React 生态）无缝集成
- uni-app x 的 uts/uvue 原生渲染红利主要在 App 端，我们 M0-M1 不做 App
- Taro 4 的 H5 + 微信小程序编译成熟，37.6k stars 社区活跃
- 团队 React 经验 > Vue 经验（与 admin-web React 统一）

**剔除 uni-app x 理由**：
- Vue 强绑定，与 admin-web(React) 技术栈分裂
- uts 语法约束多，学习成本高
- 原生渲染红利在 App 端，非本阶段目标

**来源**：https://github.com/NervJS/taro (37.6k stars), https://github.com/dcloudio/uni-app (41.6k stars)

**状态**：Accepted

---

## 6. 三端应用架构

### 6.1 admin-web (PC 管理后台)

```
┌────────────────────────────────────────────────────┐
│  apps/admin-web (React 18 + Vite + React Router)   │
├────────────────────────────────────────────────────┤
│                                                    │
│  ┌─────────────┐  ┌──────────────────────────────┐│
│  │ 路由层      │  │ 布局层                       ││
│  │ React Router│  │ ProLayout 侧边栏+顶栏+标签页 ││
│  │ + 权限守卫  │  │                              ││
│  └──────┬──────┘  └──────────────┬───────────────┘│
│         │                        │                 │
│  ┌──────┴────────────────────────┴───────────────┐│
│  │  页面层 (pages/)                               ││
│  │  Login / Dashboard / CourseMgmt / AssignMgmt   ││
│  │  StudentMgmt / TeacherMgmt / LiveMgmt /        ││
│  │  CodingMgmt / GradeMgmt / SystemSettings       ││
│  └──────────────────────┬─────────────────────────┘│
│                         │                          │
│  ┌──────────────────────┴─────────────────────────┐│
│  │  共享层 (packages/api-client + query + ui-kit) ││
│  └────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────┘
```

**技术栈**：
- React 18 + React Router 7
- Vite 7 (构建)
- Ant Design 5（PC 管理后台组件库，非 ui-kit，因为 admin-web 不需要跨端）
- `@tanstack/react-query` (服务端状态)

### 6.2 student-h5 (移动 H5 学习中心)

```
┌────────────────────────────────────────────────────┐
│  apps/student-h5 (Taro 4 → H5 编译)                │
├────────────────────────────────────────────────────┤
│                                                    │
│  ┌─────────────┐  ┌──────────────────────────────┐│
│  │ Taro Router │  │ TabBar (首页/课程/我的)       ││
│  │ (H5模式)    │  │                              ││
│  └──────┬──────┘  └──────────────┬───────────────┘│
│         │                        │                 │
│  ┌──────┴────────────────────────┴───────────────┐│
│  │  页面层 (pages/)                               ││
│  │  index(首页) / course(课程列表) /               ││
│  │  course-detail(课程详情) / profile(我的) /      ││
│  │  learning(学习页) / assignment(作业) /          ││
│  │  live(直播) / coding(在线编程)                  ││
│  └──────────────────────┬─────────────────────────┘│
│                         │                          │
│  ┌──────────────────────┴─────────────────────────┐│
│  │  共享层 (packages/api-client + query + ui-kit) ││
│  └────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────┘
```

### 6.3 student-mp (微信小程序学习中心)

```
┌────────────────────────────────────────────────────┐
│  apps/student-mp (Taro 4 → 微信小程序编译)         │
├────────────────────────────────────────────────────┤
│                                                    │
│  ┌─────────────┐  ┌──────────────────────────────┐│
│  │ Taro Router │  │ 原生 TabBar (首页/课程/我的)  ││
│  │ (小程序模式)│  │ app.config.ts 配置            ││
│  └──────┬──────┘  └──────────────┬───────────────┘│
│         │                        │                 │
│  ┌──────┴───────┐  ┌────────────┴───────────────┐ │
│  │ 主包 (pages/)│  │ 分包 (subpackages/)         │ │
│  │ 首页/课程/   │  │ live/ (直播课)              │ │
│  │ 我的/登录    │  │ coding/ (在线编程)          │ │
│  │              │  │ quiz/ (考试评测)            │ │
│  │ 主包 < 2MB   │  │ assignment/ (作业)          │ │
│  └──────┬───────┘  └────────────┬───────────────┘ │
│         │                        │                 │
│  ┌──────┴────────────────────────┴───────────────┐│
│  │  共享层 (packages/api-client + query + ui-kit) ││
│  └────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────┘
```

**分包策略**（ADR-F03）：
- 主包：首页 + 课程列表 + 我的 + 登录（< 2MB）
- 分包 live/：直播课相关（`live-player` 组件重，独立分包）
- 分包 coding/：在线编程（CodeEditor 组件大，独立分包）
- 分包 quiz/：考试评测
- 分包 assignment/：作业

---

## 7. 数据流与鉴权

### 7.1 JWT RS256 鉴权流程

```
                    ┌──────────┐
                    │  用户    │
                    └────┬─────┘
                         │ 1. 登录(username/password)
                         ▼
              ┌─────────────────────┐
              │  admin-web / h5 / mp │
              │  (前端)              │
              └──────────┬──────────┘
                         │ 2. POST /auth/login
                         ▼
              ┌─────────────────────┐
              │  SP-01 BFF (Java)   │
              │  Spring Boot 4.0.7  │
              │                     │
              │  3. 验证凭证         │
              │  4. RS256 签 JWT    │
              │     (private key)   │
              └──────────┬──────────┘
                         │ 5. 返回 { accessToken, refreshToken }
                         ▼
              ┌─────────────────────┐
              │  前端 Storage        │
              │  - accessToken      │
              │  - refreshToken     │
              └──────────┬──────────┘
                         │
                         │ 6. 后续请求携带 Authorization: Bearer <accessToken>
                         ▼
              ┌─────────────────────┐
              │  BFF (JWKS 验签)    │
              │  GET /oauth/jwks    │
              │  → public key 验签  │
              └─────────────────────┘
                         │
                         │ 7. accessToken 过期 (401)
                         ▼
              ┌─────────────────────┐
              │  前端 auth 拦截器    │
              │  - 暂停原请求        │
              │  - POST /auth/refresh│
              │  - 获取新 token      │
              │  - 重放原请求        │
              └─────────────────────┘
```

### 7.2 三端 Storage 适配

```typescript
// packages/api-client/src/auth.ts

// H5: localStorage
const h5Storage = {
  get: (key: string) => localStorage.getItem(key),
  set: (key: string, val: string) => localStorage.setItem(key, val),
  remove: (key: string) => localStorage.removeItem(key),
};

// 小程序: Taro.setStorageSync
import Taro from '@tarojs/taro';
const mpStorage = {
  get: (key: string) => Taro.getStorageSync(key) || null,
  set: (key: string, val: string) => Taro.setStorageSync(key, val),
  remove: (key: string) => Taro.removeStorageSync(key),
};

// 统一接口
export interface TokenStorage {
  getAccessToken(): string | null;
  setAccessToken(token: string): void;
  getRefreshToken(): string | null;
  setRefreshToken(token: string): void;
  clear(): void;
}
```

### 7.3 Cell 路由 Header 注入

```typescript
// packages/api-client/src/client.ts
// SP-01 CellRouter 需要 X-Cell-Routing header
export function createClient(config: ClientConfig): ApiClient {
  const fetcher = config.fetcher; // h5Fetcher or mpFetcher

  const client = new GeneratedApiClient({
    basePath: config.apiBaseUrl,
    fetch: async (url: RequestInfo, init?: RequestInit) => {
      // 1. 注入 JWT
      const token = config.tokenStorage.getAccessToken();
      const headers = new Headers(init?.headers);
      if (token) headers.set('Authorization', `Bearer ${token}`);

      // 2. 注入 Cell 路由 (SP-01 CellRouter)
      const cellId = config.getCellId?.();
      if (cellId) headers.set('X-Cell-Routing', cellId);

      // 3. 注入 trace (SP-01 OTel)
      headers.set('X-Trace-Id', crypto.randomUUID());

      // 4. 发起请求
      let response = await fetcher(url.toString(), { ...init, headers });

      // 5. 401 自动刷新
      if (response instanceof ApiError && response.status === 401) {
        const refreshed = await refreshTokens(config);
        if (refreshed) {
          headers.set('Authorization', `Bearer ${config.tokenStorage.getAccessToken()}`);
          response = await fetcher(url.toString(), { ...init, headers });
        }
      }

      return response;
    },
  });

  return client;
}
```

---

## 8. 构建与 CI/CD 架构

### 8.1 构建流程

```
开发环境:
  pnpm dev:admin    → Vite dev server (admin-web)
  pnpm dev:h5       → Taro 4 dev server (student-h5)
  pnpm dev:mp       → Taro 4 watch (student-mp) → 微信开发者工具

CI 构建:
  turbo run build lint typecheck test gen:api
    │
    ├─ gen:api (packages/api-client)
    │   └─ 下载 BFF OAS → openapi-generator → 生成 TS client + Zod
    │
    ├─ ^build (依赖拓扑序)
    │   ├─ packages/shared → build
    │   ├─ packages/api-client → build (依赖 shared)
    │   ├─ packages/query → build (依赖 shared + api-client)
    │   ├─ packages/ui-kit → build (依赖 shared)
    │   └─ apps/* → build (依赖所有 packages)
    │
    ├─ lint (全项目 ESLint)
    ├─ typecheck (全项目 tsc --noEmit)
    └─ test (全项目 Vitest)

生产构建:
  turbo run build
    ├─ admin-web → Vite build → dist/ (静态文件 → CDN)
    ├─ student-h5 → Taro build --type h5 → dist/ (静态文件 → CDN)
    └─ student-mp → Taro build --type weapp → dist/ (上传微信)
```

### 8.2 CI 契约校验门

```bash
# tools/check-contract.sh
#!/bin/bash
set -euo pipefail

# 1. 下载 BFF 最新 OAS
curl -sf http://bff-staging:8080/v3/api-docs > /tmp/bff-openapi-latest.json

# 2. 与已提交 spec 对比
if ! diff -q /tmp/bff-openapi-latest.json packages/api-client/schemas/bff-openapi.json > /dev/null; then
  echo "❌ BFF OAS 已变更但前端 client 未同步生成！"
  echo "请运行: pnpm gen:api"
  exit 1
fi

# 3. 重新生成 client，检查是否有 diff
pnpm gen:api
if ! git diff --exit-code packages/api-client/src/generated/ > /dev/null; then
  echo "❌ 生成产物与已提交版本不一致！"
  echo "请运行: pnpm gen:api && git add packages/api-client/src/generated/"
  exit 1
fi

echo "✅ 契约一致"
```

### 8.3 Turborepo 远程缓存（Self-hosted）

```json
// turbo.json 补充
{
  "remoteCache": {
    "enabled": true,
    "apiUrl": "http://turbo-cache.internal:3000",
    "timeout": 30
  }
}
```

**部署**：自建 turbo-cache 服务（基于 Redis 后端），数据不出集群，满足 PIPL。

---

## 9. 可观测架构

### 9.1 前端监控维度

| 维度 | 工具 | 采集内容 | 上报目标 |
|------|------|----------|----------|
| 错误监控 | Sentry SDK / 自建 | JS 异常 / Promise reject / 资源加载失败 | Sentry(self-hosted) 或自建 |
| 性能监控 | Web Vitals / Taro 性能 | LCP / FID / INP / CLS / TTFB | OTel Collector → Prometheus |
| 用户行为 | 自建埋点 SDK | 页面 PV/UV / 点击 / 自定义事件 | 数据中台 Kafka → ClickHouse |
| API 监控 | TanStack Query onError | 接口错误率 / 延迟 | OTel → Tempo |

### 9.2 错误上报脱敏

```typescript
// 脱敏规则(C-10):
// - 手机号: 138****1234
// - 邮箱: j***@example.com
// - 身份证: 110***********1234
// - JWT token: [REDACTED]

const SENSITIVE_PATTERNS = [
  { pattern: /\d{11}/g, replace: (m: string) => m.slice(0,3) + '****' + m.slice(7) },
  { pattern: /[\w.]+@[\w.]+/g, replace: (m: string) => m[0] + '***@' + m.split('@')[1] },
  { pattern: /\d{17}[\dXx]/g, replace: (m: string) => m.slice(0,3) + '***********' + m.slice(14) },
  { pattern: /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, replace: '[REDACTED]' },
];
```

---

## 10. 架构决策记录(ADR)

### ADR-F01：openapi-generator 选用 typescript-fetch 模板

- **决策**：typescript-fetch
- **理由**：零第三方依赖，小程序可通过 fetcher 适配层注入 Taro.request
- **状态**：Accepted
- **来源**：https://github.com/OpenAPITools/openapi-generator

### ADR-F02：Taro 4 作为跨端框架

- **决策**：Taro 4（H5 + 微信小程序同源）
- **理由**：React 语法与 admin-web 统一；H5+小程序编译成熟；37.6k stars
- **剔除**：uni-app x（Vue 绑定，App 红利非当前目标）
- **状态**：Accepted
- **来源**：https://github.com/NervJS/taro

### ADR-F03：小程序分包策略

- **决策**：主包(首页/课程/我的/登录) + 4 个分包(live/coding/quiz/assignment)
- **理由**：主包 < 2MB 微信限制；直播/编程组件重独立分包按需加载
- **状态**：Accepted

### ADR-F04：状态管理分层（TanStack Query + React Context）

- **决策**：服务端状态用 TanStack Query，客户端状态用 React Context，禁止 Redux/Zustand
- **理由**：TanStack Query 自动缓存/重试/失效，适合 API 数据；Context 适合轻量 UI 状态；Redux 过重
- **状态**：Accepted
- **来源**：https://github.com/TanStack/query (50k stars)

### ADR-F05：admin-web 使用 Ant Design 5（非 ui-kit）

- **决策**：PC 管理后台使用 Ant Design 5 组件库，不使用 packages/ui-kit
- **理由**：admin-web 不需要跨端（仅 PC 浏览器）；Ant Design 5 是 PC 管理后台事实标准；ui-kit(Taro 4) 是为 H5+小程序跨端设计的
- **状态**：Accepted

### ADR-F06：Turborepo 远程缓存 self-hosted

- **决策**：self-hosted turbo-cache（Redis 后端），禁用 Vercel 云缓存
- **理由**：PIPL 数据合规（缓存产物可能含业务代码片段，不出集群）
- **状态**：Accepted

---

## 11. 技术选型矩阵

| 维度 | 候选A | 候选B | 候选C | 决策 | 理由 | ADR |
|------|-------|-------|-------|------|------|-----|
| Monorepo 管理 | pnpm+Turborepo | Nx | 纯pnpm | pnpm+Turborepo | Rust内核增量构建，轻量，大厂验证 | D-031 |
| API 契约 | OAS codegen | tRPC | 手写 | OAS codegen | Java后端不适配tRPC；手写无类型安全 | ADR-F01 |
| 跨端 UI | Taro 4 | uni-app x | 原生分离 | Taro 4 | React语法统一，H5+小程序成熟 | ADR-F02 |
| 服务端状态 | TanStack Query | SWR | Redux | TanStack Query | 自动GC/缓存/重试，功能最全 | ADR-F04 |
| 构建工具 | Vite | Rsbuild | Webpack 5 | Vite | 事实标准，Rust内核，82.2k stars | D-031 |
| 运行时校验 | Zod | valibot | 纯TS | Zod | TS深度集成，z.infer单一数据源 | D-031 |
| PC组件库 | Ant Design 5 | ui-kit | 自建 | Ant Design 5 | PC事实标准，不需跨端 | ADR-F05 |
| 小程序分包 | 4分包 | 全主包 | 动态分包 | 4分包 | 主包<2MB，重组件按需加载 | ADR-F03 |
| 远程缓存 | self-hosted | Vercel云 | 禁用 | self-hosted | PIPL合规 | ADR-F06 |

---

## 12. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| Taro 4 小程序端 `live-player` 属性透传不全 | 中 | 高 | 直播课组件预留原生 WXML 降级方案；SP-05 联调验证 |
| openapi-generator 生成产物体积大影响小程序包 | 中 | 中 | tree-shake + typescript-fetch 轻量模板 + 分包隔离 |
| BFF OAS 与前端 client 契约漂移 | 高 | 高 | CI 契约校验门(零容忍) + Zod 运行时兜底 |
| Turborepo 远程缓存 self-hosted 运维成本 | 低 | 低 | 可选降级为本地缓存（不阻塞开发） |
| Zod 高频校验性能开销 | 低 | 中 | 仅信任边界校验 + 生产环境 10% 采样 |
| 小程序基础库版本兼容 | 低 | 中 | 锁定基础库 ≥ 3.0.0，CI 多版本验证 |
| pnpm 幽灵依赖（小程序生态旧包） | 中 | 低 | `.npmrc` shamefully-hoist 兜底 + CI 依赖审计 |

---

> **文档状态**：Approved v1.0
> **下一步**：SP-06 SDD 详细设计 → 原型 HTML → 测试/部署文档
