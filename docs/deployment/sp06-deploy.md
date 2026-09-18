# SP-06 多端前端骨架 —— 部署方案文档

> **D-200 Naming Note:** This document's filename retains the legacy code `sp06` for reference continuity. Per D-137, sp06 → portal-bff. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP06-DEPLOY-001 | v1.0 | 2026-08-04 | Approved | ZCode(DevOps架构师) | SP06-SAD-001 / SP06-SDD-001 / SP06-TEST-001 / D-031 |

> 本文是 SP-06 多端前端骨架的**部署基线**，定义三端构建产物、CDN 策略、灰度发布、回滚、Turborepo 远程缓存、环境管理。

---

## 目录

1. [部署架构总览](#1-部署架构总览)
2. [构建产物矩阵](#2-构建产物矩阵)
3. [环境管理](#3-环境管理)
4. [H5 部署(CDN + Nginx)](#4-h5-部署cdn--nginx)
5. [Admin Web 部署](#5-admin-web-部署)
6. [小程序部署](#6-小程序部署)
7. [Turborepo 远程缓存(自托管)](#7-turborepo-远程缓存自托管)
8. [灰度发布策略](#8-灰度发布策略)
9. [回滚策略](#9-回滚策略)
10. [监控与告警](#10-监控与告警)
11. [部署 CI/CD 流水线](#11-部署-cicd-流水线)

---

## 1. 部署架构总览

```
                        ┌──────────────────────────────────────┐
                        │           CI/CD (GitHub Actions)      │
                        │  pnpm build → 产物 → CDN/Nginx/微信   │
                        └──────────┬───────────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼               ▼
              ┌──────────┐  ┌──────────┐   ┌────────────┐
              │  H5 CDN  │  │ Admin CDN│   │ 微信小程序  │
              │(国内+海外)│  │(国内only) │   │  代码上传   │
              └────┬─────┘  └────┬─────┘   └─────┬──────┘
                   │              │               │
                   ▼              ▼               ▼
              ┌──────────┐  ┌──────────┐   ┌────────────┐
              │ Nginx SLB│  │ Nginx SLB│   │  微信平台    │
              │(H5 入口) │  │(Admin)   │   │  审核+发布   │
              └────┬─────┘  └──────────┘   └────────────┘
                   │
                   ▼
         ┌─────────────────┐
         │  BFF (Java)      │
         │  Spring Boot 4.0 │
         └─────────────────┘
```

### 1.1 部署区域

| 区域 | 环境 | H5 | Admin | 小程序 | CDN |
|------|------|-----|-------|--------|-----|
| 国内(华东1) | prod | ✅ | ✅ | ✅ | 阿里云 CDN |
| 国内(华南1) | prod-dr | ✅ | ✅ | - | 阿里云 CDN |
| 海外(新加坡) | prod-overseas | ✅ | - | - | Cloudflare(C-12) |

> **C-12 约束**：海外 H5 必须使用独立海外 CDN（Cloudflare），**不得**加载国内 CDN 资源，确保数据合规。

---

## 2. 构建产物矩阵

| 应用 | 构建命令 | 产物目录 | 产物类型 | 目标 |
|------|----------|----------|----------|------|
| admin-web | `pnpm build --filter=admin-web` | `apps/admin-web/dist/` | 静态文件(JS/CSS/HTML) | Nginx + CDN |
| student-h5 | `pnpm build --filter=student-h5` | `apps/student-h5/dist/` | 静态文件 | Nginx + CDN |
| student-mp | `pnpm build --filter=student-mp` | `apps/student-mp/dist/weapp/` | 微信小程序包 | 微信开发者工具上传 |
| packages/* | `pnpm build` | `packages/*/dist/` | ESM + d.ts | 内部消费(不独立部署) |

### 2.1 构建产物结构

```
# H5/Admin 产物
dist/
├── index.html
├── assets/
│   ├── index.[hash].js          # 主 chunk
│   ├── index.[hash].css
│   ├── vendor.[hash].js          # 第三方 chunk
│   ├── vendor.[hash].css
│   └── chunks/                   # 路由级懒加载 chunk
│       ├── CourseList.[hash].js
│       └── ...
└── static/                       # 图片等静态资源

# 小程序产物
dist/weapp/
├── app.js
├── app.json
├── app.wxss
├── pages/
│   ├── index/
│   │   ├── index.js
│   │   ├── index.json
│   │   ├── index.wxml
│   │   └── index.wxss
│   └── ...
├── subpackages/
│   ├── live/
│   ├── coding/
│   ├── quiz/
│   └── assignment/
└── static/
```

---

## 3. 环境管理

### 3.1 环境定义

| 环境 | 用途 | H5 URL | Admin URL | BFF URL | 分支 |
|------|------|--------|-----------|---------|------|
| dev | 本地开发 | localhost:3001 | localhost:3000 | localhost:8080 | feature/* |
| staging | 预发布 | h5-staging.ep.com | admin-staging.ep.com | bff-staging.ep.com | develop |
| prod-cn | 国内生产 | h5.ep.com | admin.ep.com | bff.ep.com | main |
| prod-overseas | 海外生产 | h5.global.ep.com | - | bff.global.ep.com | main |

### 3.2 环境变量注入

```typescript
// apps/student-h5/src/utils/env.ts
// 构建时注入，运行时不可篡改

export const APP_CONFIG = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  cdnBaseUrl: import.meta.env.VITE_CDN_BASE_URL,
  sentryDsn: import.meta.env.VITE_SENTRY_DSN,
  cellId: import.meta.env.VITE_CELL_ID,
  version: __APP_VERSION__,  // 构建时注入
  environment: import.meta.env.MODE,  // 'staging' | 'production'
} as const;
```

```bash
# .env.staging
VITE_API_BASE_URL=https://bff-staging.ep.com
VITE_CDN_BASE_URL=https://cdn-staging.ep.com
VITE_SENTRY_DSN=https://xxx@sentry-staging.ep.com/1
VITE_CELL_ID=cell-staging-01

# .env.production-cn
VITE_API_BASE_URL=https://bff.ep.com
VITE_CDN_BASE_URL=https://cdn.ep.com
VITE_SENTRY_DSN=https://xxx@sentry.ep.com/1
VITE_CELL_ID=cell-prod-cn-01

# .env.production-overseas
VITE_API_BASE_URL=https://bff.global.ep.com
VITE_CDN_BASE_URL=https://cdn.global.ep.com  # Cloudflare(C-12)
VITE_SENTRY_DSN=https://xxx@sentry.global.ep.com/1
VITE_CELL_ID=cell-prod-overseas-01
```

### 3.3 小程序环境切换

```typescript
// apps/student-mp/src/utils/env.ts
// 小程序无 import.meta.env，用构建时 defineConstants 注入

const ENVIRONMENTS = {
  development: {
    apiBaseUrl: 'http://localhost:8080',
    cdnBaseUrl: 'http://localhost:3001/static',
  },
  staging: {
    apiBaseUrl: 'https://bff-staging.ep.com',
    cdnBaseUrl: 'https://cdn-staging.ep.com',
  },
  production: {
    apiBaseUrl: 'https://bff.ep.com',
    cdnBaseUrl: 'https://cdn.ep.com',
  },
};

export const APP_CONFIG = ENVIRONMENTS[process.env.NODE_ENV as keyof typeof ENVIRONMENTS];
```

---

## 4. H5 部署(CDN + Nginx)

### 4.1 Nginx 配置

```nginx
# /etc/nginx/conf.d/student-h5.conf

server {
    listen 443 ssl http2;
    server_name h5.ep.com;

    ssl_certificate     /etc/nginx/ssl/ep.com.crt;
    ssl_certificate_key /etc/nginx/ssl/ep.com.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # 安全 headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # CSP 策略
    add_header Content-Security-Policy "
        default-src 'self';
        script-src 'self' 'unsafe-inline' https://cdn.ep.com;
        style-src 'self' 'unsafe-inline' https://cdn.ep.com;
        img-src 'self' data: https: https://cdn.ep.com;
        media-src 'self' https://cdn.ep.com;
        connect-src 'self' https://bff.ep.com https://sentry.ep.com;
        font-src 'self' data: https://cdn.ep.com;
        frame-ancestors 'self';
    " always;

    # 静态资源 → CDN(带 hash 的资源永久缓存)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        proxy_pass https://cdn.ep.com;
        expires 1y;
        add_header Cache-Control "public, immutable";
        access_log off;
    }

    # SPA fallback
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;

        # index.html 不缓存(保证发布即时生效)
        location = /index.html {
            add_header Cache-Control "no-cache, no-store, must-revalidate";
            expires 0;
        }
    }

    # 健康检查
    location /health {
        return 200 'ok';
        add_header Content-Type text/plain;
    }
}

# HTTP → HTTPS 重定向
server {
    listen 80;
    server_name h5.ep.com;
    return 301 https://$host$request_uri;
}
```

### 4.2 CDN 配置

| 配置项 | 值 | 理由 |
|--------|-----|------|
| 缓存策略 | hash 文件永久缓存(1年) | 文件名含 hash，内容变 → hash 变 → 自动失效 |
| index.html | no-cache | 保证发布后即时生效 |
| 压缩 | Brotli + Gzip | 减小传输体积 |
| HTTPS | 强制 HTTPS + HSTS | 安全 |
| 海外节点 | Cloudflare 独立(C-12) | 数据合规，不回国 |

---

## 5. Admin Web 部署

### 5.1 Admin Nginx 配置

```nginx
# /etc/nginx/conf.d/admin-web.conf

server {
    listen 443 ssl http2;
    server_name admin.ep.com;

    ssl_certificate     /etc/nginx/ssl/admin.ep.com.crt;
    ssl_certificate_key /etc/nginx/ssl/admin.ep.com.key;

    # Admin 额外安全: IP 白名单
    allow 10.0.0.0/8;        # 内网
    allow 203.0.113.0/24;    # 办公网络
    deny all;

    add_header X-Frame-Options "DENY" always;  # Admin 禁止嵌入
    add_header X-Content-Type-Options "nosniff" always;

    # CSP 更严格: 不允许外部资源
    add_header Content-Security-Policy "
        default-src 'self';
        script-src 'self';
        style-src 'self' 'unsafe-inline';
        img-src 'self' data:;
        connect-src 'self' https://bff.ep.com;
        frame-ancestors 'none';
    " always;

    location / {
        root /usr/share/nginx/html/admin;
        try_files $uri $uri/ /index.html;
    }
}
```

### 5.2 Admin 部署特点

| 特性 | H5 | Admin |
|------|-----|-------|
| IP 白名单 | ❌ | ✅ |
| X-Frame-Options | SAMEORIGIN | DENY |
| CSP 外部资源 | 允许 CDN | 仅 self |
| CDN | ✅ | ❌(仅内网) |
| 多区域 | 国内+海外 | 仅国内 |

---

## 6. 小程序部署

### 6.1 发布流程

```
开发者           微信开发者工具        微信平台           用户
  │                  │                   │                 │
  │─构建小程序──────►│                   │                 │
  │  pnpm build:mp   │                   │                 │
  │                  │                   │                 │
  │─打开工具────────►│                   │                 │
  │                  │                   │                 │
  │─上传代码────────►│──ci上传──────────►│                 │
  │                  │                   │                 │
  │                  │                   │─提交审核        │
  │                  │                   │                 │
  │                  │                   │─审核通过        │
  │                  │                   │                 │
  │─发布────────────►│──────────────────►│──发布版本────────►│
  │                  │                   │                 │
  │                  │                   │                 │◄─用户更新
```

### 6.2 小程序 CI 上传

```yaml
# .github/workflows/mp-upload.yml
name: Mini Program Upload

on:
  push:
    branches: [main]
    paths: ['frontend/apps/student-mp/**']

jobs:
  upload:
    runs-on: macos-latest  # 需要 macOS 运行微信开发者工具 CLI
    steps:
      - uses: actions/checkout@v4
      - uses: pnpm/action-setup@v4
        with: { version: 11 }
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: pnpm }

      - run: pnpm install --frozen-lockfile
      - run: pnpm build --filter=student-mp

      - name: Upload to WeChat
        run: |
          /Applications/wechatwebdevtools.app/Contents/MacOS/cli upload \
            --project apps/student-mp/dist/weapp \
            --version "v$(date +%Y%m%d%H%M)" \
            --desc "CI auto upload $(git rev-parse --short HEAD)" \
            --robot 1
        env:
          WECHAT_PRIVATE_KEY: ${{ secrets.WECHAT_PRIVATE_KEY }}
```

### 6.3 小程序灰度发布

```yaml
# 微信平台灰度配置(通过 miniprogram-ci API)
version: "1.0.0"
release:
  strategy: gradual
  steps:
    - percentage: 10      # 先 10% 用户
      duration: 24h       # 观察 24h
    - percentage: 50      # 扩大到 50%
      duration: 24h
    - percentage: 100     # 全量
```

### 6.4 小程序版本管理

| 版本类型 | 规则 | 示例 |
|----------|------|------|
| 正式版 | `x.y.z` | 1.0.0 |
| 体验版 | `x.y.z-beta.n` | 1.0.0-beta.1 |
| 开发版 | `x.y.z-dev.{hash}` | 1.0.0-dev.a1b2c3d |
| 小程序版本号 | `v{yyyyMMddHHmm}` | v202608041500 |

---

## 7. Turborepo 远程缓存(自托管)

### 7.1 部署架构

> **C-09 约束**：Turborepo 远程缓存必须自托管，**禁止**使用 Vercel 云缓存（数据合规）。

```
开发者            Turborepo CLI        自托管缓存服务
  │                  │                      │
  │─turbo build─────►│                      │
  │                  │─检查本地缓存          │
  │                  │  [miss]              │
  │                  │─请求远程缓存─────────►│
  │                  │  [miss]              │
  │                  │─执行 build            │
  │                  │─上传产物到远程────────►│
  │                  │  [store]              │
  │                  │◄─done                 │
  │◄─build complete──│                      │
  │                  │                      │
  │  // 下次相同输入 │                      │
  │─turbo build─────►│                      │
  │                  │─检查本地缓存          │
  │                  │  [miss]              │
  │                  │─请求远程缓存─────────►│
  │                  │  [HIT!]              │
  │                  │◄─cached artifacts─────│
  │                  │─恢复产物              │
  │◄─build SKIPPED───│                      │
```

### 7.2 自托管缓存部署(docker)

```yaml
# docker-compose.turbocache.yml
version: '3.8'

services:
  turborepo-remote-cache:
    image: fox1t/turborepo-remote-cache:latest
    ports:
      - "3002:3000"
    environment:
      PORT: 3000
      STORAGE_TYPE: s3
      S3_ENDPOINT: http://minio:9000
      S3_BUCKET: turborepo-cache
      S3_ACCESS_KEY: ${S3_ACCESS_KEY}
      S3_SECRET_KEY: ${S3_SECRET_KEY}
      # 认证(仅允许 CI + 开发者)
      TURBO_TOKEN: ${TURBO_TOKEN}
    depends_on:
      - minio

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    command: server /data --console-address ":9001"
    volumes:
      - minio-data:/data

volumes:
  minio-data:
```

### 7.3 Turborepo 配置

```json
// turbo.json
{
  "$schema": "https://turbo.build/schema.json",
  "globalDependencies": ["**/.env.*local"],
  "globalEnv": ["NODE_ENV", "CI"],
  "remoteCache": {
    "enabled": true,
    "signature": true
  },
  "tasks": {
    "build": {
      "dependsOn": ["^build"],
      "outputs": ["dist/**", ".next/**", "build/**"],
      "env": ["VITE_API_BASE_URL", "VITE_CDN_BASE_URL", "VITE_SENTRY_DSN"]
    },
    "dev": {
      "cache": false,
      "persistent": true
    },
    "lint": {
      "outputs": []
    },
    "typecheck": {
      "outputs": [],
      "dependsOn": ["^build"]
    },
    "test": {
      "outputs": ["coverage/**"],
      "dependsOn": []
    },
    "gen:api": {
      "outputs": ["src/generated/**"],
      "cache": false
    }
  }
}
```

```bash
# .env.turborepo (开发者本地)
TURBO_API=https://turbocache.ep.com
TURBO_TOKEN=ci-or-dev-token
TURBO_TEAM=education-platform
```

---

## 8. 灰度发布策略

### 8.1 H5/Admin 灰度(Nginx weight split)

```nginx
# /etc/nginx/conf.d/h5-canary.conf

# 蓝绿: 两个 upstream，按权重分流
upstream h5_blue {
    server 10.0.1.10:80 weight=100;  # 当前稳定版
}

upstream h5_green {
    server 10.0.1.20:80 weight=0;    # 新版本(初始 0%)
}

# 灰度: 基于 cookie 或 header 路由
map $cookie_ep_canary $upstream_pool {
    default   h5_blue;
    "true"    h5_green;
}

server {
    listen 443 ssl;
    server_name h5.ep.com;

    location / {
        proxy_pass http://$upstream_pool;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 8.2 灰度发布步骤

```
Step 1: 部署新版本到 green 环境(0%流量)
         │
         ▼
Step 2: 内部测试 cookie 灰度(手动设置 ep_canary=true)
         │  验证 30min 无异常
         ▼
Step 3: 调整 weight: blue=90, green=10
         │  监控错误率/性能 1h
         ▼
Step 4: 调整 weight: blue=50, green=50
         │  监控 2h
         ▼
Step 5: 全量: blue=0, green=100
         │  green 成为新 blue
         ▼
Step 6: 保留旧 blue 24h 作为回滚备份
         │  无异常后下线
```

### 8.3 灰度监控指标

| 指标 | 阈值 | 动作 |
|------|------|------|
| JS 错误率 | >0.5% | 自动回滚 |
| API 5xx 率 | >1% | 自动回滚 |
| LCP | >3s | 告警 + 人工评估 |
| 白屏率 | >0.1% | 自动回滚 |
| 内存泄漏 | 堆内存持续增长 | 告警 |

---

## 9. 回滚策略

### 9.1 H5/Admin 回滚

```bash
#!/bin/bash
# tools/rollback-h5.sh
# 用法: ./rollback-h5.sh <version>

set -euo pipefail

VERSION=$1
DEPLOY_DIR=/usr/share/nginx/html
BACKUP_DIR=/opt/backups/h5

echo "🔄 回滚 H5 到版本 $VERSION..."

# 1. 检查备份是否存在
if [ ! -d "$BACKUP_DIR/$VERSION" ]; then
  echo "❌ 备份版本 $VERSION 不存在"
  exit 1
fi

# 2. 当前版本备份(以防需要再次回滚)
CURRENT=$(readlink "$DEPLOY_DIR/h5" | xargs basename)
echo "当前版本: $CURRENT"

# 3. 切换软链接(原子操作)
ln -sfn "$BACKUP_DIR/$VERSION" "$DEPLOY_DIR/h5-new"
mv -Tf "$DEPLOY_DIR/h5-new" "$DEPLOY_DIR/h5"

# 4. Nginx reload(无需重启)
nginx -s reload

echo "✅ 回滚完成: $CURRENT → $VERSION"

# 5. 验证
sleep 2
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://h5.ep.com/health)
if [ "$HTTP_CODE" = "200" ]; then
  echo "✅ 健康检查通过"
else
  echo "❌ 健康检查失败(HTTP $HTTP_CODE)，请检查"
  exit 1
fi
```

### 9.2 小程序回滚

| 场景 | 方法 | 耗时 |
|------|------|------|
| 审核前撤回 | 微信开发者工具撤回上传 | 即时 |
| 发布后回退 | 微信公众平台「版本回退」功能 | <5min |
| 紧急下线 | 微信公众平台「暂停服务」 | 即时 |
| 灰度回退 | 调整灰度比例到 0% | 即时 |

### 9.3 回滚决策矩阵

| 问题严重度 | 影响范围 | 决策 | 耗时 |
|------------|----------|------|------|
| P0(白屏/无法登录) | >50% 用户 | 立即全量回滚 | <2min |
| P1(核心功能不可用) | >10% 用户 | 立即回滚到上一稳定版 | <5min |
| P2(部分功能异常) | <10% 用户 | 评估后决定回滚或热修 | <30min |
| P3(体验问题) | 个别用户 | 热修，不回滚 | 下个版本 |

---

## 10. 监控与告警

### 10.1 前端监控架构

```
浏览器/小程序
     │
     ├─ Sentry SDK (JS 错误 + 性能)
     ├─ 自定义埋点 (业务指标)
     └─ Web Vitals (FCP/LCP/CLS)
          │
          ▼
    Sentry Self-Hosted
     │           │
     ▼           ▼
  告警 webhook   Grafana Dashboard
  (钉钉/飞书)    (前端 SLO 看板)
```

### 10.2 Sentry 配置

```typescript
// packages/shared/src/monitor/sentry.ts
import * as Sentry from '@sentry/react';

export function initSentry() {
  Sentry.init({
    dsn: APP_CONFIG.sentryDsn,
    environment: APP_CONFIG.environment,
    release: APP_CONFIG.version,
    tracesSampleRate: 0.1,  // 10% 性能采样
    replaysSessionSampleRate: 0.01,  // 1% 会话回放
    replaysOnErrorSampleRate: 1.0,   // 错误 100% 回放

    // C-10: PII 脱敏
    beforeSend(event) {
      return sanitizePII(event);
    },

    // 忽略已知噪声
    ignoreErrors: [
      'ResizeObserver loop limit exceeded',
      'Non-Error promise rejection captured',
    ],
  });
}

// C-10 PII 脱敏
function sanitizePII(event: Sentry.Event): Sentry.Event {
  const PII_FIELDS = ['phone', 'email', 'idCard', 'password', 'token'];
  return JSON.parse(
    JSON.stringify(event, (key, value) => {
      if (PII_FIELDS.some(f => key.toLowerCase().includes(f))) {
        return '[REDACTED]';
      }
      // 手机号正则
      if (typeof value === 'string' && /^1[3-9]\d{9}$/.test(value)) {
        return '[PHONE_REDACTED]';
      }
      // JWT 正则
      if (typeof value === 'string' && /^eyJ[a-zA-Z0-9]/.test(value)) {
        return '[JWT_REDACTED]';
      }
      return value;
    })
  );
}
```

### 10.3 告警规则

| 告警 | 条件 | 渠道 | 优先级 |
|------|------|------|--------|
| JS 错误率激增 | 5min 内 >1% | 钉钉 + 电话 | P0 |
| 白屏率 | 5min 内 >0.1% | 钉钉 + 电话 | P0 |
| API 5xx 率 | 5min 内 >2% | 钉钉 | P1 |
| LCP 恶化 | 15min 均值 >4s | 钉钉 | P2 |
| Bundle 体积超限 | CI 阶段 | 阻断 PR | P0 |

---

## 11. 部署 CI/CD 流水线

### 11.1 完整流水线

```yaml
# .github/workflows/frontend-deploy.yml
name: Frontend Deploy

on:
  push:
    branches: [main]
    paths: ['frontend/**']

jobs:
  # ─── Stage 1: 质量门禁 ───
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: pnpm install --frozen-lockfile
      - run: pnpm lint && pnpm typecheck
      - run: pnpm test --coverage
      - run: node tools/pii-check.js  # C-10
      - run: bash tools/check-contract.sh  # 契约

  # ─── Stage 2: 构建 ───
  build:
    needs: quality
    strategy:
      matrix:
        app: [admin-web, student-h5]
        env: [staging, production-cn, production-overseas]
        exclude:
          - app: admin-web
            env: production-overseas  # Admin 无海外
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: pnpm install --frozen-lockfile

      - name: Build
        run: pnpm build --filter=${{ matrix.app }}
        env:
          NODE_ENV: production
          VITE_ENV: ${{ matrix.env }}

      - name: Bundle Size Check
        run: node tools/bundle-size-check.js ${{ matrix.app }}

      - name: Upload Artifact
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.app }}-${{ matrix.env }}
          path: apps/${{ matrix.app }}/dist/

  # ─── Stage 3: 部署 staging ───
  deploy-staging:
    needs: build
    if: github.ref == 'refs/heads/develop'
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/download-artifact@v4
        with: { name: 'student-h5-staging', path: dist/ }

      - name: Deploy to CDN
        run: |
          # 上传到阿里云 OSS → CDN 自动刷新
          aliyun oss cp -r dist/ oss://ep-h5-staging/ --force
          aliyun oss cdn refresh --path "https://h5-staging.ep.com/*"

      - name: Smoke Test
        run: |
          sleep 10
          HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://h5-staging.ep.com)
          [ "$HTTP_CODE" = "200" ] || exit 1

  # ─── Stage 4: 部署 production (手动审批) ───
  deploy-production:
    needs: deploy-staging
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    environment:
      name: production
      url: https://h5.ep.com
    steps:
      - uses: actions/download-artifact@v4
        with: { name: 'student-h5-production-cn', path: dist/ }

      - name: Backup current version
        run: |
          VERSION=$(date +%Y%m%d%H%M)
          tar czf /opt/backups/h5/$VERSION.tar.gz /usr/share/nginx/html/h5
          echo "BACKUP_VERSION=$VERSION" >> $GITHUB_ENV

      - name: Deploy to Nginx
        run: |
          scp -r dist/* nginx-server:/usr/share/nginx/html/h5-new/
          ssh nginx-server "ln -sfn /usr/share/nginx/html/h5-new /usr/share/nginx/html/h5 && nginx -s reload"

      - name: Health Check
        run: |
          for i in {1..10}; do
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://h5.ep.com/health)
            [ "$HTTP_CODE" = "200" ] && break
            sleep 3
          done
          [ "$HTTP_CODE" = "200" ] || (echo "❌ 健康检查失败，执行回滚" && ./tools/rollback-h5.sh $BACKUP_VERSION && exit 1)

      - name: Notify Success
        run: |
          curl -X POST $WEBHOOK_URL -H 'Content-Type: application/json' \
            -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"✅ H5 部署成功: ${{ github.sha }}\"}}"
```

### 11.2 部署频率

| 环境 | 触发 | 频率 | 审批 |
|------|------|------|------|
| dev | 本地 | 随时 | 无 |
| staging | push to develop | 每次提交 | 自动 |
| production | push to main | 每日/按需 | 手动审批 |

---

## 12. 合规检查清单

| 约束 | 检查点 | CI 门禁 | 工具 |
|------|--------|---------|------|
| C-09 | Turborepo 缓存自托管(非 Vercel) | ✅ | 配置审查: TURBO_API 指向自托管 |
| C-10 | 错误上报无 PII | ✅ | pii-check.js + Sentry beforeSend |
| C-11 | 小程序隐私接口声明 | ✅ | app.config.ts 审查 |
| C-12 | 海外 H5 独立 CDN | ✅ | .env.production-overseas 检查 |
| 数据合规 | 海外数据不回国 | ✅ | CSP connect-src 检查 |
| HTTPS | 全站 HTTPS | ✅ | Nginx 配置检查 |
| CSP | 严格 CSP 策略 | ✅ | Nginx header 检查 |

---

> **文档状态**：Approved v1.0
> **下一步**：三端原型 HTML（admin-web + student-h5 + student-mp）
