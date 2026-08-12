# D-116: CI/CD 部署管线 + CI 覆盖率扩展

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-116 | v1.0 | 2026-08-09 | 已批准 | DevOps 组 | 架构委员会 |

> 关联：铁律 8（≥3 方案对比）、ADR-INF-06（CI/CD 平台选型）、D-114（GitOps with ArgoCD）、D-115（镜像构建管线 JIB+Dockerfile）、D-035（K8s 部署运维铁律）。

## 1. 问题陈述与约束条件

教育平台已有 10 个 GitHub Actions 工作流，但全部是测试 / 安全扫描类，**无任何部署管线**。代码合并到 main 后，镜像构建、镜像推送、K8s 清单更新、ArgoCD 触发同步——全部靠人工。同时，Java 测试 CI 矩阵仅覆盖 5 个服务（且含错误路径 `bff-java` 应为 `services/bff-java`），Go 测试完全缺失于 CI，Python 测试未覆盖 `ep-python-common`。

具体缺口：

1. **无部署管线**：D-114 定义了 ArgoCD 自动同步、D-115 定义了镜像构建方式，但二者之间没有自动衔接——没有"代码合并→构建镜像→更新清单→触发同步"的闭环。
2. **Java CI 覆盖不足**：原 `java-test.yml` 矩阵仅 5 项（sp03-business、sp05-itbackend、`bff-java`（错误路径）、sp07-retrieval-service、integration-tests），遗漏 sp02-data、sp03-order-service、sp03-payment-service、ep-memory-java、ep-web-common、ep-api、ep-cache、ep-disruptor、ep-rw-java、sp07-agent-service、sp07-tenant-service 共 11 个服务。
3. **Go CI 完全缺失**：gateway-go 与 ep-go-common 的 `go test` 从未在 CI 运行。
4. **Python CI 不完整**：ep-python-common（D-110 共享库）无 CI。
5. **无覆盖率门禁聚合**：各服务 JaCoCo 报告散落，无聚合阈值校验。

约束条件：

1. **匹配现有工作流风格**：actions/checkout@v4、setup-java@v4、setup-go@v5、upload-artifact@v4 等版本与命名约定。
2. **secrets 不可硬编码**：Harbor 凭据等通过 `secrets.*` 引用。
3. **build-and-push 仅 main 分支触发**：PR 不推镜像。
4. **deploy 需环境审批门禁**：production 环境需人工 approve。
5. **铁律 8**：≥3 方案对比后定论。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **GitHub Actions**（已选） | 仓库内原生 CI/CD，workflow 文件即代码，与现有 10 个 workflow 同构 |
| B | **Jenkins** | 独立 CI/CD 服务器，Jenkinsfile 声明式管线，插件生态成熟 |
| C | **Tekton** | K8s 原生 CI/CD，Pipeline/Task CRD，面向云原生 |

## 3. 对比维度表

| 维度 | A. GitHub Actions（已选） | B. Jenkins | C. Tekton |
|------|--------------------------|------------|-----------|
| **与现有 workflow 一致性** | 强——10 个已有 workflow 全部为 GHA，零迁移 | 弱——需新建 Jenkinsfile 并维护两套 CI 体系 | 弱——需迁移至 CRD，与现有 GHA 异构 |
| **运维成本** | 低——SaaS 托管，无控制器 | 高——需自建/维护 Jenkins controller + agent | 中——需维护 Tekton controller + pipeline |
| **K8s 集成 / GitOps 衔接** | 强——`workflow_run` 触发 deploy，原生 push 清单 | 中——需 webhook / Jenkinsfile git push 步骤 | 强——Task 即 K8s Job，但与 GHA 体系割裂 |
| **矩阵并行** | 强——matrix 原生支持 16+ 服务并行 | 中——需 `parallel` 或 matrix 插件 | 中——需为每个服务生成 TaskRun |
| **缓存与 artifact** | 强——setup-java/go cache、upload-artifact 原生 | 中——需插件配置 | 弱——需 Task 间 workspace / PVC |
| **审批门禁** | 强——environment + required reviewers 原生 | 中——需 Input step + agent 在线 | 弱——需 ArgoCD approval 或外部 |
| **学习成本 / 团队熟悉度** | 低——团队已用 GHA 一年 | 中——Jenkinsfile DSL 学习成本 | 高——Tekton CRD 概念门槛 |
| **与 ADR-INF-06 一致性** | 一致——INF-06 已定 GHA 为 CI/CD 平台 | 不一致——推翻既有决策 | 不一致 |
| **审计 / 可观测** | 强——Actions tab 原生日志 + step summary | 中——Blue Ocean / build log | 中——Tekton dashboard |

## 4. 决策

**选方案 A：GitHub Actions。**

理由：

1. ADR-INF-06 已确立 GitHub Actions 为 CI/CD 平台，现有 10 个 workflow 全部基于此；新部署管线沿用 GHA 可零迁移、零异构运维。
2. `workflow_run` 触发器天然衔接"构建→部署"两阶段，environment 审批门禁原生支持生产环境保护，无需引入外部控制器。
3. Jenkins（B）需自建 controller/agent、维护两套 CI 体系；Tekton（C）虽 K8s 原生但与现有 GHA 体系割裂，且 CRD 学习成本高——二者性价比均低于沿用 GHA。

### 4.1 实现清单

| 文件 | 内容 |
|------|------|
| `.github/workflows/java-test.yml` | 矩阵扩展至 16 个 Java 服务（修正 `bff-java`→`services/bff-java`）；新增 `aggregate` 聚合作业 + `coverage-gate` 按服务校验 JaCoCo ≥60%（advisory） |
| `.github/workflows/go-test.yml` | 新建。矩阵 gateway-go / ep-go-common；`go-version-file` 读取 go.mod（1.25）；`go test -v -race -coverprofile=coverage.out ./...`；coverage-gate 聚合作业 |
| `.github/workflows/python-test.yml` | 拆分为 `sp04-ai`（≥70%）与 `ep-python-common`（≥60%）两作业 + coverage-gate 聚合 |
| `.github/workflows/build-and-push.yml` | 新建。main 分支 push / 手动触发；`test-gate` 锚点 → `build-java`（JIB 矩阵 7 服务）/ `build-docker`（gateway-go、sp04-ai、frontend-portal 矩阵）→ `tag-latest`（仅 main）；Harbor login via `secrets.HARBOR_USERNAME/PASSWORD` |
| `.github/workflows/deploy-gitops.yml` | 新建。`workflow_run`（build-and-push 完成后）或手动触发；`environment: production` 审批门禁；yq/sed 更新 `deploy/k8s/base/kustomization.yaml` 的 `newTag`；git commit + push 触发 ArgoCD auto-sync（D-114）；失败时 `rollback` 作业 revert 提交 |

### 4.2 管线拓扑

```
push to main
  ├─ java-test.yml      (16 services matrix + aggregate + coverage-gate)
  ├─ go-test.yml        (2 services matrix + coverage-gate)
  ├─ python-test.yml    (sp04-ai + ep-python-common + coverage-gate)
  └─ frontend-test.yml  (existing)
         │ (branch protection: all must pass)
         ▼
  build-and-push.yml
  ├─ test-gate
  ├─ build-java   (JIB, 7 services)
  ├─ build-docker (Dockerfile, 3 services)
  └─ tag-latest   (main only)
         │ (workflow_run trigger)
         ▼
  deploy-gitops.yml
  ├─ update-manifests (environment: production approval)
  │     └─ sed/yq update newTag → git push → ArgoCD auto-sync
  └─ rollback (on failure, revert commit)
```

## 5. 后果

### 正面

- **闭环达成**：代码合并→镜像构建→清单更新→ArgoCD 同步全自动，消除人工 `kubectl apply` 与手动 `docker push`。
- **CI 覆盖完整**：Java 16/16、Go 2/2、Python 2/2 服务进入 CI；覆盖率门禁（advisory 阶段）建立基线。
- **可回滚**：deploy 失败自动 revert 清单提交，ArgoCD 不滚动坏镜像。
- **生产保护**：`environment: production` 强制人工审批，防止误部署。

### 负面 / 风险

- **advisory 门禁**：覆盖率门禁当前为 `::warning` 而非硬失败，待基线建立后转为 `fail`。需跟踪 issue。
- **JIB 本地 path 依赖**：build-java 作业先 `mvn install` ep-* / sp03-*-api，构建时间增长；后续可考虑依赖缓存或拆分依赖管线。
- **workflow_run 触发延迟**：build-and-push 完成后 deploy 才触发，存在约 1-2 分钟延迟；手动 dispatch 可绕过。
- **GITOPS_PAT secret**：deploy 作业需 `secrets.GITOPS_PAT`（具 repo push 权限）——需单独创建并轮换。

### 后续工作

- 覆盖率门禁从 advisory 转 hard-fail（基线建立后）。
- 评估多 cell（hz/sz/bj/sg，D-041）渐进式发布：deploy 作业增加 cell 矩阵 + ArgoCD AppSet 按比例同步。
- 将 `GITOPS_PAT` 替换为 GitHub App token（更细粒度权限 + 审计）。
