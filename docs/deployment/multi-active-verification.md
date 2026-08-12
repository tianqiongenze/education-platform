# 多地多活部署验证手册 (Multi-Active Deployment Verification Runbook)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| MA-VERIFY-001 | v1.0 | 2026-08-04 | Approved | ZCode(SRE架构师) | SP07-DEPLOY-001 / SP10-BU-REPLICATION-001 / D-012 / D-032 / D-035 / G-01~G-10 SLO |

> 本文为 education-platform 多地多活 (异地多活) 部署架构的验证手册与 Runbook，覆盖单元 (Cell) 独立部署、跨单元连通性、数据一致性、流量切换、灾难恢复、性能基线与安全隔离七大验证域。所有验证步骤、预期结果、失败处理与脚本示例均对齐 TOGAF 运营架构 (Operations Architecture) 与铁律 D-032 / D-035。

---

## 目录

1. [验证目标与范围](#1-验证目标与范围)
2. [验证前置条件](#2-验证前置条件)
3. [单元 (Cell) 部署验证](#3-单元-cell-部署验证)
4. [跨单元 (Unit-Cell) 连通性验证](#4-跨单元-unit-cell-连通性验证)
5. [数据一致性验证](#5-数据一致性验证)
6. [流量切换验证](#6-流量切换验证)
7. [灾难恢复验证](#7-灾难恢复验证)
8. [性能基线验证](#8-性能基线验证)
9. [安全验证](#9-安全验证)
10. [验证报告模板](#10-验证报告模板)
11. [附录：术语与依据](#11-附录术语与依据)

---

## 1. 验证目标与范围

### 1.1 验证目标

本手册旨在系统化验证 education-platform 多地多活部署架构的正确性、数据一致性与容灾切换能力，确保平台在 30 亿用户 / 100 万峰值并发 (G-01/G-02) 规模下满足以下 SLO：

| 验证域 | 验证目标 | SLO 依据 |
|--------|----------|----------|
| 单元部署 | 每个单元可独立部署、自包含、100K 并发容量 | D-012 |
| 跨单元连通 | 跨单元 RPC / 消息 / 数据读写隔离且可达 | SP07 §4 |
| 数据一致性 | CDC 管道延迟 <30s，读后写一致 | G-09, ADR-T06 |
| 流量切换 | 单元故障切换 <30s，区域切换 <5min | G-08 |
| 灾难恢复 | RTO<5min，RPO<30s | SP07 §7.1 |
| 性能基线 | 核心 API P95<500ms，P99<2000ms | G-03/G-04 |
| 安全隔离 | 租户数据隔离、JWT RS256、default-deny 生效 | D-004, D-035 |

### 1.2 验证范围

**纳入范围 (In-Scope)：**

- 国内三地单元：cell-HZ (华东杭州)、cell-SZ (华南深圳)、cell-BJ (华北北京)
- 海外三地单元：cell-SG (东南亚新加坡)、cell-US (北美弗吉尼亚)、cell-EU (欧洲法兰克福)
- K8s 部署清单 (deploy/k8s/，13 个 YAML 文件)
- 基础设施组件：Nacos 3.1.1、Pulsar 3.3.9、TiDB 7.5.x、Calico/Cilium CNI、Harbor 镜像仓库
- 数据管道：TiCDC → Pulsar → Flink → Iceberg
- 可观测性：OTel Collector (tail-sampling, D-035 rule 5)

**排除范围 (Out-of-Scope)：**

- 单元内部微服务的单元测试 / 集成测试 (由各 SP 子项目负责)
- 网络专线物理层验收 (由 IDC / 网络团队负责)
- 合规审计 (由法务 / DPO 负责，本手册仅验证技术隔离手段)

### 1.3 验证原则

1. **生产环境等价**：验证须在与生产同构的预发单元 (Pre-Prod Cell) 上执行，配置漂移 <1%。
2. **可重复可自动化**：所有验证步骤须可脚本化，纳入 CI/CD 流水线 (ArgoCD/Flux GitOps)。
3. **证据驱动**：每项验证须留存命令输出、metric 截图、日志片段作为证据，禁止"目测通过"。
4. **滚动验证**：按 §3 → §9 顺序执行，前序域未通过不得进入后序域。

---

## 2. 验证前置条件

### 2.1 集群就绪检查

| 前置项 | 要求 | 验证命令 | 预期结果 |
|--------|------|----------|----------|
| K8s 集群 | v1.30+，6 个单元均 Ready | `kubectl get nodes -o wide` | 所有节点 `Ready`，6 个 context 可达 |
| CNI 插件 | Calico v3.28+ 或 Cilium v1.16+ | `kubectl -n kube-system get ds calico-node` 或 `cilium status` | DaemonSet READY 数 = 节点数 |
| Harbor 镜像仓库 | 私有仓库，5 个服务镜像可达 | `crane ls harbor.edu.internal/education-platform/bff-java` | 返回 tag 列表含 `1.0.0` |
| ArgoCD/Flux | GitOps 控制器已部署 | `kubectl -n argocd get apps` | education-platform app `Healthy` |
| 集群间专线 | 国内三地专线 <5ms RTT，海外专线 <150ms RTT | `ping -c 100 <peer-cell-api>` | 平均 RTT 满足阈值 |

### 2.2 中间件集群就绪检查

**Nacos 集群 (3.1.1)：**

```bash
# 每个单元 3 节点 Raft 集群
for cell in hz sz bj sg us eu; do
  kubectl --context=k8s-${cell} -n middleware exec -it \
    nacos-0 -- sh -c 'curl -s http://localhost:8848/nacos/v1/ns/operator/metrics' \
    | jq '.leader, .peers'
done
```

预期结果：每个单元 `leader` 非空，`peers` 数量 = 3。

**Pulsar 集群 (3.3.9)：**

```bash
# BookKeeper 与 Broker 健康
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin brokers list pulsar-cluster-${cell}
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/bookkeeper shell listbookies -rw
```

预期结果：每个单元 broker 数 ≥ 2，bookie 可写数 ≥ 3。

**TiDB 集群 (7.5.x)：**

```bash
# PD 调度器健康、TiKV 副本数达标
kubectl --context=k8s-${cell} -n middleware exec -it tidb-pd-0 -- \
  /pd-ctl -u http://localhost:2379 store
```

预期结果：每个 region 副本数 = 3，所有 store `Up` 状态。

### 2.3 前置条件失败处理

| 失败现象 | 排查方向 | 负责团队 |
|----------|----------|----------|
| CNI 未安装 | NetworkPolicy 不会被强制执行 (D-035 rule 4)，严禁继续 | 平台 SRE |
| Harbor 镜像拉取失败 | 检查 imagePullSecret、Harbor 证书、网络策略放行 | DevOps |
| Nacos 无 leader | 检查 Raft 日志 `nacos.log`，确认脑裂 | 中间件 |
| Pulsar bookie 不足 | 检查磁盘水位、Journal 目录权限 | 中间件 |
| TiKV 副本不足 | PD 调度器是否被暂停 `pd-ctl scheduler show` | DBA |

---

## 3. 单元 (Cell) 部署验证

### 3.1 验证目标

验证每个单元 (Cell) 可通过 kustomize 独立部署，K8s 清单 (13 个 YAML 文件) 校验通过，服务健康探针 (readiness/liveness) 正常，Dubbo 注册发现 (Nacos) 与 Pulsar topic 创建正确。

### 3.2 K8s Manifest 校验 (13 个 YAML 文件)

**验证步骤：**

```bash
# 步骤 1：kustomize 渲染并校验 (在仓库根目录执行)
kubectl kustomize deploy/k8s/ > /tmp/edu-rendered.yaml
kubectl apply --dry-run=server -f /tmp/edu-rendered.yaml --context=k8s-${cell}

# 步骤 2：kubeconform 静态校验 (D-035 rule 6: GitOps 可追溯)
kubeconform -strict -kubernetes-version 1.30.0 \
  -schema-location https://raw.githubusercontent.com/yannh/kubernetes-json-schema/master/master-standalone \
  deploy/k8s/*.yaml

# 步骤 3：确认 13 个文件均已渲染且资源数一致
yq eval-all '.kind' /tmp/edu-rendered.yaml | sort | uniq -c
```

**预期结果：**

- `kubectl kustomize` 无错误，渲染产物包含全部 13 个源文件对应的资源。
- `--dry-run=server` 返回 `created (server dry run)`，无 API 校验错误。
- 资源清单应包含：1 个 Namespace、若干 ConfigMap、5 个 Deployment (bff-java / sp03-business / sp05-itbackend / sp04-ai / gateway-go)、NetworkPolicy、Ingress、HPA、PDB、OTel Collector。

**验证清单：**

| # | 文件 | 资源类型 | 校验点 | 通过条件 |
|---|------|----------|--------|----------|
| 1 | namespace.yaml | Namespace | 名称 education-platform | dry-run 通过 |
| 2 | configmap.yaml | ConfigMap | 无明文密钥 (D-035 ops hardening) | 无 `password:` 明文 |
| 3 | bff-java.yaml | Deployment | progressDeadlineSeconds=900 (D-035 rule 1) | `yq` 断言通过 |
| 4 | sp03-business.yaml | Deployment | 同上 | 同上 |
| 5 | sp05-itbackend.yaml | Deployment | 同上 | 同上 |
| 6 | sp04-ai.yaml | Deployment | 同上 | 同上 |
| 7 | gateway-go.yaml | Deployment | 同上 | 同上 |
| 8 | networkpolicy.yaml | NetworkPolicy ×N | default-deny-ingress 存在 | 见 §9 |
| 9 | ingress.yaml | Ingress | TLS 配置、host 合法 | cert-manager ready |
| 10 | hpa-all.yaml | HPA ×N | minReplicas≥2 | 见 §8 |
| 11 | pdb-all.yaml | PDB ×N | minAvailable≥2 (5 个服务) | 见 §6.2 |
| 12 | otel-collector.yaml | Collector + PDB | tail_sampling 配置存在 | 见 §9.4 |
| 13 | kustomization.yaml | Kustomization | namePrefix=edu- | 渲染产物前缀正确 |

**失败处理：**

- `kubeconform` 报 schema 错误：核对 API 版本是否与集群 v1.30 对齐。
- `--dry-run=server` 报 `progressDeadlineSeconds` 字段不存在：升级 kubectl 至 v1.27+。
- 渲染产物缺资源：检查 kustomization.yaml `resources:` 段是否包含 12 个子项 (namespace 至 otel-collector)。

### 3.3 服务健康检查 (readiness/liveness probes)

**验证步骤：**

```bash
# 步骤 1：通过 ArgoCD 同步部署到目标单元
argocd app sync education-platform-${cell} --prune

# 步骤 2：等待所有 Deployment 滚动完成
kubectl --context=k8s-${cell} -n education-platform wait deployment \
  edu-bff-java edu-sp03-business edu-sp05-itbackend edu-sp04-ai edu-gateway-go \
  --for=condition=Available=True --timeout=900s

# 步骤 3：逐服务检查探针端点
for svc in bff-java sp03-business sp05-itbackend sp04-ai gateway-go; do
  echo "=== ${svc} ==="
  kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-${svc} -- \
    curl -s http://localhost:8080/actuator/health | jq .status
done
```

**预期结果：**

- 5 个 Deployment 在 900s 内达到 `Available`，符合 D-035 rule 1 (progressDeadlineSeconds=900)。
- `/actuator/health` 返回 `{"status":"UP"}`。
- `kubectl get pods` 所有 Pod `READY` 列为 `1/1`，`STATUS` 为 `Running`。
- livenessProbe 失败次数 = 0：`kubectl get events --field-selector reason=Unhealthy` 无记录。

**失败处理：**

| 现象 | 排查 |
|------|------|
| Pod CrashLoopBackOff | `kubectl logs <pod> --previous`，常见为 ConfigMap 缺失或 Nacos 连接失败 |
| readinessProbe 失败 | 检查应用启动日志，确认 Dubbo 注册完成后再暴露 /health |
| 滚动超时 (>900s) | 降低镜像大小、预热类加载、检查 JVM 启动参数 `-XX:TieredStopAtLevel=1` |

### 3.4 Dubbo 注册发现 (Nacos) 验证

**验证步骤：**

```bash
# 步骤 1：确认 Dubbo 服务已注册到 Nacos namespace (单元隔离)
kubectl --context=k8s-${cell} -n middleware exec -it nacos-0 -- \
  curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=providers:com.edu.bff.BffService&namespaceId=${cell}" \
  | jq '.hosts | length'

# 步骤 2：跨服务调用验证 (bff-java -> sp03-business)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-bff-java -- \
  curl -s "http://edu-sp03-business:8083/actuator/info" | jq .
```

**预期结果：**

- Nacos `providers:com.edu.bff.BffService` 实例数 ≥ 2 (与 Deployment replicas 一致)。
- bff-java 通过 Dubbo 注册中心成功发现并调用 sp03-business，返回业务元数据。
- Dubbo `cluster=cell-aware` 配置生效：调用仅命中本单元实例 (SP07 §5)。

**失败处理：**

- 实例未注册：检查 `dubbo.registry.address=nacos://${nacos}:8848?namespace=${cell}` 是否正确注入。
- 跨单元调用泄漏：确认 Nacos namespace 隔离，检查 `cell-aware` 路由器是否生效。

### 3.5 Pulsar Topic 创建验证

**验证步骤：**

```bash
# 步骤 1：检查单元内核心 topic 已创建 (分区数符合 SP10 配置)
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin topics list persistent://edu-${cell}/tenant/order \
  | grep -E "order-created|order-paid|course-published"

# 步骤 2：检查分区数与副本数
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin topics partitioned-stats persistent://edu-${cell}/tenant/order/order-created
```

**预期结果：**

- 核心 topic (order-created / order-paid / course-published) 均存在。
- 分区数 ≥ 8 (匹配 100K 并发吞吐)，副本数 = 3 (BookKeeper Ensemble=3, WriteQuorum=3, AckQuorum=2)。

**失败处理：**

- topic 不存在：触发初始化 Job `kubectl -n education-platform create job --from=cronjob/topic-init topic-init-${cell}`。
- 副本不足：检查 bookie 数量，BookKeeper 自动修复 `bin/bookkeeper shell recover`。

---

## 4. 跨单元 (Unit-Cell) 连通性验证

### 4.1 验证目标

验证跨单元通信隔离与受控可达：NetworkPolicy 强制执行、Dubbo 跨单元 RPC 受 Nacos namespace 隔离、Pulsar 跨单元消息路由正确、TiDB 跨单元读写分离 (ep-rw-java SDK) 生效。

### 4.2 NetworkPolicy 强制执行验证

> ⚠️ D-035 rule 4 警告：无 Calico/Cilium 时 NetworkPolicy 不被强制执行，仅 API Server 接受 YAML，数据面无隔离，产生"虚假安全感"。

**验证步骤：**

```bash
# 步骤 1：确认 CNI 在数据面强制执行 (而非仅 API 接受)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-bff-java -- \
  nc -zv edu-sp04-ai 8084   # 应被 default-deny-ingress 拒绝
# 预期: Connection refused / timeout (bff-java 未被允许访问 sp04-ai)

# 步骤 2：验证白名单放行 (bff-java -> sp03-business 8083+20883)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-bff-java -- \
  nc -zv edu-sp03-business 8083
# 预期: Connection succeeded

# 步骤 3：Cilium 策略可视化 (若使用 Cilium)
cilium policy trace --src-deploy edu-bff-java --dst-deploy edu-sp04-ai
```

**预期结果：**

- 未在 networkpolicy.yaml 白名单的路径 (如 bff-java → sp04-ai) 被拒绝。
- 白名单内路径 (bff-java → sp03-business 的 8083 / 20883) 可达。
- Cilium `policy trace` 输出 `Verdict: DENIED` 或 `ALLOWED` 与预期一致。

**失败处理：**

- 若未授权路径可达：说明 CNI 未强制执行，立即停止验证并补装 Calico/Cilium (D-035 rule 4)。
- 白名单路径被拒：核对 networkpolicy.yaml `podSelector` 标签与 Deployment `app.kubernetes.io/name` 标签是否匹配。

### 4.3 Dubbo 跨单元 RPC (Nacos namespace 隔离) 验证

**验证步骤：**

```bash
# 步骤 1：确认每个单元使用独立 Nacos namespace (cell 隔离)
for cell in hz sz bj; do
  echo "=== cell-${cell} ==="
  kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-bff-java -- \
    env | grep DUBBO_REGISTRY_NAMESPACE
done
# 预期: 每个单元 namespace 不同 (hz/sz/bj)

# 步骤 2：验证全局配置服务跨单元调用 (仅限白名单服务)
kubectl --context=k8s-hz -n education-platform exec deploy/edu-bff-java -- \
  curl -s "http://edu-sp03-business:8083/api/internal/global-config?cell=sz" | jq .
```

**预期结果：**

- 各单元 `DUBBO_REGISTRY_NAMESPACE` 与单元 ID 一致，默认调用仅命中本单元。
- 全局配置服务 (GlobalConfigService) 通过 `cluster=cell-aware` + 跨单元白名单可达对端单元，其余服务跨单元调用被熔断器 (Sentinel) 拦截。

**失败处理：**

- 跨单元调用泄漏：检查 Nacos namespace 注入，确认 `cell-aware` 路由器优先级。
- 熔断未生效：核对 Sentinel `DegradeRule` 规则 (SP07 §7.4，错误率 50% 触发)。

### 4.4 Pulsar 跨单元消息路由验证

**验证步骤：**

```bash
# 步骤 1：检查 Geo-Replication 已建立 (国内三地两两复制)
kubectl --context=k8s-hz -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin namespaces get-clusters edu-hz/tenant
# 预期: [hz, sz, bj]

# 步骤 2：生产一条消息到 cell-HZ，验证 cell-SZ 能消费
kubectl --context=k8s-hz -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-client produce -m "verify-$(date +%s)" \
  persistent://edu-hz/tenant/order/order-created

kubectl --context=k8s-sz -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-client consume -s verify-sub -n 1 \
  persistent://edu-sz/tenant/order/order-created
```

**预期结果：**

- `get-clusters` 返回 `[hz, sz, bj]`，Geo-Replication 拓扑正确。
- 在 cell-HZ 生产的消息在 cell-SZ 可消费，复制延迟 <5s (专线 RTT 决定)。

**失败处理：**

- 复制延迟过高：检查专线带宽、Pulsar Replicator 是否 backlog。
- 消息未到达：确认 topic 的 `replication-clusters` 配置包含对端单元。

### 4.5 TiDB 跨单元读写分离 (ep-rw-java SDK) 验证

**验证步骤：**

```bash
# 步骤 1：确认 ep-rw-java SDK 路由策略 (主写本单元、读本单元、跨单元只读副本)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-sp03-business -- \
  curl -s "http://localhost:8083/actuator/configprops" \
  | jq '.configurationProperties."ep-rw-java"'

# 步骤 2：写本单元、读跨单元只读副本 (Follower Read)
kubectl --context=k8s-hz -n education-platform exec deploy/edu-sp03-business -- \
  curl -s -X POST "http://edu-sp03-business:8083/api/test/cross-cell-rw" \
  -H "X-Cell: hz" -H "X-Read-Cell: sz" | jq .
```

**预期结果：**

- `ep-rw-java` 配置 `writeCell=local`、`readStrategy=follower-read` 生效。
- 写入 cell-HZ 的数据在 cell-SZ 通过 Follower Read 可读，延迟 <1s (TiDB Raft 多数派同步)。

**失败处理：**

- 跨单元读超时：检查 TiDB Placement Rules，确认副本跨单元分布。
- 读到旧数据：确认 `read-isolation-level=READ-COMMITTED`，PD `max-replica=3` 跨单元调度。

---

## 5. 数据一致性验证

### 5.1 验证目标

验证跨单元数据一致性：CDC 管道 (TiCDC→Pulsar→Flink→Iceberg) 延迟达标、读后写一致、幂等键 (ADR-T06) 与乐观锁版本号生效。

### 5.2 CDC 管道延迟测量

**验证步骤：**

```bash
# 步骤 1：写入带时间戳的测试记录到 TiDB (cell-HZ)
kubectl --context=k8s-hz -n education-platform exec deploy/edu-sp03-business -- \
  curl -s -X POST "http://edu-sp03-business:8083/api/test/cdc-probe" \
  -d '{"ts":"$(date +%s%3N)"}'

# 步骤 2：在 Iceberg (cell-HZ 与 cell-SZ) 查询该记录到达时间
kubectl --context=k8s-sz -n middleware exec -it flink-taskmanager-0 -- \
  sql-client.sh -e "SELECT * FROM iceberg.edu.order_events WHERE ts=$(date +%s%3N) LIMIT 1"

# 步骤 3：从 TiCDC checkpoint metric 提取端到端延迟
kubectl --context=k8s-hz -n middleware exec -it ticdc-0 -- \
  curl -s http://localhost:8300/metrics | grep ticdc_checkpoint_ts_lag
```

**预期结果：**

- 测试记录在 Iceberg 出现，端到端延迟 <30s (SP07 §10 告警阈值)。
- `ticdc_checkpoint_ts_lag` metric <30s，无告警 (Warning>30s, Critical>120s)。

**失败处理：**

- 延迟 >30s：检查 TiCDC changefeed 状态 `ticdc ctl changefeed list`，确认无 `stopped`。
- Flink 反压：检查 Checkpoint 时长，调大并行度或 partition 数。
- Iceberg 未提交：检查 Flink→Iceberg commit 重试日志，常见为 S3/OSS 限流。

### 5.3 读后写一致性 (Read-After-Write) 验证

**验证步骤：**

```bash
# 同一用户在 cell-HZ 写入订单，立即在 cell-SZ 读取 (跨单元)
ORDER_ID=$(kubectl --context=k8s-hz -n education-platform exec deploy/edu-sp03-business -- \
  curl -s -X POST "http://edu-sp03-business:8083/api/orders" \
  -H "X-Idempotency-Key: raw-$(date +%s)" \
  -d '{"userId":"u-raw-test","courseId":"c-1"}' | jq -r .orderId)

# 立即在 cell-SZ 读取
kubectl --context=k8s-sz -n education-platform exec deploy/edu-sp03-business -- \
  curl -s "http://edu-sp03-business:8083/api/orders/${ORDER_ID}" | jq .
```

**预期结果：**

- 跨单元读后写一致：cell-SZ 能读到 cell-HZ 刚写入的订单 (依赖 TiDB Follower Read + Raft 多数派)。
- 重复提交相同 `X-Idempotency-Key` 返回同一 orderId，不产生重复订单 (ADR-T06)。

**失败处理：**

- 读不到：检查 TiDB Placement Rules 是否跨单元副本，ep-rw-java `readTimeout` 配置。
- 重复订单：检查幂等键索引 (`uk_idempotency_key`) 与 ADR-T06 拦截器。

### 5.4 幂等键验证 (ADR-T06)

**验证步骤：**

```bash
IDEMPOTENCY_KEY="idem-verify-$(uuidgen)"
# 同一幂等键并发提交 3 次
for i in 1 2 3; do
  kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-sp03-business -- \
    curl -s -X POST "http://edu-sp03-business:8083/api/orders" \
    -H "X-Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -d '{"userId":"u-idem","courseId":"c-1"}' &
done
wait
# 查询数据库确认仅 1 条
kubectl --context=k8s-${cell} -n middleware exec -it tidb-tikv-0 -- \
  mysql -h tidb -u root -e "SELECT COUNT(*) FROM edu.orders WHERE idempotency_key='${IDEMPOTENCY_KEY}'"
```

**预期结果：**

- 数据库 `COUNT(*)` = 1，3 次并发请求返回相同 orderId。
- ADR-T06 拦截器日志记录 2 次 "duplicate idempotency key rejected"。

**失败处理：**

- COUNT > 1：检查 `idempotency_key` 唯一索引是否缺失，拦截器优先级是否低于事务提交。

### 5.5 乐观锁版本号验证

**验证步骤：**

```bash
# 并发更新同一课程库存，验证乐观锁
COURSE_ID="c-stock-test"
for i in 1 2 3 4 5; do
  kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-sp03-business -- \
    curl -s -X PATCH "http://edu-sp03-business:8083/api/courses/${COURSE_ID}/stock" \
    -H "If-Match: $(curl -s http://edu-sp03-business:8083/api/courses/${COURSE_ID} | jq -r .version)" \
    -d '{"delta":-1}' &
done
wait
# 预期: 仅 1 次成功 (version 匹配)，其余返回 409 Conflict
```

**预期结果：**

- 仅 1 次更新成功 (HTTP 200)，其余返回 `409 Conflict` (version 不匹配)。
- 数据库 `version` 字段 +1，无超卖。

**失败处理：**

- 超卖：检查 `@Version` 注解 (JPA) 或 `version` 列是否生效，事务隔离级别是否 `READ-COMMITTED`。

---

## 6. 流量切换验证

### 6.1 验证目标

验证基于 DNS/GSLB 的流量调度、单元排空 (Cell Drain, PDB 强制)、单元故障切换 (kill cell, 服务连续性)、单元恢复与重同步。

### 6.2 单元排空 (Cell Drain) 验证

**验证步骤：**

```bash
# 步骤 1：通过 GSLB 将 cell-BJ 流量权重置 0
gslb-cli set-weight edu-cn.com cell-bj 0

# 步骤 2：模拟节点排空，验证 PDB (minAvailable=2) 强制
kubectl --context=k8s-bj drain node-bj-worker-1 --ignore-daemonsets --delete-emptydir-data
# 预期: PDB 阻止驱逐，直到 Pod 重调度到其他节点维持 minAvailable

# 步骤 3：确认排空期间无新流量进入，存量请求处理完成
kubectl --context=k8s-bj -n education-platform exec deploy/edu-gateway-go -- \
  curl -s http://localhost:8080/actuator/metrics/gateway.active.requests | jq .
```

**预期结果：**

- GSLB 权重置 0 后 30s 内 cell-BJ 无新流量 (GSLB HTTP 检查 5s × 3 阈值, SP07 §7.3)。
- PDB (`minAvailable: 2`) 阻止驱逐导致低于 2 副本，kubectl drain 报 `PodDisruptionBudget` 阻塞。
- 存量请求在 60s 内处理完成，`gateway.active.requests` 归零。

**失败处理：**

- PDB 未阻止驱逐：核对 pdb-all.yaml `minAvailable` 与 Deployment replicas (replicas≥3 才有意义)。
- 流量未摘除：检查 GSLB 健康检查路径与 TTL，降低 DNS TTL 至 30s。

### 6.3 单元故障切换 (Cell Failover) 验证

**验证步骤：**

```bash
# 步骤 1：模拟 cell-BJ 整单元故障 (关闭所有网关)
kubectl --context=k8s-bj -n education-platform scale deploy/edu-gateway-go --replicas=0

# 步骤 2：观察 GSLB 自动摘除 cell-BJ (5s 检查 × 3 失败 = 15s)
watch -n2 'dig +short edu-cn.com | sort'

# 步骤 3：持续压测验证服务连续性 (cell-HZ/SZ 承接)
k6 run deploy/load-tests/k6/bff-smoke.js -e BASE_URL=https://edu-cn.com &
SMOKE_PID=$!
# 等待 GSLB 收敛
sleep 30
kill $SMOKE_PID

# 步骤 4：检查请求错误率
# (从 k6 输出读取 http_req_failed)
```

**预期结果：**

- cell-BJ 网关关闭后 ≤30s，GSLB 将其从 DNS 摘除 (G-08: 单元切换 <30s)。
- k6 smoke 期间 `http_req_failed` 临时升高但 <5% (GSLB 收敛期)，收敛后 <1%。
- cell-HZ / cell-SZ 承接全部流量，服务不中断。

**失败处理：**

- 切换 >30s：检查 GSLB 健康检查间隔与 TTL，确认客户端 DNS 缓存刷新。
- 错误率 >5%：检查 cell-HZ/SZ 容量水位 (HPA 是否已扩容)，预热实例数。

### 6.4 单元恢复与重同步验证

**验证步骤：**

```bash
# 步骤 1：恢复 cell-BJ 网关
kubectl --context=k8s-bj -n education-platform scale deploy/edu-gateway-go --replicas=3
kubectl --context=k8s-bj -n education-platform wait deploy/edu-gateway-go \
  --for=condition=Available=True --timeout=900s

# 步骤 2：验证数据重同步 (CDC backlog 追平)
kubectl --context=k8s-bj -n middleware exec -it ticdc-0 -- \
  curl -s http://localhost:8300/metrics | grep ticdc_checkpoint_ts_lag
# 预期: lag <30s (追平)

# 步骤 3：GSLB 渐进回切流量 (10% -> 50% -> 100%)
gslb-cli set-weight edu-cn.com cell-bj 10
sleep 60; gslb-cli set-weight edu-cn.com cell-bj 50
sleep 60; gslb-cli set-weight edu-cn.com cell-bj 33
```

**预期结果：**

- cell-BJ 网关恢复后 Available，健康检查通过。
- CDC backlog 在 5min 内追平 (lag<30s)。
- 渐进回切期间无错误率飙升，HPA 平滑扩容。

**失败处理：**

- CDC lag 不收敛：检查 TiCDC changefeed 是否 `stopped`，手动 `resume`。
- 回切错误率高：检查 cell-BJ 缓存预热 (Redis CRDT 同步)、JIT 预热。

---

## 7. 灾难恢复验证

### 7.1 验证目标

验证 RTO<5min / RPO<30s (SP07 §7.1)，覆盖 TiDB PITR、Pulsar 消息回放、Iceberg 快照回滚。

### 7.2 RTO/RPO 测量

**验证步骤：**

```bash
# 步骤 1：记录故障注入时间 T0
T0=$(date +%s)

# 步骤 2：模拟区域级故障 (kill cell-SZ 全部 TiDB + Pulsar)
kubectl --context=k8s-sz -n middleware scale deploy/tidb --replicas=0
kubectl --context=k8s-sz -n middleware scale sts/pulsar-broker --replicas=0

# 步骤 3：触发跨区域切流到 cell-HZ/BJ (GSLB 自动 + 人工确认)
# 记录流量恢复时间 T1
T1=$(date +%s)
echo "RTO = $((T1-T0))s"

# 步骤 4：从 TiDB PITR 恢复 cell-SZ 数据到故障前最新一致点
# 记录最后可恢复数据时间点 T_pitr (日志中最后一条事务时间)
# RPO = T0 - T_pitr
```

**预期结果 (SP07 §7.1 目标)：**

| 场景 | RTO 目标 | RPO 目标 | 实测 |
|------|----------|----------|------|
| 单元故障 (国内) | <30s | 0 | ≤30s |
| 区域故障 (国内三地之一) | <5min | <1s | ≤5min |
| 数据中心级灾难 | <4h | <1min | ≤4h |

**失败处理：**

- RTO 超 5min：检查 GSLB 收敛、HPA 扩容速度、镜像预热。
- RPO >30s：检查 TiDB Raft 多数派同步、Pulsar Geo-Replication 持久化级别 (`persistent-ack`)。

### 7.3 TiDB PITR (Point-in-Time Recovery) 验证

**验证步骤：**

```bash
# 步骤 1：确认 BR 备份与日志备份正常
kubectl --context=k8s-${cell} -n middleware exec -it tidb-br-0 -- \
  br log status --task-name edu-pitr -u "root@tidb:4000"

# 步骤 2：恢复到指定时间点 (T_target = 故障前 10s)
T_TARGET=$(date -d "-10 seconds" +%Y-%m-%dT%H:%M:%S)
kubectl --context=k8s-${cell} -n middleware exec -it tidb-br-0 -- \
  br restore point --pd "tidb-pd:2379" \
  --full-backup-storage "s3://edu-backup/${cell}/full-latest" \
  --log-backup-storage "s3://edu-backup/${cell}/log" \
  --restored-ts "${T_TARGET}"

# 步骤 3：验证数据完整性 (行数、校验和)
mysql -h tidb-${cell} -u root -e "SELECT COUNT(*), SUM(id) FROM edu.orders WHERE created_at < '${T_TARGET}'"
```

**预期结果：**

- BR 日志备份状态 `normal`，checkpoint 持续推进。
- PITR 恢复成功，恢复后数据与 `T_TARGET` 时间点一致 (校验和匹配)。
- 恢复耗时 < 15min (取决于数据量)。

**失败处理：**

- 日志备份滞后：检查 TiCDC/BR 日志，确认 S3 写入权限与带宽。
- 校验和不匹配：确认恢复期间无写入 (恢复集群隔离)，检查时区一致性。

### 7.4 Pulsar 消息回放验证

**验证步骤：**

```bash
# 步骤 1：将消费位点回退到 1 小时前
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin topics reset-cursor persistent://edu-${cell}/tenant/order/order-created \
  -s order-consumer-sub --time 1h

# 步骤 2：重启消费者，观察消息回放
kubectl --context=k8s-${cell} -n education-platform rollout restart deploy/edu-sp03-business
# 步骤 3: 检查回放消息数 (应 ≈ 1 小时内生产量)
kubectl --context=k8s-${cell} -n middleware exec -it pulsar-broker-0 -- \
  bin/pulsar-admin topics stats persistent://edu-${cell}/tenant/order/order-created \
  | jq '.subscriptions."order-consumer-sub".msgBacklog'
```

**预期结果：**

- 位点回退成功，消费者重新消费过去 1 小时消息。
- `msgBacklog` 从 0 增长到回放量，逐步归零。
- 幂等键 (ADR-T06) 保证回放不产生重复副作用。

**失败处理：**

- 回放产生重复订单：检查消费者幂等逻辑，确认 `X-Idempotency-Key` 在回放路径生效。
- 位点回退失败：确认订阅为 `Shared` 或 `Failover` (Exclusive 不支持回退)。

### 7.5 Iceberg 快照回滚验证

**验证步骤：**

```bash
# 步骤 1：列出表快照
kubectl --context=k8s-${cell} -n middleware exec -it flink-taskmanager-0 -- \
  sql-client.sh -e "CALL sys.snapshot('iceberg.edu.order_events')"
# 获取 snapshot_id 列表

# 步骤 2：回滚到指定快照
kubectl --context=k8s-${cell} -n middleware exec -it flink-taskmanager-0 -- \
  sql-client.sh -e "CALL sys.rollback_to_snapshot('iceberg.edu.order_events', ${SNAPSHOT_ID})"

# 步骤 3：验证表数据回到快照时间点
kubectl --context=k8s-${cell} -n middleware exec -it flink-taskmanager-0 -- \
  sql-client.sh -e "SELECT COUNT(*) FROM iceberg.edu.order_events"
```

**预期结果：**

- 快照列表完整，回滚后数据行数与快照时间点一致。
- 回滚为元数据操作 (秒级)，不重写数据文件。

**失败处理：**

- 快照过期：检查 `min-snapshots-to-keep` 与 `max-snapshot-age-ms` 配置，调大保留。
- 回滚后查询异常：刷新 Flink 表缓存 `ALTER TABLE ... REFRESH`。

---

## 8. 性能基线验证

### 8.1 验证目标

验证每个单元性能基线：k6 smoke 测试通过、k6 ramp 测试达 10K 并发、延迟 SLO (P95<500ms, P99<2000ms) 达标。

### 8.2 k6 Smoke 测试 (每单元)

**验证步骤：**

```bash
# 针对每个单元执行 smoke 测试 (deploy/load-tests/k6/bff-smoke.js)
for cell in hz sz bj sg us eu; do
  echo "=== cell-${cell} smoke ==="
  k6 run deploy/load-tests/k6/bff-smoke.js \
    -e BASE_URL=https://${cell}.edu-cn.com \
    --out json=smoke-${cell}.json
done
```

**预期结果 (bff-smoke.js thresholds)：**

- 10 VU / 30s 持续。
- `http_req_failed: rate<0.01` (错误率 <1%)。
- `http_req_duration: p(95)<500` (P95 <500ms)。
- 全流程验证：login → get user → list courses → create order → pay order。

**失败处理：**

- 错误率 ≥1%：检查单元内服务健康、数据库连接池。
- P95 ≥500ms：检查 JVM 预热、数据库慢查询、Dubbo 调用链。

### 8.3 k6 Ramp 测试 (10K 并发/单元)

**验证步骤：**

```bash
# 每单元执行 ramp 测试 (deploy/load-tests/k6/bff-ramp.js)
k6 run deploy/load-tests/k6/bff-ramp.js \
  -e BASE_URL=https://${cell}.edu-cn.com \
  --out json=ramp-${cell}.json
```

**预期结果 (bff-ramp.js stages)：**

- 阶梯加压：0→1000 (2min) →5000 (3min) →10000 (5min) →hold 10000 (10min) →0 (2min)。
- 10K 并发持续 10min 期间 `http_req_failed <1%`。
- 延迟 SLO：
  - P95 <500ms (G-03)
  - P99 <2000ms (本手册目标，G-04 核心 API P99<500ms 为更严格目标，ramp 场景放宽)
- HPA 触发扩容，Pod 数随负载增长，无 OOMKill。

**失败处理：**

- 10K 并发未达成：检查 HPA `maxReplicas`、节点资源余量、数据库连接池上限。
- P99 飙升：检查 GC 停顿 (`-Xlog:gc*`)、TiDB 慢查询、Pulsar 消费堆积。

### 8.4 性能基线记录

每个单元验证完成后，填写基线表：

| 单元 | 峰值并发 | http_req_failed | P95 | P99 | 峰值 QPS | 备注 |
|------|----------|-----------------|-----|-----|----------|------|
| cell-HZ | 10000 | | | | | |
| cell-SZ | 10000 | | | | | |
| cell-BJ | 10000 | | | | | |
| cell-SG | 10000 | | | | | |
| cell-US | 10000 | | | | | |
| cell-EU | 10000 | | | | | |

---

## 9. 安全验证

### 9.1 验证目标

验证租户数据隔离 (FGAC for AI 中台)、JWT RS256 token 校验、NetworkPolicy default-deny 强制、OTel tail-sampling (D-035 rule 5) 配置。

### 9.2 租户数据隔离 (FGAC for AI 中台) 验证

**验证步骤：**

```bash
# 步骤 1：以租户 A 的 token 访问 AI 中台，尝试读取租户 B 数据
TENANT_A_TOKEN=$(jwt-gen --tenant tenant-a --rs256)
TENANT_B_ID="tenant-b"

kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-sp04-ai -- \
  curl -s "http://edu-sp04-ai:8084/api/ai/tenants/${TENANT_B_ID}/models" \
  -H "Authorization: Bearer ${TENANT_A_TOKEN}"
# 预期: 403 Forbidden (FGAC 拦截)
```

**预期结果：**

- 租户 A token 访问租户 B 资源返回 `403 Forbidden`，FGAC (Fine-Grained Access Control) 在 SQL 层 / RLS 层拦截。
- AI 中台模型 / 训练数据 / 推理记录均按 `tenant_id` 隔离，无越权读取。

**失败处理：**

- 越权返回 200：检查 FGAC 策略 (`tenant_id = jwt.tenant_id`)、SQL RLS 启用状态。
- Token 伪造：确认 RS256 公钥轮换，`iss` / `aud` 校验生效。

### 9.3 JWT RS256 Token 验证

**验证步骤：**

```bash
# 步骤 1：生成合法 RS256 token，访问受保护接口
TOKEN=$(jwt-gen --rs256 --kid edu-prod --exp 300)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-gateway-go -- \
  curl -s "http://localhost:8080/api/users/me" -H "Authorization: Bearer ${TOKEN}" | jq .

# 步骤 2：伪造 HS256 token (降级攻击)，应被拒
FORGED=$(jwt-forgery --alg HS256 --secret public-key)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-gateway-go -- \
  curl -s "http://localhost:8080/api/users/me" -H "Authorization: Bearer ${FORGED}"
# 预期: 401 Unauthorized

# 步骤 3：过期 token 应被拒
EXPIRED=$(jwt-gen --rs256 --exp -10)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-gateway-go -- \
  curl -s "http://localhost:8080/api/users/me" -H "Authorization: Bearer ${EXPIRED}"
# 预期: 401 Unauthorized
```

**预期结果：**

- 合法 RS256 token 访问成功。
- HS256 降级攻击被拒 (网关强制 `alg=RS256`，不接受 HS256)。
- 过期 / 签名错误 token 返回 401。

**失败处理：**

- HS256 被接受：严重漏洞，立即修复 JWT 库配置 (禁用 `alg` 白名单外的算法)。
- 公钥未轮换：检查 JWKS endpoint，确认轮换周期 ≤24h。

### 9.4 NetworkPolicy default-deny 强制验证

**验证步骤：**

```bash
# 步骤 1：确认 default-deny-ingress 存在
kubectl --context=k8s-${cell} -n education-platform get networkpolicy default-deny-ingress
# 预期: NAME=default-deny-ingress, 配置 spec.ingress=[]

# 步骤 2：从一个未授权 Pod 访问任意服务，应被拒
kubectl --context=k8s-${cell} -n education-platform run debug --image=busybox:1.36 \
  --rm -it --restart=Never -- wget -qO- --timeout=3 http://edu-bff-java:8080
# 预期: 超时 (无白名单放行 debug Pod)
```

**预期结果：**

- `default-deny-ingress` NetworkPolicy 存在，`ingress: []`。
- 未授权 Pod 访问被拒 (D-035 rule 4 强制执行)。

**失败处理：**

- 未授权可达：CNI 未强制执行 (Calico/Cilium 缺失)，立即停止部署。
- 白名单路径被拒：检查 `podSelector` 标签匹配。

### 9.5 OTel Tail-Sampling (D-035 rule 5) 验证

**验证步骤：**

```bash
# 步骤 1：确认 tail_sampling 配置存在 (otel-collector.yaml)
kubectl --context=k8s-${cell} -n education-platform get cm edu-otel-collector-config -o yaml \
  | grep -A 20 "tail_sampling"
# 预期: decision_wait: 30s, num_traces: 50000, policies 含 errors/status_code

# 步骤 2：触发错误请求，确认错误 trace 100% 保留
# (制造一个 500 错误)
kubectl --context=k8s-${cell} -n education-platform exec deploy/edu-bff-java -- \
  curl -s "http://localhost:8080/api/test/trigger-error"

# 步骤 3：在 Jaeger 查询该错误 trace
# 预期: 错误 trace 100% 采样保留 (status_code policy)
# 正常 trace 按比例采样 (probabilistic policy)
```

**预期结果：**

- OTel Collector 配置 `tail_sampling.decision_wait=30s`，`num_traces=50000` (D-035 rule 5)。
- 错误 trace 100% 保留 (`status_code` policy)，正常 trace 按比例采样降低开销。
- Collector 为独立 Deployment (非 agent)，因 tail-sampling 需缓冲 span。

**失败处理：**

- 错误 trace 缺失：检查 `status_code.status_codes=[ERROR]` 配置，确认 Collector 缓冲未溢出。
- 采样过载：调大 `num_traces`，水平扩容 Collector (PDB minAvailable 保护)。

---

## 10. 验证报告模板

### 10.1 验证报告元数据

```
报告编号: MA-VERIFY-RPT-YYYYMMDD-NNN
验证日期: YYYY-MM-DD
验证负责人: <姓名>
验证单元: cell-HZ / cell-SZ / cell-BJ / cell-SG / cell-US / cell-EU
验证环境: Pre-Prod / Prod
GitOps commit: <argocd app commit sha>
```

### 10.2 验证检查清单 (Checklist)

| # | 验证域 | 验证项 | 通过条件 | 结果 | 证据 | 备注 |
|---|--------|--------|----------|------|------|------|
| 2.1 | 前置条件 | K8s 集群 Ready | 6 context 可达 | ☐ Pass ☐ Fail | | |
| 2.1 | 前置条件 | CNI 强制执行 | Calico/Cilium READY | ☐ Pass ☐ Fail | | |
| 2.1 | 前置条件 | Harbor 镜像可达 | 5 服务 tag 存在 | ☐ Pass ☐ Fail | | |
| 2.2 | 前置条件 | Nacos 集群 | leader+3 peers | ☐ Pass ☐ Fail | | |
| 2.2 | 前置条件 | Pulsar 集群 | broker≥2, bookie≥3 | ☐ Pass ☐ Fail | | |
| 2.2 | 前置条件 | TiDB 集群 | 副本=3, store Up | ☐ Pass ☐ Fail | | |
| 3.2 | Cell 部署 | kustomize 渲染 | 13 YAML 通过 | ☐ Pass ☐ Fail | | |
| 3.2 | Cell 部署 | kubeconform 校验 | 0 errors | ☐ Pass ☐ Fail | | |
| 3.3 | Cell 部署 | 探针健康 | 5 服务 UP | ☐ Pass ☐ Fail | | |
| 3.4 | Cell 部署 | Dubbo 注册 | 实例数=replicas | ☐ Pass ☐ Fail | | |
| 3.5 | Cell 部署 | Pulsar topic | 核心 topic 存在 | ☐ Pass ☐ Fail | | |
| 4.2 | 跨单元连通 | NetworkPolicy 强制 | 未授权被拒 | ☐ Pass ☐ Fail | | |
| 4.3 | 跨单元连通 | Dubbo 隔离 | namespace 隔离 | ☐ Pass ☐ Fail | | |
| 4.4 | 跨单元连通 | Pulsar Geo-Replication | 跨单元消费 | ☐ Pass ☐ Fail | | |
| 4.5 | 跨单元连通 | TiDB 读写分离 | Follower Read | ☐ Pass ☐ Fail | | |
| 5.2 | 数据一致性 | CDC 延迟 | <30s | ☐ Pass ☐ Fail | | |
| 5.3 | 数据一致性 | 读后写一致 | 跨单元可读 | ☐ Pass ☐ Fail | | |
| 5.4 | 数据一致性 | 幂等键 | COUNT=1 | ☐ Pass ☐ Fail | | |
| 5.5 | 数据一致性 | 乐观锁 | 仅 1 次成功 | ☐ Pass ☐ Fail | | |
| 6.2 | 流量切换 | Cell Drain | PDB 阻止 | ☐ Pass ☐ Fail | | |
| 6.3 | 流量切换 | Cell Failover | <30s 切换 | ☐ Pass ☐ Fail | | |
| 6.4 | 流量切换 | Cell 恢复重同步 | CDC 追平 | ☐ Pass ☐ Fail | | |
| 7.2 | 灾难恢复 | RTO/RPO | RTO<5min, RPO<30s | ☐ Pass ☐ Fail | | |
| 7.3 | 灾难恢复 | TiDB PITR | 校验和匹配 | ☐ Pass ☐ Fail | | |
| 7.4 | 灾难恢复 | Pulsar 回放 | 回放无重复 | ☐ Pass ☐ Fail | | |
| 7.5 | 灾难恢复 | Iceberg 回滚 | 数据一致 | ☐ Pass ☐ Fail | | |
| 8.2 | 性能基线 | k6 smoke | failed<1%, P95<500 | ☐ Pass ☐ Fail | | |
| 8.3 | 性能基线 | k6 ramp 10K | failed<1%, P99<2000 | ☐ Pass ☐ Fail | | |
| 9.2 | 安全 | FGAC 隔离 | 403 越权 | ☐ Pass ☐ Fail | | |
| 9.3 | 安全 | JWT RS256 | HS256/过期被拒 | ☐ Pass ☐ Fail | | |
| 9.4 | 安全 | default-deny | 未授权被拒 | ☐ Pass ☐ Fail | | |
| 9.5 | 安全 | OTel tail-sampling | 错误 trace 保留 | ☐ Pass ☐ Fail | | |

### 10.3 签收 (Sign-Off)

| 角色 | 姓名 | 签字 | 日期 |
|------|------|------|------|
| SRE 负责人 | | | |
| 架构师 | | | |
| DBA | | | |
| 中间件负责人 | | | |
| 安全负责人 | | | |
| 产品负责人 | | | |

### 10.4 验证报告归档要求

- 报告及全部证据 (命令输出、metric 截图、日志) 归档至 `s3://edu-audit/verify/YYYYMMDD/`。
- 归档保留期 ≥3 年 (合规要求)。
- GitOps commit sha 须可追溯至 Git 仓库具体提交。

---

## 11. 附录：术语与依据

### 11.1 术语表

| 术语 | 说明 |
|------|------|
| Cell (单元) | 自包含部署单元，单 Cell 100K 并发容量 (D-012) |
| 单元化 (Cell-based) | D-012 定义的架构模式，每单元含完整计算+数据+消息中间件 |
| 异地多活 (Multi-Active) | 多地域对等部署，均承接生产流量 |
| GSLB | 全局负载均衡 (F5/阿里云)，基于 DNS + HTTP 健康检查调度 |
| TiCDC | TiDB Change Data Capture，增量数据捕获 |
| ep-rw-java | 教育 Platform 读写分离 Java SDK，封装 TiDB Follower Read |
| FGAC | Fine-Grained Access Control，细粒度访问控制 (AI 中台租户隔离) |
| PDB | PodDisruptionBudget， voluntary 中断保护 (D-035 rule 3) |
| PITR | Point-in-Time Recovery，数据库时间点恢复 |
| ADR-T06 | 幂等键架构决策记录，保证接口幂等 |
| RTO | Recovery Time Objective，恢复时间目标 |
| RPO | Recovery Point Objective，恢复点目标 (数据丢失上限) |
| tail-sampling | OTel 尾部采样，按 trace 完整性决策采样 (D-035 rule 5) |

### 11.2 依据文档

| 编号 | 文档 | 说明 |
|------|------|------|
| SP07-DEPLOY-001 | docs/deployment/sp07-deploy.md | 多地多中心部署方案 |
| SP10-BU-REPLICATION-001 | docs/architecture/sp10-bu-replication.md | BU 数据复制方案 |
| D-012 | 单元化架构决策 | Cell-based 部署模式 |
| D-032 | 技术栈铁律 | SB4.0.7 / SC2025.1.2 / Dubbo3.3.6 / Nacos3.1.1 / Pulsar3.3.9 |
| D-035 | K8s 部署运维铁律 (8 条) | progressDeadline / PDB / NetworkPolicy / OTel 等 |
| G-01~G-10 | SLO 目标 | 30 亿用户 / 100 万并发 / 99.99% 可用性 |
| ADR-T06 | 幂等键决策 | X-Idempotency-Key 接口幂等 |

### 11.3 D-035 八条铁律映射

| D-035 规则 | 内容 | 本手册验证项 |
|------------|------|--------------|
| rule 1 | progressDeadlineSeconds ≥ 900 | §3.2 / §3.3 |
| rule 2 | (资源请求/限制) | §3.2 kubeconform |
| rule 3 | PDB minAvailable | §3.2 / §6.2 |
| rule 4 | NetworkPolicy 须 CNI 强制 | §4.2 / §9.4 |
| rule 5 | OTel tail-sampling | §9.5 |
| rule 6 | ConfigMap GitOps 管理 | §3.2 / §2.1 |
| rule 7 | (探针配置) | §3.3 |
| rule 8 | (命名规范) | §3.2 namePrefix |

---

*文档结束。任何对本手册的修改须经架构师 + SRE 联合评审，遵循变更管理流程。*
