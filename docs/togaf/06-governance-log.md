# TOGAF 架构治理日志 (Governance Log)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-GOV | v1.0 | 2026-08-12 | Approved | ZCode(架构委员会) | D-157 / 00-顶层§7 |

> **文档定位**：TOGAF ADM Phase G/H 产物。记录架构合规审查、原则与铁律合规状态、ADR 审查流水、技术债看板。是 07-Architecture Contract 违约处理的证据基础。

---

## §1 架构合规审查记录

> 重大审查节点(按 ADR 批次),每批次给出审查结论。

| 审查 ID | 批次 | 审查日期 | 审查范围 | 审查结论 | 行动项 |
|---------|------|---------|---------|---------|--------|
| GR-01 | D-001~D-027 | 2026-08-03 | 初始架构(项目群/单元化/中台/湖仓/AI 网关) | Approved,基线确立 | 产出各 SP SRS/SAD |
| GR-02 | D-028~D-035 | 2026-08-04 | BFF/网关/Seata Dubbo Pulsar Nacos 版本锁定(D-032 铁律6) | Approved,版本基线锁定 | SP-01 SAD 落地 |
| GR-03 | D-036~D-042 | 2026-08-05 | 虚拟线程(D-039)/GraalVM(D-040)/多区域多活(D-041)/AI 中台(D-042) | Approved,技术架构增强 | §14/§15/§16 追加 |
| GR-04 | D-043~D-060 | 2026-08-06 | AI 智能体(D-046)/MCP 合规(D-047)/多级记忆(D-045) | Approved,AI 架构增强 | §17/§18/§19 追加 |
| GR-05 | D-061~D-090 | 2026-08-06 | 中台能力复用/契约治理/可观测完善 | Approved | 持续 |
| GR-06 | D-091~D-120 | 2026-08-07 | Seata Saga 迁移(D-113)/GitOps(D-114)/JIB(D-115)/CICD(D-116)/JWT 外化(D-117)/OTel(D-118)/Testcontainers(D-119) | Approved,工程化基线 | 落地 ADR-D113..D120 |
| GR-07 | D-121~D-137 | 2026-08-08 | 架构评分卡(D-136)/命名审计与重构(D-137/D-149) | Approved,技术债治理启动 | 执行 D-149~D-153 |
| GR-08 | D-138~D-148 | 2026-08-08 | 架构评分 P0/P1 修复(D-148) | Approved,5 个 P0/P1 修复 | 落地 D-152 |
| GR-09 | D-149~D-154 | 2026-08-09 | 命名重构(D-149~D-151)/P0 P1 修复(D-152)/编译验证(D-153)/安全标准(D-154) | Approved,全量编译 PASS | 进入 TOGAF 构件补全(D-157) |
| GR-10 | D-155~D-157 | 2026-08-12 | TOGAF 构件补全(D-157) | Approved,7 构件创建 | 本批文档 |

## §2 架构原则 P-01..P-10 合规状态

| 原则 | 内容 | 合规状态 | 证据 | 审查日期 |
|------|------|---------|------|---------|
| P-01 | 新 BU 接入 = 调中台,不自建共享能力 | COMPLIANT | 各 BU 服务(grading/senior/boardgame)调用中台 ABB,ArchUnit 守卫 | 2026-08-09 |
| P-02 | 跨单元不直接 RPC,走 Pulsar 异步 | COMPLIANT | ABB-24 Pulsar 事件总线,跨 cell 配置 NetworkPolicy 拒绝直连 | 2026-08-09 |
| P-03 | 禁 app 拼 tenant_id,用 JWT FGAC | COMPLIANT | ABB-20 TenantFilter + SAST 规则,SIB-S-08 | 2026-08-09 |
| P-04 | 在线 TiDB 只留热数据,冷归 Iceberg | PARTIALLY | 分层策略已定(ADR-D04),冷归档自动化待 M2 落地 | 2026-08-09 |
| P-05 | 公共库 ≥80% 覆盖,安全核心 100% | COMPLIANT | JaCoCo CI 硬门,SIB-T-01 | 2026-08-09 |
| P-06 | 契约先行 + Pact 测试 | COMPLIANT | OpenAPI 3 + Dubbo IDL + Pact broker,SIB-A-05 | 2026-08-09 |
| P-07 | 探针三分离,timeout≥3s | COMPLIANT | K8s manifest 校验,SIB-O-04 | 2026-08-09 |
| P-08 | LLM 评分 human-in-the-loop | COMPLIANT | ABB-08 grading-service,ADR-I05 | 2026-08-09 |
| P-09 | 海外数据不回流国内 | COMPLIANT | cell-sg/法兰克福独立,SIB-S-10,D-004 | 2026-08-09 |
| P-10 | 上下文 80% 压缩,状态外化 Redis | COMPLIANT | D-006/D-045 状态外化,代码审查 | 2026-08-09 |

## §3 Iron Law 1-9 合规审计

| Iron Law | 内容 | 合规状态 | 证据 | 最后审查 |
|---------|------|---------|------|---------|
| 铁律1 | 新 BU 接入 = 调中台 | COMPLIANT | 见 P-01 | 2026-08-09 |
| 铁律2 | 跨单元走 Pulsar 异步 | COMPLIANT | 见 P-02 | 2026-08-09 |
| 铁律3 | JWT FGAC,禁拼 tenant_id | COMPLIANT | 见 P-03 | 2026-08-09 |
| 铁律4 | 在线只留热数据 | PARTIALLY | 见 P-04 | 2026-08-09 |
| 铁律5 | 覆盖率门禁 | COMPLIANT | 见 P-05 | 2026-08-09 |
| 铁律6 | 禁 SCG/Netflix 组件 | COMPLIANT | D-032 + 依赖扫描 CI 门 | 2026-08-09 |
| 铁律7 | 多级缓存统一(不重复造轮子) | COMPLIANT | ABB-18 ep-cache L1+L2,ADR-T04 | 2026-08-09 |
| 铁律8 | 决策需三方对比选型 | COMPLIANT | ADR-A05/A06/D-018/D-019 均有三方对比记录 | 2026-08-09 |
| 铁律9 | 上下文 80% 压缩 + 状态外化 | COMPLIANT | 见 P-10 | 2026-08-09 |

## §4 ADR 审查流水(按批次)

| 批次 | ADR 范围 | 主题 | 关键产出 | 状态 |
|------|---------|------|---------|------|
| 初始架构 | D-001~D-027 | 项目群/单元化/中台/湖仓/AI 网关/直播/沙箱/查重 | 00-顶层§3-§6 基线 | Accepted |
| 版本锁定 | D-028~D-035 | SB4/SCA/Dubbo/Nacos/Pulsar/Sentinel/SCG 禁用(D-032) | 00-顶层§6.1 | Accepted |
| 技术增强 | D-036~D-042 | 虚拟线程(D-039)/GraalVM(D-040)/多区域多活(D-041)/AI 中台(D-042) | 00-顶层§14/§15/§16 | Accepted |
| AI 智能体 | D-043~D-060 | 智能体(D-046)/MCP(D-047)/多级记忆(D-045) | 00-顶层§17/§18/§19 | Accepted |
| 中台能力 | D-061~D-090 | 能力复用/契约/可观测 | 各 SP SAD | Accepted |
| 工程化基线 | D-091~D-120 | Seata Saga(D-113)/GitOps(D-114)/JIB(D-115)/CICD(D-116)/JWT 外化(D-117)/OTel(D-118)/Testcontainers(D-119)/Nebula(D-120) | docs/adr/D-113..D-120 | Accepted |
| 技术债治理 | D-121~D-137 | 评分卡(D-136)/命名审计(D-137) | architecture-scorecard-d136 / naming-audit-d137 | Accepted |
| P0/P1 修复 | D-138~D-148 | 评分 P0/P1 修复 | D-148 | Accepted |
| 命名重构 | D-149~D-154 | 目录(D-150)/Java 包(D-151)/P0P1 修复(D-152)/编译验证(D-153)/安全标准(D-154) | services/ 目录重命名 | Accepted |
| TOGAF 补全 | D-155~D-157 | TOGAF 构件补全 | 本批 02-08 文档 | Accepted |

## §5 技术债看板

| ID | 技术债 | 影响 | 优先级 | 引入来源 | 计划 |
|----|--------|------|--------|---------|------|
| TD-01 | SP-01 ep-cache 独立 review 未完成 | 代码质量未独立验证 | P1 | 00-顶层§10.3 | M2 补 review |
| TD-02 | SP-01 Task 4-7 暂停 | 中台不完整 | P0 | 00-顶层§10.3 | M2 恢复 |
| TD-03 | SP-02/03/04 spec 未产出完整 | 数据/业务/AI 中台设计基线不全 | P0 | 00-顶层§10.3 | M2 并行产出 |
| TD-04 | K8s 集群离线(k3s 关闭) | 部署验证 0% | P1 | 00-顶层§10.3 | M2 恢复集群 |
| TD-05 | 会话状态本地兜底,未同步 Redis | 跨会话恢复受限 | P2 | 00-顶层§10.3 | M2 集群恢复后同步 |
| TD-06 | 冷热分层自动化未落地(P-04 PARTIALLY) | 在线库容量风险 | P1 | GR-07 P-04 | M2 落地 Iceberg 归档自动化 |
| TD-07 | ws-gateway OTel traceparent 修复在 CI 环境,生产未验证 | 生产链路缺失 | P1 | D-152 P0-2 | M2 生产验证 |
| TD-08 | VirtualThreadDbSemaphore 在 QEMU arm64 测试参数调优待优化 | CI 偶发抖动 | P2 | D-153 | M2 优化 |
| TD-09 | 海外 CockroachDB PoC 未启动(D-027 Proposed) | 海外多区域方案待验证 | P2 | D-027/ADR-T12 | M3 SP-07 PoC |
| TD-10 | 前端 e2e/playwright 依赖未安装(tsconfig 已排除) | E2E 测试未跑通 | P2 | D-153 | M2 安装 @playwright/test |

---

**关联文档**：00-顶层§7/§10/§12 / 04-SIB / 05-ARS / 07-Architecture Contract / 08-Transition Architecture
