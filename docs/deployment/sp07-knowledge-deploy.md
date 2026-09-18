# SP-07 知识中台 部署方案

> **D-200 Naming Note:** This document's filename retains the legacy code `sp07` for reference continuity. Per D-137, sp07 → knowledge-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-07-KDEPLOY | v1.0 | 2026-08-05 | Draft(待评审) | 知识中台架构组 | SP-07-SRS/SAD/SDD v1.0 + SP-04-AI 中台 + D-032/D-041/D-042 + G-01~G-10 SLO |

> 本部署方案覆盖 SP-07 知识中台的 K8s 部署清单、多区域多活(D-041)、AI 模型部署、Pulsar Topic 规划、监控告警、CI/CD、配置管理。技术栈基线遵循 D-032：Spring Boot 4.0.7 / Spring Cloud 2025.1.2 / Spring Cloud Alibaba 2025.1.0.0 / Dubbo 3.3.6 / Nacos 3.1.1 / Sentinel 1.8.9 / Seata 2.5.0 / Pulsar 3.3.9 / Milvus 2.5+ / Elasticsearch 8.x / Neo4j 5.x。多区域容灾复用 SP-07 多区域部署方案(sp07-deploy.md)的 Cell 框架。

---

## 目录

1. [部署架构概览](#1-部署架构概览)
2. [K8s 部署清单](#2-k8s-部署清单)
3. [多区域多活部署(D-041)](#3-多区域多活部署d-041)
4. [AI 模型部署](#4-ai-模型部署)
5. [Pulsar Topic 规划](#5-pulsar-topic-规划)
6. [监控告警](#6-监控告警)
7. [CI/CD](#7-cicd)
8. [配置管理(ConfigMap)](#8-配置管理configmap)
9. [部署追溯](#9-部署追溯)

---

## 1. 部署架构概览

### 1.1 设计目标与约束

| 维度 | 目标 | 依据 |
|------|------|------|
| 知识库规模 | 单 cell 10 万文档 / 100 万 chunk | G-01 |
| 语义搜索 P95 | <500ms | G-03 / NFR-P01 |
| RAG 问答 P95 | <3s | G-04 / NFR-P02 |
| 可用性 SLO | 99.9% | G-07 |
| 单元故障切换 | RTO<60s | G-08 / D-041 |
| 多租户隔离 | 零泄露 | NFR-S03 |
| 技术栈 | SB4.0.7 / SC2025.1.2 / Milvus2.5+ / ES8.x / Neo4j5.x / Pulsar3.3.9 | D-032 |

### 1.2 部署拓扑

```
┌──────────────────────── K8s 集群(每 cell)────────────────────────────┐
│                                                                       │
│  namespace: sp07-knowledge                                            │
│   ├─ sp07-bff                (3副本, BFF 聚合层)                       │
│   ├─ sp07-knowledge-service  (4副本, 知识库 CRUD + 文档处理)            │
│   ├─ sp07-retrieval-service  (4副本, 检索 + RAG)                       │
│   ├─ sp07-agent-service      (3副本, 智能体)                          │
│   ├─ sp07-community-service  (2副本, 社区)                            │
│   ├─ sp07-tenant-service     (2副本, 多租户管理)                       │
│   └─ sp07-learning-service   (2副本, 学习路径)                        │
│                                                                       │
│  namespace: sp07-data                                                 │
│   ├─ milvus           (StatefulSet, 3副本, 向量数据库)                 │
│   ├─ elasticsearch    (StatefulSet, 3副本, 全文检索)                   │
│   └─ neo4j            (StatefulSet, 1副本, 知识图谱)                  │
│                                                                       │
│  namespace: pulsar     (复用 SP-01/SP-07 多区域 Pulsar 3.3.9 集群)     │
│   └─ topic: kb.* / agent.* / community.* / tenant.*                   │
│                                                                       │
│  namespace: monitoring                                                │
│   └─ Prometheus + Grafana(milvus/es/neo4j exporter)                  │
└───────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │ S3/MinIO 对象存储(跨区) │
              │ bucket: edu-knowledge   │
              └────────────────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │ SP-04 AI 网关           │
              │ (Embedding/Rerank/LLM) │
              └────────────────────────┘
```

### 1.3 依赖关系

| 依赖 | 来源 | 说明 |
|------|------|------|
| Pulsar 3.3.9 | SP-01 / SP-07 | 事件总线，复用多区域集群 |
| TiDB | SP-01 | 元数据/租户/配额落库 |
| Redis Cluster | SP-01 | 缓存 / Agent 状态 / 限流 |
| S3/MinIO | SP-01 | 文档原文存储 |
| SP-04 AI 网关 | SP-04 | Embedding / Reranker / LLM 远程调用 |
| K8s / Karmada | SP-07 | 多区域集群联邦 |
| Nacos / Sentinel / Seata | SP-01 | 配置 / 限流 / 分布式事务 |

---

## 2. K8s 部署清单

### 2.1 组件资源清单

| 组件 | 类型 | 副本数 | CPU | Memory | Storage | 说明 |
|------|------|--------|-----|--------|---------|------|
| sp07-bff | Deployment | 3 | 2 vCPU | 4Gi | - | BFF 聚合层，对外暴露 HTTP/gRPC |
| sp07-knowledge-service | Deployment | 4 | 4 vCPU | 8Gi | - | 知识库 CRUD + 文档解析 + 分块 |
| sp07-retrieval-service | Deployment | 4 | 4 vCPU | 8Gi | - | 混合检索 + RAG + Reranker 调用 |
| sp07-agent-service | Deployment | 3 | 4 vCPU | 8Gi | - | Agent 编排(路由/流水线/辩论) |
| sp07-community-service | Deployment | 2 | 2 vCPU | 4Gi | - | 社区问答 / 评审工作流 |
| sp07-tenant-service | Deployment | 2 | 2 vCPU | 4Gi | - | 多租户管理 / 配额 / 开放 API |
| sp07-learning-service | Deployment | 2 | 2 vCPU | 4Gi | - | 学习路径生成 |
| milvus | StatefulSet | 3 | 8 vCPU | 32Gi | 500Gi SSD | 向量数据库(Milvus 2.5+) |
| elasticsearch | StatefulSet | 3 | 4 vCPU | 16Gi | 300Gi SSD | 全文检索(ES 8.x) |
| neo4j | StatefulSet | 1 | 4 vCPU | 16Gi | 200Gi SSD | 知识图谱(Neo4j 5.x) |

### 2.2 sp07-knowledge-service Deployment 示例

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sp07-knowledge-service
  namespace: sp07-knowledge
  labels:
    app: sp07-knowledge-service
    sp: "07"
spec:
  replicas: 4
  selector:
    matchLabels:
      app: sp07-knowledge-service
  template:
    metadata:
      labels:
        app: sp07-knowledge-service
        sp: "07"
    spec:
      serviceAccountName: sp07-knowledge
      containers:
        - name: app
          image: registry.cn-east-1.ep-cn.com/sp07/knowledge-service:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - { name: http, containerPort: 8080 }
            - { name: grpc, containerPort: 9090 }
            - { name: actuator, containerPort: 8081 }
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: NACOS_SERVER_ADDR, valueFrom: { configMapKeyRef: { name: sp07-config, key: nacos.addr } } }
            - { name: PULSAR_URL, valueFrom: { configMapKeyRef: { name: sp07-config, key: pulsar.url } } }
            - { name: MILVUS_HOST, valueFrom: { configMapKeyRef: { name: sp07-config, key: milvus.host } } }
            - { name: ES_HOST, valueFrom: { configMapKeyRef: { name: sp07-config, key: es.host } } }
            - { name: S3_BUCKET, value: edu-knowledge }
            - { name: AI_GATEWAY_URL, value: http://sp04-ai-gateway.sp04-ai:8080 }
          resources:
            requests: { cpu: "2", memory: "4Gi" }
            limits:   { cpu: "4", memory: "8Gi" }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: actuator }
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: actuator }
            initialDelaySeconds: 60
            periodSeconds: 20
          volumeMounts:
            - { name: tmp, mountPath: /tmp }
      volumes:
        - name: tmp
          emptyDir: { sizeLimit: 10Gi }
```

### 2.3 Milvus StatefulSet 示例

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: milvus
  namespace: sp07-data
spec:
  serviceName: milvus-headless
  replicas: 3
  selector:
    matchLabels: { app: milvus }
  template:
    metadata:
      labels: { app: milvus }
    spec:
      containers:
        - name: milvus
          image: milvusdb/milvus:v2.5.0
          args: ["milvus", "run", "standalone"]
          ports:
            - { name: grpc, containerPort: 19530 }
            - { name: http, containerPort: 9091 }
          env:
            - { name: ETCD_ENDPOINTS, value: "etcd:2379" }
            - { name: MINIO_ADDRESS, value: "minio:9000" }
          resources:
            requests: { cpu: "4", memory: "16Gi" }
            limits:   { cpu: "8", memory: "32Gi" }
          volumeMounts:
            - { name: data, mountPath: /var/lib/milvus }
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: ssd
        resources:
          requests: { storage: 500Gi }
```

### 2.4 命名空间与 ResourceQuota

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: sp07-knowledge
  labels: { sp: "07", mesh: cilium }
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sp07-knowledge-quota
  namespace: sp07-knowledge
spec:
  hard:
    requests.cpu: "40"
    requests.memory: "120Gi"
    persistentvolumeclaims: "20"
    services.loadbalancers: "2"
```

### 2.5 HPA 弹性伸缩

| 组件 | min | max | 指标 | 阈值 |
|------|-----|-----|------|------|
| sp07-bff | 3 | 10 | CPU | 70% |
| sp07-knowledge-service | 4 | 12 | CPU + 队列深度 | 70% / backlog>1000 |
| sp07-retrieval-service | 4 | 16 | CPU + QPS | 70% / QPS>500 |
| sp07-agent-service | 3 | 10 | CPU | 75% |

---

## 3. 多区域多活部署(D-041)

### 3.1 4 cell 部署策略

知识中台复用 SP-07 多区域部署的 4 cell 框架(华东/华南/华北/海外)，每 cell 部署完整的知识中台组件，采用 Active-Active 模式。

| Cell | 区域 | 角色 | 知识库 | 备注 |
|------|------|------|--------|------|
| cell-A | 华东1 | Active | 全量 | 主写入 cell |
| cell-B | 华南1 | Active | 全量 | 对等写入 |
| cell-C | 华北1 | Active | 全量 | 对等写入 |
| cell-D | 海外 | Active | 独立 | 数据主权隔离，不参与国内同步 |

### 3.2 知识库跨区域同步(Pulsar Geo-Replication)

文档上传事件通过 Pulsar Geo-Replication 跨区域同步，保证多 cell 知识库最终一致。

```
cell-A 上传文档
   │
   ▼
Pulsar topic: persistent://kb/tenant-103/kb.document.uploaded
   │
   ├──► Geo-Replication ──► cell-B kb.document.uploaded
   │                         │
   │                         ▼
   │                       cell-B 解析+索引(幂等)
   │
   └──► Geo-Replication ──► cell-C kb.document.uploaded
                             │
                             ▼
                           cell-C 解析+索引(幂等)
```

**同步策略**：

| 项 | 策略 |
|----|------|
| 协议 | Pulsar Geo-Replication(异步) |
| 一致性 | 最终一致，RPO<5min |
| 幂等 | doc_id 全局唯一(雪花算法)，下游按 doc_id 去重 |
| 冲突解决 | LWW(Last Write Wins)，以 `updated_at` 为准 |
| 失败重试 | Pulsar 内置重试 + DLQ |
| 监控 | 同步延迟 >5min 告警 |

### 3.3 向量索引跨区域同步策略(增量同步)

向量索引因数据量大，不走全量同步，采用增量同步：

| 阶段 | 策略 | 说明 |
|------|------|------|
| 增量写入 | 实时同步 | 通过 Pulsar 事件触发下游 cell 写入 Milvus |
| 全量重建 | 按需触发 | 通过 S3 文档桶 + 元数据重建 Milvus 索引 |
| 删除同步 | 事件驱动 | doc 删除事件触发下游 cell 软删除 chunk |
| 一致性校验 | 每日对账 | 离线作业比对各 cell Milvus chunk 数，差异告警 |

```python
# 增量同步消费者(伪码)
def on_document_indexed(event):
    doc_id = event.doc_id
    tenant = event.tenant_id
    if milvus.exists(doc_id):  # 幂等
        return
    chunks = parse_and_chunk(s3.get(event.object_key))
    embeddings = ai_gateway.embed(chunks)  # 复用 SP-04
    milvus.insert(collection=f"kb_{tenant}", docs=chunks, vectors=embeddings)
    es.index(index="kb_chunks", docs=chunks)
```

### 3.4 故障切换

| 场景 | 切换 | RTO |
|------|------|-----|
| cell-A 故障 | 流量切 cell-B/C | <60s(见 sp07-deploy.md §7) |
| Milvus 单节点故障 | StatefulSet 自动重调度 | <120s |
| ES 单节点故障 | 副本自动恢复 | <120s |
| 跨区网络中断 | 本地消费不阻塞，恢复后补同步 | 视中断时长 |

---

## 4. AI 模型部署

### 4.1 模型清单

| 模型 | 用途 | 部署方式 | 维度 | 来源 |
|------|------|---------|------|------|
| bge-large-zh-v1.5 | Embedding | ONNX 本地推理 | 1024 | 通过 SP-04 部署 |
| bge-reranker-large | Reranker | ONNX 本地推理 | - | 通过 SP-04 部署 |
| LLM(通义/文心/GPT) | RAG 生成 / Agent | 远程调用 SP-04 AI 网关 | - | SP-04 |

### 4.2 Embedding 部署(bge-large-zh-v1.5, ONNX)

知识中台不独立部署 Embedding 模型，通过 SP-04 AI 网关调用。SP-04 在 GPU 节点池部署 ONNX Runtime 推理服务：

```yaml
# SP-04 AI 网关侧 Deployment(节选)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-gateway-embedding
  namespace: sp04-ai
spec:
  replicas: 4
  template:
    spec:
      nodeSelector: { accelerator: gpu }
      containers:
        - name: embedding
          image: registry.cn-east-1.ep-cn.com/sp04/embedding-bge-large:1.0.0
          args: ["--model", "/models/bge-large-zh-v1.5.onnx", "--port", "8080"]
          resources:
            limits:
              nvidia.com/gpu: 1
              memory: "16Gi"
          volumeMounts:
            - { name: models, mountPath: /models }
      volumes:
        - name: models
          persistentVolumeClaim: { claimName: bge-models }
```

| 指标 | 目标 | 备注 |
|------|------|------|
| Embedding 延迟 P95 | <50ms(单 chunk) | ONNX 量化 |
| 吞吐 | ≥1000 chunk/s | 4 副本 + GPU |
| 批处理 | ≤32 chunk/批 | 动态 batching |

### 4.3 Reranker 部署(bge-reranker-large, ONNX)

同样通过 SP-04 AI 网关调用，ONNX 本地推理：

| 指标 | 目标 | 备注 |
|------|------|------|
| Rerank 延迟 P95 | <100ms(top-50) | ONNX 量化 |
| 吞吐 | ≥200 query/s | 4 副本 + GPU |
| 降级 | Reranker 故障 → 返回 RRF top-10 | 标记 `rerank_skipped` |

### 4.4 LLM 远程调用

LLM 不在知识中台本地部署，统一通过 SP-04 AI 网关路由到外部 provider(通义/文心/GPT)：

```yaml
# sp07-retrieval-service 调用配置
ai:
  gateway:
    url: http://sp04-ai-gateway.sp04-ai:8080
  llm:
    model: qwen-max
    timeout: 10s
    retry: 3
    fallback:
      - wenxin-ernie-4
      - gpt-4o
  embedding:
    model: bge-large-zh-v1.5
    dim: 1024
  reranker:
    model: bge-reranker-large
    top_k: 50
```

### 4.5 模型版本管理

| 模型 | 版本策略 | 回滚 |
|------|---------|------|
| Embedding | 灰度 10%/50%/100% | 旧版 ONNX 保留 2 版本 |
| Reranker | 同上 | 同上 |
| LLM | provider 多模型 fallback | 自动切换 |

---

## 5. Pulsar Topic 规划

### 5.1 Topic 清单

| Topic | Namespace | 分区 | 说明 |
|-------|-----------|------|------|
| kb.document.uploaded | kb/{tenant} | 6 | 文档上传事件，触发解析流水线 |
| kb.document.indexed | kb/{tenant} | 6 | 文档索引完成事件，触发跨区同步 |
| kb.document.failed | kb/{tenant} | 3 | 解析/索引失败事件，入 DLQ |
| agent.session.ended | agent/{tenant} | 6 | Agent 会话结束事件，用于审计/分析 |
| community.post.created | community/{tenant} | 3 | 社区问答创建事件 |
| tenant.quota.exceeded | tenant/{tenant} | 3 | 租户配额超限事件，触发告警 |

### 5.2 事件 Schema(Avro)

```json
{
  "type": "record",
  "name": "DocumentUploaded",
  "namespace": "ep.sp07.kb",
  "fields": [
    { "name": "doc_id", "type": "string" },
    { "name": "tenant_id", "type": "string" },
    { "name": "object_key", "type": "string" },
    { "name": "format", "type": { "type": "enum", "name": "Format",
      "symbols": ["pdf","word","excel","ppt","markdown","html"] } },
    { "name": "sha256", "type": "string" },
    { "name": "size_bytes", "type": "long" },
    { "name": "uploaded_by", "type": "string" },
    { "name": "uploaded_at", "type": { "type": "long", "logicalType": "timestamp-millis" } },
    { "name": "cell_id", "type": "string" }
  ]
}
```

### 5.3 Topic 策略

| 项 | 策略 |
|----|------|
| retention | 7d(uploaded/indexed), 30d(failed) |
| backlog | 100k per subscription |
| TTL | 24h(uploaded) |
| Geo-Replication | kb.document.uploaded/indexed 跨 cell 同步 |
| Schema Registry | Pulsar 内置 Avro(复用 SP-01) |
| 压缩 | Snappy |

---

## 6. 监控告警

### 6.1 监控架构

复用 SP-07 多区域监控体系，每 cell 部署 Prometheus + Grafana，通过联邦聚合到全局。

```
cell-A Prometheus ─┐
cell-B Prometheus ─┼─► 全局 Prometheus Federation ─► Grafana
cell-C Prometheus ─┘
```

### 6.2 关键指标与告警规则

| 指标 | PromQL | 告警阈值 | 严重度 |
|------|--------|---------|--------|
| 向量检索延迟 P95 | `histogram_quantile(0.95, milvus_search_latency_bucket)` | >500ms | P1 |
| RAG 问答成功率 | `1 - rate(rag_failure_total[5m]) / rate(rag_total[5m])` | <95% | P1 |
| RAG 问答 P95 | `histogram_quantile(0.95, rag_latency_bucket)` | >3s | P1 |
| Milvus 集群健康 | `up{job="milvus"}` | <3 | P0 |
| ES 集群健康 | `elasticsearch_cluster_health_status{color="red"}` | 1 | P0 |
| Neo4j 在线 | `up{job="neo4j"}` | 0 | P1 |
| 租户配额使用率 | `tenant_quota_used / tenant_quota_limit` | >90% | P2 |
| 跨区同步延迟 | `max(kb_sync_lag_seconds)` | >300s | P1 |
| 文档解析失败率 | `rate(kb_parse_failed_total[5m]) / rate(kb_parse_total[5m])` | >1% | P2 |
| Agent 会话失败率 | `rate(agent_session_failed_total[5m]) / rate(agent_session_total[5m])` | >2% | P1 |

### 6.3 Grafana Dashboard

| Dashboard | 面板 |
|-----------|------|
| 知识库总览 | 文档数 / chunk 数 / 上传 QPS / 解析延迟 |
| 检索性能 | 向量 P95 / BM25 P95 / 混合 P95 / Reranker P95 |
| RAG 问答 | 准确率 / 引用率 / 延迟 / 成功率 |
| Milvus 健康 | 节点状态 / collection 数 / QPS / 内存 |
| 租户配额 | 各租户用量 / 接近超限租户 |
| 跨区同步 | 各 cell 同步延迟 / 待同步 backlog |

### 6.4 日志

| 组件 | 日志 | 采集 |
|------|------|------|
| sp07-* 服务 | stdout JSON | Filebeat → ES |
| Milvus | stdout | Filebeat → ES |
| 审计日志 | audit index | 落 ES `audit-sp07-*` |
| 敏感操作 | 单独索引 | 落 ES `sensitive-sp07-*` |

---

## 7. CI/CD

### 7.1 知识库服务构建流水线(Java21 + Maven + Docker + K8s)

```yaml
# .github/workflows/sp07-knowledge-service.yml
name: sp07-knowledge-service
on:
  push:
    paths: ["services/knowledge-service/**"]
    branches: [main, release/*]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
      - name: Maven Build
        run: mvn -B -pl services/knowledge-service -am clean package
      - name: Unit Test + JaCoCo
        run: mvn -B test
      - name: Coverage Gate(≥80%)
        run: |
          COV=$(mvn -q jacoco:report | grep "Total" | awk '{print $NF}' | tr -d '%')
          if [ "$COV" -lt 80 ]; then exit 1; fi
      - name: Build Docker Image
        run: docker build -t registry.cn-east-1.ep-cn.com/sp07/knowledge-service:${{ github.sha }} services/knowledge-service
      - name: Push Image
        run: docker push registry.cn-east-1.ep-cn.com/sp07/knowledge-service:${{ github.sha }}
      - name: Integration Test(Testcontainers)
        run: mvn -B -pl services/knowledge-service verify -Pit
  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/release/*'
    steps:
      - name: Deploy to Staging(cell-A)
        run: |
          helm upgrade --install sp07-knowledge-service ./charts/sp07-knowledge-service \
            --namespace sp07-knowledge \
            --set image.tag=${{ github.sha }} \
            --kube-context cell-a-staging
```

### 7.2 文档解析回归测试

每次 knowledge-service 发版前自动运行文档解析回归：

```python
# scripts/parse_regression.py
def test_parse_regression():
    samples = load_samples("regression/samples/")  # 6 格式 × 5 份
    baseline = load_baseline("regression/baseline.json")
    failures = []
    for fmt, files in samples.items():
        for f in files:
            doc = parser.parse(f)
            key = f"{fmt}/{f.name}"
            if doc.text_hash != baseline[key]:
                failures.append((key, "text mismatch"))
            if doc.chunk_count != baseline[key]["chunks"]:
                failures.append((key, "chunk count mismatch"))
    assert not failures, f"解析回归失败: {failures}"
```

| 回归项 | 基线 | 阈值 |
|--------|------|------|
| 文本提取哈希 | SHA256 | 与基线一致 |
| chunk 数量 | 固定样本 | 与基线一致(±2) |
| 解析耗时 | 100MB PDF | <60s |
| 内存峰值 | 100MB PDF | <2GB |

### 7.3 向量索引兼容性测试

Embedding 模型升级时，验证旧向量索引的兼容性：

| 用例 | 操作 | 期望 |
|------|------|------|
| 同版本 | 同模型重新 embed | 向量完全一致 |
| 量化版本 | ONNX vs PyTorch | 余弦相似度>0.99 |
| 版本升级 | bge-large-zh-v1.4 → v1.5 | 触发全量重建，不混用 |
| 混用检测 | 新旧向量混查 | 检测并拒绝，提示重建 |
| 灰度 | 10% 流量走新模型 | 召回率不下降 |

### 7.4 发布策略

| 阶段 | 策略 | 工具 |
|------|------|------|
| 构建 | Maven + Docker | GitHub Actions |
| 镜像分发 | 多区域 registry 同步 | Harbor + 镜像同步 |
| 部署 | Argo Rollouts 金丝雀 | 10%/50%/100% |
| 回滚 | Argo Rollouts immediate rollback | <60s |
| 数据库变更 | 向前兼容(加列不删列) | Flyway |

---

## 8. 配置管理(ConfigMap)

### 8.1 全局配置

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: sp07-config
  namespace: sp07-knowledge
data:
  nacos.addr: "nacos.sp01-infra:8848"
  pulsar.url: "pulsar://pulsar-broker.sp01-infra:6650"
  milvus.host: "milvus.sp07-data:19530"
  es.host: "elasticsearch.sp07-data:9200"
  neo4j.url: "bolt://neo4j.sp07-data:7687"
  s3.endpoint: "https://s3.cn-east-1.ep-cn.com"
  s3.bucket: "edu-knowledge"
  ai.gateway.url: "http://sp04-ai-gateway.sp04-ai:8080"
  cell.id: "cell-a"
  region: "cn-east-1"
```

### 8.2 检索配置

```yaml
retrieval:
  vector:
    top_k: 50
    dim: 1024
    metric: "COSINE"
  bm25:
    top_k: 50
  rrf:
    k: 60
  reranker:
    top_k: 50
    final_k: 10
    fallback: true       # 故障降级
  rag:
    max_context: 4096
    max_answer: 500
    citation: true       # 强制引用溯源
```

### 8.3 多租户配置

```yaml
tenant:
  quota:
    documents: 10000     # 单租户文档上限
    chunks: 1000000      # chunk 上限
    api_qps: 100         # API QPS
    storage_gb: 50       # 存储上限
  isolation:
    milvus_expr: "tenant_id == {tenant}"   # 强制 FGAC
    es_filter: "tenant_id:{tenant}"
    neo4j_label: "Tenant_{tenant}"
  alert:
    quota_threshold: 0.8  # 80% 告警
```

### 8.4 Agent 配置

```yaml
agent:
  modes: ["route", "pipeline", "debate"]
  max_rounds: 5
  state_store: "redis"
  prompt_dir: "/etc/sp07/prompts"
  safety:
    jailbreak_guard: true
    sensitive_filter: true
    pii_mask: true
```

### 8.5 Secret 管理

| Secret | 内容 | 来源 |
|--------|------|------|
| milvus-credentials | 用户名/密码 | KMS |
| es-credentials | 用户名/密码 | KMS |
| neo4j-credentials | 用户名/密码 | KMS |
| ai-gateway-token | API Key | SP-04 KMS |
| s3-credentials | AK/SK | KMS |
| llm-provider-keys | 各 provider Key | SP-04 KMS |

---

## 9. 部署追溯

| SRS/SAD 需求 | 部署章节 |
|--------------|---------|
| FR-KB-01..06 知识库 | §2 / §3 / §7.2 |
| FR-RT-01..05 检索与 RAG | §2 / §4 / §8.2 |
| FR-AG-01..04 Agent | §2 / §8.4 |
| FR-MT-01..04 多租户 | §2 / §8.3 |
| FR-CM-01..03 社区 | §2 |
| FR-LP-01..02 学习路径 | §2 |
| NFR-P01 检索 P95<500ms | §6.2 / §8.2 |
| NFR-P02 RAG P95<3s | §6.2 / §4 / §8.2 |
| NFR-S01..04 安全 | §8.3 / §8.4 / §8.5 |
| G-03 / G-04 / G-07 / G-08 SLO | §2 / §3 / §6 |
| D-032 技术栈 | §1.1 / §2 |
| D-041 多区域多活 | §3 |
| D-042 AI 模型部署 | §4 |

---

**文档结束**。本部署方案与 SDD 编码同步迭代，组件变更需更新部署清单与配置。
