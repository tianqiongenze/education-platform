# SP-01 技术中台 —— 部署与运维文档

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP01-DEPLOY-001 | v1.0 | 2026-08-03 | Approved | ZCode(文档工程师) | SP01-SAD-001 / ADR-T05 / G-01~G-10 SLO |

> 本文定义 SP-01 技术中台的 Kubernetes 部署清单、Helm Chart 结构、CI/CD 流水线、探针三分离配置、HPA 弹性伸缩、金丝雀发布策略及运维 Runbook。探针配置严格遵循 ADR-T05。

---

## 目录

1. [Kubernetes 部署清单](#1-kubernetes-部署清单)
2. [Helm Chart 结构](#2-helm-chart-结构)
3. [CI/CD 流水线](#3-cicd-流水线)
4. [探针三分离配置(ADR-T05)](#4-探针三分离配置adr-t05)
5. [HPA 弹性伸缩配置](#5-hpa-弹性伸缩配置)
6. [金丝雀发布策略](#6-金丝雀发布策略)
7. [运维 Runbook](#7-运维-runbook)
8. [容量规划](#8-容量规划)
9. [灾备与多活](#9-灾备与多活)

---

## 1. Kubernetes 部署清单

### 1.1 UCM-Service Deployment(示例模板)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ucm-service
  namespace: prod
  labels:
    app: ucm-service
    cell: cell-0
    tier: backend
spec:
  replicas: 4
  selector:
    matchLabels: { app: ucm-service, cell: cell-0 }
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: ucm-service
        cell: cell-0
        tier: backend
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: ucm-sa
      terminationGracePeriodSeconds: 60
      containers:
      - name: app
        image: registry.cn-beijing.aliyuncs.com/ep/ucm-service:v1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - { name: http,    containerPort: 8080 }
        - { name: grpc,    containerPort: 9090 }
        - { name: metrics, containerPort: 8081 }
        env:
        - { name: SPRING_PROFILES_ACTIVE, value: "prod" }
        - { name: CELL_ID, value: "cell-0" }
        - { name: NACOS_ADDR, valueFrom: { configMapKeyRef: { name: ep-config, key: nacos-addr } } }
        - { name: REDIS_ADDR, valueFrom: { configMapKeyRef: { name: ep-config, key: redis-addr } } }
        - { name: PULSAR_ADDR, valueFrom: { configMapKeyRef: { name: ep-config, key: pulsar-addr } } }
        resources:
          requests: { cpu: "500m", memory: "1Gi" }
          limits:   { cpu: "2000m", memory: "4Gi" }
        # === 探针三分离(ADR-T05) ===
        startupProbe:
          httpGet: { path: /actuator/health/startup, port: http }
          timeoutSeconds: 3
          failureThreshold: 30
          periodSeconds: 10
        livenessProbe:
          httpGet: { path: /actuator/health/liveness, port: http }
          timeoutSeconds: 3
          failureThreshold: 5
          periodSeconds: 10
        readinessProbe:
          httpGet: { path: /actuator/health/readiness, port: http }
          timeoutSeconds: 3
          failureThreshold: 5
          periodSeconds: 5
        lifecycle:
          preStop:
            exec:
              command: ["sh", "-c", "sleep 10"]  # 优雅下线等待连接排空
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector: { matchLabels: { app: ucm-service } }
              topologyKey: kubernetes.io/hostname
```

### 1.2 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ucm-service
  namespace: prod
spec:
  selector: { app: ucm-service, cell: cell-0 }
  ports:
  - { name: http,    port: 80, targetPort: 8080 }
  - { name: grpc,    port: 9090, targetPort: 9090 }
  type: ClusterIP
```

### 1.3 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ucm-service
  namespace: prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ucm-service
  minReplicas: 4
  maxReplicas: 40
  metrics:
  - type: Resource
    resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
  - type: Resource
    resource: { name: memory, target: { type: Utilization, averageUtilization: 80 } }
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0        # 立即扩容
      policies:
      - { type: Percent, value: 100, periodSeconds: 30 }  # 30s 内最多翻倍
    scaleDown:
      stabilizationWindowSeconds: 120       # 2min 稳定后再缩
      policies:
      - { type: Percent, value: 25, periodSeconds: 120 }  # 2min 最多缩 25%
```

### 1.4 PDB

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: ucm-service
  namespace: prod
spec:
  minAvailable: 2          # 至少 2 个可用
  selector:
    matchLabels: { app: ucm-service }
```

### 1.5 NetworkPolicy

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ucm-service-netpol
  namespace: prod
spec:
  podSelector:
    matchLabels: { app: ucm-service }
  policyTypes: [Ingress, Egress]
  ingress:
  - from:
    - podSelector: { matchLabels: { app: gateway } }      # 仅网关可访问
    ports:
    - { protocol: TCP, port: 8080 }
    - { protocol: TCP, port: 9090 }
  - from:
    - namespaceSelector: { matchLabels: { name: monitoring } }  # Prometheus 抓取
    ports:
    - { protocol: TCP, port: 8081 }
  egress:
  - to:
    - podSelector: { matchLabels: { app: mysql } }
    ports: [{ protocol: TCP, port: 3306 }]
  - to:
    - podSelector: { matchLabels: { app: redis } }
    ports: [{ protocol: TCP, port: 6379 }]
  - to:
    - podSelector: { matchLabels: { app: nacos } }
    ports: [{ protocol: TCP, port: 8848 }]
  - to:
    - podSelector: { matchLabels: { app: pulsar } }
    ports: [{ protocol: TCP, port: 6650 }]
  - to:                                 # DNS
    - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } }
    ports: [{ protocol: UDP, port: 53 }]
```

### 1.6 组件部署清单矩阵

| 组件 | 副本数 | CPU req/lim | Mem req/lim | HPA | PDB minAvail |
|------|--------|-------------|-------------|-----|-------------|
| API Gateway | 6 | 1/4 | 2G/8G | 6-60 | 4 |
| WS Gateway | 4 | 1/4 | 2G/8G | 4-40 | 3 |
| UCM-Service | 4 | 0.5/2 | 1G/4G | 4-40 | 2 |
| CMP-Service | 4 | 0.5/2 | 1G/4G | 4-40 | 2 |
| Order-Service | 4 | 0.5/2 | 1G/4G | 4-40 | 2 |
| Rust 风控 | 2 | 1/4 | 0.5G/2G | 2-20 | 1 |
| CellRouter | 3 | 0.5/2 | 0.5G/2G | 3-20 | 2 |

---

## 2. Helm Chart 结构

### 2.1 目录结构

```
charts/tech-platform/
├── Chart.yaml                    # Chart 元数据
├── values.yaml                   # 默认值
├── values-prod.yaml              # 生产覆盖
├── values-staging.yaml           # 预发布覆盖
├── templates/
│   ├── _helpers.tpl              # 模板辅助函数
│   ├── deployment.yaml           # Deployment 模板(循环各组件)
│   ├── service.yaml              # Service
│   ├── hpa.yaml                  # HPA
│   ├── pdb.yaml                  # PDB
│   ├── networkpolicy.yaml        # NetworkPolicy
│   ├── configmap.yaml            # ConfigMap(Nacos 地址等)
│   ├── secret.yaml               # Secret(密钥,Sealed Secrets)
│   ├── serviceaccount.yaml       # SA
│   └── ingress.yaml              # Ingress(网关入口)
└── charts/                       # 子 Chart 依赖
    ├── mysql/
    ├── redis/
    ├── pulsar/
    └── nacos/
```

### 2.2 Chart.yaml

```yaml
apiVersion: v2
name: tech-platform
description: SP-01 技术中台 Helm Chart
type: application
version: 1.0.0
appVersion: "1.0.0"
dependencies:
- name: mysql
  version: 11.x
  repository: https://charts.bitnami.com/bitnami
  condition: mysql.enabled
- name: redis
  version: 20.x
  repository: https://charts.bitnami.com/bitnami
  condition: redis.enabled
```

### 2.3 values.yaml(节选)

```yaml
global:
  registry: registry.cn-beijing.aliyuncs.com/ep
  imageTag: v1.0.0
  cellId: cell-0

gateway:
  enabled: true
  replicas: 6
  resources:
    requests: { cpu: 1, memory: 2Gi }
    limits:   { cpu: 4, memory: 8Gi }
  hpa:
    minReplicas: 6
    maxReplicas: 60
    cpuUtilization: 70

ucmService:
  enabled: true
  replicas: 4
  resources:
    requests: { cpu: 500m, memory: 1Gi }
    limits:   { cpu: 2, memory: 4Gi }

# 探针默认值(ADR-T05)
probes:
  startup:
    timeoutSeconds: 3
    failureThreshold: 30
    periodSeconds: 10
  liveness:
    timeoutSeconds: 3
    failureThreshold: 5
    periodSeconds: 10
  readiness:
    timeoutSeconds: 3
    failureThreshold: 5
    periodSeconds: 5

# HPA 行为默认值
hpaBehavior:
  scaleUp:
    stabilizationWindowSeconds: 0
    policies:
    - { type: Percent, value: 100, periodSeconds: 30 }
  scaleDown:
    stabilizationWindowSeconds: 120
    policies:
    - { type: Percent, value: 25, periodSeconds: 120 }
```

### 2.4 部署命令

```bash
# 部署到 staging
helm upgrade --install tech-platform ./charts/tech-platform \
  -f values.yaml -f values-staging.yaml \
  -n staging

# 部署到 prod
helm upgrade --install tech-platform ./charts/tech-platform \
  -f values.yaml -f values-prod.yaml \
  -n prod
```

---

## 3. CI/CD 流水线

### 3.1 流水线阶段

```
代码提交 → Lint → 单元测试 → 构建 → SAST/SCA → 镜像扫描 → 部署 staging → 集成测试 → 性能测试 → 金丝雀 → 全量发布
```

### 3.2 GitHub Actions 示例

```yaml
name: tech-platform-ci

on:
  push:
    branches: [main, release/*]
  pull_request:
    branches: [main]

jobs:
  lint-test:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { java-version: 21, distribution: temurin }
    - name: Lint
      run: ./mvnw -B checkstyle:check
    - name: Unit Tests + Coverage
      run: ./mvnw -B verify
    - name: JaCoCo Report
      run: ./mvnw -B jacoco:report
    - name: Coverage Gate
      run: |
        COV=$(python3 scripts/check_coverage.py)
        echo "Coverage: $COV"
        [ "$COV" -ge 80 ] || { echo "Coverage below 80%"; exit 1; }

  build-scan:
    needs: lint-test
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - name: Build Image
      run: docker build -t ep/${{ matrix.service }}:${{ github.sha }} .
    - name: Trivy Scan
      run: trivy image --exit-code 1 --severity HIGH,CRITICAL ep/${{ matrix.service }}:${{ github.sha }}
    - name: Aikido SAST
      run: aikido scan --path .
    - name: Push Image
      run: |
        docker tag ep/${{ matrix.service }}:${{ github.sha }} ${{ secrets.REGISTRY }}/ep/${{ matrix.service }}:${{ github.sha }}
        docker push ${{ secrets.REGISTRY }}/ep/${{ matrix.service }}:${{ github.sha }}
    strategy:
      matrix:
        service: [gateway, ucm-service, cmp-service, order-service, rust-risk]

  deploy-staging:
    needs: build-scan
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
    - name: Deploy to Staging
      run: |
        helm upgrade --install tech-platform ./charts/tech-platform \
          -f values.yaml -f values-staging.yaml \
          --set global.imageTag=${{ github.sha }} \
          -n staging
    - name: Integration Tests
      run: ./scripts/run-integration-tests.sh staging

  canary:
    needs: deploy-staging
    runs-on: ubuntu-latest
    steps:
    - name: Canary 10%
      run: ./scripts/canary-deploy.sh 10
    - name: Monitor 5min
      run: ./scripts/monitor-canary.sh 300
    - name: Canary 50%
      run: ./scripts/canary-deploy.sh 50
    - name: Monitor 5min
      run: ./scripts/monitor-canary.sh 300
    - name: Full Rollout
      run: ./scripts/canary-deploy.sh 100
```

---

## 4. 探针三分离配置(ADR-T05)

### 4.1 设计原理

| 探针 | 作用 | 失败行为 | 配置依据 |
|------|------|---------|---------|
| startupProbe | 等待应用启动完成 | 启动期间不触发重启 | 慢启动(JIT 预热)需 5min |
| livenessProbe | 检测死锁/僵死 | 重启 Pod | 与业务健康解耦 |
| readinessProbe | 流量就绪判断 | 从 Service 摘除 | 依赖检查(MySQL/Redis 连通) |

### 4.2 配置参数表

| 参数 | startup | liveness | readiness |
|------|---------|----------|-----------|
| httpGet.path | /actuator/health/startup | /actuator/health/liveness | /actuator/health/readiness |
| timeoutSeconds | 3 | 3 | 3 |
| failureThreshold | 30 | 5 | 5 |
| periodSeconds | 10 | 10 | 5 |
| 最大容忍时间 | 5min(30×10s) | 50s(5×10s) | 25s(5×5s) |

### 4.3 Spring Boot Actuator 端点

```java
@Component
public class HealthIndicators {
    @Bean
    public HealthIndicator startupHealth() {
        return () -> appStarted.get() ? Health.up().build() : Health.outOfService().build();
    }

    @Bean
    public HealthIndicator livenessHealth() {
        return () -> !deadlockDetected ? Health.up().build() : Health.down().build();
    }

    @Bean
    public HealthIndicator readinessHealth() {
        return () -> {
            if (!mysqlReady.get() || !redisReady.get()) return Health.outOfService().build();
            return Health.up().build();
        };
    }
}
```

### 4.4 分组配置

```yaml
# application.yml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        startup:   { include: startupHealth }
        liveness:  { include: livenessHealth }
        readiness: { include: readinessHealth,mysqlHealth,redisHealth }
```

---

## 5. HPA 弹性伸缩配置

### 5.1 扩缩容策略

| 方向 | 稳定窗口 | 策略 | 说明 |
|------|---------|------|------|
| scaleUp | 0s | 100%/30s | 30s 内最多翻倍，立即响应流量突增 |
| scaleDown | 120s | 25%/120s | 2min 稳定后缩容，每次最多缩 25%，防抖 |

### 5.2 伸缩指标

| 指标 | 目标 | 说明 |
|------|------|------|
| CPU 利用率 | 70% | 主指标 |
| 内存利用率 | 80% | 辅助指标 |
| 自定义(QPS) | 5000/pod | 可选(需 Adapter) |

### 5.3 扩容场景验证

| 场景 | 初始 | 峰值 | 扩容后 | 耗时 |
|------|------|------|--------|------|
| 日常流量 | 4 pod | 10万 QPS | 20 pod | ~90s |
| 营销活动 | 4 pod | 30万 QPS | 40 pod | ~3min |
| 故障转移 | 8 pod(cell-0) | cell-1 流量涌入 | 20 pod | ~2min |

---

## 6. 金丝雀发布策略

### 6.1 发布流程

```
1. 新版本镜像推送到 registry
2. 部署 canary Deployment(replicas=1，打 canary 标签)
3. 配置 Service 流量权重: stable 90% + canary 10%
4. 监控 5min: P99 延迟 / 错误率 / SLO
   ├─ 通过 → canary 50%, 再监控 5min
   │    ├─ 通过 → 全量切换(100%)
   │    └─ 失败 → 回滚 canary
   └─ 失败 → 回滚 canary
5. 全量切换后,旧版本保留 30min 作为快速回滚点
6. 30min 后清理旧版本
```

### 6.2 Canary Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ucm-service-canary
  namespace: prod
spec:
  replicas: 1
  selector:
    matchLabels: { app: ucm-service, track: canary }
  template:
    metadata:
      labels: { app: ucm-service, track: canary, cell: cell-0 }
    spec:
      containers:
      - name: app
        image: registry.cn-beijing.aliyuncs.com/ep/ucm-service:v1.1.0
        # ... 同 stable 配置
```

### 6.3 流量切分(NGINX Ingress)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ucm-service
  namespace: prod
  annotations:
    nginx.ingress.kubernetes.io/canary: "true"
    nginx.ingress.kubernetes.io/canary-weight: "10"
spec:
  rules:
  - http:
      paths:
      - path: /api/users
        pathType: Prefix
        backend:
          service: { name: ucm-service-canary, port: { number: 80 } }
```

### 6.4 监控与回滚

| 指标 | 阈值 | 动作 |
|------|------|------|
| 错误率 | >1% | 自动回滚 |
| P99 延迟 | 回归 >20% | 人工评估 |
| SLO 违规 | 任何 | 自动回滚 |
| Pod CrashLoop | canary pod | 自动回滚 |

```bash
# 回滚命令
kubectl rollout undo deployment/ucm-service -n prod
kubectl delete deployment ucm-service-canary -n prod
```

---

## 7. 运维 Runbook

### 7.1 告警分级

| 级别 | 响应时间 | 示例 |
|------|---------|------|
| P0(致命) | 5min | 全站不可用 |
| P1(严重) | 15min | 单 cell 不可用 |
| P2(警告) | 1h | 单 Pod 频繁重启 |
| P3(提示) | 4h | 磁盘使用 80% |

### 7.2 常见故障处置

#### 7.2.1 Pod 频繁重启(P2)

```
诊断:
1. kubectl describe pod <pod> -n prod  → 查看 Events
2. kubectl logs <pod> -n prod --previous  → 查看重启前日志
3. 检查 OOMKilled: kubectl get pod <pod> -o jsonpath='{.status.containerStatuses[0].lastState}'
4. 检查 livenessProbe 失败次数

处置:
- OOMKilled → 提升 memory limit 或排查内存泄漏
- liveness 失败 → 检查死锁/线程阻塞(jstack)
- 依赖超时 → 检查 MySQL/Redis 连通性
```

#### 7.2.2 MySQL 主库故障(P0/P1)

```
诊断:
1. 确认主库不可用: mysql -h master -e "select 1"
2. 检查 WriteHoldRegistry 是否已挂起写请求
3. 检查只读流量是否切换到 follower

处置:
1. 确认 failover 触发(Orchestrator 自动切换)
2. 验证新主库: mysql -h new-master -e "select 1"
3. 解除 WriteHoldRegistry 挂起
4. 监控写流量恢复
5. 事后: 分析故障根因,更新 ADR
```

#### 7.2.3 Redis 集群故障(P1)

```
诊断:
1. redis-cli -c ping
2. 检查 ep-cache 日志: "L2 redis miss/degrade" 频率
3. 检查 L1 缓存命中率是否上升(降级生效)

处置:
1. 部分节点故障 → Cluster 自动 failover,无需干预
2. 全集群不可用 → L2 降级,L1 继续工作(ADR-T04 降级契约)
3. 恢复后 → 预热 L2(可选)
```

#### 7.2.4 Pulsar 事件积压(P2)

```
诊断:
1. pulsar-admin topics stats <topic> → 查看 backlog
2. 检查消费者速率: subscriptions[0].msgRateOut
3. 检查消费者 Pod 状态

处置:
1. 消费者慢 → 扩容消费者 Pod
2. 消费者异常 → 检查日志,修复后重启
3. 积压过多 → 临时增加 consumer 并发度
```

#### 7.2.5 Cell misroute 告警(P2)

```
诊断:
1. 检查 CellRouter 纠正计数器: ep:correct_count:{userId}
2. 检查路由表是否与 Nacos 一致
3. 检查 DNS/Nacos 解析

处置:
1. 路由表过期 → 强制刷新 Nacos 配置
2. 持续 misroute → 检查 hash 算法一致性
3. 纠正计数超限 → 检查是否有客户端伪造 cell header
```

### 7.3 日常巡检清单

| 项 | 频率 | 检查点 |
|----|------|--------|
| Pod 健康 | 每日 | 无 CrashLoop,探针通过 |
| HPA | 每日 | 扩缩容正常,无抖动 |
| 磁盘 | 每日 | <80% |
| 证书 | 每周 | TLS 证书有效期 >30 天 |
| 备份 | 每日 | MySQL/Redis 备份成功 |
| 日志 | 每日 | 无 ERROR 级别异常堆积 |
| SLO | 每日 | G-01~G-10 达标 |
| 安全 | 每周 | SAST 扫描无新增高危 |

---

## 8. 容量规划

### 8.1 单 cell 资源(10万 CCU)

| 资源 | 数量 | 规格 |
|------|------|------|
| K8s 节点 | 20 | 16C/64G |
| MySQL | 1主2从 | 32C/128G + 2TB NVMe |
| Redis | 6 节点(3主3从) | 8C/32G + 500GB |
| Pulsar | 3 broker | 16C/64G + 1TB |
| Nacos | 3 节点 | 4C/8G |

### 8.2 多 cell 扩展(30亿用户/100万 CCU)

| 规模 | Cell 数 | 总节点 | 说明 |
|------|--------|--------|------|
| M0 | 2 | 40 | 国内双活 |
| M1 | 5 | 100 | 国内多活 |
| M3 | 10+ | 200+ | 国内多活 + 海外隔离 |

---

## 9. 灾备与多活

### 9.1 RPO/RTO

| 场景 | RPO | RTO |
|------|-----|-----|
| 单 Pod 故障 | 0 | <30s |
| 单节点故障 | 0 | <2min |
| 单 cell 故障 | <1min | <5min |
| 区域故障 | <5min | <15min |

### 9.2 备份策略

| 数据 | 频率 | 保留 | 方式 |
|------|------|------|------|
| MySQL | 每日全量 + binlog 实时 | 30天 | xtrabackup + OSS |
| Redis | 每日 RDB | 7天 | RDB + AOF |
| Nacos 配置 | 每日 | 90天 | 导出 JSON + OSS |
| Pulsar | 跨 cell 复制 | 7天 | Geo Replication |

### 9.3 灾备演练

- 每季度执行一次 cell 级 failover 演练
- 验证 RPO/RTO 达标
- 演练后更新 Runbook

---

**文档结束**

> 本部署文档是 SP-01 技术中台的运维基线。探针三分离严格遵循 ADR-T05，HPA 配置遵循"快速扩容、谨慎缩容"原则。所有变更须经运维评审，重大变更更新本文档并同步 ADR。
