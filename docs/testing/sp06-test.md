# SP-06 多端前端骨架 —— 测试方案文档

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP06-TEST-001 | v1.0 | 2026-08-04 | Approved | ZCode(测试架构师) | SP06-SRS-001 / SP06-SAD-001 / SP06-SDD-001 / D-031 |

> 本文是 SP-06 多端前端骨架的**测试基线**，定义各层测试策略、用例矩阵、自动化流水线、压测方案。

---

## 目录

1. [测试策略总览](#1-测试策略总览)
2. [测试金字塔](#2-测试金字塔)
3. [单元测试](#3-单元测试)
4. [集成测试](#4-集成测试)
5. [契约测试](#5-契约测试)
6. [端到端测试(E2E)](#6-端到端测试e2e)
7. [跨端兼容性测试](#7-跨端兼容性测试)
8. [性能测试](#8-性能测试)
9. [安全测试](#9-安全测试)
10. [CI 自动化流水线](#10-ci-自动化流水线)
11. [测试用例追溯矩阵](#11-测试用例追溯矩阵)

---

## 1. 测试策略总览

### 1.1 测试原则

| 原则 | 说明 |
|------|------|
| **契约先行** | API 契约(OAS)是前后端唯一真相源；前端测试 mock 基于 OAS，后端测试验证 OAS conformance |
| **分层隔离** | 单元/集成/E2E 各层独立运行，互不依赖；E2E 仅验证关键用户旅程 |
| **跨端复用** | packages/shared/api-client/query/ui-kit 的测试在三端复用；端特定逻辑各自独立测试 |
| **TDD** | 公共包(packages/*)强制 TDD，覆盖率 ≥80%；apps 覆盖率 ≥60% |
| **快反馈** | 单元测试 <30s，集成 <3min，E2E <10min；按 PR 触发对应层 |
| **合规硬门** | C-09(缓存不出境)/C-10(错误上报无PII)/C-11(小程序隐私)/C-12(海外无国内CDN) 为 CI 硬门 |

### 1.2 测试工具链

| 层级 | 工具 | 版本 | 用途 |
|------|------|------|------|
| 单元测试 | Vitest | ^3.0.0 | packages/* + apps 逻辑测试 |
| 组件测试 | Vitest + @testing-library/react | ^16.0.0 | React 组件渲染/交互测试 |
| API Mock | MSW (Mock Service Worker) | ^2.7.0 | H5 请求拦截 mock |
| 小程序 Mock | @tarojs/helper + jest-mock | Taro 4 内置 | 小程序 API mock |
| 契约测试 | Pact JS | ^13.x | 前后端消费者驱动契约 |
| E2E (H5) | Playwright | ^1.49.0 | H5 端到端 |
| E2E (MP) | miniprogram-automator | 官方 | 微信小程序自动化 |
| 视觉回归 | Playwright screenshot | 内置 | H5 截图对比 |
| 覆盖率 | c8 / istanbul | Vitest 内置 | 覆盖率采集 |
| 压测 | k6 | ^0.54.0 | 前端资源加载 + API 并发 |

---

## 2. 测试金字塔

```
                    ┌─────────┐
                    │  E2E    │  ~5%   Playwright + miniprogram-automator
                    │ 关键旅程 │        <10min
                  ┌─┴─────────┴─┐
                  │  集成/契约   │  ~15%  Vitest + MSW + Pact
                  │  组件交互    │        <3min
                ┌─┴─────────────┴─┐
                │    单元测试      │  ~80%  Vitest
                │  纯函数/类型/Zod │        <30s
                └──────────────────┘
```

---

## 3. 单元测试

### 3.1 packages/shared 单元测试

```typescript
// packages/shared/src/utils/__tests__/format.test.ts
import { describe, it, expect } from 'vitest';
import { formatDate, formatDuration, formatPrice } from '../format';

describe('formatDate', () => {
  it('短格式返回中文日期', () => {
    expect(formatDate('2026-08-04T10:00:00Z')).toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/);
  });

  it('长格式包含时间', () => {
    const result = formatDate('2026-08-04T10:00:00Z', 'long');
    expect(result).toMatch(/\d{1,2}:\d{2}/);
  });

  it('接受 Date 对象', () => {
    expect(formatDate(new Date('2026-08-04'))).toBeTruthy();
  });
});

describe('formatDuration', () => {
  it.each([
    [0, '0s'],
    [45, '45s'],
    [90, '1m30s'],
    [3700, '1h1m40s'],
  ])('%i 秒 → %s', (input, expected) => {
    expect(formatDuration(input)).toBe(expected);
  });
});

describe('formatPrice', () => {
  it('分转元保留两位小数', () => {
    expect(formatPrice(19999)).toBe('199.99');
  });

  it('零元', () => {
    expect(formatPrice(0)).toBe('0.00');
  });
});
```

### 3.2 packages/api-client 单元测试

```typescript
// packages/api-client/src/__tests__/auth.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createAuthInterceptor } from '../auth';
import type { ClientConfig } from '../types';

const mockStorage = {
  getAccessToken: vi.fn(),
  setAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setRefreshToken: vi.fn(),
  clear: vi.fn(),
};

const mockFetcher = vi.fn();
const config: ClientConfig = {
  apiBaseUrl: 'https://api.test.com',
  fetcher: mockFetcher,
  tokenStorage: mockStorage,
  getCellId: () => 'cell-001',
};

describe('createAuthInterceptor', () => {
  beforeEach(() => vi.clearAllMocks());

  it('注入 Bearer token + X-Cell-Routing + X-Trace-Id', async () => {
    mockStorage.getAccessToken.mockReturnValue('test-token');
    const intercept = createAuthInterceptor(config);

    const result = await intercept('/api/courses', { method: 'GET' });
    const headers = new Headers(result.headers);

    expect(headers.get('Authorization')).toBe('Bearer test-token');
    expect(headers.get('X-Cell-Routing')).toBe('cell-001');
    expect(headers.get('X-Trace-Id')).toBeTruthy();
  });

  it('无 token 时不注入 Authorization', async () => {
    mockStorage.getAccessToken.mockReturnValue(null);
    const intercept = createAuthInterceptor(config);

    const result = await intercept('/api/courses', { method: 'GET' });
    const headers = new Headers(result.headers);
    expect(headers.get('Authorization')).toBeNull();
  });
});
```

### 3.3 packages/query 单元测试

```typescript
// packages/query/src/hooks/__tests__/useCourseList.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useCourseList } from '../useCourseList';

const mockClient = {
  course: { listCourses: vi.fn() },
};

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe('useCourseList', () => {
  it('成功获取课程列表', async () => {
    mockClient.course.listCourses.mockResolvedValue({
      items: [{ id: '1', title: 'Java 基础' }],
      total: 1,
      page: 1,
      pageSize: 20,
      hasMore: false,
    });

    const { result } = renderHook(() => useCourseList(mockClient as any), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toHaveLength(1);
    expect(result.current.data?.items[0].title).toBe('Java 基础');
  });

  it('API 失败时 isError 为 true', async () => {
    mockClient.course.listCourses.mockRejectedValue(new Error('Network'));
    const { result } = renderHook(() => useCourseList(mockClient as any), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
```

### 3.4 ui-kit 组件测试

```typescript
// packages/ui-kit/src/components/Button/__tests__/Button.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Button } from '../index';

describe('Button', () => {
  it('渲染 children', () => {
    render(<Button>提交</Button>);
    expect(screen.getByText('提交')).toBeDefined();
  });

  it('点击触发 onClick', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>点击</Button>);
    fireEvent.click(screen.getByText('点击'));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('disabled 时不触发 onClick', () => {
    const onClick = vi.fn();
    render(<Button disabled onClick={onClick}>禁用</Button>);
    fireEvent.click(screen.getByText('禁用'));
    expect(onClick).not.toHaveBeenCalled();
  });

  it('loading 状态不触发 onClick', () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>加载中</Button>);
    fireEvent.click(screen.getByText('加载中'));
    expect(onClick).not.toHaveBeenCalled();
  });
});
```

---

## 4. 集成测试

### 4.1 MSW 请求拦截集成测试

```typescript
// apps/admin-web/src/integration/course-list.test.tsx
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CourseList from '../../src/pages/CourseMgmt/List';

const server = setupServer(
  http.get('https://api.test.com/api/courses', () => {
    return HttpResponse.json({
      code: 0,
      message: 'success',
      data: {
        items: [
          { id: '1', title: 'Java 基础', teacherName: '张老师', enrollmentCount: 1200 },
        ],
        total: 1,
        page: 1,
        pageSize: 20,
        hasMore: false,
      },
      traceId: 'test-trace',
      timestamp: Date.now(),
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('CourseList 集成测试', () => {
  it('加载并渲染课程列表', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <CourseList />
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Java 基础')).toBeDefined();
      expect(screen.getByText('张老师')).toBeDefined();
      expect(screen.getByText('1200')).toBeDefined();
    });
  });
});
```

### 4.2 Token 刷新集成测试

```typescript
// packages/api-client/src/integration/token-refresh.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createClient } from '../client';

let accessToken = 'expired-token';
let refreshCalled = 0;

const server = setupServer(
  http.get('https://api.test.com/api/courses', ({ request }) => {
    const auth = request.headers.get('Authorization');
    if (auth === 'Bearer expired-token') {
      return HttpResponse.json({ code: 401, message: 'token expired' }, { status: 401 });
    }
    return HttpResponse.json({ code: 0, data: { items: [] } });
  }),
  http.post('https://api.test.com/auth/refresh', () => {
    refreshCalled++;
    accessToken = 'new-token';
    return HttpResponse.json({
      code: 0,
      data: { accessToken: 'new-token', refreshToken: 'new-refresh' },
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('Token 自动刷新', () => {
  it('401 后自动刷新并重放请求', async () => {
    const storage = {
      getAccessToken: () => 'expired-token',
      setAccessToken: (t: string) => { accessToken = t; },
      getRefreshToken: () => 'old-refresh',
      setRefreshToken: () => {},
      clear: () => {},
    };

    const client = createClient({
      apiBaseUrl: 'https://api.test.com',
      fetcher: globalThis.fetch,
      tokenStorage: storage,
    });

    const res = await client.course.listCourses({ page: 1 });
    expect(refreshCalled).toBe(1);
    expect(accessToken).toBe('new-token');
  });
});
```

---

## 5. 契约测试

### 5.1 Pact 消费者契约

```typescript
// packages/api-client/src/contract/course-api.pact.test.ts
import { describe, it, expect } from 'vitest';
import { Pact } from '@pact-foundation/pact';
import path from 'path';

const provider = new Pact({
  consumer: 'ep-frontend',
  provider: 'ep-bff',
  log: path.resolve(__dirname, 'logs', 'pact.log'),
  dir: path.resolve(__dirname, 'pacts'),
});

describe('Course API 契约', () => {
  beforeAll(() => provider.setup());
  afterAll(() => provider.finalize());

  it('GET /api/courses 返回课程列表', async () => {
    await provider.addInteraction({
      uponReceiving: '请求课程列表',
      withRequest: {
        method: 'GET',
        path: '/api/courses',
        query: { page: '1', pageSize: '20' },
        headers: { Authorization: 'Bearer test-token' },
      },
      willRespondWith: {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: {
          code: 0,
          message: 'success',
          data: {
            items: [
              {
                id: like('1'),
                title: like('Java 基础'),
                teacherName: like('张老师'),
                enrollmentCount: like(100),
              },
            ],
            total: like(1),
            page: like(1),
            pageSize: like(20),
            hasMore: false,
          },
        },
      },
    });

    // 用实际 client 验证
    const client = createClient({
      apiBaseUrl: provider.mockService.baseUrl,
      fetcher: globalThis.fetch,
      tokenStorage: mockStorage,
    });

    const res = await client.course.listCourses({ page: 1, pageSize: 20 });
    expect(res.items[0].title).toBe('Java 基础');
  });
});
```

### 5.2 OAS 契约一致性检查

```bash
#!/bin/bash
# tools/check-contract.sh — CI 契约校验脚本

set -euo pipefail

BFF_URL="${BFF_URL:-http://localhost:8080}"
COMMITTED_OAS="packages/api-client/schemas/bff-openapi.json"

echo "📥 下载 BFF OAS..."
curl -s "$BFF_URL/v3/api-docs" -o /tmp/live-oas.json

echo "🔍 对比 committed OAS vs live OAS..."
DIFF=$(jq --argfile a "$COMMITTED_OAS" --argfile b /tmp/live-oas.json -n 'def deepdiff: .. | select(scalars) as $s | {path: [path], value: $s}; ($a | deepdiff) as $la | ($b | deepdiff) as $lb | $la - $lb')

if [ -n "$DIFF" ] && [ "$DIFF" != "[]" ]; then
  echo "❌ OAS 契约不一致！"
  echo "$DIFF"
  echo "请运行 pnpm gen:api 更新本地 OAS 副本并重新提交"
  exit 1
fi

echo "✅ OAS 契约一致"
```

---

## 6. 端到端测试(E2E)

### 6.1 H5 E2E (Playwright)

```typescript
// e2e/h5/student-journey.spec.ts
import { test, expect } from '@playwright/test';

test.describe('学生 H5 学习旅程', () => {
  test.beforeEach(async ({ page }) => {
    // 登录
    await page.goto('/login');
    await page.fill('[data-testid="username"]', 'student@test.com');
    await page.fill('[data-testid="password"]', 'Test1234!');
    await page.click('[data-testid="login-btn"]');
    await page.waitForURL('/index');
  });

  test('浏览课程 → 查看详情 → 报名 → 开始学习', async ({ page }) => {
    // 1. 首页 → 课程列表
    await page.click('[data-testid="tab-course"]');
    await expect(page.locator('[data-testid="course-card"]')).toHaveCount.greaterThan(0);

    // 2. 点击第一个课程
    await page.click('[data-testid="course-card"] >> nth=0');
    await expect(page.locator('[data-testid="course-title"]')).toBeVisible();

    // 3. 报名
    await page.click('[data-testid="enroll-btn"]');
    await expect(page.locator('[data-testid="enroll-success"]')).toBeVisible();

    // 4. 开始学习
    await page.click('[data-testid="start-learning-btn"]');
    await expect(page.locator('[data-testid="video-player"]')).toBeVisible();
    await expect(page.locator('[data-testid="lesson-title"]')).toBeVisible();
  });

  test('个人中心查看学习进度', async ({ page }) => {
    await page.click('[data-testid="tab-profile"]');
    await expect(page.locator('[data-testid="progress-bar"]')).toBeVisible();
    await expect(page.locator('[data-testid="enrolled-count"]')).toBeVisible();
  });
});
```

### 6.2 Admin E2E (Playwright)

```typescript
// e2e/admin/course-management.spec.ts
import { test, expect } from '@playwright/test';

test.describe('Admin 课程管理', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login');
    await page.fill('[data-testid="admin-username"]', 'admin@test.com');
    await page.fill('[data-testid="admin-password"]', 'Admin1234!');
    await page.click('[data-testid="login-btn"]');
    await page.waitForURL('/dashboard');
  });

  test('创建新课程', async ({ page }) => {
    await page.click('text=课程管理');
    await page.click('[data-testid="create-course-btn"]');

    await page.fill('[data-testid="course-title-input"]', 'E2E 测试课程');
    await page.fill('[data-testid="course-desc-input"]', '自动化测试创建');
    await page.click('[data-testid="submit-btn"]');

    await expect(page.locator('text=创建成功')).toBeVisible();
    await expect(page.locator('text=E2E 测试课程')).toBeVisible();
  });

  test('权限守卫: 普通教师无法访问系统设置', async ({ page, context }) => {
    // 以教师角色登录
    // ... 省略登录代码
    await page.goto('/settings');
    await expect(page).toHaveURL('/403');
  });
});
```

### 6.3 小程序 E2E (miniprogram-automator)

```javascript
// e2e/mp/student-journey.test.js
const automator = require('miniprogram-automator');

describe('小程序学生旅程', () => {
  let miniProgram;
  let page;

  beforeAll(async () => {
    miniProgram = await automator.launch({
      cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli',
      projectPath: 'apps/student-mp/dist/weapp',
    });
  });

  afterAll(async () => {
    await miniProgram.close();
  });

  beforeEach(async () => {
    page = await miniProgram.currentPage();
  });

  it('首页加载课程列表', async () => {
    await page.waitFor(500);
    const courseCards = await page.$$('.course-card');
    expect(courseCards.length).toBeGreaterThan(0);
  });

  it('点击课程进入详情页', async () => {
    const card = await page.$('.course-card');
    await card.tap();
    await page.waitFor(500);
    const title = await page.$('.course-title');
    expect(await title.text()).toBeTruthy();
  });

  it('报名课程', async () => {
    const enrollBtn = await page.$('.enroll-btn');
    await enrollBtn.tap();
    await page.waitFor(1000);
    const toast = await page.$('.toast-content');
    const text = await toast.text();
    expect(text).toContain('报名成功');
  });
});
```

---

## 7. 跨端兼容性测试

### 7.1 H5 浏览器矩阵

| 浏览器 | 版本 | 优先级 | 自动化 |
|--------|------|--------|--------|
| Chrome | 最新-2 | P0 | Playwright |
| Safari | 最新-1 | P0 | Playwright (macOS) |
| Firefox | 最新-1 | P1 | Playwright |
| Edge | 最新 | P1 | Playwright |
| 微信内置浏览器 | 最新 | P0 | 手动 + X5 内核检测 |
| UC 浏览器 | 最新 | P2 | 手动 |

### 7.2 小程序兼容性

| 维度 | 范围 | 测试点 |
|------|------|--------|
| 基础库版本 | 3.5.0+ | API 可用性、polyfill |
| 微信版本 | iOS/Android 最新-2 | live-player、wx.login |
| 分包加载 | WiFi/4G/弱网 | preloadRule、分包下载超时 |
| 隐私接口 | 授权/拒绝 | getLocation、chooseMedia |
| 暗黑模式 | 跟随系统 | CSS Variables 适配 |

### 7.3 弱网测试

```
# k6 弱网模拟配置
stages:
  - duration: 30s, target: 50      # 正常
  - duration: 60s, target: 50      # 持续
    networkConditions:
      downloadRate: '50kbit/s'     # 模拟 3G
      uploadRate: '20kbit/s'
      latency: '300ms'
```

---

## 8. 性能测试

### 8.1 前端性能指标(SLO)

| 指标 | 目标 | 测量工具 |
|------|------|----------|
| FCP (First Contentful Paint) | <1.5s | Lighthouse |
| LCP (Largest Contentful Paint) | <2.5s | Lighthouse |
| FID (First Input Delay) | <100ms | Lighthouse |
| CLS (Cumulative Layout Shift) | <0.1 | Lighthouse |
| H5 首屏 JS Bundle | <300KB(gzip) | webpack-bundle-analyzer |
| 小程序主包大小 | <2MB | 微信开发者工具 |
| 小程序分包大小 | <2MB(单包) | 微信开发者工具 |
| 总分包大小 | <20MB | 微信开发者工具 |

### 8.2 Bundle 体积 CI 门禁

```typescript
// tools/bundle-size-check.ts
const LIMITS = {
  'admin-web': { total: 500 * 1024, chunk: 300 * 1024 },  // KB
  'student-h5': { total: 400 * 1024, chunk: 200 * 1024 },
  'student-mp-main': { total: 1800 * 1024 },  // 小程序主包 <2MB(留余量)
  'student-mp-subpackage': { total: 1800 * 1024 },
};

export function checkBundleSize(app: string, stats: BundleStats): boolean {
  const limit = LIMITS[app];
  if (stats.total > limit.total) {
    console.error(`❌ ${app} 总体积 ${stats.total}KB 超限 ${limit.total}KB`);
    return false;
  }
  // ...chunk 检查
  return true;
}
```

### 8.3 Lighthouse CI

```yaml
# lighthouserc.json
{
  "ci": {
    "collect": {
      "url": ["http://localhost:3000", "http://localhost:3001"],
      "numberOfRuns": 3,
      "settings": {
        "preset": "desktop"
      }
    },
    "assert": {
      "assertions": {
        "categories:performance": ["warn", { "minScore": 0.8 }],
        "categories:accessibility": ["error", { "minScore": 0.9 }],
        "first-contentful-paint": ["error", { "maxNumericValue": 1500 }],
        "largest-contentful-paint": ["error", { "maxNumericValue": 2500 }],
        "cumulative-layout-shift": ["error", { "maxNumericValue": 0.1 }]
      }
    }
  }
}
```

---

## 9. 安全测试

### 9.1 前端安全检查清单

| 检查项 | 要求 | CI 门禁 | 工具 |
|--------|------|---------|------|
| XSS 防护 | React 默认转义 + DOMPurify 处理富文本 | error | eslint-plugin-no-unsanitized |
| CSP 策略 | H5 配置 Content-Security-Policy header | error | 自定义脚本检查 |
| Token 存储 | access_token 存 Storage(非 localStorage for H5) | warn | 代码审查 |
| PII 脱敏 | 错误上报不含 phone/email/ID/JWT | error | 自定义脚本检查(C-10) |
| 依赖漏洞 | npm audit 0 high/critical | error | npm audit + Snyk |
| 小程序隐私 | 隐私接口声明完整 + 授权弹窗 | error | 代码审查(C-11) |
| 海外资源 | overseas H5 不加载国内 CDN | error | 自定义脚本检查(C-12) |

### 9.2 PII 脱敏检查脚本

```typescript
// tools/pii-check.ts
import { glob } from 'glob';
import { readFileSync } from 'fs';

const PII_PATTERNS = [
  /1[3-9]\d{9}/g,                    // 手机号
  /[\w.]+@[\w.]+\.\w+/g,            // 邮箱
  /\d{15}|\d{18}/g,                  // 身份证
  /eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+/g, // JWT
];

const ERROR_REPORT_DIRS = ['src/**/error-report*', 'src/**/sentry*', 'src/**/monitor*'];

async function checkPII() {
  const files = await glob(ERROR_REPORT_DIRS);
  let violations = 0;

  for (const file of files) {
    const content = readFileSync(file, 'utf-8');
    for (const pattern of PII_PATTERNS) {
      if (pattern.test(content)) {
        console.error(`❌ ${file}: 发现疑似 PII (${pattern})`);
        violations++;
      }
    }
  }

  if (violations > 0) {
    console.error(`\n共发现 ${violations} 处 PII 违规(C-10)`);
    process.exit(1);
  }
  console.log('✅ PII 检查通过');
}

checkPII();
```

---

## 10. CI 自动化流水线

### 10.1 GitHub Actions 流水线

```yaml
# .github/workflows/frontend-ci.yml
name: Frontend CI

on:
  pull_request:
    paths: ['frontend/**']
  push:
    branches: [main]
    paths: ['frontend/**']

jobs:
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 11 }
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: pnpm }
      - run: pnpm install --frozen-lockfile

      - name: Lint
        run: pnpm lint

      - name: Type Check
        run: pnpm typecheck

      - name: Unit Tests
        run: pnpm test --coverage

      - name: Upload Coverage
        uses: codecov/codecov-action@v4

  contract:
    runs-on: ubuntu-latest
    needs: quality
    steps:
      - uses: actions/checkout@v4
      - run: pnpm install --frozen-lockfile

      - name: Generate API Client
        run: pnpm gen:api

      - name: Check Generated Files Committed
        run: git diff --exit-code packages/api-client/src/generated/

      - name: OAS Contract Check
        run: bash tools/check-contract.sh
        env:
          BFF_URL: http://localhost:8080

  build:
    runs-on: ubuntu-latest
    needs: quality
    steps:
      - uses: actions/checkout@v4
      - run: pnpm install --frozen-lockfile

      - name: Build All Apps
        run: pnpm build

      - name: Bundle Size Check
        run: node tools/bundle-size-check.js

      - name: PII Check (C-10)
        run: node tools/pii-check.js

  e2e:
    runs-on: ubuntu-latest
    needs: build
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v4
      - run: pnpm install --frozen-lockfile

      - name: Start BFF Mock
        run: docker run -d -p 8080:8080 ep/bff-mock:latest

      - name: Build H5
        run: pnpm build --filter=student-h5

      - name: Playwright E2E
        run: npx playwright test --project=h5

      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-report
          path: playwright-report/
```

### 10.2 PR 合并门禁

| 门禁 | 类型 | 阻断条件 |
|------|------|----------|
| Lint | error | 任何 error |
| TypeCheck | error | 任何 error |
| 单元测试 | error | 任何 fail 或覆盖率 <80%(packages)/<60%(apps) |
| 契约一致性 | error | OAS diff 或 generated 未提交 |
| Build | error | 任何 build fail |
| Bundle Size | error | 超限 |
| PII 检查 | error | 发现 PII |
| E2E (H5) | error | 关键旅程 fail |

---

## 11. 测试用例追溯矩阵

| 需求 ID | 测试用例 | 层级 | 工具 | 覆盖 |
|---------|----------|------|------|------|
| FR-MONO-01 | workspace 配置解析 | 单元 | Vitest | ✅ |
| FR-MONO-02 | Turborepo 任务依赖 | 集成 | Vitest | ✅ |
| FR-MONO-03 | 跨包类型引用 | 类型 | tsc | ✅ |
| FR-API-01 | OAS → typescript-fetch 生成 | 契约 | openapi-generator | ✅ |
| FR-API-02 | Fetcher 适配 H5 fetch | 单元 | Vitest | ✅ |
| FR-API-02 | Fetcher 适配 Taro.request | 单元 | Vitest + Taro mock | ✅ |
| FR-API-03 | JWT Bearer 注入 | 单元 | Vitest | ✅ |
| FR-API-04 | X-Cell-Routing 注入 | 单元 | Vitest | ✅ |
| FR-API-05 | 401 自动刷新 | 集成 | Vitest + MSW | ✅ |
| FR-API-06 | 并发刷新去重 | 单元 | Vitest | ✅ |
| FR-API-07 | Zod 开发全量校验 | 单元 | Vitest | ✅ |
| FR-API-08 | Zod 生产采样 | 单元 | Vitest | ✅ |
| FR-QUERY-01 | QueryClient 配置 | 单元 | Vitest | ✅ |
| FR-QUERY-02 | queryKey 规范化 | 单元 | Vitest | ✅ |
| FR-QUERY-03 | staleTime/gcTime | 集成 | Vitest | ✅ |
| FR-QUERY-04 | 乐观更新 | 集成 | Vitest | ✅ |
| FR-QUERY-05 | 4xx 不重试 | 单元 | Vitest | ✅ |
| FR-QUERY-06 | DevTools 仅 dev | 组件 | Vitest | ✅ |
| FR-UI-01 | Button 变体 | 组件 | Vitest + RTL | ✅ |
| FR-UI-02 | 跨端 VideoPlayer | 组件 | Vitest + Taro mock | ✅ |
| FR-UI-03 | 主题切换 | 组件 | Vitest | ✅ |
| FR-UI-04 | 暗黑模式 | 组件 | Vitest | ✅ |
| FR-ADMIN-01 | ProLayout 渲染 | E2E | Playwright | ✅ |
| FR-ADMIN-02 | 路由权限守卫 | E2E | Playwright | ✅ |
| FR-ADMIN-03 | 课程 CRUD | E2E | Playwright | ✅ |
| FR-H5-01 | 首页课程列表 | E2E | Playwright | ✅ |
| FR-H5-02 | 课程详情 | E2E | Playwright | ✅ |
| FR-H5-03 | 视频学习 | E2E | Playwright | ✅ |
| FR-MP-01 | wx.login | E2E | miniprogram-automator | ✅ |
| FR-MP-02 | 分包加载 | E2E | miniprogram-automator | ✅ |
| FR-MP-03 | 隐私授权 | 手动 | 人工 | ✅ |
| FR-CI-01 | 契约 CI 校验 | CI | check-contract.sh | ✅ |
| FR-CI-02 | Bundle 体积门禁 | CI | bundle-size-check | ✅ |
| FR-CI-03 | PII 检查门禁 | CI | pii-check.ts | ✅ |
| NFR-01 | FCP <1.5s | 性能 | Lighthouse CI | ✅ |
| NFR-02 | LCP <2.5s | 性能 | Lighthouse CI | ✅ |
| NFR-03 | CLS <0.1 | 性能 | Lighthouse CI | ✅ |
| NFR-04 | H5 JS <300KB | 性能 | bundle-analyzer | ✅ |
| NFR-05 | 主包 <2MB | 性能 | 微信工具 | ✅ |
| C-09 | Turborepo 自托管 | 安全 | 配置审查 | ✅ |
| C-10 | 错误上报无 PII | 安全 | pii-check | ✅ |
| C-11 | 小程序隐私声明 | 安全 | 代码审查 | ✅ |
| C-12 | 海外无国内 CDN | 安全 | 资源检查脚本 | ✅ |

---

> **文档状态**：Approved v1.0
> **下一步**：SP-06 部署文档 → 三端原型 HTML
