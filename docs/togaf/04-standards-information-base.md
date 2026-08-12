# TOGAF 标准信息库 (Standards Information Base)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-SIB | v1.0 | 2026-08-12 | Approved | ZCode(架构治理) | D-157 / D-112 / D-149 / D-154 |

> **文档定位**：TOGAF ADM Phase G 实施治理产物。登记 education-platform 全部强制/推荐标准，作为 CI/CD 硬门与架构合规审查依据。强制等级：**MUST**(红线) / **SHOULD**(强推荐) / **MAY**(可选)。

---

## 1. 编码标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-C-01 | Java 编码规范 | 编码 | MUST | Google Java Style + Spring Boot 约定;包名 `com.ep.*` 全小写反域名;类名 PascalCase;JPA 实体 `Model` 后缀,API DTO 用 `Dto`(非 DTO),命令用 `Cmd` | D-112 coding-standards.md §1 | spotless/checkstyle + ArchUnit 包规则 |
| SIB-C-02 | Go 编码规范 | 编码 | MUST | effective go;包名全小写单数;导出标识符 PascalCase;gofmt + govet + golangci-lint | coding-standards.md | golangci-lint CI 门 |
| SIB-C-03 | Python 编码规范 | 编码 | MUST | PEP 8 + 类型注解(mypy strict);litestar 框架约定;禁裸 except | coding-standards.md | ruff + mypy CI 门 |
| SIB-C-04 | TypeScript 编码规范 | 编码 | MUST | strict mode(`"strict": true`);禁 any(需 eslint-disable 显式声明);ESM;函数式优先 | coding-standards.md | tsc --noEmit + eslint CI 门 |
| SIB-C-05 | DDD 分层约束 | 编码 | MUST | domain 层不依赖 web/infra;聚合根充血模型;禁在 domain 引入 Spring MVC 注解 | 00-顶层§5 | ArchUnit `layeredArchitecture()` CI 硬门 |

## 2. 命名规范

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-N-01 | 目录领域语义命名 | 命名 | MUST | 服务目录用领域语义名(course-service, order-platform, ai-platform),禁用 SP 编号前缀 | D-149/D-150 naming-audit-d137 | 目录扫描脚本 + CI 校验 |
| SIB-N-02 | Java 包名规范 | 命名 | MUST | `com.ep.{domain}.*`,禁重复段;Dubbo 契约独立模块(grading-api) | D-151 coding-standards.md §1.1 | ArchUnit 包规则 |
| SIB-N-03 | 前端 scope 统一 | 命名 | MUST | pnpm workspace scope 统一 `@ep/*`(@ep/admin-web, @ep/portal-web, @ep/student-h5, @ep/student-mp) | D-150 | pnpm workspace 校验 |
| SIB-N-04 | REST 路径规范 | 命名 | MUST | 统一 `/api/v1/` 前缀;kebab-case 路径段;资源复数;子资源嵌套 | coding-standards.md §1.4 | OpenAPI lint + 契约测试 |
| SIB-N-05 | Dubbo 契约独立模块 | 命名 | MUST | RPC 契约独立 maven 模块(`*-api`),禁散落在 service 模块 | D-151 | ArchUnit 模块依赖规则 |

## 3. API 设计标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-A-01 | OpenAPI 3 规范 | API | MUST | 所有 REST 接口先出 OpenAPI 3.0 spec;契约先行(P-06) | 00-顶层§5.4 | openapi-generator + spectral lint |
| SIB-A-02 | RESTful 约定 | API | MUST | 资源导向;HTTP 语义化;统一错误响应 + traceId 透出 | SIB-C-01 | 契约测试 + ArchUnit |
| SIB-A-03 | Dubbo Triple Protobuf | API | MUST | RPC 用 Triple(HTTP/2+Protobuf),禁 STRICT 序列化(ADR-T09) | ADR-T09 | Dubbo 配置扫描 |
| SIB-A-04 | 版本策略 | API | MUST | 语义化版本(SemVer) + 向后兼容窗口 2 个 minor;Avro EVOLVE 模式 for CDC | 00-顶层§5.4 | Pact 契约测试 CI 硬门 |
| SIB-A-05 | Pact 契约测试 | API | MUST | 消费者驱动契约,CI 硬门阻断(P-06) | 00-顶层§5.4 | Pact broker 验证 |

## 4. 安全标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-S-01 | JWT RS256 鉴权 | 安全 | MUST | BFF 签发 JWT RS256 + JWKS 下发;下游 fail-closed 验签;密钥外化(D-117) | ADR-T01/D-117 | SAST + 集成测试 |
| SIB-S-02 | BFF header 剥离 | 安全 | MUST | BFF 剥离 hop-by-hop/内部头,禁透传 `x-internal-*` 到下游 | D-154 | ArchUnit + 集成测试 |
| SIB-S-03 | Actuator 鉴权 | 安全 | MUST | Actuator 端点强制鉴权,禁裸露 /actuator/* 到公网 | D-154 | NetworkPolicy + 配置扫描 |
| SIB-S-04 | CORS 显式头 | 安全 | MUST | CORS 仅显式白名单 Origin/Header/Method,禁 `*` | D-154 | 配置扫描 + 集成测试 |
| SIB-S-05 | Bean Validation | 安全 | MUST | 入参 `@Valid` + JSR-380 校验,禁裸接收 | D-154 | ArchUnit 注解扫描 |
| SIB-S-06 | 限流 Rate Limiting | 安全 | MUST | 网关 + 服务双层 Sentinel(规则走 Nacos),禁本地内存(ADR-T08) | ADR-T08 | 配置 + 压测验证 |
| SIB-S-07 | NetworkPolicy deny-by-default | 安全 | MUST | K8s NetworkPolicy 默认拒绝,白名单放通(00-顶层§6.6) | D-154 | kyverno/cilium 策略校验 |
| SIB-S-08 | 多租户 FGAC | 安全 | MUST | 禁 app 层拼 tenant_id,用 JWT FGAC(P-03);RAG namespace 隔离(ADR-A02) | ADR-A02/P-03 | SAST 规则 + 集成测试 |
| SIB-S-09 | 沙箱隔离 | 安全 | MUST | 评测 Judge 用 Firecracker microVM(ADR-I02),禁 Docker | D-022 | 部署清单校验 |
| SIB-S-10 | 数据合规 PIPL | 安全 | MUST | 海外数据不回流国内(P-09);海外独立集群 | D-004/P-09 | 数据流审计 |

## 5. 可观测标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-O-01 | W3C TraceContext | 可观测 | MUST | 全链路注入 `traceparent` header;BFF/WS 网关/AI 网关均透传 | D-118 ADR | OTel collector 验证 |
| SIB-O-02 | Prometheus 指标 | 可观测 | MUST | 业务/技术指标 Prometheus 格式;histogram for latency | 00-顶层§6.4 | promtool lint |
| SIB-O-03 | JSON 结构化日志 | 可观测 | MUST | JSON 结构化 + traceId MDC;双日志轨(access + business) | 00-顶层§6.4 | 日志格式校验 |
| SIB-O-04 | 探针三分离 | 可观测 | MUST | startup/liveness/readiness 三分离,timeout≥3s(P-07) | ADR-T05 | k8s manifest 校验 |
| SIB-O-05 | OTel 全链路 | 可观测 | SHOULD | OTLP 导出 + Edge Collector tail-sampling 10% | D-118 | 部署清单 |

## 6. 部署标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-D-01 | GitOps(ArgoCD) | 部署 | MUST | 声明式部署,ArgoCD 同步,禁 kubectl 手工 apply | D-114 | ArgoCD 同步状态 |
| SIB-D-02 | JIB 镜像构建 | 部署 | MUST | Java 服务用 JIB 无 Dockerfile 构建(D-115) | D-115 | 构建脚本校验 |
| SIB-D-03 | HPA autoscaling/v2 | 部署 | MUST | HPA 激进扩/防抖缩(00-顶层§6.6) | ADR-T05 | k8s manifest 校验 |
| SIB-D-04 | PDB | 部署 | MUST | PodDisruptionBudget minAvailable:1,滚动不中断 | ADR-T05 | k8s manifest 校验 |
| SIB-D-05 | SealedSecret | 部署 | MUST | 密钥用 SealedSecret/External Secrets,禁明文 ConfigMap | — | kustomize/sealed-secrets 校验 |
| SIB-D-06 | GraalVM 双模式 | 部署 | SHOULD | JIT(开发/压测) + Native(生产突发扩容)双镜像 tag(D-040) | D-040 | 构建流水线 |
| SIB-D-07 | 虚拟线程开启 | 部署 | MUST | SB4 服务强制 `spring.threads.virtual.enabled=true`(D-039) | D-039 | 配置扫描 |

## 7. 测试标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-T-01 | 单元测试覆盖率 | 测试 | MUST | 公共库 ≥80%,安全核心 100%(P-05);JaCoCo 采集 | P-05 | JaCoCo threshold CI 硬门 |
| SIB-T-02 | ArchUnit 架构守卫 | 测试 | MUST | DDD 分层/包规范/依赖方向守卫(P-05) | P-05 | ArchUnit CI 硬门 |
| SIB-T-03 | 变异测试 PITest | 测试 | SHOULD | 核心模块变异测试,存活率阈值 | — | PITest CI 报告 |
| SIB-T-04 | 契约测试 Pact | 测试 | MUST | 消费者驱动,Pact broker CI 硬门(P-06) | P-06 | Pact 验证 |
| SIB-T-05 | 集成测试 Testcontainers | 测试 | MUST | 真依赖(TiDB/Redis/Pulsar/Milvus)集成测试(D-119) | D-119 | CI 集成阶段 |
| SIB-T-06 | 压测 k6 | 测试 | SHOULD | 核心 API 压测,SLO G-03/04 达标验证 | G-03/04 | k6 压测流水线 |
| SIB-T-07 | E2E Playwright | 测试 | SHOULD | 关键用户旅程三端 E2E | — | E2E 流水线 |

## 8. 数据标准

| ID | 名称 | 分类 | 强制等级 | 内容要点 | 引用文档 | CI 验证方式 |
|----|------|------|---------|---------|---------|------------|
| SIB-DB-01 | TiDB AUTO_RANDOM(Snowflake) | 数据 | MUST | 主键 AUTO_RANDOM(5) + PRE_SPLIT_REGIONS,禁自增(热点) | D-015 | DDL 审查 |
| SIB-DB-02 | Cache-Aside + 惊群防护 | 数据 | MUST | L1 Caffeine + L2 Redis,Cache-Aside 模式 + EMPTY_SENTINEL 防穿透 + 单飞防惊群 | ADR-T04 | ArchUnit + 代码审查 |
| SIB-DB-03 | 冷热分层 | 数据 | MUST | 在线 TiDB 只留热数据,冷数据归 Iceberg(P-04) | D-004 ADR-D04 | 容量监控告警 |
| SIB-DB-04 | 大表 RANGE 分区 | 数据 | MUST | 大表强制 RANGE 分区(防 session_turns 82M OOM, ADR-T10) | ADR-T10 | DDL 审查 |
| SIB-DB-05 | CDC 走 Pulsar | 数据 | MUST | TiCDC→Pulsar(ns=cdc://, Avro)→Flink→Iceberg,禁直连 Kafka(ADR-D01) | ADR-D01/D-016 | 部署清单 |
| SIB-DB-06 | Redis key 规范 | 数据 | MUST | key = `{bu}:{svc}:{entity}:{id}`,值 snake_case JSON | 00-顶层§4.2 | key 扫描脚本 |

## 9. CI/CD 门禁汇总

| 门禁 | 阻断条件 | 关联标准 |
|------|---------|---------|
| 编译构建 | 全模块编译失败 | SIB-C-01..04 |
| 单元测试 | 覆盖率 <80% / 安全核心 <100% | SIB-T-01 |
| ArchUnit | 分层/包/依赖违规 | SIB-C-05/SIB-T-02/SIB-N-02 |
| 契约测试 | Pact 验证失败 | SIB-A-05/SIB-T-04 |
| SAST | Semgrep/CodeQL 高危 | SIB-S-* |
| 镜像扫描 | Trivy CVE 高危 | SIB-D-02 |
| 探针配置 | 三分离缺失/timeout<3s | SIB-O-04 |

---

**关联文档**：00-顶层§6/§7 / 02-ABB / 03-SBB / 06-Governance Log / 07-Architecture Contract
