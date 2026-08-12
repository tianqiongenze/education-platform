| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-06 | v1.0 | 2026-08-05 | 已批准 | DevOps 组 | 架构委员会 |

# ADR-INF-06: CI/CD 平台选型 — GitHub Actions vs GitLab CI vs Jenkins

> 关联：D-048 审计 P2-1、铁律 8（决策对比分析）、D-035（K8s 部署运维铁律）、D-040（GraalVM native 构建门禁）。

## 1. 问题陈述与约束条件

教育平台需统一的 CI/CD 平台承担：

- **静态扫描门禁**（铁律 8）：CI 钩子检测 `ConcurrentHashMap` 当 DB / `hashCode()` 密码 / 手写 JSON-RPC / 声明但未用的依赖。
- **多区域部署**（D-041）：杭州/深圳/北京/新加坡四 cell 滚动发布。
- **GraalVM native 构建**（D-040）：双模式（JIT/Native）构建冒烟。
- **多语言构建**：Java 21（Maven）、Python 3.12（uv/poetry）、Go（gateway-go）、TypeScript（pnpm/Turbo）。
- **缓存**：Maven/pnpm/uv 依赖缓存。
- **K8s 集成**：D-035 的 K8s 部署 YAML 校验 + kustomize 渲染。

约束条件：

1. **铁律 8 静态扫描钩子集成**：必须能在 PR 阶段跑自定义静态扫描脚本。
2. **多区域部署**：cell 级滚动 + 回滚。
3. **缓存**：依赖缓存降低构建时间。
4. **成本**：开源/自托管优先。
5. **与 K8s 集成**：部署到 K8s + kustomize/helm。
6. **铁律 8**：三方对比后定论。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **GitHub Actions**（已用） | 与 GitHub 仓库原生集成，自托管 runner 支持 K8s |
| B | **GitLab CI** | 一体化（仓库 + CI + CD + Registry），`.gitlab-ci.yml` |
| C | **Jenkins** | 最成熟，插件最多，自托管 |

## 3. 对比维度表

| 维度 | GitHub Actions（A） | GitLab CI（B） | Jenkins（C） |
|------|----------------------|-----------------|---------------|
| **成熟度** | 2019 GA，迭代快 | 2015 GA，一体化成熟 | 2011 GA，最成熟 |
| **社区活跃度** | 20k★+ actions marketplace，生态最广 | GitLab 7w★+，内置生态 | 23k★+，1500+ 插件 |
| **与 GitHub 仓库集成** | 原生最佳（PR/Issue/Release 联动） | 需迁移仓库到 GitLab | 需 webhook 配置 |
| **铁律 8 静态扫描钩子** | PR `on: pull_request` 触发自定义 action，fail-fast | `.gitlab-ci.yml` 阶段化，原生 | Pipeline script，原生 |
| **多区域部署** | self-hosted runner per cell + matrix strategy | runners per cell + parallel | agents per cell + pipeline |
| **缓存** | `actions/cache` 内置 | `cache:` 关键字内置 | Job Cacher 插件 |
| **成本** | 公共仓库免费；私有仓库 2000 分钟/月免费，超出按分钟计费；self-hosted runner 免费 | self-hosted runner 免费；SaaS 按席位 | 开源免费；自托管资源成本 |
| **与 K8s 集成** | `actions-runner-controller`（ARC）self-hosted runner on K8s | K8s executor 原生 | K8s plugin |
| **多语言构建** | actions marketplace 覆盖全 | image 模板覆盖全 | 插件覆盖全 |
| **GraalVM native 构建** | `graalvm/setup-graalvm` action 一键 | 需自配镜像 | 需自配 |
| **UI/可观测性** | 简洁，PR 内联 | 一体化，详细 | 经典，插件丰富 |
| **运维复杂度** | SaaS + 可选 self-hosted runner | SaaS 或自托管 GitLab | 自托管 Jenkins + agents |
| **多租户隔离** | organization/registry 隔离 | group/project 隔离 | folder/role 隔离 |

## 4. 结论与理由

**采纳方案 A：GitHub Actions + self-hosted runner（K8s actions-runner-controller）**（与项目现状一致，记录对比理由）。

理由：

1. **与 GitHub 仓库原生集成**：项目已用 Git + GitHub，PR/Issue/Release 联动是 GHA 独有优势；GitLab CI 需迁移仓库（成本高、铁律 8 反对无谓迁移）；Jenkins 需额外 webhook 维护。
2. **铁律 8 静态扫描钩子**：`on: pull_request` 触发自定义 action，PR 内联 fail-fast，最贴合铁律 8 的"PR 阶段拦截手写轮子"目标。
3. **GraalVM native 构建一键**：`graalvm/setup-graalvm` action 官方维护，D-040 native 构建门禁零成本接入；Jenkins/GitLab 需自配镜像。
4. **多区域部署**：`actions-runner-controller` 在 K8s 上 per-cell 部署 runner，matrix strategy 实现 cell 级滚动；D-041 四 cell 部署可直接编排。
5. **缓存内置**：`actions/cache` 内置 Maven/pnpm/uv 缓存，无需插件。
6. **self-hosted runner 免成本**：私有仓库超出免费分钟数后，self-hosted runner 完全免费，与 K8s 共用资源池。

**取舍**：放弃 GitLab CI 的一体化（仓库+CI+CD+Registry）与 Jenkins 的插件丰富度，换取与 GitHub 原生集成 + GraalVM action + K8s runner 的零摩擦。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| 私有仓库免费分钟数限制 | 关键流水线走 self-hosted runner（K8s ARC） |
| GHA 平台依赖（GitHub 故障） | 关键发布流水线备份 Jenkins job（降级方案） |
| ARC 运维 | K8s Helm 部署 + HPA 自动扩缩 runner |

## 6. 铁律 8 静态扫描 action 设计

PR 阶段 `.github/workflows/iron-law-8.yml` 扫描脚本（fail-fast）：

- `ConcurrentHashMap` / `HashMap` 字段出现在 `*ServiceImpl` → fail
- 密码字段调用 `hashCode()` → fail
- 手写 `JsonRpc` / `tools/call` / `initialize` 字符串 → fail
- `pom.xml` 声明 `sentinel`/`redisson`/`seata` 但源码无 `@SentinelResource`/`RLock`/`@GlobalTransactional` → fail

---

**文档结束**。本 ADR 补齐 D-048 审计 P2-1 的 CI/CD 平台选型对比缺失，记录 GitHub Actions 已选理由，满足铁律 8 三方对比要求。
