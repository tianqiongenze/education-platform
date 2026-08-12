# 生产运维手册 (Production Runbook)

> **文档版本**: v2.0 | **更新日期**: 2026-08-12 | **适用环境**: Production (K8s 共享集群)
>
> 本手册覆盖教育平台 13 个服务的生产运维操作。服务清单: portal-bff(8090), core-business(8083), order-platform/order-platform-service(8084), payment-platform/payment-platform-service(8087), grading-service(8085), ai-platform(8086), ws-gateway(8081), knowledge-platform/retrieval-service, knowledge-platform/agent-service, knowledge-platform/tenant-service, knowledge-platform/knowledge-bff, knowledge-platform/knowledge-service, data-platform, portal-web, admin-web
>
> 基础设施: K8s 共享集群 (Karmada hz/sz/bj/sg 四 cell), TiDB v8.5.5, Redis 6-pod, Pulsar 3+3+3

---

## 1. 部署流程 (Deployment Process)

### 1.1 GitOps 同步 (ArgoCD, D-114)

| 步骤 | 操作 | 命令/工具 | 负责人 |
|------|------|-----------|--------|
| 1 | 代码合并至 release 分支 | `git merge` / PR | Dev |
| 2 | CI/CD 触发 JIB 镜像构建 (D-115) | Jenkins/GitHub Actions | DevOps |
| 3 | 镜像推送 Harbor，更新 GitOps 仓库 tag | yq/kustomize | DevOps |
| 4 | ArgoCD 自动检测同步 | ArgoCD UI / `argocd app sync` | DevOps |
| 5 | 四 cell 滚动同步 (hz→sz→bj→sg) | Karmada PropagationPolicy | DevOps |
| 6 | 健康检查通过后标记完成 | ArgoCD Health | DevOps |

```bash
# 查看 ArgoCD 应用状态
argocd app list
argocd app get <app-name> --refresh

# 手动触发同步
argocd app sync <app-name> --prune
```

### 1.2 Canary 金丝雀部署

| 阶段 | 流量比例 | 观察指标 | 持续时间 |
|------|----------|----------|----------|
| Canary | 10% | 错误率 <0.1%, P95 <1s | 15 min |
| Canary | 30% | 错误率 <0.1%, CPU <70% | 15 min |
| Canary | 50% | 错误率 <0.1%, TiKV 无积压 | 30 min |
| 全量 | 100% | 全指标绿 | - |

**回滚触发条件**: 任一阶段错误率 >1% 或 P95 >2s，立即回滚 (见 rollback-plan.md)。

### 1.3 镜像构建 (JIB, D-115)

```bash
# Java 服务 (core-business / order-platform / grading-service / portal-bff 等)
mvn compile jib:build -Pprod

# Go ws-gateway
docker buildx build --platform linux/amd64 -t harbor.edu/ws-gateway:v1.x.x services/ws-gateway

# Python ai-platform
docker build -t harbor.edu/ai-platform:v1.x.x services/ai-platform
```

---

## 2. 扩缩容操作 (Scaling Operations)

### 2.1 HPA/VPA 自动扩缩容

| 服务 | HPA (min-max) | 触发条件 | VPA |
|------|---------------|----------|-----|
| ws-gateway | 3-20 | CPU>70% | - |
| portal-bff | 3-15 | CPU>70%, 错误率>0.5% | - |
| core-business | 2-10 | CPU>70% | - |
| order-platform/order-platform-service | 2-10 | CPU>70% + 队列积压 | - |
| ai-platform | 2-8 | CPU>80% | worker 数 |
| grading-service | 2-8 | CPU>70% | - |
| knowledge-platform/* | 2-6 各 | CPU>70% | - |
| data-platform | 2-6 | CPU>70% | - |

```bash
# 查看当前 HPA
kubectl get hpa -n edu-prod

# 手动扩容
kubectl scale deployment ws-gateway --replicas=10 -n edu-prod

# 临时调整 HPA 上限
kubectl patch hpa ws-gateway -n edu-prod --type merge -p '{"spec":{"maxReplicas":30}}'
```

### 2.2 TiKV 扩容 (核心瓶颈项)

> **风险提示**: 当前单节点 TiKV 最大 587 RPS，1M 级压测时 Java 服务全量 crash。**上线前必须扩至 ≥3 节点**。

```bash
# 查看当前 TiKV 节点
tiup cluster display edu-tidb | grep tikv

# 扩容 TiKV 节点
tiup cluster scale-out edu-tidb scale-tikv.yaml

# 验证 Region 均衡
pd-ctl store --jq '.stores[].status'
```

### 2.3 Redis 扩容

```bash
# 查看集群状态
redis-cli -c -h redis-cluster -p 6379 cluster nodes

# 添加节点后重新分片
redis-cli --cluster reshard redis-cluster:6379
```

### 2.4 Pulsar 扩容

```bash
# Broker 扩容
kubectl scale statefulset pulsar-broker --replicas=4 -n pulsar

# Bookie 扩容
kubectl scale statefulset pulsar-bookie --replicas=4 -n pulsar
```

---

## 3. 故障处理 (Incident Response)

### 3.1 服务不可用 (Service Down)

| 现象 | 诊断步骤 | 修复 |
|------|----------|------|
| Pod CrashLoopBackOff | `kubectl logs <pod> --previous`; 检查 OOM/配置 | 调整资源/修配置/回滚 |
| 服务 502/504 | 检查 ws-gateway 上游健康; `kubectl get endpoints` | 重启 pod/扩容 |
| Java portal-bff crash | 查 TiKV 连接; 查 GC 日志 | TiKV 扩容/调 JVM |
| Python ai-platform 93% error | 查 worker 线程耗尽; `gunicorn workers` | 扩 worker + 限流 |

### 3.2 数据库超时 (TiDB/TiKV Timeout)

```bash
# 1. 查看慢查询
SHOW PROCESSLIST;
SELECT * FROM information_schema.slow_query ORDER BY time DESC LIMIT 10;

# 2. 检查 TiKV 健康与负载
tiup cluster display edu-tidb
pd-ctl store

# 3. 检查 Region 热点
pd-ctl hot region

# 4. 缓解: 扩容 TiKV 或加缓存
```

> **压测基线**: 1M VU 时 TiKV 单节点 587 RPS 为硬瓶颈，课程查询错误率 90-100%。

### 3.3 Redis 驱逐 (Redis Eviction)

```bash
# 查看驱逐与内存
redis-cli -c info memory | grep evicted
redis-cli -c info stats | grep evicted_keys

# 检查大 key
redis-cli -c --bigkeys

# 缓解: 扩容内存或清理冷 key
redis-cli -c scan 0 count 1000 match 'cache:course:*'
```

### 3.4 Pulsar 积压 (Pulsar Backlog)

```bash
# 查看积压
pulsar-admin topics stats persistent://edu/order-create

# 查看订阅者
pulsar-admin topics subscriptions persistent://edu/order-create

# 堆积清理 (谨慎)
pulsar-admin topics skip persistent://edu/order-create -s <sub> -n 1000

# 重放消息
pulsar-admin topics reset-cursor persistent://edu/order-create -s <sub> -t <timestamp>
```

### 3.5 故障升级矩阵

| 严重度 | 响应时效 | 升级路径 |
|--------|----------|----------|
| P0 (全站不可用) | 5 min | On-Call → Tech Lead → CTO |
| P1 (核心功能不可用) | 15 min | On-Call → 服务负责人 |
| P2 (部分功能降级) | 30 min | On-Call 处理 |
| P3 (体验问题) | 4 h | 工单跟进 |

---

## 4. 监控告警 (Monitoring & Alerting)

### 4.1 Grafana 仪表盘

| 仪表盘 | URL/路径 | 关键面板 |
|--------|----------|----------|
| 网关总览 | grafana/d/ws-gateway | QPS/错误率/P95/上游 |
| Java 服务 | grafana/d/java-svc | JVM/GC/线程池/HikariCP |
| TiDB 集群 | grafana/d/tidb | TiKV RPS/Region/慢查询 |
| Redis 集群 | grafana/d/redis | 内存/命中率/驱逐 |
| Pulsar | grafana/d/pulsar | 积压/生产/消费速率 |
| K8s 集群 | grafana/d/k8s | Pod/Node/网络 |

### 4.2 Prometheus 告警规则

| 告警 | 表达式 | 严重度 |
|------|--------|--------|
| 服务错误率高 | `rate(http_errors_total[5m]) > 0.01` | P1 |
| P95 延迟高 | `histogram_quantile(0.95, ...) > 2` | P1 |
| TiKV 节点 <3 | `count(tikv_store_up) < 3` | **P0** |
| Pod 重启频繁 | `rate(kube_pod_container_status_restarts[1h]) > 3` | P2 |
| Redis 内存 >80% | `redis_memory_used / redis_memory_max > 0.8` | P2 |

### 4.3 OTel 链路追踪 (D-118)

```bash
# Tempo 查询 traceId
tempo-cli query trace <traceId>

# 通过 span 属性过滤
{resource.service.name="order-platform/order-platform-service" && span.http.status_code>=500}
```

---

## 5. 日志查询 (Log Queries)

### 5.1 Loki/ELK 统一日志查询

| 场景 | LogQL / KQL |
|------|-------------|
| 错误日志 (全服务) | `{namespace="edu-prod"} |= "ERROR"` |
| 指定服务 | `{app="order-platform/order-platform-service"} |= "Exception"` |
| 按 traceId 串联 | `{namespace="edu-prod"} |= "traceId=abc123"` |
| Java OOM | `{app=~"portal-bff|core-business.*"} |= "OutOfMemoryError"` |
| TiKV 超时 | `{app=~"core-business|grading-service"} |= "TiKV.*timeout"` |
| 秒杀幂等 | `{app="order-platform/order-platform-service"} |= "seckill" |= "idempotent"` |

```bash
# kubectl 直接查日志 (紧急)
kubectl logs -n edu-prod -l app=order-platform/order-platform-service --tail=200 -f
kubectl logs -n edu-prod <pod> --previous | grep ERROR
```

### 5.2 日志结构规范

所有服务日志必须为 JSON 且包含: `timestamp, level, service, traceId, spanId, msg, ...attrs`。

---

## 6. 数据库运维 (Database Operations)

### 6.1 TiDB 备份/恢复 (BR)

```bash
# 全量备份
tiup br backup full --pd "edu-tidb-pd:2379" -s "s3://edu-backup/$(date +%F)"

# 快照恢复
tiup br restore full --pd "edu-tidb-pd:2379" -s "s3://edu-backup/2026-08-09"
```

### 6.2 TiKV Region 均衡

```bash
# 查看 Region 分布
pd-ctl region --jq '.regions | length'
pd-ctl store --jq '.stores[] | {id, region_count: .status.region_count}'

# 手动触发均衡
pd-ctl operator add balance-region
```

### 6.3 慢查询分析

```sql
-- 查看慢查询 Top 10
SELECT time, digest_text, avg_latency, exec_count
FROM information_schema.slow_query
ORDER BY time DESC LIMIT 10;

-- 查看执行计划
EXPLAIN ANALYZE <sql>;
```

> **压测发现**: 课程查询在 100K+ VU 时因 TiKV 单节点瓶颈错误率 90-100%，需通过 Redis 缓存缓解 (评分查询缓存路径 0% error 已验证)。

---

## 7. 缓存运维 (Cache Operations)

### 7.1 Redis Key 巡检

```bash
# 大 key 扫描
redis-cli -c --bigkeys

# key 数量与 TTL
redis-cli -c dbsize
redis-cli -c info keyspace

# 扫描特定前缀
redis-cli -c --scan --pattern 'cache:course:*' | head
```

### 7.2 缓存清理

```bash
# 清理指定 DB (谨慎，需审批)
redis-cli -c -n <db> FLUSHDB

# 选择性删除
redis-cli -c --scan --pattern 'cache:seckill:*' | xargs redis-cli -c del
```

### 7.3 缓存预热 (Warm-up)

```bash
# 评分查询缓存预热 (压测验证 0% error)
kubectl exec -n edu-prod deploy/core-business -- java -cp app.jar \
  com.edu.cache.Warmup --type=grading

# 课程查询缓存预热 (缓解 TiKV 瓶颈)
kubectl exec -n edu-prod deploy/core-business -- java -cp app.jar \
  com.edu.cache.Warmup --type=course
```

---

## 8. 消息队列运维 (Message Queue Operations)

### 8.1 Topic 管理

| Topic | 用途 | 分区 | DLQ |
|-------|------|------|-----|
| persistent://edu/order-create | 订单创建 | 6 | order-create-dlq |
| persistent://edu/payment-callback | 支付回调 | 4 | payment-callback-dlq |
| persistent://edu/course-event | 课程事件 | 4 | course-event-dlq |

```bash
# 列出 topic
pulsar-admin topics list edu

# 查看 topic 统计
pulsar-admin topics stats <topic>

# 创建 topic
pulsar-admin topics create <topic> -p 6
```

### 8.2 订阅清理

```bash
# 查看订阅
pulsar-admin topics subscriptions <topic>

# 删除无效订阅
pulsar-admin topics delete-subscription <topic> -s <sub>
```

### 8.3 积压监控

| 指标 | 告警阈值 | 处理 |
|------|----------|------|
| backlog size | >10K | 扩消费者 / 跳过 |
| 消费延迟 | >5s | 查消费者日志 |
| DLQ 堆积 | >100 | 人工介入重放 |

---

## 9. 安全运维 (Security Operations)

### 9.1 密钥轮换 (D-117)

```bash
# 轮换 JWT 签名密钥
kubectl create secret generic jwt-secret -n edu-prod \
  --from-literal=jwt-key=<new-key> --dry-run=client -o yaml | kubectl apply -f -

# 滚动重启所有依赖 JWT 的服务
kubectl rollout restart deploy -n edu-prod -l security=jwt
```

### 9.2 Secret 更新

```bash
# 更新 DB 密码
kubectl edit secret db-secret -n edu-prod

# 重启依赖服务
kubectl rollout restart deploy -n edu-prod -l db=tidb
```

### 9.3 审计日志审查

| 审查项 | 频率 | 工具 |
|--------|------|------|
| 异常登录 | 每日 | Loki `{app="ws-gateway"} |= "login" |= "FAIL"` |
| 越权访问 | 每日 | RBAC 日志 |
| 敏感数据导出 | 实时告警 | 审计日志 → 告警 |
| Aikido 安全扫描 | 每次发版 | D-077 (8/8 已闭) |

---

## 10. D-154 安全配置

> 本节覆盖 D-154 安全加固 (P1/P2) 的运维要点。详见 `services/ep-web-common/src/main/java/com/ep/web/common/`。

### 10.1 Actuator 端点鉴权 (P1-4)

**实现**: `ep-web-common/ActuatorSecurityAutoConfiguration` (Spring Boot AutoConfiguration，14 个 Java 服务自动生效)。

**规则**:
- `/actuator/health`、`/actuator/info` — `permitAll` (liveness/readiness 探针 + 服务发现)
- 其他 `/actuator/**` — 需要 `ACTUATOR` 角色 (HTTP Basic)
- Stateless (不创建 HTTP Session)

**配置参数**:

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `ep.actuator.security.enabled` | `true` | 总开关，缺省启用 |
| `ep.actuator.security.password` | `actuator-secret` | dev-local 默认；生产通过 `EP_ACTUATOR_PASSWORD` 环境变量覆盖 |

**生产部署配置** (K8s Secret 注入):

```bash
# 设置强密码
kubectl create secret generic actuator-secret -n edu-prod \
  --from-literal=EP_ACTUATOR_PASSWORD=<strong-secret> --dry-run=client -o yaml | kubectl apply -f -

# 滚动重启所有 Java 服务
kubectl rollout restart deploy -n edu-prod -l lang=java
```

**验证**:

```bash
# 应返回 401
curl -i https://<svc>/actuator/prometheus

# 带凭据应返回 200
curl -i -u actuator:<password> https://<svc>/actuator/prometheus

# health 端点应无需鉴权
curl -i https://<svc>/actuator/health   # 200
```

### 10.2 Rate Limiting (P2-10, Resilience4j RateLimiter)

**实现**: `ep-web-common/RateLimitAutoConfiguration` (开关 `ep.ratelimit.enabled=true`)。portal-bff `ProxyController` 已加 `@RateLimiter("default")`。

**配置参数** (各服务 `application.yml`):

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        limitForPeriod: 20           # 每周期许可数
        limitRefreshPeriod: 1s       # 周期
        timeoutDuration: 5s          # 许可等待超时
ep:
  ratelimit:
    enabled: true
```

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| `limitForPeriod` | 20 (登录) / 100 (代理) | 与 HikariCP `maximum-pool-size` 匹配 |
| `limitRefreshPeriod` | 1s | 令牌桶刷新周期 |
| `timeoutDuration` | 5s | 超限请求的等待超时 |

**调整限流阈值**:

```bash
# 编辑 ConfigMap
kubectl edit configmap portal-bff-config -n edu-prod
# 修改 resilience4j.ratelimiter.configs.default.limitForPeriod

# 滚动重启
kubectl rollout restart deploy/portal-bff -n edu-prod
```

### 10.3 Bean Validation (P2-9)

**实现**: GlobalExceptionHandler 捕获 `MethodArgumentNotValidException`；9 个 Controller 已加 `@Valid`；12 个 DTO 已加约束注解 (`@NotBlank`、`@Size`、`@Pattern` 等)。

**运维要点**:
- 所有新增 Controller 端点**必须**加 `@Valid` 注解，否则 DTO 约束不生效
- 验证失败返回 HTTP 400 + 结构化错误响应 (`{code, message, fieldErrors}`)
- 无需运行时配置；为编译期/代码审查强约束

**验证**:

```bash
# 应返回 400 + 字段错误
curl -i -X POST https://<svc>/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"quantity": -1}'
```

### 10.4 CORS 显式 headers/origins (P2-7)

**实现**: 8 个 Java 服务 + 1 个 Python 服务 (ai-platform)。拒绝通配符 `*`，仅允许生产域名白名单。

**配置示例** (Java 服务):

```yaml
spring:
  web:
    cors:
      allowed-origins:
        - https://portal.example.com
        - https://admin.example.com
      allowed-headers:
        - Authorization
        - Content-Type
        - X-Role
        - X-Tenant-Id
      allowed-methods: [GET, POST, PUT, DELETE, OPTIONS]
```

**验证** (见 `docs/testing/manual-test-guide.md` §10.4):

```bash
# 非白名单来源应被拒
curl -I -H "Origin: https://evil.com" https://<svc>/api/v1/courses
# 预期: 无 Access-Control-Allow-Origin 回显

# 白名单来源应放行
curl -I -H "Origin: https://portal.example.com" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: X-Role,X-Tenant-Id" \
  https://<svc>/api/v1/courses
# 预期: Access-Control-Allow-Origin: https://portal.example.com
#        Access-Control-Allow-Headers 含 X-Role, X-Tenant-Id
```

**验证点**: 任何接口都不应返回 `Access-Control-Allow-Origin: *`。

---

## 11. Snowflake ID 配置说明 (D-132)

> 8 个实体启用 Snowflake 主键，消除 TiDB 单 Region 写热点。实现见各服务 `common/SnowflakeIdGenerator.java`。

### 11.1 ID 结构

```
| sign(1) | timestamp(41) | worker(5) | sequence(12) |
```

- **EPOCH**: `2024-01-01T00:00:00Z` (1704067200000)
- **workerId**: 0-31 (5 bit)，区分并发 JVM 实例
- **sequence**: 0-4095 (12 bit)，同一 ms 内同一 worker 的序号
- 单实例单 ms 上限: 4096 个 ID

### 11.2 配置参数

| 参数 | 来源 | 默认值 | 说明 |
|------|------|--------|------|
| `ep.snowflake.worker-id` | System property | 0 | 优先读取 |
| `EP_SNOWFLAKE_WORKER_ID` | 环境变量 | 0 | 次优先读取 |
| - | - | 0 | 兜底；多副本部署时必须显式区分 |

**多副本部署配置** (K8s StatefulSet 推荐):

```yaml
# 每个 pod 用其 ordinal index 作为 worker-id
spec:
  template:
    spec:
      containers:
        - name: core-business
          env:
            - name: EP_SNOWFLAKE_WORKER_ID
              valueFrom:
                fieldRef:
                  fieldPath: metadata.annotations['apps.kubernetes.io/pod-index']
```

或通过 ConfigMap 为每个副本显式分配 (0, 1, 2, ...)。

### 11.3 验证 Snowflake ID 生效

```bash
# 1. 检查实体 ID 为 19 位数字 (Java long 最大约 19 位)
mysql -h edu-tidb -u admin -p -e "SELECT id FROM edu.course LIMIT 5;"
# 预期: id 为类似 7289546291234567890 的 19 位数字 (非 1,2,3 连续自增)

# 2. 通过日志确认 worker-id
kubectl logs -n edu-prod deploy/core-business | grep -i snowflake

# 3. 同一毫秒内多 ID 应不同 (sequence 递增)
mysql -h edu-tidb -u admin -p -e "
  SELECT id, FROM_UNIXTIME(id >> 22 / 1000 + 1704067200) AS ts
  FROM edu.course ORDER BY id DESC LIMIT 10;"
```

### 11.4 回退方案 (如需)

如遇 Snowflake ID 问题 (例如 worker-id 冲突)，可临时回退至 IDENTITY 生成:

1. 修改实体 `@GeneratedValue(strategy = GenerationType.IDENTITY)`
2. 重新构建镜像并部署
3. 注意: 回退后会重新引入 TiDB 写热点风险，仅作应急

---

## 12. 值班交接 (On-Call Handoff)

### 12.1 交接模板

```
=== 值班交接 (On-Call Handoff) ===
日期: 2026-08-12
值班人 (交): __________  值班人 (接): __________

1. 当班事件摘要:
   - [事件1] 严重度/状态/处理人
   - [事件2] ...

2. 未关闭事件:
   - [JIRA-xxx] 描述 / 下一步 / ETA

3. 进行中变更:
   - [变更号] 服务/状态/回滚点

4. 基础设施状态:
   - TiKV 节点数: __ (需 ≥3)
   - Redis 集群: 正常/异常
   - Pulsar 积压: __
   - K8s 节点: __

5. 待办事项:
   - [ ] ...

6. 风险提醒:
   - TiKV 单节点瓶颈 (587 RPS)
   - ai-platform worker 需关注
   - Iron Law 9: k3s 集群恢复后需全量部署验证

签字(交): ________  签字(接): ________
```

### 12.2 升级矩阵

| 角色 | 主值班 | 备值班 | 升级对象 |
|------|--------|--------|----------|
| DevOps On-Call | A | B | DevOps Lead |
| DBA On-Call | C | D | DBA Lead |
| Java On-Call | E | F | Backend Lead |
| Go On-Call | G | H | Backend Lead |
| AI On-Call | I | J | AI Lead |
| 安全 On-Call | K | L | Security Lead |

| 严重度 | 响应时效 | 升级 |
|--------|----------|------|
| P0 | 5 min | On-Call → Lead → CTO |
| P1 | 15 min | On-Call → 服务负责人 |
| P2 | 30 min | On-Call 自处理 |
| P3 | 4 h | 工单 |

---

> **关联文档**: [go-live-checklist.md](./go-live-checklist.md) | [rollback-plan.md](./rollback-plan.md) | [production-readiness-report.md](../testing/production-readiness-report.md)
