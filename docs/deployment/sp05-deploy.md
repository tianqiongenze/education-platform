# SP-05 IT 后端 MVP 部署方案

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-05-DEPLOY | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-05-SAD v1.0 + SP-05-SDD v1.0 + ADR-I01..I05 |

> 本部署方案覆盖 4 子平台（直播/在线编程/作业/评分）、三端前端、CI/CD pipeline、灰度发布、运维 runbook。单 cell MVP 部署，预留多 cell。

---

## 1. 引言

### 1.1 目的
定义 SP-05 IT 后端 MVP 的部署架构、K8s 资源配置、CI/CD pipeline、灰度发布策略、运维 runbook，作为部署执行与运维的依据。

### 1.2 范围
- 直播：SRS v7 origin/edge K8s 部署、LiveKit SFU 部署、CDN 配置、录制存储
- 在线编程：Firecracker K8s CRD 部署、SandboxWarmPool 配置、judge0 worker 部署
- 作业/评分：Java 服务 K8s Deployment/HPA、Firecracker 共享
- 三端前端：web(React)构建部署、h5 部署、小程序发布
- CI/CD pipeline、灰度发布、运维 runbook

### 1.3 依据
- SP-05-SAD v1.0 §9 部署拓扑
- ADR-I01..I05

---

## 2. 部署架构总览

```
┌──────────────────────── 单 cell K8s 集群（MVP）────────────────────────┐
│                                                                        │
│  namespace: it-backend-frontend                                        │
│    web-nginx │ h5-nginx │ (小程序走微信发布,不在集群)                    │
│                                                                        │
│  namespace: it-backend-bff                                             │
│    bff-scg(Java) │ ws-gateway(Go) │ cell-router(Spring)               │
│                                                                        │
│  namespace: it-backend-user-domain                                     │
│    account │ learning │ order │ profile (Java)                        │
│                                                                        │
│  namespace: it-backend-live                                            │
│    live-room(Java) │ srs-origin(Go) │ srs-edge(Go) │ livekit-sfu(Go)  │
│                                                                        │
│  namespace: it-backend-ide                                             │
│    ide-session(TS/Rust) │ yjs-ws(Go) │ judge0 │ judge0-worker(Rust)   │
│    firecracker-crd-controller(Rust) │ SandboxWarmPool                  │
│                                                                        │
│  namespace: it-backend-assignment                                      │
│    assignment(Java) │ plagiarism-tier1(Python) │ plagiarism-tier2(Python) │
│                                                                        │
│  namespace: it-backend-grading                                         │
│    grading-pipeline(Java) │ llm-grader(Python) │ grade-agg(Java)       │
│                                                                        │
│  namespace: middleware-platform(复用,技术中台)                          │
│    tech/data/business/ai 中台副本                                       │
│                                                                        │
│  namespace: infra(复用基础设施)                                         │
│    TiDB/Redis/Pulsar/Kafka/Milvus/MinIO/CDN/SRS/LiveKit/OTel          │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 直播平台 Live 部署

### 3.1 SRS v7 origin/edge K8s 部署

```yaml
# SRS origin Deployment(无状态,水平扩展)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: srs-origin
  namespace: it-backend-live
spec:
  replicas: 3
  selector:
    matchLabels: {app: srs-origin}
  template:
    metadata:
      labels: {app: srs-origin}
    spec:
      containers:
      - name: srs
        image: ossrs/srs:v7
        ports:
        - {containerPort: 1935, name: rtmp}    # RTMP
        - {containerPort: 8080, name: http}    # HTTP-FLV/HLS
        - {containerPort: 1985, name: api}     # API
        - {containerPort: 8000, name: whip}    # WHIP
        resources:
          requests: {cpu: "2", memory: "4Gi"}
          limits:   {cpu: "4", memory: "8Gi"}
        env:
        - {name: SRS_LISTEN, value: "1935"}
        - {name: SRS_HTTP_SERVER, value: "on"}
        - {name: SRS_HTTP_API, value: "on"}
        - {name: SRS_VHOST_ORIGIN, value: "cluster"}
        volumeMounts:
        - {name: config, mountPath: /usr/local/srs/conf}
      volumes:
      - name: config
        configMap: {name: srs-origin-config}
---
# SRS origin Service
apiVersion: v1
kind: Service
metadata:
  name: srs-origin
  namespace: it-backend-live
spec:
  selector: {app: srs-origin}
  ports:
  - {name: rtmp, port: 1935, targetPort: 1935}
  - {name: http, port: 8080, targetPort: 8080}
  - {name: api, port: 1985, targetPort: 1985}
  - {name: whip, port: 8000, targetPort: 8000}
---
# SRS edge Deployment(边缘拉流,前叠 Squid/ATS 缓存 HLS)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: srs-edge
  namespace: it-backend-live
spec:
  replicas: 5
  selector:
    matchLabels: {app: srs-edge}
  template:
    metadata:
      labels: {app: srs-edge}
    spec:
      containers:
      - name: srs
        image: ossrs/srs:v7
        ports:
        - {containerPort: 8080, name: hls}
        env:
        - {name: SRS_VHOST_EDGE, value: "cluster"}
        - {name: SRS_EDGE_ORIGIN, value: "srs-origin.it-backend-live.svc"}
```

### 3.2 LiveKit SFU 部署（StatefulSet，需高带宽）

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: livekit-sfu
  namespace: it-backend-live
spec:
  serviceName: livekit-sfu
  replicas: 3
  selector:
    matchLabels: {app: livekit-sfu}
  template:
    metadata:
      labels: {app: livekit-sfu}
    spec:
      nodeSelector:
        node-role: live-high-bandwidth    # 高带宽节点
      containers:
      - name: livekit
        image: livekit/livekit-server:v1.6
        ports:
        - {containerPort: 7880, name: api}
        - {containerPort: 7881, name: rtc-tcp}
        - {containerPort: 50000-60000, name: rtc-udp, protocol: UDP}
        resources:
          requests: {cpu: "4", memory: "8Gi"}
          limits:   {cpu: "8", memory: "16Gi"}
        env:
        - {name: LIVEKIT_KEYS, valueFrom: {secretKeyRef: {name: livekit-secret, key: keys}}}
        volumeMounts:
        - {name: config, mountPath: /etc/livekit}
  volumeClaimTemplates:
  - metadata: {name: config}
    spec:
      accessModes: ["ReadWriteOnce"]
      resources: {requests: {storage: "10Gi"}}
```

### 3.3 CDN 配置（LL-HLS）

```
CDN 配置(LL-HLS, 大众观看):
  - 源站: SRS edge cluster
  - 切片: CMAF, 部分分片(Partial Segment) 200-500ms
  - 缓存: HLS 不缓存(前叠 Squid/ATS 缓存)
  - 回源: 边缘 miss 回 SRS edge
  - 跨 cell: cell-agnostic, 跨 cell 观众走 CDN 边缘
  - 带宽: 按峰值 1 万 CCU 预留(可弹性扩)
  - 防盗链: 签名 URL, 过期 5min
```

### 3.4 录制存储

```
录制 pipeline:
  SRS origin → 转码(ffmpeg/NVENC) → FLV/MP4 → 对象存储(MinIO/S3)
  bucket: live-recordings/{courseId}/{roomId}/{startedAt}.mp4
  元数据: live-recordings-meta 表(courseId/roomId/uri/duration/createdAt)
  回放: 签名 URL 走 CDN, 支持时移/倍速/章节
  保留: 7 天热存储, 之后归档 Iceberg 冷存储
```

---

## 4. 在线编程 IDE 部署

### 4.1 Firecracker K8s CRD 部署

```yaml
# Firecracker CRD 定义(简化)
apiVersion: apiextensions.k8s.io/v1
kind: CustomResourceDefinition
metadata:
  name: sandboxwarmpools.ide.itbackend
spec:
  group: ide.itbackend
  names:
    kind: SandboxWarmPool
    plural: sandboxwarmpools
  scope: Namespaced
  versions:
  - name: v1
    served: true
    storage: true
    schema:
      openAPIV3Schema:
        type: object
        properties:
          spec:
            type: object
            properties:
              languages:
                type: array
                items:
                  type: object
                  properties:
                    language: {type: string}
                    image: {type: string}
                    snapshotCount: {type: integer}
                    memMB: {type: integer}
                    cpuCount: {type: integer}
              warmupStrategy:
                type: object
                properties:
                  minReady: {type: integer}
                  replenishThreshold: {type: number}
              limits:
                type: object
                properties:
                  cpuMicros: {type: integer}
                  memMB: {type: integer}
                  netEgressBps: {type: integer}
                  timeoutSec: {type: integer}
---
# CRD 控制器 Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: firecracker-controller
  namespace: it-backend-ide
spec:
  replicas: 1
  selector: {matchLabels: {app: firecracker-controller}}
  template:
    metadata: {labels: {app: firecracker-controller}}
    spec:
      containers:
      - name: controller
        image: itbackend/firecracker-controller:v1.0
        resources:
          requests: {cpu: "500m", memory: "512Mi"}
        env:
        - {name: FIRECRACKER_BIN, value: "/usr/local/bin/firecracker"}
      nodeSelector:
        node-role: firecracker-host   # 需 KVM 支持的节点
```

### 4.2 SandboxWarmPool 配置实例

```yaml
apiVersion: ide.itbackend/v1
kind: SandboxWarmPool
metadata:
  name: judge-warm-pool
  namespace: it-backend-ide
spec:
  languages:
    - {language: JAVA,   image: firecracker-jdk21,      snapshotCount: 20, memMB: 512, cpuCount: 1}
    - {language: PYTHON, image: firecracker-python3.12, snapshotCount: 20, memMB: 256, cpuCount: 1}
    - {language: GO,     image: firecracker-go1.22,     snapshotCount: 10, memMB: 256, cpuCount: 1}
    - {language: CPP,    image: firecracker-gcc13,      snapshotCount: 10, memMB: 512, cpuCount: 1}
  warmupStrategy:
    minReady: 5
    replenishThreshold: 0.5
  limits:
    cpuMicros: 1000000
    memMB: 512
    netEgressBps: 0
    timeoutSec: 30
```

### 4.3 judge0 worker 部署

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: judge0-worker
  namespace: it-backend-ide
spec:
  replicas: 5
  selector: {matchLabels: {app: judge0-worker}}
  template:
    metadata: {labels: {app: judge0-worker}}
    spec:
      containers:
      - name: judge0
        image: judge0/judge0:v1.13
        resources:
          requests: {cpu: "1", memory: "2Gi"}
          limits:   {cpu: "2", memory: "4Gi"}
        env:
        - {name: REDIS_URL, value: "redis://redis.infra:6379"}
        - {name: JUDGE0_FIRECRACKER_POOL, value: "judge-warm-pool"}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: judge0-worker-hpa
  namespace: it-backend-ide
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: judge0-worker
  minReplicas: 5
  maxReplicas: 50
  metrics:
  - type: Resource
    resource: {name: cpu, target: {type: Utilization, averageUtilization: 70}}
  behavior:
    scaleUp:   {stabilizationWindowSeconds: 0}
    scaleDown: {stabilizationWindowSeconds: 120, policies: [{type: Percent, value: 25, periodSeconds: 60}]}
```

### 4.4 Yjs WebSocket 网关部署

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: yjs-ws-gateway
  namespace: it-backend-ide
spec:
  replicas: 3
  selector: {matchLabels: {app: yjs-ws-gateway}}
  template:
    metadata: {labels: {app: yjs-ws-gateway}}
    spec:
      containers:
      - name: yjs-ws
        image: itbackend/yjs-ws-server:v1.0
        ports: [{containerPort: 8080, name: ws}]
        resources:
          requests: {cpu: "500m", memory: "1Gi"}
          limits:   {cpu: "1", memory: "2Gi"}
```

---

## 5. 作业/评分 Java 服务部署

### 5.1 作业服务 Deployment/HPA

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: assignment-service
  namespace: it-backend-assignment
spec:
  replicas: 3
  selector: {matchLabels: {app: assignment-service}}
  template:
    metadata: {labels: {app: assignment-service}}
    spec:
      containers:
      - name: assignment
        image: itbackend/assignment-service:v1.0
        ports: [{containerPort: 8080}]
        env:
        - {name: SPRING_PROFILES_ACTIVE, value: "prod"}
        - {name: TIDB_URL, valueFrom: {secretKeyRef: {name: tidb-secret, key: url}}}
        - {name: REDIS_URL, valueFrom: {secretKeyRef: {name: redis-secret, key: url}}}
        - {name: PULSAR_URL, valueFrom: {secretKeyRef: {name: pulsar-secret, key: url}}}
        - {name: NACOS_SERVER, value: "nacos.infra:8848"}
        resources:
          requests: {cpu: "1", memory: "2Gi"}
          limits:   {cpu: "2", memory: "4Gi"}
        readinessProbe:
          httpGet: {path: /actuator/health/readiness, port: 8080}
          initialDelaySeconds: 30
          periodSeconds: 10
          failureThreshold: 5
        livenessProbe:
          httpGet: {path: /actuator/health/liveness, port: 8080}
          initialDelaySeconds: 60
          periodSeconds: 15
          failureThreshold: 5
        startupProbe:
          httpGet: {path: /actuator/health, port: 8080}
          failureThreshold: 30
          periodSeconds: 10
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: assignment-hpa
  namespace: it-backend-assignment
spec:
  scaleTargetRef: {apiVersion: apps/v1, kind: Deployment, name: assignment-service}
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource: {name: cpu, target: {type: Utilization, averageUtilization: 65}}
  behavior:
    scaleUp:   {stabilizationWindowSeconds: 0}
    scaleDown: {stabilizationWindowSeconds: 120, policies: [{type: Percent, value: 25, periodSeconds: 60}]}
```

### 5.2 评分服务部署（含 Python LLM）

```yaml
# 评分 pipeline(Java,确定性阶段)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: grading-pipeline
  namespace: it-backend-grading
spec:
  replicas: 3
  selector: {matchLabels: {app: grading-pipeline}}
  template:
    metadata: {labels: {app: grading-pipeline}}
    spec:
      containers:
      - name: grading
        image: itbackend/grading-pipeline:v1.0
        ports: [{containerPort: 8080}]
        env:
        - {name: FIRECRACKER_POOL, value: "judge-warm-pool"}   # 共享 Firecracker
        - {name: AI_GATEWAY_URL, value: "http://ai-gateway.middleware-platform:8080"}
        resources:
          requests: {cpu: "1", memory: "2Gi"}
          limits:   {cpu: "2", memory: "4Gi"}
---
# LLM grader(Python,异步批)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: llm-grader
  namespace: it-backend-grading
spec:
  replicas: 2
  selector: {matchLabels: {app: llm-grader}}
  template:
    metadata: {labels: {app: llm-grader}}
    spec:
      containers:
      - name: llm-grader
        image: itbackend/llm-grader:v1.0
        env:
        - {name: AI_GATEWAY_URL, value: "http://ai-gateway.middleware-platform:8080"}
        - {name: LLM_K_SAMPLES, value: "5"}
        - {name: LLM_MODE, value: "mock"}    # 压测/CI 用 mock, 生产切真实
        resources:
          requests: {cpu: "1", memory: "2Gi"}
          limits:   {cpu: "2", memory: "4Gi"}
```

### 5.3 查重服务部署（Python）

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: plagiarism-tier1
  namespace: it-backend-assignment
spec:
  replicas: 2
  selector: {matchLabels: {app: plagiarism-tier1}}
  template:
    metadata: {labels: {app: plagiarism-tier1}}
    spec:
      containers:
      - name: tier1
        image: itbackend/plagiarism-tier1:v1.0
        env:
        - {name: MILVUS_URL, value: "milvus.infra:19530"}
        resources: {requests: {cpu: "1", memory: "2Gi"}}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: plagiarism-tier2
  namespace: it-backend-assignment
spec:
  replicas: 3
  selector: {matchLabels: {app: plagiarism-tier2}}
  template:
    metadata: {labels: {app: plagiarism-tier2}}
    spec:
      containers:
      - name: tier2
        image: itbackend/plagiarism-tier2:v1.0
        env:
        - {name: DOLOS_BIN, value: "/usr/local/bin/dolos"}
        - {name: JPLAG_JAR, value: "/opt/jplag/jplag.jar"}
        resources: {requests: {cpu: "1", memory: "2Gi"}}
```

---

## 6. 三端前端部署

### 6.1 Web(React)构建部署

```yaml
# web 构建后 nginx 静态托管
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-frontend
  namespace: it-backend-frontend
spec:
  replicas: 2
  selector: {matchLabels: {app: web-frontend}}
  template:
    metadata: {labels: {app: web-frontend}}
    spec:
      containers:
      - name: nginx
        image: itbackend/web-frontend:v1.0
        ports: [{containerPort: 80}]
        resources: {requests: {cpu: "200m", memory: "256Mi"}}
---
apiVersion: v1
kind: Service
metadata:
  name: web-frontend
  namespace: it-backend-frontend
spec:
  selector: {app: web-frontend}
  ports: [{port: 80, targetPort: 80}]
---
# Ingress(web.example.com)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: web-ingress
  namespace: it-backend-frontend
spec:
  rules:
  - host: web.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend: {service: {name: web-frontend, port: {number: 80}}}
```

### 6.2 H5 部署

```yaml
# h5 同 web,独立域名 h5.example.com
apiVersion: apps/v1
kind: Deployment
metadata:
  name: h5-frontend
  namespace: it-backend-frontend
spec:
  replicas: 2
  selector: {matchLabels: {app: h5-frontend}}
  template:
    metadata: {labels: {app: h5-frontend}}
    spec:
      containers:
      - name: nginx
        image: itbackend/h5-frontend:v1.0
        ports: [{containerPort: 80}]
```

### 6.3 小程序发布

```
小程序发布流程(Taro 转译):
  1. CI 执行 taro build --type weapp → dist/
  2. 微信开发者工具 CI 上传体验版(miniprogram-ci)
  3. 提交审核 → 审核通过发布
  4. 版本管理: 灰度发布(微信平台支持按比例)
  注: 小程序前端不在 K8s 集群, 走微信平台分发
```

---

## 7. CI/CD Pipeline

### 7.1 Pipeline 流程

```
开发者 PR
   │
   ▼
[CI 触发]
   ├─ 代码检查(ESLint/Checkstyle/Go vet/Ruff)
   ├─ 单元测试(Java/Go/Python/TS)
   │   - 公共库 ≥80% 覆盖
   │   - 安全核心 100% CI 硬门
   ├─ 契约测试(Pact)
   │   - 三端↔BFF, BFF↔子平台, 子平台↔中台
   │   - 不兼容 PR 阻止合并
   ├─ 安全扫描(Aikido SAST+Secrets)
   │   - SQG 阻断
   ├─ 镜像构建(docker build)
   └─ 镜像推送(registry)
   │
   ▼
[合并到 main]
   ├─ 部署 staging(自动)
   ├─ 集成测试
   ├─ E2E 测试(Playwright 三端)
   └─ 压测(nightly, 1 万 CCU)
   │
   ▼
[人工审批]
   ├─ 部署 prod(灰度)
   └─ 监控验证
```

### 7.2 GitOps

```
GitOps(ArgoCD):
  - Git 仓库 = 单一事实来源(K8s manifests/Helm charts)
  - ArgoCD watch Git, 自动同步到集群
  - 配置变更走 PR, 审计可追溯
  - 灰度: ArgoCD Rollout(Canary 5%→25%→100%)
  - 回滚: ArgoCD 一键回滚到任意版本
```

---

## 8. 灰度发布策略

### 8.1 特性开关（独立灰度）

```
特性开关(Nacos):
  - live.enabled            直播功能开关
  - ide.webcontainer.enabled WebContainer 开关
  - ide.firecracker.enabled Firecracker 开关
  - plagiarism.tier1.enabled Tier1 查重开关
  - plagiarism.tier2.enabled Tier2 查重开关
  - grading.llm.enabled     LLM 评分开关
  - grading.llm.k_samples   LLM k 值(可调)

灰度维度:
  - 按 user_id 百分比(hash(user_id)%100 < threshold)
  - 按端(web/h5/小程序)
  - 按课程(courseId 白名单)
  - 按时间(逐步放量)

回滚:
  - 特性开关秒级关闭(不需重新部署)
  - ArgoCD 镜像回滚(分钟级)
```

### 8.2 Canary 发布

```
ArgoCD Rollout(Canary):
  - 新版本先 5% 流量, 观察 10min
  - 指标达标(P95<200ms, 错误率<0.1%) → 25%
  - 再观察 10min → 50% → 100%
  - 任一指标异常 → 自动回滚
```

---

## 9. 运维 Runbook

### 9.1 直播故障 Runbook

```
故障: 直播间卡顿/延迟高
  1. 检查 SRS origin 负载(Prometheus: srs_origin_streams)
  2. 检查 LiveKit SFU 房间数/带宽(Prometheus: livekit_rooms, livekit_bandwidth)
  3. 检查 CDN 回源延迟(CDN 日志)
  4. 若 origin 负载高 → HPA 扩容 srs-origin
  5. 若 SFU 带宽满 → 扩容 livekit-sfu(需高带宽节点)
  6. 若互动观众超限 → 降级新观众到 LL-HLS

故障: 直播中断
  1. 检查推流端是否在线(OBS/ffmpeg)
  2. 检查 SRS origin 收流状态(srs API /api/v1/streams)
  3. 检查 cell 健康(CellRouter 路由表)
  4. 若 cell 故障 → 路由器重连健康 cell
  5. 通知教师重新推流

故障: 录制失败
  1. 检查对象存储可用性(MinIO/S3)
  2. 检查转码任务队列(ffmpeg/NVENC)
  3. 重启录制任务(从 SRS 重新拉流录制)
```

### 9.2 评测/Judge 故障 Runbook

```
故障: 评测排队/超时
  1. 检查 judge0 worker 队列堆积(Redis: judge0:queue len)
  2. 检查 SandboxWarmPool 就绪数(CRD status.readyCounts)
  3. 若池耗尽 → 扩容 judge0-worker + 检查 CRD 控制器补充
  4. 若持续 → 特性开关降级(关闭 LLM 评分, 仅确定性阶段)

故障: 沙箱逃逸告警
  1. 立即隔离涉事 microVM(kill + 不释放)
  2. 取证(日志+内存 dump)
  3. 通知安全团队
  4. 评估是否需禁用评测(特性开关)

故障: Firecracker 节点 KVM 不可用
  1. 检查节点 /dev/kvm 权限
  2. 重启节点或迁移 pod
  3. 通知运维
```

### 9.3 作业/评分故障 Runbook

```
故障: 查重任务堆积
  1. 检查 plagiarism-tier1/tier2 队列
  2. 扩容 tier1/tier2 Deployment
  3. 若桶爆炸 → 检查二次 LSH 细分配置

故障: LLM 评分漂移告警(Leniency)
  1. 检查 LLM 评分历史 leniency 趋势
  2. 若漂移 >阈值 → 暂停 LLM 评分(特性开关)
  3. 通知教师人工复核积压
  4. 评估 LLM 模型/prompt 调整

故障: 成绩聚合错误
  1. 检查 grade 表数据一致性
  2. 检查加权方案配置(assignment.weight)
  3. 重新触发聚合(幂等)
```

### 9.4 三端/网关故障 Runbook

```
故障: BFF 网关限流
  1. 检查 Sentinel 限流规则
  2. 评估是否需调高阈值
  3. 扩容 bff-scg

故障: CellRouter 路由错误
  1. 检查路由表(Nacos: cell_route_table)
  2. 检查 misroute 纠正计数器
  3. 若 failover 期 → 检查写挂起状态

故障: 三端前端构建失败
  1. 检查 CI 日志(ESLint/TS 编译)
  2. 修复后重新构建
  3. 小程序审核失败 → 检查微信审核反馈
```

---

## 10. 监控与告警

### 10.1 关键指标

| 子平台 | 指标 | 阈值 | 告警 |
|-------|------|------|------|
| 直播 | WebRTC 延迟 P95 | >800ms | P1 |
| 直播 | SFU 房间数 | >上限 80% | P2 |
| 直播 | 录制失败率 | >1% | P1 |
| IDE | Firecracker 冷启动 | >200ms | P2 |
| IDE | SandboxWarmPool 就绪 | <minReady | P1 |
| IDE | Judge 队列堆积 | >500 | P2 |
| 作业 | 查重任务延迟 | >60s | P2 |
| 评分 | LLM 评分延迟 | >30s | P2 |
| 评分 | Leniency 漂移 | >1.0 | P1 |
| 全局 | 核心 API P95 | >200ms | P1 |
| 全局 | 可用性 | <99.9% | P0 |
| 全局 | cell 故障切换 | >60s | P0 |

### 10.2 告警渠道
- P0/P1: 电话+短信+IM
- P2: IM
- 告警含 trace 链接（Tempo）+ 看板链接（Grafana）+ runbook 链接

---

## 11. 风险与开放问题

| ID | 风险 | 缓解 |
|----|------|------|
| R-DEPLOY-01 | Firecracker 节点需 KVM，公共云限制 | 选用支持嵌套虚拟化或裸金属节点 |
| R-DEPLOY-02 | LiveKit SFU 高带宽节点成本 | 按直播并发弹性扩缩，闲时缩容 |
| R-DEPLOY-03 | 小程序审核周期不可控 | 提前提交审核，保留回滚版本 |
| R-DEPLOY-04 | 单 cell 部署无异地多活 | MVP 验证拓扑，多 cell 在 SP-07 |
| R-DEPLOY-05 | LLM 真实模式成本 | mock 模式 CI/压测，生产语义缓存+模型路由 |
| O-DEPLOY-01 | CDN 供应商选型 | 评估阿里云/腾讯云/Cloudflare |

---

**下一步**：本部署方案经评审通过后，作为 SP-05 部署执行与运维的依据。
