# TiDB 集群扩缩容运维手册

> **适用范围**: 教育平台生产 TiDB 集群 (`education-tidb` / namespace `education-platform`)
> **目标**: 解决 1M 并发压测 (D-079/D-080) 暴露的单 TiKV 节点 587 RPS 瓶颈。
> **配套清单**: `deploy/k8s/base/tidb-cluster.yaml`、`tidb-monitor.yaml`、`tidb-backup-schedule.yaml`
> **关联决策**: D-027 (TiDB v8.5.5)、D-041 (多区域多活)、D-055、Iron Law 2 (读写分离)
> **最后更新**: 2026-08-08

---

## 1. 架构概览

```
                ┌─────────────────────────────────────────┐
   应用层 ──▶   │  TiDB SQL ×2  (无状态, port 4000)        │
 (BFF/sp03/    │  对齐 configmap.yaml: tidb.edu.svc:4000  │
  sp05)        └──────────────────┬──────────────────────┘
                                  │
            ┌─────────────────────┼─────────────────────┐
            ▼                     ▼                     ▼
     ┌─────────────┐       ┌─────────────┐       ┌─────────────┐
     │  TiKV ×3    │       │  TiFlash ×2 │       │  TiCDC ×2   │
     │  行存 OLTP  │       │  列存 OLAP  │       │  跨region复制│
     │  4C/16G/SSD │       │  4C/16G/SSD │       │  2C/4G      │
     │  (3 zone)   │       │  (2 zone)   │       │  (2 zone)   │
     └──────┬──────┘       └─────────────┘       └─────────────┘
            │ region Raft (max-replicas=3)
            ▼
     ┌─────────────┐
     │  PD ×3      │  Raft 共识 + 调度 + Dashboard
     │  1C/2G      │  (3 zone, DoNotSchedule maxSkew=1)
     └─────────────┘
```

| 组件 | 副本 | 角色 | 关键能力 |
|------|------|------|----------|
| PD | 3 | 调度/元数据 | Raft HA, region 调度 |
| TiKV | 3 | 行存 | OLTP 写入, Follower Read 源 |
| TiDB | 2 | SQL | 协议入口 (MySQL 兼容 4000) |
| TiFlash | 2 | 列存 | 分析读 (Iron Law 2) |
| TiCDC | 2 | 复制 | 跨 region 变更同步 (D-041) |

---

## 2. 前置条件

- [ ] **TiDB Operator v1.5+ 已安装**: 提供 `TidbCluster`/`TidbMonitor`/`BackupSchedule` CRD。
  ```bash
  helm install tidb-operator pingcap/tidb-operator \
    --namespace tidb-admin --create-namespace \
    --version "v1.5.x"
  kubectl get crd | grep pingcap.com   # 预期 5 个 CRD
  ```
- [ ] **K8s 集群**: ≥3 worker 节点，每节点 ≥16 vCPU / 64Gi（满足 TiKV 4C×3 + TiFlash 4C×2 + PD/TiDB/TiCDC 共存仍有余量）。
- [ ] **StorageClass**: dev `local-path`、生产 `gp3`（SSD，IOPS ≥3000）。`local-path` 仅限 dev，生产必须 gp3。
- [ ] **凭据 Secret**: `s3-secret`（备份）、`tidb-init-secret`（初始化密码）由 External Secrets / Sealed Secrets 注入。
- [ ] **节点标签**: worker 节点已打 `topology.kubernetes.io/zone` 与 `ep.io/cell` 标签，否则 `topologySpreadConstraints` 无法生效。

---

## 3. 部署

```bash
# 1) 部署完整基线（含 TiDB 集群、监控、备份）
kubectl apply -k deploy/k8s/base/

# 2) 观察 Pod 起床顺序: PD 先就绪 → TiKV/TiFlash 拉起 → TiDB/TiCDC 最后
kubectl get pods -n education-platform -l app.kubernetes.io/instance=education-tidb -w

# 3) 确认全部 Ready（PD/TiKV 约需 3~5 分钟首次选举）
kubectl get tidbcluster education-tidb -n education-platform
# 期望: READY 各组件全 True

# 4) 连通性验证（使用初始化账号）
kubectl exec -it deploy/edu-sp03-business -n education-platform -- \
  mysql -h edu-tidb-svc -P 4000 -u app_rw -p -e "SELECT version();"
# 期望: 8.5.5
```

> **注意**: kustomization `namePrefix=edu-` 会把实例名变为 `edu-education-tidb`、Service 变为 `edu-tidb-svc`。
> `configmap.yaml` 中 `TIDB_JDBC_URL` 指向 `tidb.edu.svc:4000`，需通过 ExternalName Service 或修改该 ConfigMap 指向 `edu-tidb-svc`。

---

## 4. 扩缩容 TiKV

> **触发时机**: TiKV CPU 持续 >80%、P95 延迟 >500ms，或为新一轮压测预留容量。

**扩容 (3 → 5 节点)**：
```bash
# 编辑 Git 仓库中的 tidb-cluster.yaml，修改 spec.tikv.replicas: 5，提交 PR。
# 经 ArgoCD 同步后:
kubectl patch tidbcluster education-tidb -n education-platform \
  --type merge -p '{"spec":{"tikv":{"replicas":5}}}'   # 紧急情况临时操作（需事后回填 Git）

# 监控 region 再平衡（关键步骤，避免数据热点）
watch -n5 'kubectl exec -n education-platform edu-education-tidb-pd-0 -- \
  /pd-ctl store -u http://127.0.0.1:2379 | jq ".stores | length"'
# 期望 store 数从 3 涨到 5；region leader 分布趋于均匀
```

| 副本数 | 预期 RPS | 再平衡耗时 (500GB) | 适用场景 |
|--------|----------|-------------------|----------|
| 3 | ~1,700 | — | 生产基线 |
| 5 | ~2,900 | 30~60 min | 高峰扩容 |
| 7 | ~4,100 | 60~120 min | 1M 压测 |

**缩容 (5 → 3 节点)**：
```bash
# 1) 缩容前必须确认剩余副本数 ≥ max-replicas(=3)，否则数据不可用！
# 2) PD 会先迁移该节点上的 region，完成后才下线 Pod
kubectl patch tidbcluster education-tidb -n education-platform \
  --type merge -p '{"spec":{"tikv":{"replicas":3}}}'
# 监控直到对应 Pod 进入 Tombstone 才可判定完成
```

> **禁忌**: 永远不要直接 `kubectl delete pod` TiKV，必须通过 replicas 走 PD 优雅下线，否则触发 region 副本不足告警。

---

## 5. 启用 Follower Read (D-041 跨 region 读)

> **原理**: 默认所有读走 region leader。跨 region 部署时 leader 可能在远端 cell，读延迟高。
> Follower Read 让本地 follower 副本直接承接读，延迟从跨 region (50~150ms) 降到本地 (<5ms)。

**会话级开启**（验证用）：
```sql
-- 已通过 app_ro 账号连接 TiDB
SET tidb_replica_read = 'leader-and-follower';
SELECT @@tidb_replica_read;   -- 期望: leader-and-follower
-- 后续 SELECT 自动均衡到 follower
```

**应用级开启**（生产推荐）：
```yaml
# configmap.yaml 已预置（无需新增）:
#   TIDB_FOLLOWER_READ: "true"
#   TIDB_FOLLOWER_READ_STRATEGY: "follower-read"
```
应用在连接初始化时执行 `SET GLOBAL tidb_replica_read = 'leader-and-follower'` 或 JDBC URL 追加 `&followerRead=true`。

**验证 follower 承接读**：
```bash
# TiDB Dashboard → Statement Analysis，观察读请求在 TiKV 节点间均匀分布
# 或 PD metrics: tikv_grpc_msg_duration_seconds{type="coprocessor"} 在所有 store 量级接近
```

---

## 6. TiFlash 列存引擎 (Iron Law 2 读写分离)

> **目标**: OLTP 写走 TiKV 行存，分析/报表读走 TiFlash 列存，二者物理隔离，
> 杜绝 1M 压测中分析查询把行存读队列打满导致 sp05 全面崩溃。

**为分析表建立 TiFlash 副本**：
```sql
-- 按表建立列存副本（2 副本，与 tiflash.replicas 对齐）
ALTER TABLE education.homework_submission
  SET TIFLASH REPLICA 2;

-- 按库批量（谨慎，大表同步耗时）
ALTER TABLE education.course_summary
  SET TIFLASH REPLICA 2;

-- 查询同步进度
SELECT * FROM information_schema.tiflash_replica WHERE TABLE_SCHEMA='education';
-- AVAILABLE=1 表示列存就绪
```

**强制读行走 TiFlash**（HTAP，自动）：
```sql
-- TiDB 优化器默认按代价自动选择 TiFlash。强制走列存:
SET tidb_isolation_read_engines = 'tiflash,tidb';   -- 仅读 TiFlash
SELECT COUNT(*), AVG(score) FROM education.homework_submission
  WHERE create_at > NOW() - INTERVAL 1 DAY;          -- 该聚合自动走列存
```

> **容量规划**: TiFlash 副本 = 表数据量 × 2。500GB 表需 1TB TiFlash 存储，扩容前先扩 `tiflash.replicas` 或单节点 storage。

---

## 7. 从单节点迁移到集群

> 现状: 生产 TiDB 以裸金属单节点运行（1M 压测的瓶颈来源）。
> 目标: 迁移到本清单定义的 K8s TiDB Operator 集群。

```
[单节点 TiDB] --dumpling--> [S3 全量] --lightning--> [K8s 集群]
       │                                                  │
       └────────── CDC 增量 ──────────────────────────────┘
                                                          │
                                              [切 DNS, 观察流量] ──▶ [下线单节点]
```

```bash
# 1) 全量导出 (Dumpling)
tiup dumpling -h <单节点IP> -P 4000 -u root -t 8 -F 256MiB \
  -o s3://ep-tidb-backup-hz/migration/full/

# 2) 全量导入 (Lightning，直连 TiKV 加速)
tiup tidb-lightning --tidb-host edu-tidb-svc --tidb-port 4000 \
  --backend tidb --data-source s3://ep-tidb-backup-hz/migration/full/

# 3) 增量同步 (CDC，从 binlog checkpoint 到新集群)
tiup cdc cli changefeed create \
  --server http://<单节点PD>:2379 \
  --sink-uri "mysql://app_rw@edu-tidb-svc:4000" \
  --start-dumpling-position <dumpling完成时的TSO>

# 4) 校验数据一致 (sync-diff-inspector)
tiup sync-diff-inspector --config sync.toml   # 报告差异率应为 0

# 5) 切换 DNS: tidb.edu.svc 由单节点指向 edu-tidb-svc
#    先灰度 10% 流量观察 30min，再全量切

# 6) 确认无回归后下线单节点（保留 7 天回滚窗口）
```

---

## 8. 1M 并发压测验证

> **目的**: 验证新集群突破 587 RPS 瓶颈，达到 ≥3,000 RPS（5 倍提升目标）。

```bash
# 1) 压测前快照备份（防止脏数据）
kubectl apply -f deploy/k8s/base/tidb-backup-schedule.yaml

# 2) 分布式压测: 10 个 k6 节点 × 每节点 100k VU
cd deploy/load-tests/
for i in $(seq 1 10); do
  k6 run --out influxdb=http://k6-influx:8086 \
    --vus 100000 --duration 12m \
    scenarios/million-user-simulation.js &
done; wait

# 3) 关键指标验收门槛
```

| 指标 | 1M 单节点 (基线) | 1M 集群目标 | 判定 |
|------|-----------------|------------|------|
| 实际 RPS | 587 | ≥ 3,000 | 必须 |
| HTTP 错误率 | 99.88% | < 1% | 必须 |
| BFF 课程 P95 | 7.81s | < 1s | 必须 |
| sp05 作业错误率 | 99.87% | < 0.1% | 必须 |
| TiKV CPU | 100% 单点 | < 60% 均值 | 推荐 |

报告输出到 `deploy/load-tests/results/1m-test-report-cluster.md`，与单节点报告对比。

---

## 9. 故障排查

| 现象 | 可能原因 | 排查命令 | 处置 |
|------|---------|---------|------|
| Pod 卡 Pending | StorageClass 未挂载/PVC 不满足 topologySpread | `kubectl describe pod <pd-0>` 看 Events | 检查 StorageClass、节点 zone 标签 |
| PD 不可用 / leader 抖动 | PD 副本 < quorum(2) | `pd-ctl members` | 立即恢复失败的 PD Pod，勿删多数派 |
| TiKV region 不均 | 新节点上线未再平衡 | `pd-ctl store`，看 leader 差异 | `pd-ctl scheduler add balance-leader-scheduler` |
| TiFlash OOM | 列存查询内存超限 | `kubectl logs <tiflash-0>` 搜 `OOM` | 调大 `max_memory_usage` 或扩 `tiflash.replicas` |
| 读延迟陡升 | Follower Read 命中未同步副本 | `information_schema.tiflash_replica` AVAILABLE | 等 AVAILABLE=1 再切流量 |
| 备份失败 | s3-secret 过期 / MinIO 不可达 | `kubectl logs <backup-job>` | 轮换 Secret，确认 endpoint 网络 |
| TiCDC lag 飙升 | 跨 region 链路抖动 | `ticdc cli changefeed list` lag(ms) | 临时降并发，检查 sort-engine 落盘 |

**通用诊断入口**：
```bash
# TiDB Dashboard（PD 内嵌）
kubectl port-forward -n education-platform svc/edu-education-tidb-pd 2379:2379
open http://localhost:2379/dashboard    # 初始账号: root / 无密码(改!)

# 各组件日志
kubectl logs -n education-platform -l app.kubernetes.io/component=tikv --tail=200
```

---

## 10. 监控告警

Grafana 面板由 `tidb-monitor.yaml` 自动部署，端口转发：
```bash
kubectl port-forward -n education-platform svc/edu-education-tidb-monitor-grafana 3000:3000
open http://localhost:3000   # → TiDB Overview / Performance / Disk I/O
```

**关键告警规则**（Prometheus，对齐 §9 故障）：

| 告警 | 表达式 | 级别 | 处置章节 |
|------|--------|------|---------|
| TiKV CPU 高 | `avg(rate(tikv_grpc_msg_duration_seconds_count[5m])) by (instance) > 0.8` | P2 | §4 扩容 |
| region 倾斜 | `max(tidb_tikv_region_count) / min(tidb_tikv_region_count) > 1.3` | P2 | §9 |
| PD down | `count(up{job="pd"} == 0) >= 1` | P1 | §9 立即恢复 |
| TiFlash OOM 预警 | `tiflash_memory_usage / tiflash_memory_limit > 0.9` | P2 | §9 |
| 备份失败 | `tidb_backup_status{phase="failed"} == 1` | P1 | §9 |
| TiCDC lag 大 | `ticdc_changefeed_lag_ms > 60000` | P2 | §9 |

**告警通道**: 经 otel-collector / Alertmanager → 飞书 P1 群 + PagerDuty。

---

## 附录: 文件索引

| 文件 | 说明 |
|------|------|
| `deploy/k8s/base/tidb-cluster.yaml` | 集群 CRD (PD/TiKV/TiDB/TiFlash/TiCDC) |
| `deploy/k8s/base/tidb-monitor.yaml` | Prometheus + Grafana |
| `deploy/k8s/base/tidb-backup-schedule.yaml` | 全量 + 增量备份 |
| `deploy/k8s/base/kustomization.yaml` | 资源清单聚合 |
| `deploy/load-tests/results/1m-test-report.md` | 单节点瓶颈原始报告 |
| `deploy/load-tests/results/scaling-requirement-analysis.md` | 扩容需求与成本分析 |
| `deploy/k8s/base/configmap.yaml` | `TIDB_FOLLOWER_READ` 等应用配置 |
