# TOGAF 架构契约 (Architecture Contract)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-AC | v1.0 | 2026-08-12 | Active | ZCode(架构委员会) | D-157 / 00-顶层§7 |

> **文档定位**：TOGAF ADM Phase G 实施治理产物。架构委员会与各子项目(SP-01..SP-10)负责人之间的合规契约。违反契约条款触发 §3 违约处理流程。

---

## §1 契约方

| 角色 | 代表 | 职责 |
|------|------|------|
| 甲方(架构治理方) | 架构委员会(CTO + 4 中台负责人 + SRE 负责人 + 安全合规负责人) | 架构原则守护 / ADR 审批 / 合规审查 / 违约裁决 |
| 乙方(实施方) | SP-01 技术中台负责人 | 技术中台 ABB/SBB 实施合规 |
| 乙方 | SP-02 数据中台负责人 | 数据中台 ABB/SBB 实施合规 |
| 乙方 | SP-03 业务中台负责人 | 业务中台 ABB/SBB 实施合规 |
| 乙方 | SP-04 AI 中台负责人 | AI 中台 ABB/SBB 实施合规 |
| 乙方 | SP-05 IT 后端 MVP 负责人 | IT 后端 BU 服务实施合规 |
| 乙方 | SP-06 多端前端负责人 | 前端 ABB-28 实施合规 |
| 乙方 | SP-07 单元化+多区域负责人 | 多活部署合规 |
| 乙方 | SP-08 测试体系负责人 | 测试标准合规 |
| 乙方 | SP-09 扩容天花板负责人 | SLO 达标合规 |
| 乙方 | SP-10 横向复制新事业群负责人 | 新 BU 接入合规 |

## §2 合规条款(必须遵守)

### 2.1 架构原则 P-01..P-10 不可违反

详见 00-顶层§7.1 与 06-Governance Log §2。违反任一原则即触发 §3 违约处理。

### 2.2 Iron Law 1-9 不可违反

详见 05-ARS §6 与 06-Governance Log §3。铁律违规等同红线,直接进入 §3 第 3 阶段。

### 2.3 DDD 分层约束

- domain 层不依赖 web/infra/persistence(SIB-C-05)
- 聚合根充血模型,禁贫血 service+DTO
- ArchUnit `layeredArchitecture()` CI 硬门阻断

### 2.4 命名规范(D-149)

- 服务目录用领域语义名,禁 SP 编号前缀(SIB-N-01)
- Java 包名 `com.ep.{domain}.*`(SIB-N-02)
- 前端 scope 统一 `@ep/*`(SIB-N-03)
- Dubbo 契约独立 `*-api` 模块(SIB-N-05)

### 2.5 安全标准(D-154)

- JWT RS256 + JWKS + 密钥外化(D-117)(SIB-S-01)
- BFF hop-by-hop header 剥离(SIB-S-02)
- Actuator 鉴权,禁裸露(SIB-S-03)
- CORS 显式白名单,禁 `*`(SIB-S-04)
- Bean Validation `@Valid`(SIB-S-05)
- 网关+服务双层 Sentinel 限流,规则走 Nacos(SIB-S-06)
- NetworkPolicy deny-by-default(SIB-S-07)
- 多租户 JWT FGAC,禁拼 tenant_id(SIB-S-08)
- 评测沙箱 Firecracker(SIB-S-09)
- PIPL 海外数据不回流(SIB-S-10)

### 2.6 测试标准

- 公共库覆盖率 ≥80%,安全核心 100%(SIB-T-01/P-05)
- ArchUnit 架构守卫(SIB-T-02)
- Pact 契约测试 CI 硬门(SIB-T-04/P-06)
- Testcontainers 集成测试(SIB-T-05)

### 2.7 CI/CD 门禁

- 编译构建全绿(SIB-C-*)
- 单元测试覆盖率门禁(SIB-T-01)
- ArchUnit 门禁(SIB-T-02)
- Pact 契约验证门禁(SIB-A-05)
- SAST(Semgrep/CodeQL)无高危(SIB-S-*)
- 镜像扫描(Trivy)无高危(SIB-D-02)
- 探针配置校验(SIB-O-04)
- 任一门禁失败 = 禁止部署到 Staging/Prod

### 2.8 版本锁定(不可擅自降级/升级)

- Spring Boot 4.0.7(铁律,D-032/D-039)
- SCA 2025.1.0.0(D-032)
- Dubbo 3.3.6(ADR-T09)
- TiDB 8.5 LTS
- Pulsar 3.3.x(ADR-T11,4.x 延至 v2.0)
- Nacos 2.4.3(不升 v3)
- GraalVM native-maven-plugin 0.10.4(D-040)

### 2.9 禁用清单(03-SBB §3)

禁用 Spring Cloud Netflix/SCG、Kafka、Seata AT、Dubbo STRICT、FastAPI/Uvicorn、Spring AI Alibaba、Docker/gVisor 沙箱。

## §3 违约处理流程

```
发现违规(自动 CI / 架构审查 / SAST / 举报)
    │
    ▼
§3.1 警告(架构委员会向 SP 负责人发书面通知,记录到 06-Governance Log)
    │  限期: SHOULD 级 5 工作日 / MUST 级 2 工作日
    ▼
§3.2 限期修复(SP 负责人提交修复 PR + ADR 若涉及变更)
    │  修复验证: CI 全绿 + 架构审查签字
    ├─ 修复完成 → 关闭,记录到 Governance Log
    └─ 逾期未修复 → 进入 §3.3
    ▼
§3.3 架构委员会评审(紧急会,48h 内召开)
    │
    ├─ 否决发布(阻断该 SP 的 Staging/Prod 部署)
    ├─ 降级处理(该 SP 服务标记 NON-COMPLIANT,Governance Log 公示)
    └─ 重大红线(铁律/PIPL/安全)→ 直接否决 + 升级到 CTO
```

## §4 变更管理(ADR 流程)

### 4.1 ADR 状态机

```
Proposed(草案) ──► 架构委员会评审 ──► Accepted(生效,更新 00-顶层 + 本契约)
                       │                     │
                       ├─ Rejected(归档)     └─ Deprecated(被 Supersededor 废弃)
                       └─ Deferred(补充后重审)         ↑
                                                  Superseded by 新 ADR
```

### 4.2 变更类型

| 类型 | 触发 | 审批 | 时效 |
|------|------|------|------|
| 常规变更 | 技术演进/重构 | 双周架构评审会 | 正常 |
| 紧急变更(生产故障) | SRE 决策 | 48h 内补 ADR | 紧急 |
| 紧急变更(安全漏洞) | 安全合规队决策 | 24h 内补 ADR | 紧急 |
| 红线变更(铁律/原则) | 必须 CTO + 架构委员会全票 | 季度复盘 | 严格 |

### 4.3 契约变更

本契约自身变更需架构委员会全票通过,版本号递增,通知所有 SP 负责人重新签署(§5)。

## §5 签署

> 模板。各 SP 负责人在 M1/M2/M3 阶段切换时签署。

| 角色 | 姓名 | 签署日期 | 版本 | 签名 |
|------|------|---------|------|------|
| 架构委员会主席(CTO) | _____ | 2026-08-12 | v1.0 | _____ |
| SP-01 技术中台负责人 | _____ | _____ | v1.0 | _____ |
| SP-02 数据中台负责人 | _____ | _____ | v1.0 | _____ |
| SP-03 业务中台负责人 | _____ | _____ | v1.0 | _____ |
| SP-04 AI 中台负责人 | _____ | _____ | v1.0 | _____ |
| SP-05 IT 后端负责人 | _____ | _____ | v1.0 | _____ |
| SP-06 多端前端负责人 | _____ | _____ | v1.0 | _____ |
| SP-07 单元化+多区域负责人 | _____ | _____ | v1.0 | _____ |
| SP-08 测试体系负责人 | _____ | _____ | v1.0 | _____ |
| SP-09 扩容天花板负责人 | _____ | _____ | v1.0 | _____ |
| SP-10 横向复制新事业群负责人 | _____ | _____ | v1.0 | _____ |
| SRE 负责人 | _____ | _____ | v1.0 | _____ |
| 安全合规负责人 | _____ | _____ | v1.0 | _____ |

---

**关联文档**：00-顶层§7/§11 / 04-SIB / 05-ARS / 06-Governance Log
