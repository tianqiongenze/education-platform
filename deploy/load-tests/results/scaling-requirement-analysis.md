# TiDB 扩容需求分析 — 从单节点瓶颈到生产分布式集群

> **输入报告**: `deploy/load-tests/results/1m-test-report.md` (测试日期 2026-08-08)
> **配套清单**: `deploy/k8s/base/tidb-cluster.yaml`
> **配套运维**: `deploy/docs/tidb-scaling-runbook.md`
> **关联决策**: D-027 (TiDB v8.5.5)、D-041 (多区域多活)、D-055、Iron Law 2 (读写分离)
> **分析日期**: 2026-08-08

---

## 1. 单节点 TiKV 瓶颈摘要

1M 并发压测 (D-079/D-080) 在 RPS 驱动模式下，系统实际仅达到 **587 RPS** 后触顶，远未达到 5,000 RPS 的阶梯目标。根本原因是 **TiKV 单节点行存读队列完全饱和**，所有 DB 依赖服务连锁崩溃。

### 1.1 关键指标

| 指标 | 1M (单节点) | 备注 |
|------|------------|------|
| 实际 RPS | **586.80** | 目标 5,000，达成率 11.7% |
| HTTP 错误率 | **99.88%** | 几乎全量失败 |
| 业务错误率 | **99.87%** | 与 HTTP 一致，非偶发 |
| 丢掉迭代数 | **1,858,923** | 排队丢弃，非拒绝 |
| BFF 课程 P95 | 7.81s | Dubbo 10s 超时边界 |
| sp05 作业错误 | 99.87% | DB 依赖服务先崩 |
| AI 服务错误 | 93.07% | 无 DB 服务也在 1M 级别退化 |
| Go 服务错误 | 52% | 最稳，但仍出现错误 |

### 1.2 趋势: 10K → 100K → 1M

| 指标 | 10K | 100K | 1M | 增长 |
|------|-----|------|-----|------|
| 实际 RPS | 220 | 430 | 587 | 仅 2.7× |
| HTTP 错误率 | 72.89% | 78.33% | 99.88% | 持续恶化 |
| DB 服务错误 (sp05) | 100% | 99.30% | 99.87% | 全级别崩溃 |
| 无 DB 服务错误 | 0% | 0% | 52~93% | 仅在 1M 退化 |

**核心结论**: TiKV 单节点是系统的**硬天花板**。RPS 从 10K 到 1M 仅增长 2.7 倍，验证了线性扩展假设不成立——单 store 的 Raft + RocksDB 写路径无法横向吸收并发。详见 `1m-test-report.md` 第 80~87 行。

---

## 2. 生产目标拓扑

新建集群采用 TiDB Operator 管理的分布式拓扑，分散单点瓶颈，并原生支持 D-041 跨 region 读与 Iron Law 2 读写分离。

| 组件 | 副本 | 单节点规格 | 角色 | 解决的问题 |
|------|------|-----------|------|-----------|
| **PD** | 3 | 1C / 2G / 20G | 调度 + 元数据 | HA 法定人数 |
| **TiKV** | 3 (起步, 可扩 5~7) | 4C / 16G / 500G SSD | 行存 OLTP | **突破 587 RPS 瓶颈** |
| **TiDB** | 2 | 2C / 4G | SQL 入口 | 无状态水平扩展 |
| **TiFlash** | 2 | 4C / 16G / 500G SSD | 列存 OLAP | **Iron Law 2 读写分离** |
| **TiCDC** | 2 | 2C / 4G | 跨 region 复制 | **D-041 多区域多活** |

### 2.1 容量预估

- **TiKV 行存**: 3 × 500G = 1.5TB 可用 (Raft 3 副本后有效容量 ≈ 500GB)
- **TiFlash 列存**: 2 × 500G = 1TB (列存压缩后有效容量 ≈ 800GB)
- **预期吞吐**: 3 TiKV × 587 RPS ≈ **1,760 RPS 基线**；扩 5 节点 ≈ 2,900 RPS；扩 7 节点 ≈ 4,100 RPS (满足 1M 压测 3,000 RPS 目标)

### 2.2 高可用

- **PD**: 3 副本 Raft，容忍 1 节点宕机
- **TiKV**: region 3 副本跨可用区 (`topology.kubernetes.io/zone` `DoNotSchedule maxSkew=1`)，容忍单 zone 故障
- **TiDB**: 2 副本无状态，Service 自动负载均衡
- **TiFlash**: 2 副本列存，表级 `TIFLASH REPLICA 2`

---

## 3. 成本估算 (单 region)

基于 AWS 实例类型，每节点规格对齐 K8s manifest 的 Guaranteed QoS 资源配额。

| 组件 | 实例类型 | 数量 | 单价 (月) | 小计 (月) |
|------|---------|------|----------|----------|
| TiKV | c5.4xlarge (16C/32G) + 500GB gp3 | 3 | ~$1,100 | ~$3,300 |
| TiFlash | r5.4xlarge (16C/128G) + 500GB gp3 | 2 | ~$1,000 | ~$2,000 |
| PD | c5.xlarge (4C/8G) + 20GB gp3 | 3 | ~$170 | ~$510 |
| TiDB | c5.large (2C/4G) | 2 | ~$90 | ~$180 |
| TiCDC | c5.large (2C/4G) | 2 | ~$90 | ~$180 |
| 存储 (gp3 SSD 含 IOPS) | — | — | — | ~$1,800 |
| **单 region 小计** | | | | **~$4,500/月** |

> **说明**: 单 region 约 **$4,500/月**，与任务基线一致。
> TiKV 选计算优化型 (c5) 因 OLTP CPU 密集；TiFlash 选内存优化型 (r5) 因列存聚合需大内存。
> 上表为 on-demand 价格，采用 1 年 RI / Savings Plan 可降 30~40% 至 ~$2,800/月。

---

## 4. D-041 多区域多活映射

D-041 要求每个 region 独立可读写、跨 region 数据同步。TiDB 集群拓扑如下：

```
   Region: cn-east-1 (杭州)          Region: cn-south-1 (深圳)
   ┌────────────────────┐            ┌────────────────────┐
   │ education-tidb-hz  │            │ education-tidb-sz  │
   │ PD3 TiKV3 TiDB2    │◀── TiCDC ─▶│ PD3 TiKV3 TiDB2    │
   │ TiFlash2 TiCDC2    │   双向同步   │ TiFlash2 TiCDC2    │
   └────────────────────┘            └────────────────────┘
            │                                  │
            └────────── GSLB (edu.example.com) ─┘
                  就近路由 + 故障切换
```

- **每 region 独立集群**: 各部署一份 `tidb-cluster.yaml`，实例名按 cell 区分 (`education-tidb-hz` / `education-tidb-sz`)。
- **跨 region 复制**: TiCDC 双向 changefeed，对齐 `configmap.yaml` 中 `PULSAR_REPLICATION_CLUSTERS: hz,sz,bj` 的多活语义。
- **跨 region 读**: 每集群内启用 Follower Read（`SET tidb_replica_read = 'leader-and-follower'`），本地 follower 承接读，避免跨 region 延迟。`configmap.yaml` 已预置 `TIDB_FOLLOWER_READ: "true"`。
- **成本翻倍**: 2 region ≈ $9,000/月；3 region ≈ $13,500/月。

---

## 5. Iron Law 2 读写分离实现

| 读类型 | 引擎 | 账号 | 说明 |
|--------|------|------|------|
| OLTP 读 (业务实时) | TiKV 行存 | `app_rw` | 走 leader/follower，强一致 |
| OLAP 读 (报表/统计) | TiFlash 列存 | `app_ro` | 走列存，最终一致，不挤占 OLTP |

账号在 `tidb-backup-schedule.yaml` 的 `TidbInitializer` 中创建。应用通过 `app_ro` 连接执行分析查询，物理隔离 OLTP 写入，根治 1M 压测中"分析查询打满行存读队列 → sp05 崩溃"的链路。

---

## 6. 与现有 K8s 基线的集成点

| 现有资源 | 关系 |
|---------|------|
| `deploy/k8s/base/configmap.yaml` | `TIDB_JDBC_URL` 指向 `tidb.edu.svc:4000`；`TIDB_FOLLOWER_READ=true` 已预置 |
| `deploy/k8s/base/kustomization.yaml` | 已追加 `tidb-cluster.yaml`/`tidb-monitor.yaml`/`tidb-backup-schedule.yaml`；`namePrefix=edu-` 会把实例名前缀化 |
| `deploy/k8s/base/networkpolicy.yaml` | 需放行 `app_rw`/`app_ro` → `edu-tidb-svc:4000` (后续 PR) |
| `deploy/k8s/base/sp03-business.yaml` / `sp05-itbackend.yaml` | DB 依赖服务，迁移后受益最大 (sp05 错误率预期从 99.87% 降至 <0.1%) |

> **注意 (实施时处理)**: kustomization `namePrefix=edu-` 把 Service 命名为 `edu-tidb-svc`，而 `configmap.yaml` 中 `TIDB_JDBC_URL` 写死 `tidb.edu.svc`。迁移时需新增 ExternalName Service 桥接，或更新 ConfigMap 指向 `edu-tidb-svc`。

---

## 7. 行动项

- [x] 创建 `deploy/k8s/base/tidb-cluster.yaml` (生产集群 CRD)
- [x] 创建 `deploy/k8s/base/tidb-monitor.yaml` (Prometheus + Grafana)
- [x] 创建 `deploy/k8s/base/tidb-backup-schedule.yaml` (全量 + 增量)
- [x] 更新 `deploy/k8s/base/kustomization.yaml`
- [x] 创建 `deploy/docs/tidb-scaling-runbook.md` (运维手册)
- [ ] **待办**: 部署 TiDB Operator v1.5+ 到集群
- [ ] **待办**: 创建 `s3-secret` / `tidb-init-secret` (External Secrets)
- [ ] **待办**: 更新 NetworkPolicy 放行 TiDB 端口
- [ ] **待办**: 桥接 `tidb.edu.svc` → `edu-tidb-svc` (DNS)
- [ ] **待办**: 单节点 → 集群数据迁移 (Dumpling + Lightning + CDC)
- [ ] **待办**: 重跑 1M 压试验证 ≥3,000 RPS

---

## 参考

- 单节点瓶颈原始数据: `deploy/load-tests/results/1m-test-report.md`
- 集群 CRD 清单: `deploy/k8s/base/tidb-cluster.yaml`
- 运维手册: `deploy/docs/tidb-scaling-runbook.md`
- 应用配置 (Follower Read): `deploy/k8s/base/configmap.yaml` (第 41~43 行)
- TiDB Operator 文档: https://docs.pingcap.com/tidb-in-kubernetes/
