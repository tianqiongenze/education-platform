# applications-cluster 部署 Runbook（D-225）

> 目标集群：`applications-cluster`（K8s v1.28.2，k8s-master=10.167.2.175 / k8s-worker1=10.167.2.176，CentOS 7，docker 26.1.4）
> 项目命名空间：`education-platform`；共享基础设施**复用不重建**：`infra` / `dify-plus`
> 铁律约束：D-215（ClusterIP-only + 3 个 Traefik Ingress）、铁律 9（运行验证真相律）

---

## 1. 集群实测盘点（2026-09-06，kubectl 实证）

### 1.1 infra 命名空间（共享，直接使用）

| 组件 | 状态 | 入口 | 备注 |
|---|---|---|---|
| CockroachDB ×3 | ✅ Running | `cockroachdb.infra.svc:26257`（PG wire，NodePort 30257） | **无 TiDB**；insecure 模式；业务库 `education` 已建 |
| Milvus standalone | ✅ Running | `milvus.infra.svc:19530`（NodePort 30909） | etcd+minio+standalone |
| NebulaGraph | ✅ Running | `nebula-graphd.infra.svc:9669` | metad/storaged/graphd |
| Pulsar | ⚠️ 降级 | `pulsar.infra.svc:6650` | broker 起、**bookies CrashLoop**（ZK 会话超时）；修复见 §5 |
| Redis（单机） | ✅ Running | `redis.infra.svc:6379` | 非 Cluster |

### 1.2 dify-plus 命名空间（共享）

| 组件 | 状态 | 入口 |
|---|---|---|
| Redis Cluster（redis8） | ✅ 3 节点 Running | `redis8-node-{0,1,2}.dify-plus.svc:6379`（headless: redis8-svc） |
| PostgreSQL/pgbouncer/weaviate | ✅ Running | dify 自用，勿动 |

### 1.3 集群缺失项（诚实记录）

- ❌ **TiDB**：全集群无 → 数据库切换到 CRDB（PG wire，`jdbc:postgresql://…/education?sslmode=disable`，驱动 `org.postgresql.Driver` 由 env 覆盖）
- ❌ **Nacos**：全集群无 → Dubbo registry=N/A（Wave-1 跨服务 RPC 降级，Wave-2 在项目命名空间内补 Nacos）
- ⚠️ Pulsar bookies 崩溃 → 服务以 D-195 no-op 事件路径启动

---

## 2. SSH 免密（已配置）

```
本机 ~/.ssh/id_ed25519_ep（无 passphrase 专用自动化密钥）
  → root@10.167.2.175 (k8s-master) / root@10.167.2.176 (k8s-worker1)
~/.ssh/config 两个 Host 条目指定 IdentitiesOnly
```

**踩坑 P-1**：用户主密钥 `id_ed25519` 有 passphrase 且 macOS 重启后 ssh-agent 清空 →
`ssh-add` 解锁前免密不可用。解决：生成专用无密码密钥 `id_ed25519_ep` 并
`ssh-copy-id -o PubkeyAuthentication=no` 安装（避免 ssh-copy-id 先试加密主密钥卡 passphrase 提示）。

---

## 3. 构建与分发（本机无 Docker 的替代管线）

本机 colima VZ 启动失败、Rancher Desktop daemon 未运行 → **放弃本地 docker build**：

```
① Java：mvn compile jib:buildTar -Pjib-build（免 daemon，出 target/jib-image.tar）
   基础镜像走 daocloud 镜像源（本机/节点均连不上 Docker Hub）：
   -Djib.from.image=m.daocloud.io/docker.io/library/eclipse-temurin:25-jre-alpine
   ⚠️ 必须带 GIT_URL/GIT_COMMIT 环境变量（pom OCI labels 引用，缺了报 "labels map contains null values"）
   ⚠️ 用全限定 goal com.google.cloud.tools:jib-maven-plugin:3.4.4:buildTar（-pl 下短前缀解析失败）
② 传输：scp tar → master: docker load；master: docker save | 本机中转 | worker1: docker load
   ⚠️ 踩坑 P-2：175→176 节点间无 SSH 互信，master 内嵌 ssh 管道会 Permission denied；
   管道两端必须都由本机发起（`ssh 175 "docker save" | ssh 176 "docker load"`）
③ Go：CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build → scp → 节点 docker build（FROM scratch，纯 stdlib）
```

镜像名：`harbor.edu.internal/education-platform/<service>:1.0.0`（与 base 清单一致，
imagePullPolicy=IfNotPresent 直接命中节点本地镜像，无需真实 harbor）。

---

## 4. 部署流程（education-platform 命名空间）

```bash
# 1) 渲染（本机）
kubectl kustomize deploy/k8s/overlays/applications-cluster > rendered.yaml
#    middleware-endpoints.yaml 已按实测盘点写死真实端点（键名=服务实际消费的 env 名：
#    TIDB_URL/DB_USERNAME/DB_PASSWORD/SPRING_DATASOURCE_DRIVER_CLASS_NAME/
#    SPRING_DATA_REDIS_CLUSTER_NODES/DUBBO_REGISTRY_ADDRESS=N/A）
# 2) CRDB 建库（首次）
kubectl exec cockroachdb-a-0 -n infra -- /cockroach-binary/cockroach sql --insecure \
  --host=localhost:26257 -e 'CREATE DATABASE IF NOT EXISTS education;'
#    ⚠️ 踩坑 P-3：镜像内二进制在 /cockroach-binary/cockroach（copy-binary init 注入），
#    不在惯例的 /cockroach/cockroach
# 3) 应用（先无 NetworkPolicy/HPA 验证功能，再补）
kubectl apply -f rendered-wave1.yaml
kubectl -n education-platform rollout status deploy/edu-core-business --timeout=300s
# 4) 冒烟
kubectl -n education-platform run smoke --rm -it --image=curlimages/curl -- \
  curl -s http://edu-core-business:8083/actuator/health
```

依赖顺序（D-215）：namespace → config → (security) → foundation(core-business/tenant) →
domain(grading/order/payment) → edge(portal-bff/ws-gateway/ingress) → autoscale。

### Wave 状态

| Wave | 服务 | 状态 |
|---|---|---|
| 1 | core-business, grading-service, order-platform, payment-platform, portal-bff, ws-gateway | 本 runbook 覆盖 |
| 2 | knowledge ×5, data-platform, boardgame, senior-care, live-go, ai-platform | 镜像补建中，同管线 |

---

## 5. 踩坑与解决方案（实录）

| # | 坑 | 根因 | 解决 |
|---|---|---|---|
| P-1 | ssh 免密失效 | 主密钥 passphrase + agent 清空 | 专用无密码密钥 + `PubkeyAuthentication=no` 安装 |
| P-2 | 节点间 load 失败 | 175/176 无互信 | 本机中转管道 |
| P-3 | CRDB 建库 127 | 二进制非惯例路径 | `/cockroach-binary/cockroach` |
| P-4 | Java 服务启动即崩：`ClassNotFoundException …autoconfigure.data.redis.RedisProperties` | **redisson-spring-boot-starter 3.51 兼容 Boot≤3.5**，其 RedissonAutoConfiguration 引用 Boot 3 FQN；Boot 4 中类已迁 `org.springframework.boot.data.redis.autoconfigure` | redisson 为**零代码使用死依赖** → 从 4 个服务 pom 删除（铁律8：(a)删依赖✓ (b)升 redisson 4.x (c)exclude autoconfig） |
| P-5 | `Cannot load driver class: org.postgresql.Driver` | fat jar 只带 mysql-connector；CRDB 走 PG wire | 5 服务加 `org.postgresql:postgresql:runtime`（42.7.11，BOM 管理） |
| P-6 | overlay 键名失效风险 | 服务实际消费 `TIDB_URL`（非 TIDB_JDBC_URL）；driver-class-name yml 硬编码 | ConfigMap 键名对齐实测消费点 + `SPRING_DATASOURCE_*` relaxed-binding 覆盖 |
| P-7 | Dubbo 无注册中心 | 集群无 Nacos | registry=N/A（providers 不注册仍可服务；Wave-2 补 Nacos） |
| P-8 | 直连 URL 方案否决 | ConfigMap-as-env 键含 `.` 不合法 | 放弃 per-interface url 注入 |
| P-9 | 基础镜像拉取失败 | 本机/节点均不可达 Docker Hub | daocloud 镜像源 + 节点已有 temurin:25-jre-alpine |
| P-10 | 集群资源浪费 | qianrenqianmian 7 个 Deployment 15 Pod 崩溃循环 + dify 18 个 Completed 僵尸 | 可逆 scale-to-0 + 清理 Succeeded Pod（worker1 CPU 37%→2%） |

---

| P-11 | Dubbo 启动即崩：`NoClassDefFoundError com/alibaba/fastjson/JSONException`（r4 新副本） | Dubbo 3.3.6 `JsonUtils` 按 SPI 顺序尝试 `FastJsonImpl`（fastjson **v1**）；树里只有 fastjson2（2.0.56），v1 类缺失 | **待修（部署暂停）**：给 dubbo 服务显式加 `com.alibaba:fastjson:2.0.58-compatible`（v1 API shim，fastjson2 官方兼容包），或 `-Ddubbo.application.json-util=gson` 强制走 Gson 实现（gson 已在树中）；根因与 redisson 同类——SPI 实现探测在运行期才发现缺类，构建期应加 enforcer 规则 |

## 6. 已知降级项（Wave-1）

1. **跨服务 Dubbo RPC 不可用**（registry=N/A）：BFF 聚合调用返回降级错误；HTTP/健康/单服务功能正常
2. **事件发布 no-op**（D-195 路径）：Pulsar bookies 修复后自动恢复
3. **NetworkPolicy/HPA 未启用**：功能验证通过后按 phase 3/8 补上
4. **CRDB insecure 模式**：共享 infra 现状；生产化需 TLS + 用户鉴权（runbook 后续项）
