# SP-02 数据中台 部署方案

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-02-DEPLOY | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-02-SAD/SDD v1.0 + EP-SPEC §8 |

> 本部署方案覆盖：Flink K8s Operator 部署、Iceberg catalog 配置、S3/MinIO 对接、Kafka 集群配置、Trino 部署、监控、CI/CD、运维 runbook。承接 SAD §9 部署拓扑。

---

## 1. 部署总览

```
┌──────────────────────── K8s 集群(国内多活每 cell)─────────────────────┐
│                                                                       │
│  namespace: flink                                                     │
│   ├─ FlinkKubernetesOperator(1.7+)                                    │
│   ├─ FlinkDeployment: cdc-order / cdc-learn / cdc-user                │
│   ├─ FlinkDeployment: rt-learn / rt-live / rt-trade / rt-risk / rt-bill│
│   └─ FlinkDeployment: fs-online                                       │
│                                                                       │
│  namespace: kafka                                                     │
│   └─ Kafka(Strimzi Operator) + ZooKeeper                              │
│       topic: cdc.order_group / cdc.learning_event_group / cdc.user_group│
│              alert.* / dash.*                                         │
│                                                                       │
│  namespace: schema-registry                                           │
│   └─ Apicurio Registry(或 Confluent)                                  │
│                                                                       │
│  namespace: iceberg-catalog                                           │
│   └─ PostgreSQL(JDBC catalog)                                         │
│                                                                       │
│  namespace: trino                                                     │
│   ├─ Trino Coordinator                                                 │
│   └─ Trino Workers                                                     │
│                                                                       │
│  namespace: spark                                                     │
│   └─ SparkOperator(离线数仓/归档/对账作业)                            │
│                                                                       │
│  namespace: monitoring                                                │
│   └─ Prometheus + Grafana(flink exporter/kafka exporter/iceberg DQ)  │
└───────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌────────────────────────┐
              │ S3/MinIO 对象存储(跨区) │
              │ bucket: edu-iceberg     │
              └────────────────────────┘
```

---

## 2. Flink K8s Operator 部署

### 2.1 Operator 安装
```bash
helm repo add flink-operator https://downloads.apache.org/flink/flink-kubernetes-operator-1.7.0/
helm install flink-operator flink-operator/flink-kubernetes-operator \
  --namespace flink --create-namespace \
  --set defaultConfiguration.create=true \
  --set webhook.create=true
```

### 2.2 FlinkDeployment CRD 示例（cdc-order）

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata:
  name: cdc-order
  namespace: flink
spec:
  image: flink:1.18-java11
  flinkVersion: v1_18
  serviceAccount: flink
  jobManager:
    replicas: 1
    resource:
      memory: 4g
      cpu: 2
  taskManager:
    replicas: 8
    resource:
      memory: 8g
      cpu: 4
  job:
    jarURI: local:///opt/flink/usrlib/flink-cdc-pipeline.jar
    entryClass: org.apache.flink.cdc.pipeline.PipelineJob
    args:
      - "--pipeline"
      - "s3://edu-config/cdc/cdc-order-to-iceberg.yaml"
    parallelism: 32
    upgradeMode: savepoint
    state: running
  flinkConfiguration:
    state.backend: rocksdb
    state.backend.rocksdb.localdir: /mnt/ssd/flink/rocksdb
    execution.checkpointing.interval: 5min
    execution.checkpointing.mode: EXACTLY_ONCE
    execution.checkpointing.timeout: 10min
    execution.checkpointing.min-pause: 1min
    execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION
    restart_strategy: exponential_delay
    restart_strategy.exponential_delay.initial-backoff: 10s
    restart_strategy.exponential_delay.max-backoff: 5min
    metric.reporters: prom
    metrics.reporter.prom.class: org.apache.flink.metrics.prometheus.PrometheusReporter
    metrics.reporter.prom.port: 9999
    containerized.master.env.ENABLE_BUILT_IN_PLUGINS: flink-connector-iceberg-1.18.jar;flink-cdc-pipeline-connector.jar
  podTemplate:
    spec:
      containers:
        - name: flink-main-container
          volumeMounts:
            - name: ssd
              mountPath: /mnt/ssd/flink
            - name: ice-conf
              mountPath: /opt/flink/conf/iceberg
      volumes:
        - name: ssd
          hostPath: { path: /var/flink/ssd }
        - name: ice-conf
          configMap: { name: iceberg-catalog-conf }
```

### 2.3 作业资源规划

| Job | JM | TM(replicas×cpu/mem) | 并行度 | 说明 |
|-----|----|----------------------|--------|------|
| cdc-order | 2/4g | 8×4/8g | 32 | 订单 CDC |
| cdc-learn | 2/4g | 16×4/8g | 64 | 学习事件高频 |
| cdc-user | 1/2g | 2×2/4g | 8 | 低频 |
| rt-learn | 2/4g | 4×4/8g | 16 | 实时指标 |
| rt-live | 2/4g | 4×4/8g | 16 | CEP 30s checkpoint |
| rt-trade | 2/4g | 4×4/8g | 16 | GMV |
| rt-risk | 2/4g | 8×4/8g | 32 | CEP 10s checkpoint |
| rt-bill | 1/2g | 2×2/4g | 8 | 对账 |
| fs-online | 2/4g | 8×4/8g | 32 | 在线特征 |

### 2.4 作业生命周期
- **部署**：`kubectl apply -f cdc-order.yaml`
- **升级**：`upgradeMode: savepoint`，Operator 自动 savepoint→cancel→从 savepoint 启动
- **暂停/恢复**：`state: stopped`/`state: running`
- **savepoint**：`kubectl flink savepoint <deployment>`

---

## 3. Iceberg Catalog 配置

### 3.1 JDBC Catalog（PostgreSQL）

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: iceberg-catalog-pg
  namespace: iceberg-catalog
spec:
  serviceName: iceberg-catalog-pg
  replicas: 1
  template:
    spec:
      containers:
        - name: postgres
          image: postgres:16
          env:
            - name: POSTGRES_DB
              value: iceberg
            - name: POSTGRES_USER
              valueFrom: { secretKeyRef: { name: catalog-secret, key: user } }
            - name: POSTGRES_PASSWORD
              valueFrom: { secretKeyRef: { name: catalog-secret, key: password } }
          volumeMounts:
            - { name: data, mountPath: /var/lib/postgresql/data }
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        resources: { requests: { storage: 100Gi } }
        accessModes: ["ReadWriteOnce"]
```

### 3.2 Flink/Iceberg catalog 配置（ConfigMap）

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: iceberg-catalog-conf
  namespace: flink
data:
  catalog.sql: |
    CREATE CATALOG iceberg_jdbc WITH (
      'type'='iceberg',
      'catalog-impl'='org.apache.iceberg.jdbc.JdbcCatalog',
      'uri'='jdbc:postgresql://iceberg-catalog-pg.iceberg-catalog:5432/iceberg',
      'jdbc.user'='${CATALOG_USER}',
      'jdbc.password'='${CATALOG_PASSWORD}',
      'warehouse'='s3://edu-iceberg/warehouse',
      'io-impl'='org.apache.iceberg.aws.s3.S3FileIO',
      's3.endpoint'='https://s3.cn-north-1.amazonaws.com.cn',
      's3.path-style-access'='false'
    );
```

### 3.3 MinIO 对接（私有部署备选）
```properties
# iceberg.properties
io-impl=org.apache.iceberg.aws.s3.S3FileIO
s3.endpoint=http://minio.minio:9000
s3.path-style-access=true
s3.access-key-id=${MINIO_KEY}
s3.secret-access-key=${MINIO_SECRET}
warehouse=s3://edu-iceberg/warehouse
```

---

## 4. S3/MinIO 对接

### 4.1 Bucket 规划
| Bucket | 用途 | 生命周期 |
|--------|------|---------|
| edu-iceberg/warehouse | Iceberg 表数据 | 按表 expire_snapshots 管理 |
| edu-iceberg/metadata | Iceberg 元数据 | 同上 |
| edu-config | Flink pipeline YAML / 作业配置 | 版本化 |
| edu-archive | 冷归档 | 跨区复制 |

### 4.2 跨区复制
- S3：开启 Cross-Region Replication 到华南/华东
- MinIO：启用 Bucket Replication（site replication）

### 4.3 权限
- Flink SA：读写 `edu-iceberg/*`
- Trino SA：只读 `edu-iceberg/*`
- 归档作业 SA：读写 `edu-iceberg/archive/*`

---

## 5. Kafka 集群配置（Strimzi）

### 5.1 Kafka CRD
```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: edu-kafka
  namespace: kafka
spec:
  kafka:
    replicas: 5
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
      default.replication.factor: 3
      min.insync.replicas: 2
      log.retention.hours: 72
      num.partitions: 12
    storage:
      type: jbod
      volumes:
        - id: 0
          type: persistent-claim
          size: 500Gi
          class: ssd
  zookeeper:
    replicas: 3
    storage:
      type: persistent-claim
      size: 50Gi
```

### 5.2 Topic 创建
```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: cdc.order-group
  labels: { strimzi.io/cluster: edu-kafka }
  namespace: kafka
spec:
  partitions: 12
  replicas: 3
  config:
    retention.ms: 259200000   # 72h
    compression.type: producer
```
topic 清单：`cdc.order_group` / `cdc.learning_event_group` / `cdc.user_group` / `alert.live_anomaly` / `alert.risk.*` / `dash.*`。

### 5.3 Schema Registry（Apicurio）
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apicurio-registry
  namespace: schema-registry
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: registry
          image: apicurio/apicurio-registry-sql:2.5
          env:
            - name: REGISTRY_DATASOURCE_URL
              value: jdbc:postgresql://registry-pg:5432/registry
            - name: REGISTRY_DATASOURCE_USERNAME
              valueFrom: { secretKeyRef: { name: registry-secret, key: user } }
            - name: REGISTRY_DATASOURCE_PASSWORD
              valueFrom: { secretKeyRef: { name: registry-secret, key: password } }
```
兼容性策略：全局 `BACKWARD_TRANSITIVE`，CDC topic 组 `FULL_TRANSITIVE`。

---

## 6. Trino 部署

### 6.1 Trino 集群
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: trino-catalog
  namespace: trino
data:
  iceberg.properties: |
    connector.name=iceberg
    iceberg.catalog.type=jdbc
    iceberg.jdbc-catalog.driver-class=org.postgresql.Driver
    iceberg.jdbc-catalog.connection-url=jdbc:postgresql://iceberg-catalog-pg.iceberg-catalog:5432/iceberg
    iceberg.jdbc-catalog.connection-user=${ENV:CATALOG_USER}
    iceberg.jdbc-catalog.connection-password=${ENV:CATALOG_PASSWORD}
    iceberg.file-format=PARQUET
    iceberg.compression-codec=ZSTD
  tidb.properties: |
    connector.name=postgresql
    connection-url=jdbc:postgresql://tidb-proxy:4000/
    connection-user=${ENV:TIDB_USER}
    connection-password=${ENV:TIDB_PASSWORD}
```

### 6.2 资源组（per BU 隔离）
```json
{
  "rootGroups": [
    {"resourceGroupId": "global", "softMemoryLimit": "80%", "maxQueued": 100, "hardConcurrencyLimit": 50},
    {"resourceGroupId": "global.bu_it", "softMemoryLimit": "30%", "hardConcurrencyLimit": 20},
    {"resourceGroupId": "global.bu_kaoyan", "softMemoryLimit": "20%", "hardConcurrencyLimit": 10}
  ],
  "selectors": [
    {"resourceGroupId": "global.bu_it", "source": "trino-it"},
    {"resourceGroupId": "global.bu_kaoyan", "source": "trino-kaoyan"}
  ]
}
```

### 6.3 Trino 部署规模
- Coordinator：1（4 vCPU/8GB）
- Worker：5（8 vCPU/32GB），按查询并发扩

---

## 7. Spark Operator 部署（离线/归档/对账）

```yaml
apiVersion: sparkoperator.k8s.io/v1beta2
kind: SparkApplication
metadata:
  name: dw-retention-daily
  namespace: spark
spec:
  type: Scala
  mode: cluster
  image: spark:3.5-iceberg
  mainClass: com.edu.dw.RetentionJob
  mainApplicationFile: s3://edu-config/jobs/dw-retention.jar
  driver: { cores: 2, memory: 4g }
  executor: { cores: 4, memory: 8g, instances: 10 }
  schedule: "@daily 02:00"
  sparkConf:
    spark.sql.catalog.iceberg_jdbc: org.apache.iceberg.spark.SparkCatalog
    spark.sql.catalog.iceberg_jberg.catalog-impl: org.apache.iceberg.jdbc.JdbcCatalog
```

---

## 8. 监控（Prometheus + Grafana）

### 8.1 Flink Exporter
- FlinkDeployment 内置 Prometheus reporter（port 9999）
- ServiceMonitor 采集：
```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: flink-metrics
  namespace: flink
spec:
  selector:
    matchLabels: { app: flink }
  endpoints:
    - port: metrics
      path: /
      interval: 30s
```

### 8.2 关键 Dashboard
| Dashboard | 面板 |
|-----------|------|
| Flink Job 总览 | job 状态/吞吐/checkpoint 失败率/重启次数 |
| CDC 链路 | TiCDC lag/Kafka lag/入湖延迟/commit 时长 |
| Iceberg 表健康 | 小文件数/快照数/orphan/表大小 |
| Feature Store | 在线查询 P99/skew 率/新鲜度 |
| 数据质量 | schema 失败/行数漂移/空值率 |

### 8.3 告警规则
```yaml
groups:
- name: flink
  rules:
  - alert: FlinkCheckpointFailed
    expr: increase(flink_jobmanager_job_numberOfFailedCheckpoints[5m]) > 0
    for: 1m
    annotations: { summary: "Flink checkpoint 失败 {{ $labels.job_name }}" }
  - alert: CDCIngestLagHigh
    expr: histogram_quantile(0.95, cdc_ingest_lag_seconds_bucket) > 300
    for: 5m
    annotations: { summary: "CDC 入湖 P95 超 5min" }
  - alert: IcebergSmallFiles
    expr: iceberg_small_file_count > 50
    for: 10m
  - alert: FeatureSkewHigh
    expr: feature_skew_rate > 0.001
    for: 15m
```

---

## 9. CI/CD（数据 pipeline 部署）

### 9.1 流水线
```
Git Push → CI(单测+契约) → 构建镜像 → 部署预发 → 集成测试 → 灰度生产 → 全量
```

### 9.2 GitLab CI 示例
```yaml
stages:
  - test
  - build
  - deploy-staging
  - integration
  - deploy-prod

test:flink-udf:
  stage: test
  image: maven:3.9
  script: mvn test -pl flink-jobs

build:pipeline-image:
  stage: build
  script: docker build -t registry.edu/flink-cdc-pipeline:${CI_COMMIT_SHA} .

deploy:staging:
  stage: deploy-staging
  script:
    - sed -i "s|IMAGE_TAG|${CI_COMMIT_SHA}|" k8s/cdc-order.yaml
    - kubectl apply -f k8s/cdc-order.yaml --context=staging

integration:test:
  stage: integration
  script: mvn verify -pl tests/integration -Denv=staging

deploy:prod:
  stage: deploy-prod
  when: manual
  script:
    - kubectl apply -f k8s/cdc-order.yaml --context=prod
```

### 9.3 Schema 演化发布流程
1. DDL PR 评审 → Schema Registry 注册新版本（兼容性校验）
2. 灰度：10% Flink job 消费新 schema，监控
3. 全量：100% 切换
4. 回滚：Registry 回滚版本 + job 回滚

---

## 10. 运维 Runbook

### 10.1 Checkpoint 失败处理

**现象**：`FlinkCheckpointFailed` 告警；`numberOfFailedCheckpoints` 上升。

**排查**：
1. `kubectl logs <jm-pod> -n flink | grep "checkpoint"` 查失败原因
2. 检查 TM 是否 OOM：`kubectl top pod -n flink`
3. 检查对象存储延迟：Prometheus `s3_request_duration`
4. 检查 Kafka lag：`kafka-consumer-lag`

**处理**：
- 偶发失败：Flink 自动重启（exponential_delay），观察 5min
- 持续失败：从 savepoint 恢复
  ```bash
  kubectl flink savepoint cdc-order -n flink
  kubectl patch flinkdeployment cdc-order --type=merge -p '{"spec":{"job":{"state":"stopped"}}}'
  # 等停止后从 savepoint 启动
  kubectl flink start cdc-order --from-savepoint <savepoint-path> -n flink
  ```
- TM OOM：扩 TM replicas 或 mem
- 对象存储抖动：检查 S3/MinIO 健康，必要时切 MinIO 备份

**验证**：checkpoint 成功率恢复 >99%，入湖延迟 <5min。

### 10.2 Compaction 调度

**现象**：`IcebergSmallFiles` 告警；查询变慢。

**排查**：
1. `SELECT partition, count(*) FROM iceberg_jdbc.ods.ods_order.files GROUP BY partition` 看小文件分布
2. 检查 SinkV2 maintenance 是否启用

**处理**：
- 手动触发 compaction：
  ```sql
  -- Flink SQL
  CALL iceberg_jdbc.system.rewrite_data_files('ods.ods_order', '{ "target_file_size_bytes": 268435456 }');
  CALL iceberg_jdbc.system.expire_snapshots('ods.ods_order', TIMESTAMP '2026-07-01');
  CALL iceberg_jdbc.system.remove_orphan_files('ods.ods_order');
  ```
- 调整 maintenance 触发阈值：`min_input_files=3`
- 高频表（ods_learning_event）compaction 间隔调到 30min

**验证**：小文件数<50/分区，查询 P95 恢复。

### 10.3 Schema Evolution 流程

**场景**：上游 TiDB order 表 ADD COLUMN `coupon_id BIGINT`。

**流程**：
1. 业务方提 DDL PR，架构评审
2. Schema Registry 注册新 Avro schema（兼容性校验 BACKWARD_TRANSITIVE）
3. 灰度：TiDB 执行 DDL，TiCDC 输出新 schema 消息
4. Flink CDC pipeline 自动 EVOLVE（YAML `value.fields-include=ALL`）
5. Iceberg 表 `ALTER TABLE ods_order ADD COLUMN coupon_id BIGINT`
6. 监控下游消费方 10min，无报错
7. 全量：通知所有消费方
8. 回滚预案：若下游不兼容，Registry 回滚 + Flink job 回滚到旧版本

**验证**：新列可查，历史行 NULL，下游查询不中断。

### 10.4 Flink Job 恢复（savepoint）

```bash
# 1. 触发 savepoint
kubectl flink savepoint cdc-learn -n flink
# 输出: Savepoint at s3://edu-flink-sp/cdc-learn/sv-1234

# 2. 停止作业
kubectl patch flinkdeployment cdc-learn --type=merge -p '{"spec":{"job":{"state":"stopped"}}}'

# 3. 从 savepoint 启动
kubectl flink start cdc-learn --from-savepoint s3://edu-flink-sp/cdc-learn/sv-1234 -n flink
```
RTO 目标 <60s（NFR-06）。验证：无数据丢失（对比 Kafka offset 与 Iceberg 行数）。

### 10.5 对象存储故障

**现象**：S3/MinIO 5xx 错误；checkpoint 超时。

**处理**：
- S3：切到备份 region endpoint
- MinIO：切到备份集群
- 紧急：Flink job 进入 `stopped`，等存储恢复后从 savepoint 启动

### 10.6 Trino 大查询拖垮

**现象**：Trino 查询排队；Coordinator CPU 100%。

**处理**：
- 资源组限流：降低问题 BU 的 `hardConcurrencyLimit`
- kill 大查询：`SELECT * FROM system.runtime.queries WHERE state='RUNNING'`
- 加物化视图加速重复查询

---

## 11. 扩容规划

| 阶段 | Flink TM | Kafka 分区 | Trino Worker | 目标 |
|------|---------|-----------|--------------|------|
| M1 | 8 | 12 | 5 | 10,000 ev/s |
| M2 | 16 | 24 | 8 | 50,000 ev/s |
| M3 | 32 | 48 | 12 | 500,000 ev/s |

扩容操作：`kubectl scale flinkdeployment cdc-learn --replicas=16`；Kafka 加分区（注意重分区）；Trino 加 Worker。

---

## 12. 备份与灾备

| 数据 | 备份策略 | RPO |
|------|---------|-----|
| Iceberg catalog (PG) | 每日 pg_dump + WAL 归档 | 5min |
| Schema Registry | 每日 dump | 1h |
| Iceberg 表数据 | S3 跨区复制 + versioning | 0（强一致） |
| Flink savepoint | 保留最近 3 个 | 最近 checkpoint |

---

## 13. 安全加固

| 项 | 措施 |
|----|------|
| K8s RBAC | Flink/Trino/Spark SA 最小权限 |
| Secret 管理 | Vault + CSI Secret Store |
| 网络策略 | namespace 间 NetworkPolicy 限制 |
| 镜像扫描 | Trivy 扫描 Flink/Trino 镜像 |
| 审计 | K8s audit log + Flink 作业操作审计 |

---

## 14. 部署追溯

| SRS/SAD | 部署章节 |
|---------|---------|
| FR-OPS-01 作业管理 | §2 Flink Operator |
| FR-LH-01 表创建 | §3 Iceberg catalog |
| FR-CDC-02 Avro+Registry | §5.3 Schema Registry |
| FR-QY-02 Trino | §6 |
| FR-OPS-02 监控 | §8 |
| NFR-06 恢复 | §10.4 |
| NFR-10 合规 | §12 海外独立 |

---

**文档结束**。本部署方案与 SDD 同步迭代，新增组件需补部署清单与 runbook。
