# D-203: CockroachDB 替换 TiDB 评估

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-203 | v1.0 | 2026-08-12 | 已决策 | 平台架构组 | 架构委员会 |

> **Iron Law 合规**: IL8 (≥3 alternatives), IL9 (运行验证 — Testcontainers 集成测试 D-202), IL10 (设计→实现可追溯 — D-194 DualDbConfig 已实现)。
>
> **关联**: D-194 (CockroachDB PoC 结果)、D-132 (TiDB AUTO_RANDOM 主键热点修复)、D-079/D-080 (TiKV 587 RPS 负载测试瓶颈)、D-093 (TiDB CRD)、D-119 (TiCDC→Pulsar CDC 链路)。

## 1. 背景

用户提问: **"国内外数据存储层可以统一使用 CockroachDB 替换 TiDB 及其相关组件吗?"**

### 1.1 当前 TiDB 技术栈

| 组件 | 作用 | 规模/状态 |
|------|------|-----------|
| TiDB | SQL 层, MySQL 协议 | ~7 个服务通过 MySQL 协议连接 |
| TiKV | KV 存储引擎 | 单节点 587 RPS 瓶颈 (D-079/D-080), 待扩容 |
| TiFlash | HTAP 列存引擎 | K8s 部署 2 副本 (D-093), 基础设施就绪, 应用层未接入 (PLANNED) |
| PD | Placement Driver | 3 副本 |
| TiCDC | CDC → Pulsar → Flink → Iceberg 数据管道 | 3 个 Flink jobs (D-119) |
| AUTO_RANDOM | 主键分散写热点 | order/payment DDL 已应用 |
| TiDBDialect | 方言适配 | boardgame-service |

### 1.2 CockroachDB 现状 (D-194)

- PoC 制品就绪 (集成测试覆盖 D-202)
- CrdbCluster CRD (3 nodes, 2 regions)
- DualDbConfig + RoutingDataSource 按 `TenantContext.region` 路由 (已实现)
- CockroachDbIntegrationIT (Testcontainers 真实 CRDB 容器, EP_LIVE=1)
- DualDbConfigIT (H2 双源路由测试, 始终运行)
- PostgreSQL wire 协议

## 2. Iron Law 8: 三方案对比

### 方案 A: 全量替换 TiDB → CockroachDB

| 维度 | 评估 |
|------|------|
| 协议变更 | MySQL → PostgreSQL (全服务改造, ~7 服务 JDBC driver + DDL + 方言) |
| TiFlash HTAP | 丢失列存引擎, 无等价替代 (CRDB 纯行存, 无列存) |
| TiCDC 管道 | 3 个 Flink job 全重写 (CRDB changefeeds 协议不同) |
| AUTO_RANDOM | → UUID (schema breaking change, 所有表 PK 重设计) |
| TiDBDialect | 全删除 + 新增 CRDB dialect |
| GDPR 数据驻留 | REGIONAL BY ROW 原生支持 |
| 运维统一性 | 单一引擎 |
| 迁移成本 | 极高 (~7 服务 + 3 Flink jobs + 全部 DDL) |
| 风险 | 高 (协议变更引入大量回归风险) |

### 方案 B: 双 DB (CRDB 海外 + TiDB 国内)

| 维度 | 评估 |
|------|------|
| 协议变更 | 无 (按 region 路由, 各走各的协议) |
| TiFlash HTAP | 保留国内 TiFlash |
| TiCDC 管道 | 海外新建 CRDB changefeeds, 国内不变 |
| AUTO_RANDOM | 无变更 |
| GDPR 数据驻留 | 海外 CRDB REGIONAL BY ROW, 国内 TiDB Placement Rules |
| 运维统一性 | 双引擎 (增加运维复杂度) |
| 迁移成本 | 低 (RoutingDataSource 已在 D-194 实现) |
| 风险 | 低 (增量变更, 国内不受影响) |
| 已有基础 | D-194 DualDbConfig + RoutingDataSource + 集成测试 (D-202) |

### 方案 C: 保持纯 TiDB (TiDB 海外集群)

| 维度 | 评估 |
|------|------|
| 协议变更 | 无 |
| TiFlash HTAP | 保留 (国内 + 海外) |
| TiCDC 管道 | 不变 |
| AUTO_RANDOM | 无变更 |
| GDPR 数据驻留 | TiDB Placement Rules (需额外配置, 不如 CRDB REGIONAL BY ROW 原生) |
| 运维统一性 | 单一引擎 |
| 迁移成本 | 零 |
| 风险 | 低 |
| 缺点 | 海外数据驻留需 Placement Rules 额外配置; TiKV 587 RPS 瓶颈在海外也存在 |

## 3. 对比总结

| 维度 | A. 全量替换 | B. 双 DB | C. 纯 TiDB |
|------|------------|----------|------------|
| 协议变更 | MySQL→PG (全服务) | 无 | 无 |
| TiFlash HTAP | 丢失 | 保留国内 | 保留 |
| TiCDC 管道 | 全重写 | 海外新建 | 不变 |
| AUTO_RANDOM | →UUID (breaking) | 无 | 无 |
| GDPR 数据驻留 | 原生 REGIONAL BY ROW | 原生 REGIONAL BY ROW | Placement Rules |
| 运维统一性 | 单引擎 | 双引擎 | 单引擎 |
| 迁移成本 | 极高 (~7 服务) | 低 (已有基础) | 零 |
| 风险 | 高 | 低 | 低 |
| 海外数据驻留原生度 | 高 | 高 | 中 (需额外配置) |

## 4. 决策: 方案 B (双 DB 策略)

### 4.1 CockroachDB 不能全量替换 TiDB

1. **TiFlash HTAP 列存引擎无等价替代** — CRDB 是纯行存, 无法替代 TiFlash 的 OLAP 能力。丢失 TiFlash 意味着 HTAP 架构降级为纯 OLTP, 与 D-093 (TiFlash 2 副本) 的基础设施投入矛盾。
2. **TiCDC→Pulsar→Flink→Iceberg 数据管道重写成本极高** — 3 个 Flink jobs, changefeeds 协议与 TiCDC 完全不同, 全量重写 + 双跑验证周期长。
3. **MySQL→PostgreSQL 协议变更影响 ~7 个服务** — JDBC driver、DDL 语法、方言配置全部改造, 回归风险大。
4. **AUTO_RANDOM→UUID 是 schema breaking change** — 所有表 PK 重设计, 历史数据迁移, 与 D-132 (Snowflake ID 热点修复) 成果冲突。

### 4.2 双 DB 策略是最优解

1. **RoutingDataSource 已在 D-194 实现** — 按 `TenantContext.region` 路由, D-202 集成测试覆盖 (DualDbConfigIT 始终运行 + CockroachDbIntegrationIT EP_LIVE 真实验证)。
2. **国内业务完全不受影响** — 继续使用 TiDB + TiFlash + TiCDC, 零迁移成本。
3. **海外业务用 CockroachDB REGIONAL BY ROW 原生支持 GDPR 数据驻留** — 行级区域绑定, 无需分区表或应用层路由。
4. **增量变更, 风险可控** — `ep.db.dual.enabled` 开关控制, EP_DEV_ONLY 保护, 默认关闭。

### 4.3 全量替换的唯一动机是"运维统一"

- 双引擎确实增加运维复杂度, 但代价远超收益 (7 服务改造 + 3 Flink 重写 + 全部 DDL + 协议变更风险)。
- 运维复杂度可通过统一监控 (OpenTelemetry, D-133) 和 IaC (GitOps, D-114) 缓解。
- 单一引擎的运维收益不足以抵消迁移成本与回归风险。

### 4.4 未选方案 C 的原因

- 方案 C (纯 TiDB 海外集群) 运维最简, 但 TiDB Placement Rules 做行级数据驻留需分区表 + 额外配置, 不如 CRDB REGIONAL BY ROW 原生。
- D-194 PoC 已验证 CRDB REGIONAL BY ROW 的 DDL 与驱动兼容性 (D-202 Testcontainers 集成测试), 投入已有回报。
- 海外集群用 CRDB 可避免 TiKV 587 RPS 瓶颈在海外复现 (D-079/D-080)。

## 5. 实施路径

1. ✅ D-194: DualDbConfig + RoutingDataSource 已实现
2. ✅ D-202: 集成测试覆盖 (CockroachDbIntegrationIT + DualDbConfigIT)
3. ☐ 生产 CockroachDB 集群部署 (CrdbCluster CRD apply, cell-sg K8s)
4. ☐ 海外租户路由验证 (生产环境, `ep.db.dual.enabled=true`)
5. ☐ CRDB changefeeds → Pulsar 适配器实现 (海外 CDC 链路)
6. ☐ 扩展 RoutingDataSource 到 boardgame-service、core-business (如需)

## 6. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 双引擎运维复杂度 | 故障排查面增大 | 统一 OTel 监控 (D-133) + GitOps IaC (D-114); RoutingDataSource 路由逻辑有 DualDbConfigIT 持续守护 |
| CRDB changefeeds 需企业版 license | 海外 CDC 链路阻塞 | D-202 已验证 DDL 语法; 生产部署申请企业版 license; 降级方案: Debezium on PostgreSQL 协议 |
| 跨大洲延迟未真实验证 | 海外写延迟可能超 SLA | D-202 单节点容器已验证 DDL 兼容; 生产多区域集群基准测试待执行 (D-194 §9) |
| 路由配置错误导致数据写错库 | GDPR 数据驻留违规 | RoutingDataSource 默认路由 primary (国内), 仅 region=overseas 才路由 overseas; DualDbConfigIT 验证 fail-safe 默认行为 |

## 7. 关联

- D-194: CockroachDB PoC 结果 (v1.1, 制品就绪 + 集成测试覆盖)
- D-202: CockroachDB PoC 集成测试 (CockroachDbIntegrationIT + DualDbConfigIT)
- D-132: TiDB AUTO_RANDOM 主键热点修复 (Snowflake ID)
- D-079/D-080: TiKV 587 RPS 负载测试瓶颈
- D-093: TiDB CRD (PD3+TiKV3+TiDB2+TiFlash2+TiCDC2)
- D-119: TiCDC → Pulsar → Flink → Iceberg CDC 链路
- D-114: GitOps (Argo CD) 部署流水线
- D-133: OpenTelemetry 全链路追踪
- Iron Law 8: ≥3 alternatives (本 ADR §2 三方案对比)
- Iron Law 9: 运行验证 (D-202 Testcontainers 集成测试)
- Iron Law 10: 设计→实现可追溯 (D-194 DualDbConfig 已实现 + D-202 测试覆盖)
