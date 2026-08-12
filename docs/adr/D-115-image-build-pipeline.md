# D-115: 镜像构建管线 — JIB (Java) + Dockerfile (Go/Python/Frontend)

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-115 | v1.0 | 2026-08-09 | 已批准 | DevOps 组 | 架构委员会 |

> 关联：D-035（K8s 部署运维铁律）、D-040（GraalVM native 构建门禁）、铁律 1（会话状态外化）、铁律 8（决策对比分析）、ADR-INF-06（CI/CD 平台选型）、D-111（统一前端门户 portal-web）。

## 1. 问题陈述与约束条件

教育平台所有 K8s Deployment 引用的镜像形如 `harbor.edu.internal/education-platform/bff-java:1.0.0`，但仓库中：

- **零 Dockerfile**：Go（gateway-go）、Python（sp04-ai）、Frontend（portal-web）三类非 Java 服务的镜像完全不可重建。
- **零 JIB 配置**：7 个 Java 服务的 `pom.xml` 均无 `jib-maven-plugin`，镜像来源不透明，无法从代码复现。
- **阻断 GitOps**：Argo CD / Flux 能同步 YAML，但代码变更无法触发镜像重建——镜像成黑盒，回滚与可重建性（铁律 1 衍生要求）无保障。

约束条件：

1. **Java 21**：基础镜像必须为 JRE 21（匹配 `java.version=21`，见各 pom.xml）。
2. **非 root 运行**：所有 Dockerfile 必须以非 root 用户运行（安全基线）。
3. **HEALTHCHECK**：所有镜像必须内建健康检查（K8s probe 配套）。
4. **端口对齐**：容器暴露端口必须与 K8s Deployment / BFF 路由表一致（bff-java 8090、sp03-business 8083、sp03-order 8084、sp03-payment 8087、sp05-itbackend 8085、sp02-data 8082、sp07-retrieval 8070、gateway-go 8081、sp04-ai 8086、portal-web 80→3004）。
5. **本地 path 依赖**：gateway-go（ep-go-common）、sp04-ai（ep-python-common）、portal-web（@ep/* workspace）均依赖仓库内同级模块，Dockerfile 构建上下文必须覆盖这些路径。
6. **铁律 8**：≥3 方案对比后定论。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **JIB + Dockerfile 混合**（已选） | Java 用 JIB（无 Dockerfile、无需 daemon、直接推 registry），Go/Python/Frontend 用多阶段 Dockerfile |
| B | **全 Dockerfile** | 所有服务（含 Java）统一用多阶段 Dockerfile 构建 |
| C | **Buildpacks (pack / kpack)** | Cloud Native Buildpacks 自动检测语言并构建，无需手写任何 Dockerfile |

## 3. 对比维度表

| 维度 | A. JIB+Dockerfile 混合（已选） | B. 全 Dockerfile | C. Buildpacks |
|------|--------------------------------|------------------|---------------|
| **Java 构建速度** | 快——JIB 增量分层、跳过 `docker build` daemon、直接推 registry | 慢——需 daemon、`docker build` 全量上下文 | 中——builder 镜像检测+构建 |
| **Java 可重复构建** | 强——纯 Maven 插件，无 daemon 依赖 | 中——依赖 daemon 与 Dockerfile 缓存 | 中——依赖 builder 版本 |
| **非 Java 灵活性** | 高——Dockerfile 完全可控（multi-stage、CGO_ENABLED=0、tini 等） | 高 | 低——受 builder 检测逻辑限制，自定义需 `project.toml` 覆盖 |
| **本地 path 依赖支持** | Java 不涉及；Go/Python/Frontend 通过构建上下文 `services/` 或 `frontend/` 覆盖同级 replace/workspace | 同 A | 难——builder 默认上下文为单目录，需额外配置 |
| **镜像体积（Java）** | 小——`eclipse-temurin:21-jre-alpine` + 分层 jar | 中——需手动选基础镜像与分层 | 小——builder 自动分层 |
| **OCI 标签 / 可观测** | 强——JIB `<labels>` 原生注入 source/revision/maintained-by | 中——需 `LABEL` 指令手写 | 中——builder 自动注入 |
| **GitOps 适配** | 强——`mvn jib:build -Pjib-build` 可在 CI 无 daemon 运行 | 中——需 Docker daemon 或 kaniko/buildkit | 强——kpack 可监听 Git 仓库 |
| **学习成本 / 团队熟悉度** | 中——Java 团队需懂 JIB；Go/Py/Frontend 团队仍用 Dockerfile | 低——全 Dockerfile，团队最熟 | 中——Buildpacks 概念需学习 |
| **与 ADR-INF-06 (GitHub Actions) 集成** | 原生——Maven action + docker/build-push-action | 原生——docker/build-push-action | 需 setup-pack / kpack controller |
| **D-040 GraalVM native 兼容** | 兼容——native profile 与 jib-build profile 正交 | 兼容 | 兼容（需 native builder） |

## 4. 决策

**选方案 A：JIB + Dockerfile 混合。**

理由：

1. Java 服务已有 Maven 工程结构（spring-boot-starter-parent 4.0.7、`mvn install` 依赖链完整），JIB 作为 Maven 插件零侵入接入，且无需 Docker daemon——CI runner 资源占用低、可并行。
2. 非 Java 服务（Go/Python/Frontend）的本地 path 依赖（ep-go-common / ep-python-common / @ep/* workspace）对构建上下文有强约束，多阶段 Dockerfile 显式控制最清晰、最可控。
3. Buildpacks（C）的自动检测在多模块 path 依赖场景下需大量覆盖配置，且引入额外 controller（kpack）或工具链（pack），与现有 GitHub Actions + Maven 栈增加复杂度，性价比低。

### 4.1 实现清单

| 类别 | 文件 | 说明 |
|------|------|------|
| 共享 JIB profile | `deploy/build/jib-pom-profile.xml` | 可复用 `<profile id="jib-build">` 片段；基础镜像 `eclipse-temurin:21-jre-alpine`；JVM flags `-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`；OCI 标签 source/revision/maintained-by=gitops；registry `harbor.edu.internal/education-platform` |
| Java pom 改造 | 7 个 `pom.xml` 各内联 `jib-build` profile + `<jib.container.port>` 属性 | bff-java(8090) / sp03-business(8083) / sp02-data(8082) / sp05-itbackend(8085) / sp03-order-service(8084) / sp03-payment-service(8087) / sp07-retrieval-service(8070) |
| Go Dockerfile | `services/gateway-go/Dockerfile` | builder=`golang:1.23-alpine`、runtime=`alpine:3.20`；`CGO_ENABLED=0 go build -o gateway-go ./src`；非 root `app`；HEALTHCHECK `/healthz` |
| Python Dockerfile | `services/sp04-ai/Dockerfile` | builder=`python:3.12-slim` + Poetry export requirements；runtime=`python:3.12-slim`；CMD `granian --interface asgi ai_gateway.app:create_app`；非 root `app(10001)`；HEALTHCHECK `/health` |
| Frontend Dockerfile | `frontend/apps/portal-web/Dockerfile` | builder=`node:22-alpine` + pnpm@11 + `pnpm run build`；runtime=`nginx:alpine`；非 root `nginx`；HEALTHCHECK `/` |
| Frontend nginx | `frontend/apps/portal-web/nginx.conf` | SPA `try_files $uri $uri/ /index.html`；`/assets/` 长缓存；安全头 |
| .dockerignore | 各服务目录 | 排除 `**/target/`、`**/.venv/`、`**/node_modules/`、`**/.git/`、`**/*.md`、`**/test/` 等 |
| 构建脚本 | `deploy/build/build-all-images.sh` | 一键构建全部：Java `mvn compile jib:build -Pjib-build`；Go/Py/Frontend `docker build -t ... -f ... <context>`；支持 `VERSION` 参数与子集选择 |

### 4.2 关键设计点

- **JIB 推 registry vs 本地 daemon**：`jib:build` 直接推送 harbor，无需本地 daemon；如需仅本地测试用 `jib:dockerBuild` 或 `jib:buildTar`（脚本支持 `SKIP_PUSH=1` 仅作用于 Dockerfile 镜像，Java 走 JIB 始终推送）。
- **JVM flags 选择**：ZGC + ZGenerational 适配容器内存限制的低延迟 GC；`MaxRAMPercentage=75.0` 留 25% 给 metaspace/直接内存；`ExitOnOutOfMemoryError` 让 K8s 快速重启 Pod（铁律 1：故障快速隔离）。
- **构建上下文**：gateway-go / sp04-ai 的 Dockerfile 构建上下文为 `services/`（含同级 ep-go-common / ep-python-common）；portal-web 上下文为 `frontend/`（pnpm workspace 根）。
- **Go 静态二进制**：`CGO_ENABLED=0 GOOS=linux` 产出全静态二进制，可在 alpine 无 libc 依赖运行。
- **非 root**：所有 runtime 镜像切换到非 root 用户（alpine `app`、nginx 内置 `nginx`、Python `app(10001)`）。

## 5. 备选方案否决理由

- **B 全 Dockerfile**：Java 团队已重度依赖 Maven 生命周期与 `mvn install` 依赖链；额外引入 Dockerfile + daemon 增加构建步骤与 CI 资源，且 JIB 的分层与无 daemon 推送优势被丢弃。否决。
- **C Buildpacks**：本地 path 依赖（ep-go-common / ep-python-common / @ep/* workspace）需自定义 builder 与 `project.toml`，复杂度高于直接 Dockerfile；且 kpack controller 增加集群运维负担，与 ADR-INF-06 已选 GitHub Actions 栈耦合度低。否决。

## 6. 后果

### 正面

- **GitOps 闭环**：代码变更 → CI 触发 `build-all-images.sh` → 推 harbor → Argo CD 同步新 tag → 滚动发布。镜像可从代码完整复现。
- **Java 构建提速**：JIB 增量分层 + 无 daemon，CI 并行度高。
- **可观测**：OCI 标签 `source`/`revision` 让任意镜像可追溯到 commit。
- **安全基线**：非 root + HEALTHCHECK 全面落地。

### 负面 / 风险

- **依赖 `mvn install` 顺序**：Java JIB 构建前需先 `mvn install` ep-api / ep-web-common / sp03-*-api 等依赖模块（脚本未自动处理，CI pipeline 需先跑 `mvn install -pl ep-*`）。已在脚本注释说明。
- **harbor 凭证**：JIB 推送需 Maven `settings.xml` 配置 harbor 凭证；Dockerfile 镜像需 `docker login harbor.edu.internal`。CI 需配置 secret。
- **sp07-retrieval 端口差异**：该服务 `application.yml` 默认 `server.port=8072`，但 K8s 与 BFF 路由按 8070；镜像 EXPOSE 8070，运行时通过 `SERVER_PORT` env 覆盖。需在 K8s Deployment 注入该 env（已有，见 D-035）。
- **构建上下文较大**：gateway-go / sp04-ai 上下文为 `services/`，`.dockerignore` 已排除 target/.venv/node_modules，但首次构建仍较慢；后续靠 BuildKit 缓存缓解。

## 7. 后续工作

- CI pipeline（ADR-INF-06 GitHub Actions）集成 `build-all-images.sh`，按服务路径触发增量构建。
- 引入镜像签名（cosign）与 SBOM 生成（syft）作为 D-115 的安全增强后续。
- 评估 `jib:buildTar` 用于本地无 daemon 环境的冒烟测试。

## 8. 验证

- 7 个 Java `pom.xml` 通过 `python3 xml.dom.minidom.parse` XML 合法性校验。
- `deploy/build/jib-pom-profile.xml` 通过 XML 合法性校验。
- `deploy/build/build-all-images.sh` 通过 `bash -n` 语法校验。
- 各 Dockerfile 端口与 K8s Deployment / BFF 路由表对齐（见 §1 约束 4）。
- 各 Dockerfile 均含 `USER <非root>` 与 `HEALTHCHECK`。
