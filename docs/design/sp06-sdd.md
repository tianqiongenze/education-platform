# SP-06 多端前端骨架 —— 软件详细设计文档(SDD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp06` for reference continuity. Per D-137, sp06 → portal-bff. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP06-SDD-001 | v1.0 | 2026-08-04 | Approved | ZCode(前端架构师) | SP06-SRS-001 / SP06-SAD-001 / D-031 |

> 本文是 SP-06 多端前端骨架的**详细设计基线**，定义 Monorepo 工程结构、各包内部实现、关键类/接口/时序、构建配置。

---

## 目录

1. [工程结构总览](#1-工程结构总览)
2. [packages/shared 详细设计](#2-packagesshared-详细设计)
3. [packages/api-client 详细设计](#3-packagesapi-client-详细设计)
4. [packages/query 详细设计](#4-packagesquery-详细设计)
5. [packages/ui-kit 详细设计](#5-packagesui-kit-详细设计)
6. [apps/admin-web 详细设计](#6-appsadmin-web-详细设计)
7. [apps/student-h5 详细设计](#7-appsstudent-h5-详细设计)
8. [apps/student-mp 详细设计](#8-appsstudent-mp-详细设计)
9. [构建配置详细设计](#9-构建配置详细设计)
10. [关键时序图](#10-关键时序图)

---

## 1. 工程结构总览

### 1.1 根目录文件

```
frontend/
├── pnpm-workspace.yaml          # workspace 定义
├── turbo.json                   # Turborepo 任务管道
├── package.json                 # root scripts + devDeps
├── .npmrc                       # pnpm 配置
├── tsconfig.base.json           # TS strict 基础配置
├── .gitignore
├── .editorconfig
└── README.md
```

### 1.2 根 package.json

```json
{
  "name": "education-platform-frontend",
  "version": "1.0.0",
  "private": true,
  "packageManager": "pnpm@11.0.0",
  "scripts": {
    "dev:admin": "turbo run dev --filter=admin-web",
    "dev:h5": "turbo run dev --filter=student-h5",
    "dev:mp": "turbo run dev --filter=student-mp",
    "build": "turbo run build",
    "lint": "turbo run lint",
    "typecheck": "turbo run typecheck",
    "test": "turbo run test",
    "gen:api": "turbo run gen:api --filter=api-client",
    "storybook": "turbo run storybook --filter=ui-kit"
  },
  "devDependencies": {
    "turbo": "^2.5.0",
    "typescript": "^5.7.0",
    "prettier": "^3.4.0"
  },
  "engines": {
    "node": ">=22.0.0",
    "pnpm": ">=11.0.0"
  }
}
```

### 1.3 .npmrc

```ini
# 小程序生态部分旧包未正确声明 peerDeps，需要 shamefully-hoist 兜底
shamefully-hoist=true
# 严格 peer deps 检查（开发阶段发现问题）
strict-peer-dependencies=false
# 自动安装 peer deps
auto-install-peers=true
```

### 1.4 tsconfig.base.json

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "jsx": "react-jsx",

    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "noImplicitReturns": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,

    "esModuleInterop": true,
    "allowSyntheticDefaultImports": true,
    "forceConsistentCasingInFileNames": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "verbatimModuleSyntax": true,

    "declaration": true,
    "declarationMap": true,
    "sourceMap": true
  }
}
```

---

## 2. packages/shared 详细设计

### 2.1 目录结构

```
packages/shared/
├── src/
│   ├── types/
│   │   ├── user.ts          # User / UserRole / UserProfile
│   │   ├── course.ts        # Course / CourseChapter / Lesson
│   │   ├── assignment.ts    # Assignment / Submission / Grade
│   │   ├── live.ts          # LiveRoom / LiveSession
│   │   ├── coding.ts        # CodeSubmission / JudgeResult
│   │   ├── common.ts        # Pagination / ApiResponse / ErrorCode
│   │   └── index.ts
│   ├── constants/
│   │   ├── env.ts           # 环境变量 key
│   │   ├── storage.ts       # Storage key 常量
│   │   ├── api.ts           # API 路径常量
│   │   └── index.ts
│   ├── utils/
│   │   ├── format.ts        # 日期/金额/文件大小格式化
│   │   ├── validate.ts      # 通用校验函数
│   │   ├── retry.ts         # 指数退避重试
│   │   └── index.ts
│   ├── schemas/
│   │   ├── form.ts          # 表单 Zod schema(非 API 边界)
│   │   └── index.ts
│   └── index.ts
├── tsconfig.json
└── package.json
```

### 2.2 核心类型定义

```typescript
// packages/shared/src/types/common.ts
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
  timestamp: number;
}

export interface Pagination<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  hasMore: boolean;
}

export type ErrorCode =
  | 'SUCCESS'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'VALIDATION_ERROR'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR';
```

```typescript
// packages/shared/src/types/user.ts
export type UserRole = 'student' | 'teacher' | 'admin' | 'super_admin';

export interface User {
  id: string;
  username: string;
  nickname: string;
  avatar: string;
  role: UserRole;
  cellId: string;
  email: string | null;
  phone: string | null;
  createdAt: string;  // ISO 8601
  updatedAt: string;
}

export interface UserProfile extends User {
  bio: string | null;
  enrolledCourseCount: number;
  completedLessonCount: number;
  certificateCount: number;
}
```

```typescript
// packages/shared/src/types/course.ts
export type CourseStatus = 'draft' | 'published' | 'archived';
export type LessonType = 'video' | 'live' | 'coding' | 'quiz' | 'reading';

export interface Course {
  id: string;
  title: string;
  description: string;
  coverImage: string;
  status: CourseStatus;
  teacherId: string;
  teacherName: string;
  price: number;
  enrollmentCount: number;
  rating: number;
  tags: string[];
  chapters: CourseChapter[];
  createdAt: string;
  updatedAt: string;
}

export interface CourseChapter {
  id: string;
  courseId: string;
  title: string;
  order: number;
  lessons: Lesson[];
}

export interface Lesson {
  id: string;
  chapterId: string;
  title: string;
  type: LessonType;
  duration: number;       // 秒
  order: number;
  isFree: boolean;
  resourceUrl: string | null;
}
```

### 2.3 通用工具函数

```typescript
// packages/shared/src/utils/format.ts
export function formatDate(date: string | Date, format: 'short' | 'long' = 'short'): string {
  const d = typeof date === 'string' ? new Date(date) : date;
  if (format === 'short') {
    return d.toLocaleDateString('zh-CN');
  }
  return d.toLocaleString('zh-CN');
}

export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}h${m}m`;
  if (m > 0) return `${m}m${s}s`;
  return `${s}s`;
}

export function formatPrice(cents: number): string {
  return (cents / 100).toFixed(2);
}
```

### 2.4 Storage Key 常量

```typescript
// packages/shared/src/constants/storage.ts
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'ep_access_token',
  REFRESH_TOKEN: 'ep_refresh_token',
  USER_PROFILE: 'ep_user_profile',
  THEME: 'ep_theme',
  CELL_ID: 'ep_cell_id',
  LANGUAGE: 'ep_language',
} as const;
```

---

## 3. packages/api-client 详细设计

### 3.1 目录结构

```
packages/api-client/
├── src/
│   ├── generated/              # openapi-generator 产物(committed)
│   │   ├── apis/
│   │   │   ├── CourseApi.ts
│   │   │   ├── UserApi.ts
│   │   │   ├── AssignmentApi.ts
│   │   │   └── ...
│   │   ├── models/
│   │   │   ├── Course.ts
│   │   │   ├── User.ts
│   │   │   └── ...
│   │   └── index.ts
│   ├── client.ts               # createClient 工厂
│   ├── fetcher.ts              # H5/MP fetcher 适配
│   ├── auth.ts                 # JWT token 管理 + 401 刷新
│   ├── validate.ts             # Zod 校验包装
│   ├── error.ts                # ApiError 定义
│   ├── types.ts                # Fetcher / ClientConfig 类型
│   └── index.ts
├── schemas/
│   └── bff-openapi.json        # BFF OAS 副本(CI 对比用)
├── openapi-generator.config.yaml
├── tsconfig.json
└── package.json
```

### 3.2 openapi-generator.config.yaml

```yaml
generatorName: typescript-fetch
outputDir: src/generated
inputSpec: schemas/bff-openapi.json
npmName: '@ep/api-client'
npmVersion: '1.0.0'
additionalProperties:
  supportsES6: true
  typescriptThreePlus: true
  withoutRuntimeChecks: false
  useSingleRequestParameter: true
```

### 3.3 核心接口定义

```typescript
// packages/api-client/src/types.ts
export type Fetcher = (
  url: string,
  init: RequestInit
) => Promise<unknown>;

export interface TokenStorage {
  getAccessToken(): string | null;
  setAccessToken(token: string): void;
  getRefreshToken(): string | null;
  setRefreshToken(token: string): void;
  clear(): void;
}

export interface ClientConfig {
  apiBaseUrl: string;
  fetcher: Fetcher;
  tokenStorage: TokenStorage;
  getCellId?: () => string | undefined;
  refreshUrl?: string;         // 默认 /auth/refresh
  zodValidate?: boolean;       // 默认 true(开发环境) / 采样(生产)
}
```

### 3.4 createClient 工厂实现

```typescript
// packages/api-client/src/client.ts
import { Configuration, CourseApi, UserApi, AssignmentApi } from './generated';
import type { ClientConfig, Fetcher } from './types';
import { createAuthInterceptor } from './auth';
import { createValidationInterceptor } from './validate';

export function createClient(config: ClientConfig) {
  const authInterceptor = createAuthInterceptor(config);
  const validationInterceptor = createValidationInterceptor(config);

  const wrappedFetcher: Fetcher = async (url, init) => {
    // 1. auth 拦截器: 注入 JWT + Cell header + Trace header
    const authedInit = await authInterceptor(url, init);
    // 2. 发起请求
    let result = await config.fetcher(url, authedInit);
    // 3. 401 刷新 + 重放
    if (result instanceof ApiError && result.status === 401) {
      const refreshed = await authInterceptor.refresh();
      if (refreshed) {
        const reAuthedInit = await authInterceptor(url, init);
        result = await config.fetcher(url, reAuthedInit);
      }
    }
    // 4. Zod 校验
    return validationInterceptor(url, result);
  };

  const configuration = new Configuration({
    basePath: config.apiBaseUrl,
    fetchApi: wrappedFetcher as any,
  });

  return {
    course: new CourseApi(configuration),
    user: new UserApi(configuration),
    assignment: new AssignmentApi(configuration),
    // ... 其他 API
  };
}

export type ApiClient = ReturnType<typeof createClient>;
```

### 3.5 Auth 拦截器实现

```typescript
// packages/api-client/src/auth.ts
import type { ClientConfig, RequestInit } from './types';

export function createAuthInterceptor(config: ClientConfig) {
  let refreshing: Promise<boolean> | null = null;

  async function inject(url: string, init: RequestInit): Promise<RequestInit> {
    const headers = new Headers(init.headers);
    const token = config.tokenStorage.getAccessToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    const cellId = config.getCellId?.();
    if (cellId) {
      headers.set('X-Cell-Routing', cellId);
    }
    headers.set('X-Trace-Id', generateTraceId());
    headers.set('X-Client-Version', APP_VERSION);
    return { ...init, headers };
  }

  async function refresh(): Promise<boolean> {
    // 防止并发刷新
    if (refreshing) return refreshing;

    refreshing = doRefresh(config);
    const result = await refreshing;
    refreshing = null;
    return result;
  }

  return Object.assign(inject, { refresh });
}

async function doRefresh(config: ClientConfig): Promise<boolean> {
  const refreshToken = config.tokenStorage.getRefreshToken();
  if (!refreshToken) return false;

  try {
    const res = await config.fetcher(`${config.apiBaseUrl}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    const { accessToken, refreshToken: newRefresh } = res as TokenResponse;
    config.tokenStorage.setAccessToken(accessToken);
    config.tokenStorage.setRefreshToken(newRefresh);
    return true;
  } catch {
    config.tokenStorage.clear();
    return false;
  }
}
```

### 3.6 Zod 校验包装

```typescript
// packages/api-client/src/validate.ts
import type { ClientConfig } from './types';
import { zodSchemas } from './generated/zod-schemas'; // 由 zod-openapi 生成

export function createValidationInterceptor(config: ClientConfig) {
  const shouldValidate = config.zodValidate ?? true;
  const sampleRate = process.env.NODE_ENV === 'production' ? 0.1 : 1.0;

  return function validate(url: string, data: unknown): unknown {
    if (!shouldValidate) return data;
    if (Math.random() > sampleRate) return data;

    // 根据 URL 匹配对应的 Zod schema
    const schema = matchSchema(url);
    if (!schema) return data;

    const result = schema.safeParse(data);
    if (!result.success) {
      // 开发环境: 抛出详细错误
      if (process.env.NODE_ENV !== 'production') {
        console.error('[Zod] API response validation failed:', {
          url,
          errors: result.error.issues,
        });
        throw new ApiError(500, `Zod validation failed: ${result.error.message}`);
      }
      // 生产环境: 上报但不阻塞(返回原始数据，避免白屏)
      reportValidationError(url, result.error);
      return data;
    }
    return result.data;
  };
}
```

---

## 4. packages/query 详细设计

### 4.1 目录结构

```
packages/query/
├── src/
│   ├── client.ts               # QueryClient 配置
│   ├── keys.ts                 # queryKey 规范化
│   ├── hooks/                  # 查询 hooks
│   │   ├── useCourseList.ts
│   │   ├── useCourseDetail.ts
│   │   ├── useUserProfile.ts
│   │   ├── useEnrollments.ts
│   │   ├── useAssignmentList.ts
│   │   └── ...
│   ├── mutations/              # 变更 hooks
│   │   ├── useLogin.ts
│   │   ├── useEnrollCourse.ts
│   │   ├── useSubmitAssignment.ts
│   │   ├── useUpdateProfile.ts
│   │   └── ...
│   ├── providers/              # QueryProvider 封装
│   │   ├── QueryProvider.tsx
│   │   └── index.ts
│   └── index.ts
├── tsconfig.json
└── package.json
```

### 4.2 QueryClient 配置

```typescript
// packages/query/src/client.ts
import { QueryClient } from '@tanstack/react-query';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5 * 60 * 1000,
        gcTime: 30 * 60 * 1000,
        retry: (failureCount, error) => {
          // 4xx 错误不重试(除了 401/429)
          if (error instanceof ApiError) {
            if (error.status >= 400 && error.status < 500) {
              return error.status === 401 || error.status === 429;
            }
          }
          return failureCount < 3;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 30000),
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
      },
      mutations: {
        retry: 1,
        retryDelay: 1000,
      },
    },
  });
}
```

### 4.3 QueryProvider

```typescript
// packages/query/src/providers/QueryProvider.tsx
import { QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { createQueryClient } from '../client';
import type { ReactNode } from 'react';

const queryClient = createQueryClient();

export function QueryProvider({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {process.env.NODE_ENV === 'development' && (
        <ReactQueryDevtools initialIsOpen={false} />
      )}
    </QueryClientProvider>
  );
}
```

### 4.4 典型查询 Hook

```typescript
// packages/query/src/hooks/useCourseList.ts
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { Pagination, Course } from '@ep/shared';

interface UseCourseListParams {
  page?: number;
  pageSize?: number;
  keyword?: string;
  tag?: string;
}

export function useCourseList(
  client: ApiClient,
  params: UseCourseListParams = {}
) {
  return useQuery({
    queryKey: queryKeys.courses.list(params),
    queryFn: async (): Promise<Pagination<Course>> => {
      const res = await client.course.listCourses({
        page: params.page ?? 1,
        pageSize: params.pageSize ?? 20,
        keyword: params.keyword,
        tag: params.tag,
      });
      return res;
    },
    placeholderData: (prev) => prev, // 翻页时保持旧数据
  });
}
```

### 4.5 典型变更 Hook（乐观更新）

```typescript
// packages/query/src/mutations/useEnrollCourse.ts
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';

export function useEnrollCourse(client: ApiClient) {
  const qc = useQueryClient();

  return useMutation({
    mutationFn: (courseId: string) => client.course.enroll({ courseId }),

    onMutate: async (courseId) => {
      await qc.cancelQueries({ queryKey: queryKeys.user.enrollments() });
      const prev = qc.getQueryData<string[]>(queryKeys.user.enrollments());
      qc.setQueryData<string[]>(queryKeys.user.enrollments(), (old = []) => [
        ...old,
        courseId,
      ]);
      return { prev };
    },

    onError: (_err, _courseId, ctx) => {
      if (ctx?.prev) qc.setQueryData(queryKeys.user.enrollments(), ctx.prev);
    },

    onSettled: () => {
      qc.invalidateQueries({ queryKey: queryKeys.user.enrollments() });
    },
  });
}
```

---

## 5. packages/ui-kit 详细设计

### 5.1 基础组件设计

#### Button 组件

```typescript
// packages/ui-kit/src/components/Button/index.tsx
import { Button as TaroButton } from '@tarojs/components';
import type { ButtonProps } from './types';

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled = false,
  block = false,
  children,
  onClick,
  ...rest
}: ButtonProps) {
  const cls = classNames(
    'ep-btn',
    `ep-btn--${variant}`,
    `ep-btn--${size}`,
    { 'ep-btn--block': block, 'ep-btn--disabled': disabled }
  );

  return (
    <TaroButton
      className={cls}
      loading={loading}
      disabled={disabled || loading}
      onClick={disabled || loading ? undefined : onClick}
      {...rest}
    >
      {children}
    </TaroButton>
  );
}
```

```typescript
// packages/ui-kit/src/components/Button/types.ts
export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link';
export type ButtonSize = 'xs' | 'sm' | 'md' | 'lg';

export interface ButtonProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
  children: ReactNode;
  onClick?: () => void;
}
```

#### VideoPlayer 组件（三端适配）

```typescript
// packages/ui-kit/src/components/VideoPlayer/index.tsx
import { View, Video, LivePlayer } from '@tarojs/components';
import { useEnv } from '@tarojs/taro';
import type { VideoPlayerProps } from './types';

export function VideoPlayer({
  src,
  poster,
  isLive = false,
  onTimeUpdate,
  onEnded,
}: VideoPlayerProps) {
  const env = useEnv();

  // 直播 + 小程序: 使用 live-player
  if (isLive && env === Taro.ENV_TYPE.WEAPP) {
    return (
      <LivePlayer
        src={src}
        mode="live"
        autoplay
        muted={false}
        orientation="vertical"
        objectFit="contain"
      />
    );
  }

  // H5 或点播: 使用 video
  return (
    <Video
      src={src}
      poster={poster}
      controls
      autoplay={false}
      showCenterPlayBtn
      enableProgressGesture
      onTimeUpdate={(e) => onTimeUpdate?.(e.detail.currentTime)}
      onEnded={onEnded}
    />
  );
}
```

### 5.2 主题实现

```typescript
// packages/ui-kit/src/theme/tokens.ts
export const lightTheme = {
  '--color-primary': '#1890ff',
  '--color-primary-hover': '#40a9ff',
  '--color-success': '#52c41a',
  '--color-warning': '#faad14',
  '--color-error': '#ff4d4f',
  '--color-text-primary': '#262626',
  '--color-text-secondary': '#8c8c8c',
  '--color-bg-primary': '#ffffff',
  '--color-bg-secondary': '#f5f5f5',
  '--color-border': '#d9d9d9',
} as const;

export const darkTheme = {
  '--color-primary': '#177ddc',
  '--color-primary-hover': '#1389d6',
  '--color-success': '#389e0d',
  '--color-warning': '#d48806',
  '--color-error': '#cf1322',
  '--color-text-primary': '#e6e6e6',
  '--color-text-secondary': '#8c8c8c',
  '--color-bg-primary': '#1f1f1f',
  '--color-bg-secondary': '#2a2a2a',
  '--color-border': '#434343',
} as const;
```

```typescript
// packages/ui-kit/src/theme/ThemeProvider.tsx
import { useEffect, useState } from 'react';
import { STORAGE_KEYS } from '@ep/shared';
import { lightTheme, darkTheme } from './tokens';

type Theme = 'light' | 'dark';

const ThemeContext = createContext<{ theme: Theme; toggle: () => void }>({
  theme: 'light',
  toggle: () => {},
});

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(
    Taro.getStorageSync(STORAGE_KEYS.THEME) || 'light'
  );

  useEffect(() => {
    const cssVars = theme === 'dark' ? darkTheme : lightTheme;
    // H5: 注入 CSS Variables
    if (process.env.TARO_ENV === 'h5') {
      Object.entries(cssVars).forEach(([key, val]) => {
        document.documentElement.style.setProperty(key, val);
      });
    }
    Taro.setStorageSync(STORAGE_KEYS.THEME, theme);
  }, [theme]);

  return (
    <ThemeContext.Provider value={{ theme, toggle: () => setTheme(t => t === 'light' ? 'dark' : 'light') }}>
      {children}
    </ThemeContext.Provider>
  );
}
```

---

## 6. apps/admin-web 详细设计

### 6.1 目录结构

```
apps/admin-web/
├── src/
│   ├── main.tsx                # 入口
│   ├── App.tsx                 # 根组件 + 路由
│   ├── vite-env.d.ts
│   ├── layouts/
│   │   └── AdminLayout.tsx     # ProLayout 侧边栏+顶栏+标签页
│   ├── routes/
│   │   ├── index.tsx           # 路由定义
│   │   └── AuthGuard.tsx       # 权限守卫
│   ├── pages/
│   │   ├── Login/
│   │   │   └── index.tsx
│   │   ├── Dashboard/
│   │   │   └── index.tsx
│   │   ├── CourseMgmt/
│   │   │   ├── List.tsx
│   │   │   └── Detail.tsx
│   │   ├── AssignmentMgmt/
│   │   ├── StudentMgmt/
│   │   ├── TeacherMgmt/
│   │   ├── LiveMgmt/
│   │   ├── CodingMgmt/
│   │   ├── GradeMgmt/
│   │   └── SystemSettings/
│   ├── hooks/
│   │   └── usePermission.ts
│   ├── stores/                 # 客户端状态(Context)
│   │   └── UserContext.tsx
│   └── styles/
│       └── global.css
├── vite.config.ts
├── tsconfig.json
└── package.json
```

### 6.2 路由 + 权限守卫

```typescript
// apps/admin-web/src/routes/index.tsx
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AuthGuard } from './AuthGuard';
import AdminLayout from '../layouts/AdminLayout';

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <Login />,
  },
  {
    path: '/',
    element: (
      <AuthGuard>
        <AdminLayout />
      </AuthGuard>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'courses', element: <CourseList /> },
      { path: 'courses/:id', element: <CourseDetail /> },
      { path: 'assignments', element: <AssignmentMgmt /> },
      { path: 'students', element: <StudentMgmt /> },
      { path: 'teachers', element: <TeacherMgmt /> },
      { path: 'live', element: <LiveMgmt /> },
      { path: 'coding', element: <CodingMgmt /> },
      { path: 'grades', element: <GradeMgmt /> },
      { path: 'settings', element: <SystemSettings /> },
    ],
  },
]);
```

```typescript
// apps/admin-web/src/routes/AuthGuard.tsx
import { Navigate, useLocation } from 'react-router-dom';
import { useUserStore } from '../stores/UserContext';

export function AuthGuard({ children }: { children: ReactNode }) {
  const { user } = useUserStore();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  // 权限路由: 根据 role 检查
  const requiredRole = getRequiredRole(location.pathname);
  if (requiredRole && !hasRole(user.role, requiredRole)) {
    return <Navigate to="/403" replace />;
  }

  return <>{children}</>;
}
```

### 6.3 Vite 配置

```typescript
// apps/admin-web/vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@ep/api-client': resolve(__dirname, '../../packages/api-client/src'),
      '@ep/query': resolve(__dirname, '../../packages/query/src'),
      '@ep/shared': resolve(__dirname, '../../packages/shared/src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // BFF
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd', '@ant-design/icons'],
          'query-vendor': ['@tanstack/react-query'],
        },
      },
    },
  },
});
```

---

## 7. apps/student-h5 详细设计

### 7.1 目录结构

```
apps/student-h5/
├── src/
│   ├── app.tsx                 # Taro 入口
│   ├── app.config.ts           # Taro H5 配置
│   ├── app.scss
│   ├── pages/
│   │   ├── index/              # 首页
│   │   │   ├── index.tsx
│   │   │   └── index.scss
│   │   ├── course/             # 课程列表
│   │   │   └── index.tsx
│   │   ├── course-detail/      # 课程详情
│   │   │   └── index.tsx
│   │   ├── learning/           # 学习页
│   │   │   └── index.tsx
│   │   ├── profile/            # 我的
│   │   │   └── index.tsx
│   │   └── login/
│   │       └── index.tsx
│   ├── components/             # H5 特有组件
│   └── utils/
├── config/
│   ├── h5.ts                   # Taro H5 编译配置
│   └── index.ts
├── tsconfig.json
└── package.json
```

### 7.2 Taro 配置

```typescript
// apps/student-h5/config/index.ts
import { defineConfig } from '@tarojs/cli';
import devConfig from './dev';
import prodConfig from './prod';

export default defineConfig(async (merge) => {
  const base: UserConfigExport = {
    projectName: 'student-h5',
    date: '2026-08-04',
    designWidth: 750,
    deviceRatio: { 640: 2.34 / 2, 750: 1, 828: 1.81 / 2 },
    sourceRoot: 'src',
    outputRoot: 'dist',
    plugins: [],
    defineConstants: {},
    copy: { patterns: [], options: {} },
    framework: 'react',
    compiler: 'webpack5',
    cache: { enable: false },
    mini: {}, // H5 only, no mini config
    h5: {
      publicPath: '/',
      staticDirectory: 'static',
      output: { filename: 'js/[name].[hash:8].js', chunkFilename: 'js/[name].[chunkhash:8].js' },
      router: { mode: 'browser' },
      devServer: {
        port: 3001,
        proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
      },
    },
  };
  return merge(base, process.env.NODE_ENV === 'development' ? devConfig : prodConfig);
});
```

### 7.3 app.config.ts

```typescript
// apps/student-h5/src/app.config.ts
export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/course/index',
    'pages/course-detail/index',
    'pages/learning/index',
    'pages/profile/index',
    'pages/login/index',
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#1890ff',
    navigationBarTitleText: 'Education Platform',
    navigationBarTextStyle: 'white',
  },
  tabBar: {
    color: '#8c8c8c',
    selectedColor: '#1890ff',
    backgroundColor: '#ffffff',
    borderStyle: 'black',
    list: [
      { pagePath: 'pages/index/index', text: '首页', iconPath: 'assets/tab/home.png', selectedIconPath: 'assets/tab/home-active.png' },
      { pagePath: 'pages/course/index', text: '课程', iconPath: 'assets/tab/course.png', selectedIconPath: 'assets/tab/course-active.png' },
      { pagePath: 'pages/profile/index', text: '我的', iconPath: 'assets/tab/profile.png', selectedIconPath: 'assets/tab/profile-active.png' },
    ],
  },
});
```

---

## 8. apps/student-mp 详细设计

### 8.1 目录结构

```
apps/student-mp/
├── src/
│   ├── app.tsx                 # Taro 入口
│   ├── app.config.ts           # 小程序全局配置(tabBar + 分包)
│   ├── app.scss
│   ├── pages/                  # 主包页面
│   │   ├── index/              # 首页
│   │   ├── course/             # 课程列表
│   │   ├── profile/            # 我的
│   │   └── login/              # 登录(wx.login)
│   ├── subpackages/            # 分包
│   │   ├── live/               # 直播分包
│   │   │   └── pages/
│   │   │       ├── room/       # 直播间
│   │   │       └── list/       # 直播列表
│   │   ├── coding/             # 在线编程分包
│   │   │   └── pages/
│   │   │       ├── editor/     # 代码编辑器
│   │   │       └── result/     # 评测结果
│   │   ├── quiz/               # 考试评测分包
│   │   │   └── pages/
│   │   │       ├── exam/       # 考试页
│   │   │       └── result/     # 成绩页
│   │   └── assignment/         # 作业分包
│   │       └── pages/
│   │           ├── detail/     # 作业详情
│   │           └── submit/     # 提交页
│   └── utils/
│       └── wx-login.ts         # 小程序登录
├── config/
│   ├── weapp.ts                # 微信小程序编译配置
│   └── index.ts
├── project.config.json         # 微信开发者工具配置
├── tsconfig.json
└── package.json
```

### 8.2 小程序 app.config.ts（含分包）

```typescript
// apps/student-mp/src/app.config.ts
export default defineAppConfig({
  pages: [
    'pages/index/index',
    'pages/course/index',
    'pages/profile/index',
    'pages/login/index',
  ],
  subPackages: [
    {
      root: 'subpackages/live',
      pages: ['pages/room/index', 'pages/list/index'],
    },
    {
      root: 'subpackages/coding',
      pages: ['pages/editor/index', 'pages/result/index'],
    },
    {
      root: 'subpackages/quiz',
      pages: ['pages/exam/index', 'pages/result/index'],
    },
    {
      root: 'subpackages/assignment',
      pages: ['pages/detail/index', 'pages/submit/index'],
    },
  ],
  preloadRule: {
    'pages/index/index': {
      network: 'wifi',
      packages: ['subpackages/live'],
    },
  },
  window: {
    navigationBarBackgroundColor: '#1890ff',
    navigationBarTitleText: 'Education Platform',
    navigationBarTextStyle: 'white',
  },
  tabBar: {
    color: '#8c8c8c',
    selectedColor: '#1890ff',
    backgroundColor: '#ffffff',
    borderStyle: 'black',
    list: [
      { pagePath: 'pages/index/index', text: '首页' },
      { pagePath: 'pages/course/index', text: '课程' },
      { pagePath: 'pages/profile/index', text: '我的' },
    ],
  },
  permission: {
    'scope.userLocation': { desc: '用于推荐附近课程' },
  },
  requiredPrivateInfos: ['getLocation'],
  // 隐私协议(C-11)
  __usePrivacyCheck__: true,
});
```

### 8.3 小程序登录流程

```typescript
// apps/student-mp/src/utils/wx-login.ts
import Taro from '@tarojs/taro';
import { STORAGE_KEYS } from '@ep/shared';

export async function wxLogin(): Promise<{ accessToken: string; refreshToken: string }> {
  // 1. wx.login 获取 code
  const { code } = await Taro.login();

  // 2. 发送 code 到 BFF 换 JWT
  const res = await Taro.request({
    url: `${API_BASE_URL}/auth/wx-login`,
    method: 'POST',
    data: { code },
  });

  if (res.statusCode !== 200) {
    throw new Error(`微信登录失败: ${res.data.message}`);
  }

  const { accessToken, refreshToken } = res.data.data;

  // 3. 存储 token
  Taro.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken);
  Taro.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, refreshToken);

  return { accessToken, refreshToken };
}
```

---

## 9. 构建配置详细设计

### 9.1 ESLint 配置

```javascript
// packages/lint-config/eslint.config.js
import tseslint from 'typescript-eslint';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  {
    ignores: ['**/dist/**', '**/build/**', '**/.taro/**', '**/src/generated/**'],
  },
  ...tseslint.configs.strict,
  {
    files: ['**/*.tsx', '**/*.ts'],
    plugins: { react, 'react-hooks': reactHooks },
    rules: {
      'react/react-in-jsx-scope': 'off',
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  }
);
```

### 9.2 Vitest 配置

```typescript
// packages/shared/vitest.config.ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      thresholds: { lines: 80, functions: 80, branches: 75 },
    },
  },
});
```

### 9.3 CI Pipeline (.github-ci.yml 概念)

```yaml
# CI 流水线概念(实际用 GitHub Actions / GitLab CI)
stages:
  - install
  - lint
  - typecheck
  - test
  - gen-api
  - build
  - contract-check

install:
  - pnpm install --frozen-lockfile

lint:
  - turbo run lint

typecheck:
  - turbo run typecheck

test:
  - turbo run test --coverage

gen-api:
  - turbo run gen:api
  - git diff --exit-code packages/api-client/src/generated/

build:
  - turbo run build

contract-check:
  - bash tools/check-contract.sh
```

---

## 10. 关键时序图

### 10.1 首次登录时序

```
用户         admin-web/h5/mp      BFF(Java)         JWKS
 │                │                  │                 │
 │─输入账密──────►│                  │                 │
 │                │─POST /auth/login─►│                 │
 │                │                  │─验证凭证        │
 │                │                  │─RS256签JWT      │
 │                │◄─{access,refresh}─│                 │
 │                │                  │                 │
 │                │─存Storage        │                 │
 │                │─跳转首页         │                 │
 │                │                  │                 │
 │                │─GET /api/profile─►│                 │
 │                │                  │─JWKS验签────────►│
 │                │                  │◄─public key──────│
 │                │                  │─验签通过        │
 │                │◄─user profile────│                 │
 │◄─渲染首页──────│                  │                 │
```

### 10.2 Token 过期自动刷新时序

```
前端                     BFF
  │                       │
  │─GET /api/courses      │
  │  Authorization: Bearer│
  │  <expired-token>      │
  │──────────────────────►│
  │                       │
  │◄──401 Unauthorized────│
  │                       │
  │  // 拦截器检测401      │
  │  // 暂停所有并发请求   │
  │                       │
  │─POST /auth/refresh    │
  │  {refreshToken}       │
  │──────────────────────►│
  │                       │
  │◄──{new-access,new-refresh}│
  │                       │
  │  // 更新Storage       │
  │  // 重放原请求        │
  │                       │
  │─GET /api/courses      │
  │  Authorization: Bearer│
  │  <new-token>          │
  │──────────────────────►│
  │                       │
  │◄──200 courses─────────│
```

### 10.3 API 契约 CI 校验时序

```
开发者提交PR         CI              BFF(Staging)
   │                 │                  │
   │─git push────────►│                  │
   │                 │                  │
   │                 │─下载BFF OAS──────►│
   │                 │  GET /v3/api-docs │
   │                 │◄──OAS JSON────────│
   │                 │                  │
   │                 │─diff vs committed │
   │                 │  OAS副本          │
   │                 │                  │
   │                 │  [有diff?]        │
   │                 │  ├─是→❌CI fail   │
   │                 │  │  "请运行gen:api"│
   │                 │  │                │
   │                 │  └─否→继续        │
   │                 │                  │
   │                 │─pnpm gen:api     │
   │                 │  (重新生成)       │
   │                 │                  │
   │                 │─git diff          │
   │                 │  src/generated/   │
   │                 │                  │
   │                 │  [有diff?]        │
   │                 │  ├─是→❌CI fail   │
   │                 │  │  "生成产物未提交"│
   │                 │  │                │
   │                 │  └─否→✅契约一致  │
   │                 │                  │
   │◄──CI结果────────│                  │
```

---

> **文档状态**：Approved v1.0
> **下一步**：SP-06 测试/部署文档 → 三端原型 HTML
