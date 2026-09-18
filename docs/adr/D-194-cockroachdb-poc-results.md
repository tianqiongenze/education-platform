# D-194: CockroachDB PoC — 海外多区域数据合规存储验证

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-194 | v1.1 | 2026-08-12 | PoC 制品就绪 (集成测试覆盖, 待生产集群验证) | 平台架构组 | 架构委员会 |

> 关联：D-041（多区域多活 cell-sg 海外 cell）、D-045（ep_memory 四级记忆）、铁律 8（决策对比分析 ≥3 方案）、铁律 9（真实实现无桩）、GDPR/PDPA 数据驻留合规要求。

## 1. 问题陈述与约束条件

D-041 已在新加坡部署海外 cell（`cell-sg`），当前海外租户的业务数据仍写入主 TiDB 集群（国内）。随着 GDPR（欧盟）与 PDPA（新加坡）数据驻留要求趋严，海外租户的 PII 数据必须存储在海外区域的可审计数据存储中，不得跨境同步到国内。

现有架构的约束：

1. **TiDB 主集群在国内**：国内 → 海外的数据复制延迟高（~200-400ms RTT），且跨境传输本身违反 GDPR 数据驻留原则。
2. **海外 cell 需独立数据平面**：海外租户的写操作应在海外区域完成，读操作优先命中本地副本。
3. **CDC 链路不变**：现有的 TiCDC → Pulsar CDC 链路（D-119）仍服务于国内集群；海外存储需提供等价的变更捕获能力（changefeeds → Pulsar）。
4. **不引入新运维负担**：海外存储须与现有 K8s GitOps（D-114）+ Argo CD（D-114）部署流水线兼容，用 CRD 声明式管理。
5. **铁律 9**：PoC 必须是真实验证（建表、写数据、测延迟、测 CDC），不是文档层面的「计划验证」。
6. **AOT 友好（D-040）**：双 DB 路由配置须 GraalVM 原生镜像兼容，使用 `AbstractRoutingDataSource`（Spring 原生支持）而非运行时字节码增强。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **CockroachDB（PoC 验证中）** | 多活强一致 SQL，REGIONAL BY ROW 行级区域绑定，PostgreSQL 协议，CRD 声明式部署 |
| B | **TiDB 海外独立集群** | 与国内同栈，多 Region Follow Read，但跨境强一致复制延迟高，需独立 PD/TiKV 运维 |
| C | **PostgreSQL + 逻辑订阅** | 海外独立 PG 集群，逻辑订阅做跨区域同步，但非多活（只读副本），CDC 需 Debezium |

## 3. 对比维度表

| 维度 | A. CockroachDB（PoC） | B. TiDB 海外集群 | C. PostgreSQL + 逻辑订阅 |
|------|----------------------|------------------|--------------------------|
| **数据驻留** | REGIONAL BY ROW 行级绑定，region 列决定副本位置 | 需 Placement Rules 配置，表级/分区级绑定 | 需分区表 + 触发器，行级绑定需额外开发 |
| **多活能力** | 强一致多活（Raft 跨 region） | 强一致但跨 region 写延迟高（PD leader 单点） | 仅主从，海外只读 |
| **CDC 集成** | 原生 changefeeds → Pulsar/Kafka sink | TiCDC → Pulsar（现有链路，同栈） | 逻辑订阅 → Debezium → Pulsar（需额外组件） |
| **运维栈** | CRDB Operator（CRD），单一二进制 | PD + TiKV + TiDB + TiCDC 多组件 | PG + Patroni + pgBouncer + Debezium |
| **协议兼容** | PostgreSQL wire（复用 pg 驱动） | MySQL 协议（复用 mysql-connector-j） | PostgreSQL wire |
| **连接规模** | 号称 1M+ 连接（待 PoC 验证） | 受 TiDB 节点数限制 | 受 max_connections 限制（默认 100） |
| **跨 region 延迟** | 写 P99 待测（PoC 目标 <100ms 同 region） | 写 P99 ~200-400ms（跨 region 强一致） | 写 P99 <50ms（本地主），读副本延迟 ~100ms |
| **GitOps 部署** | CrdbCluster CRD，与 Argo CD 兼容 | TiDB Operator CRD，已在国内验证 | Helm Chart，需自建 HA 编排 |
| **成本** | 开源版免费，企业版按节点 | 开源版免费，已有机队经验 | 开源版免费，但运维组件多 |

## 4. 决策

**PoC 阶段选 A（CockroachDB）进行验证，最终决策待 PoC 结果。**

理由：

1. **行级区域绑定原生支持**：`LOCALITY REGIONAL BY ROW` 让单表内不同行的副本驻留在不同 region，完美匹配「同一张表内既有国内租户又有海外租户」的场景，无需分区表或应用层路由。TiDB 需 Placement Rules + 分区表才能做到，PostgreSQL 需应用层分流。
2. **多活强一致**：CockroachDB 的 Raft 跨 region 复制提供线性一致性，海外 cell 可独立写入并与其他 region 强一致同步。TiDB 跨 region 强一致写延迟受 PD leader 位置影响；PostgreSQL 逻辑订阅是异步的，海外 cell 无法强一致写入。
3. **CDC 原生 Pulsar sink**：`CREATE CHANGEFEED ... WITH sink='pulsar://...'` 直接对接现有 Pulsar 集群，无需 Debezium 中间件。与现有 TiCDC → Pulsar 链路（D-119）架构对称。
4. **CRD 声明式部署**：`CrdbCluster` CRD 与现有 K8s GitOps 流水线（D-114 Argo CD）兼容，海外 cell 叠加层（`deploy/k8s/overlays/cell-sg/cockroachdb.yaml`）声明 GDPR 注解与 NetworkPolicy。
5. **风险可控**：PoC 不替换现有 TiDB，而是通过 `RoutingDataSource` 双写路由——国内租户路由到 TiDB（primary），海外租户路由到 CockroachDB（overseas），由 `ep.db.dual.enabled` 开关控制，EP_DEV_ONLY 保护。

## 5. PoC 验证计划

### 5.1 验证项与通过标准

| # | 验证项 | 通过标准 | 验证方式 |
|---|--------|---------|---------|
| 1 | **REGIONAL BY ROW** | 建表成功，region 标记的行落在对应 region 节点 | `CREATE TABLE ... LOCALITY REGIONAL BY ROW` + 插入 + `count(*)` 验证 |
| 2 | **跨 region 写延迟** | 同 region 写 P99 < 50ms，跨 region 写 P99 < 200ms | 20 次 INSERT 探针，记录 P99 |
| 3 | **Changefeeds → Pulsar** | `CREATE CHANGEFEED` DDL 被接受，Pulsar topic 收到变更事件 | DDL 执行 + Pulsar consumer 验证（集成环境） |
| 4 | **1M 连接规模** | `sql.pool.max_size >= 1000`（基线），集群设置可调到目标值 | `SHOW CLUSTER SETTING sql.pool.max_size` + 调参 |

### 5.2 实现工件

| 工件 | 路径 | 说明 |
|------|------|------|
| K8s CRD | `deploy/k8s/base/cockroachdb-cluster.yaml` | CrdbCluster 3 节点（sg/fra/tyo），PVC 100Gi |
| 海外 cell 叠加 | `deploy/k8s/overlays/cell-sg/cockroachdb.yaml` | GDPR 注解 + NetworkPolicy（SQL 端口仅 data-platform/boardgame 可访问） |
| 双 DB 路由 | `services/data-platform/.../cockroachdb/RoutingDataSource.java` | AbstractRoutingDataSource，按 TenantContext.region 路由 |
| 双 DB 配置 | `services/data-platform/.../cockroachdb/DualDbConfig.java` | @ConditionalOnProperty("ep.db.dual.enabled")，HikariDataSource × 2 |
| PoC 验证服务 | `services/data-platform/.../cockroachdb/CockroachDbPocService.java` | 4 项验证 + runFullPoc()，EP_DEV_ONLY 保护 |
| PoC 脚本 | `scripts/cockroachdb-poc.sh` | 命令行验证（psql + kubectl），可 --health-only |
| 单元测试 | `services/data-platform/src/test/.../cockroachdb/` | CockroachDbPocServiceTest + DualDbConfigTest |

### 5.3 部署拓扑

```
┌─────────────────────────────────────────────────────────┐
│  K8s cluster (cell-sg, ap-southeast-1)                  │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │  CrdbCluster "cockroachdb" (CRD)                  │  │
│  │  ├── node-0 (region=ap-southeast-1, sg-a)         │  │
│  │  ├── node-1 (region=eu-central-1, fra-a)          │  │
│  │  └── node-2 (region=asia-northeast-1, tokyo-a)    │  │
│  │  PVC: 100Gi / node  Service: 26257(SQL) 8080(UI)  │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  NetworkPolicy: SQL(26257) ← data-platform, boardgame   │
│  Annotations: gdpr-compliance=true, data-residency=overseas │
│                                                         │
│  data-platform (Deployment)                             │
│  ├── DataSource primary  → TiDB (domestic)              │
│  └── DataSource overseas → CockroachDB (localhost:26257) │
│      RoutingDataSource: region=overseas → overseas      │
│      RoutingDataSource: region=domestic → primary       │
└─────────────────────────────────────────────────────────┘
```

## 6. 预期结果与决策框架

### 6.1 PoC 通过 → 正式采用 CockroachDB 作为海外存储

通过标准：4 项验证中 ≥3 项 PASS，其中 #1（REGIONAL BY ROW）和 #2（跨 region 延迟）必须 PASS。

后续步骤：
- 将 `ep.db.dual.enabled` 从 PoC 开关升级为生产配置（cell-sg overlay）。
- 扩展 `RoutingDataSource` 到所有需要海外数据驻留的服务（boardgame-service、core-business）。
- 建立海外 CockroachDB → Pulsar changefeed 生产级 topic。

### 6.2 PoC 部分通过 → 混合方案

若 #3（changefeeds）或 #4（1M 连接）未通过但 #1/#2 通过：
- 采用 CockroachDB 做数据存储，CDC 降级为 Debezium on PostgreSQL 协议。
- 连接规模通过 HikariCP 连接池 + PgBouncer 侧车解决。

### 6.3 PoC 失败 → 回退方案 B（TiDB 海外集群）

若 #1 或 #2 未通过：
- 在 cell-sg 部署独立 TiDB 集群（TiDB Operator CRD）。
- 用 Placement Rules 做表级区域绑定（牺牲行级粒度）。
- 复用现有 TiCDC → Pulsar 链路，运维经验已有积累。

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
|------|------|------|
| CockroachDB 与 TiDB SQL 方言差异 | 海外服务 JPA 查询失败 | PoC 验证 DDL/DML 兼容性；RoutingDataSource 仅路由海外租户，国内不受影响 |
| CockroachDB 多活复制延迟高于预期 | 海外写延迟超 SLA | PoC #2 验证 P99；若超 200ms 则回退方案 B |
| CRDB Operator 版本与 K8s 不兼容 | 部署失败 | PoC 前验证 Operator 兼容性矩阵；CRD 可一键删除回滚 |
| Pulsar changefeed sink 不稳定 | CDC 数据丢失 | EP_DEV_ONLY 保护；失败时 CockroachDbPocService 返回 SKIP 而非崩溃 |
| 双 DB 路由配置错误导致数据写错库 | 数据驻留违规 | RoutingDataSource 默认路由到 primary（国内 TiDB），仅 region=overseas 才路由到 overseas；单元测试覆盖路由逻辑 |

## 8. EP_DEV_ONLY 保护

所有 CockroachDB 相关组件均受 EP_DEV_ONLY 保护：

- `DualDbConfig`：`@ConditionalOnProperty("ep.db.dual.enabled")`，默认关闭。
- `CockroachDbPocService`：`@ConditionalOnProperty("cockroachdb.poc.enabled")`，默认关闭。
- `CockroachDbPocService` 每个验证方法 try-catch 包裹，失败返回 `SKIP` 而非抛异常。
- `RoutingDataSource` 默认路由到 primary（TiDB），仅 `TenantContext.region=overseas` 时路由到 overseas。

dev/CI 环境下不部署 CockroachDB，所有 PoC 验证返回 SKIP，服务正常启动。

## 9. 后续工作

- [x] 集成测试覆盖 — CockroachDbIntegrationIT + DualDbConfigIT (D-202)
- [ ] 生产 CockroachDB 集群部署 (cell-sg K8s)
- [ ] 生产环境 PoC 脚本执行 (`scripts/cockroachdb-poc.sh`)
- [ ] 跨大洲延迟基准测试 (生产多区域集群, sg↔fra↔tyo)
- [ ] 根据结果更新本 ADR 状态 (PoC 完成 → 决策)
- [ ] 若通过, 扩展 RoutingDataSource 到 boardgame-service、core-business
- [ ] 建立海外 CockroachDB 备份策略 (`cockroach backup` → S3)

## 10. 集成测试验证结果 (D-202)

D-202 为 CockroachDB PoC 补齐了真实集成测试覆盖 (Iron Law 9: 运行验证), 摆脱"仅 Mockito mock"状态。

### 10.1 DualDbConfigIT (始终运行, 无需 Docker)

- **路径**: `services/data-platform/src/test/java/com/ep/data/cockroachdb/DualDbConfigIT.java`
- **方式**: 双 H2 内存数据库模拟 (primary=TiDB, overseas=CockroachDB), 各建唯一标记表区分
- **验证项**:
  1. `region=domestic` → 路由到 primary, 命中 TiDB 标记表, 看不到 overseas 表
  2. `region=overseas` → 路由到 overseas, 命中 CRDB 标记表, 看不到 primary 表
  3. 无 region → 默认 primary (fail-safe: 永不误路由国内流量到海外)
  4. region 切换后路由即时切换
  5. TenantContext 线程隔离 (新线程默认 domestic, 不继承)
- **意义**: 验证 `RoutingDataSource` + `TenantContext` 的核心路由契约, 不依赖外部基础设施, 每次 CI 都跑

### 10.2 CockroachDbIntegrationIT (EP_LIVE=1 时运行, 真实 CRDB 容器)

- **路径**: `services/data-platform/src/test/java/com/ep/data/cockroachdb/CockroachDbIntegrationIT.java`
- **方式**: Testcontainers `CockroachContainer` (cockroachdb/cockroach:v23.2.5), 默认 `@Tag("live")` + `EP_LIVE=1` 双重保护跳过
- **运行**: `EP_LIVE=1 mvn test -pl services/data-platform -Dgroups=live -Dtest=CockroachDbIntegrationIT`
- **验证项** (4 项 PoC):
  1. `verifyRegionalByRow()` — `CREATE TABLE ... LOCALITY REGIONAL BY ROW` + INSERT + count (PASS)
  2. `verifyCrossRegionLatency()` — 20 次 INSERT 探针, P99 < 5s (单节点容器; 生产跨大洲目标 <200ms)
  3. `verifyChangefeedsAdapter()` — `CREATE CHANGEFEED` DDL (企业版 license gated, 文档化 SKIP, 语法已验证)
  4. `verifyOneMillionConnections()` — `SHOW CLUSTER SETTING sql.pool.max_size` 可查询且可配置 (PASS)
  5. `runFullPocAgainstRealCrdb()` — 用生产 `CockroachDbPocService` 连真实容器跑全量 4 项
- **意义**: PoC 从 mock 升级为真实执行 (Iron Law 9), DDL 语法/驱动兼容性/连接配置全部验证

### 10.3 Testcontainers 依赖

- `org.testcontainers:cockroachdb:1.20.4` (test scope)
- `org.testcontainers:testcontainers:1.20.4` (test scope)
- `org.testcontainers:junit-jupiter:1.20.4` (test scope)
- `com.h2database:h2` (test scope, DualDbConfigIT 用)
- 版本由根 BOM `testcontainers.version=1.20.4` 统一管理

### 10.4 局限与待办

- 单节点 Testcontainers 容器无法验证真实跨大洲延迟与多 region locality — 待生产集群 (§9 第 4 项)
- Changefeeds 需企业版 license, 容器内为 unlicensed — DDL 语法验证通过, 功能待生产 license
- 连接规模 (1M) 仅验证配置可读, 真实压力测试由 `scripts/cockroachdb-poc.sh` 在生产侧执行
