# SP-03 业务中台 部署方案

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-03-DEPLOY | v1.0 | 2026-08-03 | Released | SRE 组 | SP-03-SAD；ADR-B02/B05；D-004/D-009/D-014 |

> 本部署方案覆盖 8 组件 K8s 部署、Rust 热点服务交叉编译、ES 集群、CI/CD、灰度、运维 runbook。

---

## 1. 部署总览

```
┌────────────────── K8s 集群(国内多活三角 + 海外独立)──────────────────┐
│                                                                    │
│  namespace: sp03(每单元独立 ns, 如 sp03-cell0/sp03-cell1)            │
│                                                                    │
│  ┌─ Java 组件(UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK) Spring Cloud ──┐    │
│  ├─ Rust 热点(OPM-Billing / RSK-Rule) ─────────────────────────┤    │
│  ├─ Go(WS/IM 网关) ────────────────────────────────────────────┤    │
│  └─ ES Operator / Milvus Operator / TiDB Operator(托管的 CR) ──┘    │
│                                                                    │
│  配置: Helm Chart + values-cell{N}.yaml(单元差异化)                  │
│  发布: ArgoCD GitOps + 灰度(Arsenal/Rollout)                        │
└────────────────────────────────────────────────────────────────────┘
```

---

## 2. K8s Deployment/HPA/PDB 配置(每组件)

### 2.1 UCM(示例, 其余组件同模式)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ucm
  namespace: sp03-cell0
  labels: { app: ucm, component: ucm, domain: user, cell: "0" }
spec:
  replicas: 3
  selector: { matchLabels: { app: ucm } }
  strategy:
    type: RollingUpdate
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  template:
    metadata:
      labels: { app: ucm, component: ucm, domain: user, cell: "0" }
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: /metrics
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm: { labelSelector: { matchLabels: { app: ucm } }, topologyKey: kubernetes.io/hostname }
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: { matchLabels: { app: ucm } }
      containers:
        - name: ucm
          image: registry.internal/sp03/ucm:1.0.0
          ports: [ { name: http, containerPort: 8080 }, { name: grpc, containerPort: 9090 } ]
          env:
            - name: CELL_ID
              valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
            - name: TIDB_DSN
              valueFrom: { secretKeyRef: { name: tidb-sp03, key: dsn } }
            - name: REDIS_CLUSTER_NODES
              valueFrom: { configMapKeyRef: { name: redis-sp03, key: nodes } }
            - name: PULSAR_URL
              valueFrom: { configMapKeyRef: { name: pulsar-sp03, key: url } }
          resources:
            requests: { cpu: "1", memory: "2Gi" }
            limits:   { cpu: "2", memory: "4Gi" }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 20
          lifecycle:
            preStop:
              exec: { command: [ "sh", "-c", "/app/graceful-shutdown.sh" ] }
      terminationGracePeriodSeconds: 60
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: ucm, namespace: sp03-cell0 }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: ucm }
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 65 } }
    - type: Resource
      resource: { name: memory, target: { type: Utilization, averageUtilization: 75 } }
  behavior:
    scaleUp:   { policies: [ { type: Percent, value: 100, periodSeconds: 60 } ] }
    scaleDown: { policies: [ { type: Percent, value: 50,  periodSeconds: 120 } ], stabilizationWindowSeconds: 300 }
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: ucm, namespace: sp03-cell0 }
spec:
  minAvailable: 2
  selector: { matchLabels: { app: ucm } }
```

### 2.2 各组件副本/资源/HPA 汇总

| 组件 | ns | replicas | req CPU/mem | HPA min/max | PDB minAvailable |
|------|----|---------:|------------|-------------|------------------|
| UCM | cell | 3 | 1/2Gi | 3/12 | 2 |
| OPM | cell | 3 | 2/4Gi | 3/15 | 2 |
| OPM-Billing(Rust) | cell | 2 | 1/1Gi | 2/8 | 1 |
| MMP-G | global | 3 | 1/2Gi | 3/8 | 2 |
| MMP-U | cell | 3 | 1/2Gi | 3/8 | 2 |
| NTF | cross | 3 | 1/2Gi | 3/10 | 2 |
| CMP | global | 3 | 1/2Gi | 3/8 | 2 |
| SRH | global | 3 | 2/4Gi | 3/10 | 2 |
| TKT | global | 3 | 1/2Gi | 3/8 | 2 |
| TKT-WS(Go) | global | 3 | 1/1Gi | 3/15 | 2 |
| RSK | global+cell | 3 | 1/2Gi | 3/10 | 2 |
| RSK-Rule(Rust) | global | 2 | 1/1Gi | 2/8 | 1 |

---

## 3. Rust 热点服务交叉编译部署

### 3.1 多架构镜像构建

```dockerfile
# Dockerfile.billing (OPM-Billing, Rust)
FROM --platform=$BUILDPLATFORM rust:1.81 AS builder
WORKDIR /app
COPY . .
RUN apt-get update && apt-get install -y protobuf-compiler && \
    rustup target add aarch64-unknown-linux-gnu x86_64-unknown-linux-gnu
# 交叉编译(根据 BUILDPLATFORM 分支)
RUN cargo build --release --target ${TARGETARCH//amd64/x86_64}-unknown-linux-gnu

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=builder /app/target/*/release/billing_engine /usr/local/bin/billing_engine
USER nonroot
EXPOSE 9090
ENTRYPOINT ["/usr/local/bin/billing_engine"]
```

CI(GitHub Actions/自建)：
```yaml
jobs:
  build-rust:
    strategy: { matrix: { arch: [amd64, arm64] } }
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/build-push-action@v5
        with:
          context: .
          file: Dockerfile.billing
          platforms: linux/amd64,linux/arm64
          push: true
          tags: registry.internal/sp03/billing:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### 3.2 Rust 服务部署清单

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: opm-billing, namespace: sp03-cell0 }
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: billing
          image: registry.internal/sp03/billing:1.0.0
          ports: [ { name: grpc, containerPort: 9090 } ]
          env:
            - { name: RUST_LOG, value: info }
            - { name: REDIS_CLUSTER_NODES, valueFrom: { configMapKeyRef: { name: redis-sp03, key: nodes } } }
          resources:
            requests: { cpu: "1", memory: "1Gi" }
            limits:   { cpu: "2", memory: "2Gi" }
          readinessProbe: { grpc: { port: 9090 }, initialDelaySeconds: 5 }
          livenessProbe:  { grpc: { port: 9090 }, initialDelaySeconds: 10 }
```

Rust 优势：启动秒级(无 JVM)，HPA 弹性更敏捷。

---

## 4. ES 集群配置(SRH)

```
ES 集群(全局域, 跨区):
  - 3 master(华北/华东/华南各 1, anti-affinity)
  - data 节点按索引分: srh_course / srh_question
    data 节点 6 起(每区 2), 按量扩
  - coordinating 节点 3(查询路由)

ES Operator(ECK):
  apiVersion: elasticsearch.k8s.elastic.co/v1
  kind: Elasticsearch
  metadata: { name: srh-es, namespace: sp03-global }
  spec:
    version: 8.14.x
    nodeSets:
      - name: masters
        count: 3
        config: { node.roles: [master], discovery.zen.minimum_master_nodes: 2 }
      - name: data-course
        count: 6
        config: { node.roles: [data, ingest], index.codec: best_compression }
        volumeClaimTemplates: [ { metadata: { name: data }, spec: { accessModes: [ReadWriteOnce], resources: { requests: { storage: 500Gi } } } } ]
    http: { tls: { selfSignedCertificate: { disabled: true } } }
```

索引管理：
- ILM(rollover 100GB / 30d, force_merge, delete 180d)
- 蓝绿索引重建 + alias 切换
- slowlog 监控 → Prometheus

---

## 5. CI/CD Pipeline

### 5.1 流水线

```
Push/PR
  ├─ Lint(Java checkstyle / Rust clippy / Go vet)
  ├─ L1 单元测试(覆盖率 ≥80%)
  ├─ L2 集成测试(Testcontainers)
  ├─ L3 契约测试(Pact 校验)
  ├─ 构建(Java multi-arch / Rust cross / Go)
  ├─ 镜像扫描(Trivy, 高危阻断)
  ├─ 部署 staging → L4/L5 验证
  └─ 人工审批 → ArgoCD 同步 prod(灰度)
```

### 5.2 ArgoCD GitOps

- Git 仓 `sp03-deploy` 存 Helm Chart + values-cell{N}.yaml
- ArgoCD 监听, 自动同步
- 灰度: Argo Rollouts(canary 10%→50%→100%, 按错误率/P95 自动推进/回滚)

### 5.3 配置管理

- Nacos: 风控规则 / 路由表 / 业务开关
- K8s ConfigMap/Secret: 静态配置 / 凭证
- Secret 加密: Sealed Secrets 或 Vault

---

## 6. 灰度发布

### 6.1 灰度策略

| 维度 | 策略 |
|------|------|
| 流量 | Argo Rollouts canary 10%→50%→100% |
| 单元 | 单元灰度(cell0→cell1→cell2) |
| 用户 | 按 user_id % 100 灰度 |
| BU | 单 BU 试点(IT 后端先行) |
| 规则 | RSK 规则灰度比例(0%/10%/100%) |

### 6.2 回滚

- Argo Rollouts 自动回滚(错误率>0.5% 或 P95>SLO)
- 人工回滚: ArgoCD 一键回滚到上一版
- 数据回滚: 不回滚(向前修复 + 兼容); schema 变更必须向前兼容

---

## 7. 运维 Runbook

### 7.1 支付故障处理

```
告警: 支付回调失败率 >1% 持续 1min
  │
  ▼
1. 确认范围(单单元/全局, 单渠道/全渠道)
   - kubectl logs -n sp03-cell0 deploy/opm | grep callback
   - 检查支付网关状态页
2. 若支付网关故障:
   - 切换备用渠道(MMP 配置)
   - 通知 NTF 发公告
3. 若 OPM 自身故障:
   - 检查 TiDB 连接 / Redis 连接
   - HPA 是否扩容
   - 必要时降级: 暂停新下单, 已支付订单保护
4. 幂等键冲突告警:
   - 检查 Redis 集群健康
   - 检查 t_opm_payment 唯一索引冲突
5. 对账差异告警:
   - 差异挂起队列确认
   - 人工对账凭证
6. 事后: 故障复盘 + runbook 更新
```

### 7.2 风控规则热更新

```
操作: 管理员在控制台修改规则 RULE_A 拦截→放行
  │
  ▼
1. 控制台调用 RSK 规则 API(写 t_rsk_rule 新版本)
2. Nacos 配置中心发布新规则版本
3. RSK-Rule(Rust) 各节点 Nacos listener 触发, 原子 swap Arc<RuleSet>
4. 5s 内全节点生效(不重启)
5. 监控:
   - 规则版本分布(各节点当前版本)
   - 拦截率变化
   - P95 <10ms
6. 回滚: 控制台一键回滚到上一版本(同样 5s 生效)
7. 审计: 规则变更人/时间/版本落 Iceberg
```

### 7.3 单单元故障切换

```
告警: Cell-1 健康检查失败 / TiDB 分片主节点宕
  │
  ▼
1. D-014 流量纠正: CellRouter 将 Cell-1 流量自动纠正到 Cell-0/Cell-2
2. 跨单元写挂起: Cell-1 的 user_id 写操作挂起队列
3. TiDB Raft 多数派切主(跨区, <30s)
4. 切换完成后挂起队列重放
5. 验证: 健康检查恢复, 数据一致
6. 故障单元修复后回切(灰度)
```

### 7.4 ES 索引重建

```
场景: 索引 mapping 变更 / 全量重建
  │
  ▼
1. 创建新索引 srh_course_v2
2. 全量同步(CMP 主库 → Flink CDC → ES v2)
3. 验证文档数一致
4. alias 切换: srh_course → v2(原子)
5. 监控查询错误率
6. 删除旧索引 v1
```

---

## 8. 可观测

| 维度 | 工具 | 指标 |
|------|------|------|
| Metrics | Prometheus + Grafana | QPS/P50/P95/P99/错误率/资源/业务指标 |
| Logs | Loki | 结构化 JSON, trace_id 关联 |
| Traces | Tempo + OpenTelemetry | W3C traceparent 全链路 |
| 告警 | Alertmanager → 钉钉/PagerDuty | SLO 违例 / 故障 |
| 审计 | Iceberg + BI | 管理员/客服操作 |

关键面板：
- OPM 订单/支付大盘(OPS/回调成功率/对账差异)
- RSK 风控大盘(拦截率/误拦率/规则版本分布/P95)
- UCM 登录/注册大盘(成功率/多端在线数)
- 单元健康(Cell 各组件 up/写挂起队列深度)

---

## 9. 容量与扩容

| 组件 | 扩容信号 | 动作 |
|------|---------|------|
| OPM | CPU>65% / OPS 接近上限 | HPA 扩 + 考虑增单元 |
| RSK-Rule | P95 接近 10ms | HPA 扩 Rust 副本 |
| TKT-WS | 并发连接接近上限 | HPA 扩 + 调整 ulimit |
| ES | 磁盘>70% | 加 data 节点 + ILM |
| TiDB | Region 热点 / CPU>70% | 加 TiKV + PRE_SPLIT |
| Redis | 内存>70% | 扩分片 |

增单元流程(M3 阶段)：
1. 新 Cell 部署(ns/Helm/values)
2. 路由表 Nacos 更新(N+1)
3. 灰度切流(新单元 5%→50%→100%)
4. 数据迁移(可选, 按需)

---

## 10. 海外独立部署

- 新加坡 + 法兰克福单元，独立 K8s 集群
- 海外 CMP 白名单同步(CDC 增量, 审核流)
- 海外用户数据不回流国内(PIPL)
- 海外单元复用同一 Helm Chart, values-overseas.yaml 差异化

---

## 11. 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | SRE 组 |
