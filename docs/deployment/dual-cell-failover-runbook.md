# v8 P1-1 (D-244): 双 cell 流量切换演练 runbook
# =====================================================================
# 背景：v8 评分（1M CCU 标尺）将「双 cell 流量切换演练通过」列为
# P1-1 的验收标准。Karmada 权重分发（karmada-propagation.yaml，D-041）
# 与 cell-bj overlay 已就绪；本文件补齐演练流程，使 P1-1 从
# 「overlay 就绪」升级为「演练可执行」。
#
# 铁律 9 声明：本 runbook 的每一步都标注了【预期】与【实证命令】。
# 在真实双 cell 集群执行完毕、全部实证通过之前，P1-1 保持
# 「runbook 就绪」状态——这与 v8 评分卡的状态标注一致。
# =====================================================================

## 0. 前置条件（全部满足才开始）

| # | 条件 | 实证命令 |
|---|------|---------|
| 0.1 | Karmada 控制面就绪（host 集群） | `karmadactl get clusters` 显示 cell-hz 与 cell-bj 均 Ready |
| 0.2 | 两 cell 的 education-platform namespace 已同步 | `kubectl --context cell-hz get pods -n education-platform` 与 `kubectl --context cell-bj ...` 均 Running |
| 0.3 | 数据层跨 cell 可达（CRDB 主区域 + follower） | 两 cell 各 pod 内 `psql -h <crdb-svc> -c 'select 1'` 成功 |
| 0.4 | Pulsar 跨 cell 复制已配置（domain-events topic） | 两 cell 各发一条测试事件，对端可消费 |
| 0.5 | 权重分发已生效 | `kubectl --context karmada-apiserver get propagationpolicy ep-multi-active-propagation -o yaml` 权重 40/30/20/10 |

## 1. 演练一：权重调整灰度（无损，先做）

目的：验证 Karmada 把副本变化正确下发到两个 cell。

```bash
# 1.1 临时把 cell-bj 权重从 20 提到 35（karmada-propagation.yaml 副本）
kubectl --context karmada-apiserver patch propagationpolicy ep-multi-active-propagation \
  --type='json' -p='[{"op":"replace","path":"/spec/placement/replicaScheduling/replicaDivisionPreference/weightPreference/staticWeightList/2/weightPreference","value":35}]' 2>/dev/null \
  || kubectl --context karmada-apiserver edit propagationpolicy ep-multi-active-propagation  # 手工编辑 fallback

# 1.2 观察 cell-bj 的 portal-bff 副本数是否按新权重扩张
karmadactl get pods -n education-platform --cluster cell-bj -w
```

【预期】cell-bj portal-bff 副本在 30s 内扩张到新权重对应数量；cell-hz 不变。
【回滚】恢复 weight=20。

## 2. 演练二：单 cell 故障切换（核心验收）

目的：cell-bj 全部失联时，流量 100% 回收至 cell-hz，用户无感。

```bash
# 2.1 模拟 cell-bj 故障：隔离节点网络或直接将成员集群标记 unschedulable
kubectl --context cell-bj cordon <all-bj-nodes>
# （更强模拟：iptables 封 Karmada 联邦通道 —— karmadactl 会在
#   grace-period 后将 cell-bj 标记 NotReady）

# 2.2 触发 failover：Karmada 对 NotReady 集群的迁移策略
kubectl --context karmada-apiserver patch propagationpolicy ep-multi-active-propagation \
  --type='merge' -p='{"spec":{"failover":{"application":{"conditions":[{"feature":"ApplicationFailover"}]}}}}'
# ApplicationFailover 生效后，原分给 cell-bj 的副本由 karmada-scheduler
# 重新分配到 cell-hz（权重归一化）。

# 2.3 实证：cell-hz 承接全部流量
karmadactl get pods -n education-platform --cluster cell-hz   # 副本数 ≈ 原两 cell 总和
kubectl --context cell-hz -n education-platform get hpa        # HPA 随负载扩张
```

【预期】
- 30~60s 内 cell-hz 副本扩张到位（HPA + Karmada 重调度）
- 冒烟：cell-hz 入口域名 `/actuator/health` 200；登录/课程/下单核心链路 T0 冒烟全过
- 用户侧：错误率尖峰 < 1%（以 SLA 端点 `/api/v1/sla` 观察）

【回滚】cell-bj 节点 `uncordon`，Karmada 自动按权重回流副本。

## 3. 演练三：数据层主从切换（资金安全红线，最后做）

```bash
# 3.1 CRDB region 视角确认 follower 位置
kubectl --context cell-hz -n infra exec -it crdb-0 -- cockroach sql \
  --insecure -e "SELECT region, replica_count FROM crdb_internal.gossip_nodes;"

# 3.2 模拟主区域故障 → CRDB 内部自动选主（Raft），应用侧只需确认：
kubectl --context cell-bj -n infra exec -it crdb-0 -- cockroach sql \
  --insecure -e "SHOW CLUSTER SETTING cluster.organization;"  # 连通性

# 3.3 应用层验证：下单 + 支付全链路（T0 冒烟 + 对账五段式一键脚本）
./scripts/reconcile-verify.sh --window 10m
```

【预期】Raft 选主 < 5s；应用侧错误率尖峰 < 2%；对账无差异行。
【回滚】恢复主区域节点，Raft 自动回迁（可选：手动 `MOVE REPLICAS` 调平）。

## 4. 验收记录表（填入后 P1-1 升级为「运行验证通过」）

| 演练 | 开始时间 | 结束时间 | 预期达成 | 用户感知错误率尖峰 | 执行人 |
|------|---------|---------|---------|------------------|--------|
| 权重灰度 | | | ☐ | | |
| 单 cell 故障切换 | | | ☐ | | |
| 数据层主从切换 | | | ☐ | | |

> 三项全勾 → 更新 v8 评分卡 P1-1 状态为 ✅ 运行验证通过，决策号留痕。
