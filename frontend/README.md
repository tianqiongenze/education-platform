# EP Frontend — 多端 Monorepo

> 教育平台前端骨架：pnpm workspace + Turborepo + Taro 4 + React + Ant Design 5

## 技术栈（D-031）

| 层 | 技术 | 版本 |
|----|------|------|
| 包管理 | pnpm | 11.0.0 (exact) |
| 构建编排 | Turborepo | 2.5.0 (exact) |
| 类型 | TypeScript | 5.8.3 (exact, strict) |
| Admin | Vite + React 19 + Ant Design 5 | latest |
| H5/小程序 | Taro 4 | 4.x |
| 数据层 | TanStack Query v5 | 5.x |
| 校验 | Zod | 3.x |

## 快速开始

```bash
# 安装依赖
pnpm install

# 开发
pnpm dev:admin    # admin-web → localhost:3000
pnpm dev:h5       # student-h5 → localhost:3001
pnpm dev:mp       # student-mp → 微信开发者工具

# 构建
pnpm build

# 代码检查
pnpm lint
pnpm typecheck
pnpm test

# 生成 API 客户端（从 BFF OpenAPI）
pnpm gen:api
```

## 目录结构

```
frontend/
├── apps/
│   ├── admin-web/       # 管理后台 (Vite + React + AntD5)
│   ├── student-h5/      # 学生 H5 (Taro 4)
│   └── student-mp/      # 学生小程序 (Taro 4)
├── packages/
│   ├── shared/          # 共享类型、常量、工具
│   ├── api-client/      # API 客户端 (openapi-generator + fetcher)
│   ├── query/           # TanStack Query hooks/mutations
│   └── ui-kit/          # 共享 UI 组件 (三端适配)
├── pnpm-workspace.yaml
├── turbo.json
├── tsconfig.base.json
└── package.json
```

## 铁律约束

- **A-15**: 所有依赖精确锁定（`save-exact=true`），禁止 `^` / `~` 前缀
- **C-09**: Turborepo 远程缓存必须自建（禁止 Vercel 云缓存）
- **C-10**: 前端错误上报不得包含 PII（phone/email/id_card 等）
- **C-11**: 小程序隐私协议须声明所有隐私接口
- **C-12**: 海外 H5 不得加载国内 CDN 资源
