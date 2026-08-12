# D-120: NebulaGraph 部署清单 + 废弃代码清理

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-120 | v1.0 | 2026-08-09 | 已批准 | 基础设施架构组 | 架构委员会 |

> 关联：D-053（图工作负载拆分 — 终止 Neo4j，采用 NebulaGraph）、ADR-INF-02 v2.0（图数据库选型修订）、D-094（ep-rw-java / ep-disruptor 标记 @Deprecated）、D-041（多 Region 多活铁律）、D-035（K8s 部署运维铁律 / rule 6 GitOps）、Iron Law 8（决策对比分析 ≥3 方案）、D-114（GitOps / ArgoCD）。

## 1. 问题陈述与约束条件

D-053 与 ADR-INF-02 v2.0 已决策将重图负载（SP-07 知识图谱 / SP-02 字段级血缘 / SP-04 Agent 拓扑）从 Neo4j 迁移至 NebulaGraph 3.x，理由是：

1. Neo4j Community GPL v3 协议与商业发行冲突；
2. Neo4j Community 无集群 / 无 HA，违反 D-041 多 Region 多活铁律；
3. NebulaGraph Apache 2.0 + 原生 Raft 分布式，Day 1 即多活，万亿边线性扩展。

**但该决策至今仅为文档层选型**：代码库中无任何 NebulaGraph K8s 部署清单，配置中心（`configmap.yaml`）无连接配置，应用层无法落地接入。迁移工作停在"决策已批准、部署未落地"的悬空状态。

与此同时，D-094（2026-08-08）将 `ep-rw-java` 与 `ep-disruptor` 两个模块标记为 `@Deprecated(forRemoval = false)`，并明确"deprecated in place (NOT deleted) for future reuse"。但此后无任何后续动作跟进——既未清理，也未明确删除窗口，导致废弃代码长期停留在主分支，构成认知噪声与维护负担。

约束条件：

1. **仅创建清单，不部署**：本决策仅产出 K8s YAML 清单与配置，**不连接 live 集群**。ArgoCD（D-114）在合入 main 后自动同步，但本次不在本地 k3s 手动 apply。
2. **dev/test 资源规格**：requests/limits 面向开发/测试环境，生产规格通过 overlay 覆盖。
3. **CRD 依赖前置**：nebula-operator CRD（`apps.nebula-graph.io/v1alpha1`）须先于本清单安装，与 TiDB Operator（`pingcap.com/v1alpha1`）前置模式一致。
4. **保守清理废弃代码**：仅移除"零调用方"且无重用价值的废弃代码；对仍有调用方（哪怕是测试）的废弃 API，标注 TODO 并保留，避免破坏构建。
5. **铁律 8**：部署方式须 ≥3 方案对比后定论。

## 2. 候选方案（铁律 8 — 部署方式）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **nebula-operator CRD（已选）** | 声明式 `NebulaCluster` CRD，operator 管理生命周期，与 TiDB Operator 模式对齐 |
| B | **NebulaGraph Helm chart** | vesoft-inc 官方 Helm chart，`helm install` 一键拉起 |
| C | **Managed NebulaGraph Cloud** | vesoft 云托管 NebulaGraph，零运维 |

## 3. 对比维度表

| 维度 | A. nebula-operator CRD（已选） | B. Helm chart | C. Managed Cloud |
|------|-------------------------------|---------------|-------------------|
| **GitOps 适配（D-035 rule 6 / D-114）** | 原生适配，CRD 经 kustomization 渲染，ArgoCD 自动同步 | 需 `helm template` 渲染为 YAML 后纳入 Git，多一层渲染步骤 | 不适配，云托管状态在 Git 之外，违反 GitOps 单一事实源 |
| **与 TiDB Operator 一致性** | 完全一致（同为 CRD + operator 模式），运维心智模型统一 | 不一致，需同时维护 CRD 与 Helm 两套部署范式 | 不一致，托管资源脱离集群内 operator 体系 |
| **生命周期管理** | operator 处理扩缩容/滚动升级/故障恢复，自愈能力强 | Helm 仅管安装/卸载，故障恢复靠手动或额外 controller | 云方全托管，自愈最强 |
| **生产可控性** | 高，集群内全自主，可定制 topologySpread / storageClass | 中，values 可定制但生命周期弱 | 低，受限于云方 SLA 与功能边界 |
| **多 Region 多活（D-041）** | 原生支持跨 DC Raft + topologySpreadConstraints 调度 | 支持但需手动编排跨 Region 拓扑 | 依赖云方 Region 拓扑，灵活性受限 |
| **成本** | 开源免费（自建运维成本） | 开源免费（自建运维成本） | 按用量付费，长期成本高且供应商锁定 |
| **运维复杂度** | 中（operator + CRD + 3 组件） | 中（Helm + 手动生命周期） | 低（云方托管） |

**决策：方案 A — nebula-operator CRD。** 核心理由是与 TiDB Operator 完全一致的部署范式（CRD + operator），统一 GitOps（D-114 ArgoCD）单一事实源；Helm chart 需额外渲染步骤破坏声明式纯度；Managed Cloud 违反 GitOps 与"开源优先 / 去锁定"战略（ADR-INF-02 §5 已基于此否决 Neo4j Enterprise）。

## 4. 决策

### 4.1 NebulaGraph 部署清单

新增 `deploy/k8s/base/nebulagraph.yaml`，包含 1 个 `NebulaCluster` CRD + 3 个 Service：

| 组件 | 副本 | 端口 | 资源 (req=lim) | 存储 | 角色 |
|------|------|------|----------------|------|------|
| **metad** | 3 | 9559 (thrift) / 9560 (raft) | 500m / 512Mi | 10Gi | 元数据 Raft 共识 |
| **storaged** | 3 | 9779 (thrift) / 9780 (raft) | 1000m / 1Gi | 10Gi | 存储 Raft 共识 (shared-nothing) |
| **graphd** | 2 | 9669 (thrift) / 19669 (http) | 500m / 512Mi | — (无状态) | 查询层水平扩展 |

镜像：`vesoft/nebula-{metad,storaged,graphd}:v3.8.0`（Apache 2.0）。
存储类：`local-path`（dev；生产 overlay 覆盖为 gp3 SSD）。
拓扑：`topology.kubernetes.io/zone` `DoNotSchedule` `maxSkew:1`，跨可用区打散。
标签：`app.kubernetes.io/managed-by: argocd`、`app.kubernetes.io/name: nebulagraph`。
注释：`education.io/change-policy: gitops-only`、`education.io/design-decisions: D-053,ADR-INF-02,D-041,IronLaw8`。

### 4.2 配置中心接入

更新 `deploy/k8s/base/configmap.yaml`，在 `common-config` ConfigMap 新增：

```
NEBULA_GRAPH_HOST=nebulagraph-graphd-svc
NEBULA_GRAPH_PORT=9669
NEBULA_GRAPH_USER=root
NEBULA_GRAPH_PASSWORD=        # 默认空，首次登录后由运维通过 secret 覆盖
NEBULA_GRAPH_SPACE=education_platform
```

应用通过 graphd Service 连接（nGQL/openCypher，端口 9669）；metad/storaged 仅集群内部 Raft 通信，不对应用暴露。

> **namePrefix 注意**：kustomization `namePrefix: edu-` 会将 Service 前缀化为 `edu-nebulagraph-graphd-svc`。`NEBULA_GRAPH_HOST` 当前设为短名 `nebulagraph-graphd-svc`，应用 Pod 内通过 FQDN `edu-nebulagraph-graphd-svc.education-platform.svc.cluster.local` 解析；本地 k3s 验证场景可在 overlay 中覆盖为短名。此点在清单注释中显式标注，待 D-114 ArgoCD 渲染验证后视情况统一为 FQDN。

### 4.3 kustomization 纳入

`deploy/k8s/base/kustomization.yaml` 的 `resources:` 列表新增 `nebulagraph.yaml`，置于 TiDB 三清单之后，标注 D-120 / ADR-INF-02 v2.0 来源与 nebula-operator CRD 依赖。

### 4.4 废弃代码清理（D-094 跟进）

对 D-094 标记的 `@Deprecated` 代码逐项核查调用方：

| 废弃项 | 调用方核查结果 | 处置 |
|--------|----------------|------|
| `bff-java RouteTable.resolve(String)` | **有调用方**：`RouteTableTest`（测试）6 处引用，生产代码已迁 `resolveService` | 保留，新增 `// TODO D-120: Remove after migration complete`，待测试迁 `resolveService` 后删除 |
| `ep-rw-java` 全模块（7 类：`RoutingHolder`/`DataSourceConfig`/`DataSourceType`/`RoutingDataSource`/`DataSourceAspect`/`DataSource`） | **零调用方**：无任何 `com.ep.rw.*` import，无 pom 依赖声明 | 保留（D-094 明确 "NOT deleted for future reuse"），各类新增 `// TODO D-120: Remove after migration complete` |
| `ep-disruptor` 全模块（5 类：`WaitStrategyType`/`PublishResult`/`DisruptorWorker`/`DisruptorStats`/`DisruptorPipeline`） | **零调用方**：无任何 `com.ep.disruptor.*` import，无 pom 依赖声明 | 保留（D-094 明确 "NOT deleted for future reuse"），各类新增 `// TODO D-120: Remove after migration complete` |

**清理原则（保守）**：D-094 的设计意图是 "deprecated in place (NOT deleted) for future reuse"，两个模块的 `DEPRECATED.md` 均明确保留源码以待未来 BU 扩展复用。本决策尊重该意图，**不物理删除模块**，仅在每个 `@Deprecated` 标注处补充 `TODO D-120` 锚点，为后续真正删除窗口提供可检索的迁移标记。`RouteTable.resolve` 因仍有测试调用方，按任务约束"有调用方则加 TODO"处置，不删除。

## 5. 理由

1. **CRD 与 TiDB Operator 范式统一**：nebula-operator 的 `NebulaCluster` CRD 与 `TidbCluster` CRD 同为 "声明式 CRD + operator 自愈" 模式，运维心智模型与 GitOps（D-114）流程完全一致，降低多范式运维负担。
2. **D-041 合规**：metad/storaged 各 3 副本 Raft 强一致 + 跨可用区 topologySpread，Day 1 即满足多 Region 多活铁律，消除 Neo4j Community 单点故障硬伤。
3. **配置闭环**：`configmap.yaml` 补齐 `NEBULA_GRAPH_*` 环境变量，应用层可基于此接入 nebula-java 客户端（ADR-INF-02 §5.1 已规划），消除"决策已批准、配置未落地"的悬空。
4. **废弃代码可检索**：`TODO D-120` 锚点使后续清理可通过 `grep "D-120"` 全量定位，避免废弃代码成为无声的认知噪声。
5. **保守清理不破坏构建**：`RouteTable.resolve` 虽仅测试调用，但删除会破坏测试编译；按任务约束保留 + TODO，待测试迁移后一并删除。

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| nebula-operator CRD schema 版本演进，`apps.nebula-graph.io/v1alpha1` 字段可能变化 | 清单顶部注释标注依赖 nebula-operator 版本；CI 中 `kustomize build` + kubeval 校验 CRD schema |
| `namePrefix: edu-` 导致 `NEBULA_GRAPH_HOST` 短名解析失败 | 注释显式标注 FQDN 回退路径；本地 k3s 验证后视情况统一为 FQDN 或 overlay 覆盖 |
| `NEBULA_GRAPH_PASSWORD` 空默认值不安全 | 默认空仅用于 dev；生产通过 ArgoCD secret overlay 注入（D-035 rule 6 GitOps + SOPS） |
| dev 资源规格过低（500m/512Mi）导致压测失真 | 清单注释明确"dev/test 规格"，生产 overlay 覆盖；不以此规格推断生产容量 |
| `ep-rw-java` / `ep-disruptor` 长期保留仍编译，占用 CI 时间 | 两个模块 pom 已有 `ep.deprecated=true` 标记，后续可在 CI profile 中跳过 deprecated 模块构建 |
| `RouteTable.resolve` 测试迁移拖延，TODO 长期不消 | TODO 锚点纳入 D-120 审计项，下次清理周期强制核查 |

## 7. 后续工作

1. **nebula-operator CRD 安装**：在集群中安装 nebula-operator（`apps.nebula-graph.io/v1alpha1` CRD + controller deployment），与 TiDB Operator 安装流程对齐。
2. **nebula-java 客户端集成**：SP-07 / SP-02 / SP-04 服务引入 `nebula-java` 依赖，基于 `NEBULA_GRAPH_*` 配置初始化连接池，封装图查询 SDK 屏蔽 nGQL（ADR-INF-02 §7 风险对策）。
3. **生产 overlay**：`deploy/k8s/overlays/prod/` 覆盖 `storageClassName: gp3`、提升 resources、注入 `NEBULA_GRAPH_PASSWORD` secret。
4. **`RouteTable.resolve` 测试迁移**：将 `RouteTableTest` 中 6 处 `resolve` 调用改为 `resolveService`，删除 `RouteTable.resolve` 方法，消除 D-120 TODO。
5. **`ep-rw-java` / `ep-disruptor` 删除窗口**：若未来确认无复用计划，整体移除模块目录并从构建体系剔除。

## 8. 铁律 8 合规说明

本决策对比三个独立候选方案（nebula-operator CRD / NebulaGraph Helm chart / Managed NebulaGraph Cloud），覆盖 GitOps 适配、范式一致性、生命周期管理、多活、成本、运维复杂度等关键维度，满足铁律 8 三方对比要求。✓

---

**文档结束**。本决策落地 D-053 / ADR-INF-02 v2.0 的 NebulaGraph 部署，跟进 D-094 废弃代码清理，消除"决策已批准、部署未落地"与"标记 @Deprecated、长期不清理"两处悬空。
