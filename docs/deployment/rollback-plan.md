# 生产回滚计划 (Production Rollback Plan)

> **文档版本**: v2.0 | **更新日期**: 2026-08-12 | **适用环境**: Production (K8s 共享集群)
>
> 本计划覆盖教育平台 13 个服务的生产回滚操作。回滚目标: **P0/P1 事件 30 分钟内恢复**。
>
> 服务范围: portal-bff(8090), core-business(8083), order-platform/order-platform-service(8084), payment-platform/payment-platform-service(8087), grading-service(8085), ai-platform(8086), ws-gateway(8081), knowledge-platform/retrieval-service, knowledge-platform/agent-service, knowledge-platform/tenant-service, knowledge-platform/knowledge-bff, knowledge-platform/knowledge-service, data-platform, portal-web, admin-web

---

## 1. 回滚原则 (Rollback Principles)

| 原则 | 说明 |
|------|------|
| 安全第一 (Safety First) | 任何回滚操作前先评估对共享集群 (TiDB/Redis/Pulsar) 的影响 |
| 数据完整性 (Data Integrity) | 涉及 DB 变更的回滚必须先做快照备份，优先保证数据不丢不脏 |
| 快速回滚 (Fast Rollback) | GitOps 镜像回滚目标 <5 min；DB 回滚目标 <30 min |
| 可逆优先 (Reversible First) | 优先回滚到上一已知稳定版本，而非尝试热修复 |
| 最小爆炸半径 (Blast Radius) | 单服务回滚不影响其他服务；四 cell 分批回滚 |
| 通知透明 (Transparency) | 回滚决策必须同步 On-Call/Lead/产品，并记录事后复盘 |

**回滚决策权**: P0 由 Tech Lead 决策；P1 由服务负责人 + On-Call 决策；P2 由 On-Call 决策并报备。

---

## 2. GitOps 回滚 (GitOps Rollback)

### 2.1 ArgoCD 应用回滚 (推荐，最快)

> 适用场景: 镜像/配置变更导致的服务异常，无 DB Schema 不可逆变更。

| 步骤 | 操作 | 命令 | 预计耗时 |
|------|------|------|----------|
| 1 | 查看应用历史 | `argocd app history <app-name>` | <1 min |
| 2 | 选定上一稳定版本 | 确认 revision/hash | <1 min |
| 3 | 执行回滚 | `argocd app rollback <app-name> <revision-id>` | 2-5 min |
| 4 | 健康检查 | `argocd app wait <app-name> --health` | 2-3 min |
| 5 | 冒烟测试 | 自动化脚本 | 2-5 min |

```bash
# 查看历史
argocd app history core-business

# 回滚到指定版本
argocd app rollback core-business <revision-id>

# 强制同步 (如回滚未触发)
argocd app sync core-business --revision <git-commit-sha> --force
```

### 2.2 Git Revert + 强制同步 (持久化回滚)

> 适用场景: 需要将回滚固化到 Git 历史，避免 ArgoCD 自动同步再次前滚。

```bash
# 1. 在 GitOps 仓库 revert 变更
git revert <commit-sha>
git push origin main

# 2. ArgoCD 检测到新 commit，自动同步
argocd app sync core-business --prune

# 3. 验证
argocd app get core-business
```

### 2.3 四 cell 分批回滚 (Karmada, D-041)

| 批次 | Cell | 操作 | 观察期 |
|------|------|------|--------|
| 1 | hz | 回滚 + 健康检查 | 10 min |
| 2 | sz | 回滚 + 健康检查 | 10 min |
| 3 | bj | 回滚 + 健康检查 | 10 min |
| 4 | sg | 回滚 + 健康检查 | - |

```bash
# 调整 Karmada PropagationPolicy 暂停部分 cell
kubectl patch propagationpolicy core-business -n edu-prod \
  --type merge -p '{"spec":{"placement":{"clusterAffinity":{"exclude":["sz","bj","sg"]}}}}'
```

---

## 3. 数据库回滚 (Database Rollback)

### 3.1 TiDB 快照备份/恢复 (BR)

> **前置条件**: 上线前必须执行快照备份 (见 checklist 7.3)。

```bash
# 1. 定位上线前备份
aws s3 ls s3://edu-backup/ | grep 2026-08-12

# 2. 恢复快照 (谨慎，会覆盖现有数据)
tiup br restore full --pd "edu-tidb-pd:2379" \
  --s3.endpoint="s3://edu-backup/2026-08-12-pre-release"

# 3. 验证数据
mysql -h edu-tidb -u admin -p -e "SELECT COUNT(*) FROM edu.course;"
```

### 3.2 迁移回滚 (Migration Rollback)

| 迁移类型 | 回滚方式 | 注意事项 |
|----------|----------|----------|
| 加列 (ADD COLUMN) | 无需回滚 (向前兼容) | 旧代码忽略新列 |
| 删列 (DROP COLUMN) | **不可逆**，需 BR 恢复 | 上线前必须备份 |
| 改类型 (ALTER TYPE) | 需 BR 恢复 | 高风险，避免 |
| 加索引 (ADD INDEX) | `DROP INDEX` | 无数据风险 |
| 加表 (CREATE TABLE) | `DROP TABLE` | 确认无数据写入 |
| Snowflake PK (D-132) | 见 §6.3 | ID 类型不变，向前兼容 |

```bash
# 执行 Flyway 回滚脚本 (如已准备)
kubectl exec -n edu-prod deploy/core-business -- \
  java -cp app.jar org.flywaydb.core.Flyway migrate \
  -configFiles=/app/db/rollback.conf
```

### 3.3 数据对账 (Data Reconciliation)

```sql
-- 回滚后比对关键表行数
SELECT 'order' AS t, COUNT(*) FROM edu.order_info
UNION ALL
SELECT 'payment', COUNT(*) FROM edu.payment_info
UNION ALL
SELECT 'course', COUNT(*) FROM edu.course;

-- 对账脚本: 比对备份与当前差异
-- 工具: tiup br validate
```

---

## 4. 缓存清理 (Cache Cleanup)

### 4.1 回滚后必须清理缓存

> **原因**: 新版本可能写入了新结构的缓存 key，回滚后旧代码无法识别，导致脏读/反序列化失败。

| 步骤 | 操作 | 命令 | 风险 |
|------|------|------|------|
| 1 | 识别受影响 DB | 确认哪些 Redis DB 缓存了变更服务的数据 | 低 |
| 2 | 清理 (FLUSHDB) | `redis-cli -c -n <db> FLUSHDB` | 中 (短暂缓存miss) |
| 3 | 或选择性删除 | `redis-cli -c --scan --pattern '<prefix>*' \| xargs redis-cli -c del` | 低 |
| 4 | 缓存预热 | 执行 Warm-up 脚本 | 低 |
| 5 | 验证 | 抽样比对缓存与 DB | 低 |

```bash
# 清理 core-business 缓存 (DB 1)
redis-cli -c -n 1 FLUSHDB

# 选择性删除秒杀相关 key
redis-cli -c --scan --pattern 'cache:seckill:*' | xargs -L 100 redis-cli -c del

# 清理课程查询缓存 (缓解 TiKV 瓶颈的缓存路径)
redis-cli -c --scan --pattern 'cache:course:*' | xargs -L 100 redis-cli -c del
```

### 4.2 缓存预热 (回滚后)

```bash
# 评分查询缓存预热 (压测验证 0% error 路径)
kubectl exec -n edu-prod deploy/core-business -- java -cp app.jar \
  com.edu.cache.Warmup --type=grading

# 课程查询缓存预热
kubectl exec -n edu-prod deploy/core-business -- java -cp app.jar \
  com.edu.cache.Warmup --type=course
```

---

## 5. 消息队列回滚 (Message Queue Rollback)

### 5.1 DLQ 处理 (Dead Letter Queue)

| 场景 | 处理 |
|------|------|
| 新版本产生的异常消息在 DLQ | 回滚后评估是否需重放 (消息格式可能不兼容) |
| 回滚后消费者版本不匹配 | 跳过 DLQ 中不兼容消息，记录后人工处理 |

```bash
# 查看 DLQ
pulsar-admin topics stats persistent://edu/order-create-dlq

# 重放 DLQ 消息到主 topic (确认兼容后)
pulsar-admin topics peek-messages persistent://edu/order-create-dlq -s <sub> -n 10

# 跳过 DLQ 中不兼容消息
pulsar-admin topics skip persistent://edu/order-create-dlq -s <sub> -n 100
```

### 5.2 消息重放 (Replay)

```bash
# 重置订阅游标到指定时间 (用于补偿)
pulsar-admin topics reset-cursor persistent://edu/order-create \
  -s <sub> -t "2026-08-12T10:00:00"

# 验证重放进度
pulsar-admin topics stats persistent://edu/order-create -s <sub>
```

> **压测验证**: 秒杀场景 50K VU 幂等性已验证 (无重复、无超卖)，重放安全。

---

## 6. 配置回滚 (Configuration Rollback)

### 6.1 ConfigMap/Secret 回滚

```bash
# 1. 查看 ConfigMap 历史 (gitops repo)
cd edu-gitops && git log --oneline -- configs/core-business-config.yaml

# 2. 恢复上一版本
git checkout <prev-commit> -- configs/core-business-config.yaml
git commit -m "rollback: core-business config" && git push

# 3. ArgoCD 自动同步
argocd app sync core-business

# 4. 滚动重启以加载新配置
kubectl rollout restart deploy/core-business -n edu-prod
```

### 6.2 Profile 切换

| 场景 | 操作 |
|------|------|
| 误用 dev profile | 修改 `--spring.profiles.active=prod`，重启 |
| Feature Flag 误开 | 更新 ConfigMap 中 `feature.xxx.enabled=false` |
| 密钥泄露 | 轮换 Secret + 重启依赖服务 (见 runbook 9.1) |

### 6.3 Snowflake ID 回滚 (D-132)

> **风险等级**: 低。Snowflake ID 与 IDENTITY 同为 64-bit bigint，向前兼容；回退仅重新引入 TiDB 写热点风险。

| 步骤 | 操作 | 命令 |
|------|------|------|
| 1 | 修改实体注解 | `@GeneratedValue(strategy = GenerationType.IDENTITY)` (替换 `@CustomId` / Snowflake 赋值) |
| 2 | 移除 SnowflakeIdGenerator 调用 | 在 service impl 中移除 `SnowflakeIdGenerator.getInstance().nextId()` |
| 3 | 重新构建镜像 | `mvn compile jib:build -Pprod` |
| 4 | ArgoCD 滚动部署 | `argocd app sync <service>` |
| 5 | 验证 ID 自增 | `SELECT id FROM edu.course ORDER BY id DESC LIMIT 5;` (应为连续自增) |
| 6 | 监控 TiKV 热点 | `pd-ctl hot region` |

**回退触发条件**:
- worker-id 冲突导致 ID 重复 (极端场景，理论不可能但需预案)
- Snowflake 时钟回拨导致 ID 异常

**注意**: 回退后 TiDB 写热点风险恢复，需尽快排查 Snowflake 根因并重新启用。

### 6.4 前端回滚 (CDN 版本切换)

```bash
# 切换 CDN 指向上一版本产物
aws cloudfront create-invalidation \
  --distribution-id <id> --paths "/*"

# 或更新 CDN origin 指向 v1.x.x-1 目录
```

---

## 7. D-154 安全变更回滚

> 本节覆盖 D-154 安全加固 (P1/P2) 的回滚步骤。所有回滚均为应急操作，需同步 Security Lead。

### 7.1 Actuator 鉴权回滚 (P1-4)

> **触发场景**: Actuator 鉴权导致监控/运维工具无法抓取 metrics，或密码丢失导致运维阻塞。

**方案 A: 临时关闭 (推荐，最快)**

```bash
# 1. 在 ConfigMap 中关闭开关
kubectl edit configmap core-business-config -n edu-prod
# 设置: ep.actuator.security.enabled=false

# 2. 滚动重启
kubectl rollout restart deploy/core-business -n edu-prod
```

**方案 B: 重置密码**

```bash
# 重置为已知强密码
kubectl create secret generic actuator-secret -n edu-prod \
  --from-literal=EP_ACTUATOR_PASSWORD=<new-strong-secret> --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deploy -n edu-prod -l lang=java
```

**风险**: 关闭后 `/actuator/**` 无鉴权暴露，仅作为临时应急，必须在 24h 内恢复。

### 7.2 Rate Limiting 回滚 (P2-10)

> **触发场景**: 限流阈值过低导致正常流量被拒 (误杀)。

**方案 A: 提高阈值**

```bash
# 编辑 ConfigMap
kubectl edit configmap portal-bff-config -n edu-prod
# resilience4j.ratelimiter.configs.default.limitForPeriod: 1000  (或更高)

kubectl rollout restart deploy/portal-bff -n edu-prod
```

**方案 B: 完全禁用**

```bash
# 设置开关
kubectl edit configmap portal-bff-config -n edu-prod
# ep.ratelimit.enabled=false

kubectl rollout restart deploy/portal-bff -n edu-prod
```

**风险**: 禁用限流后下游服务 (TiDB/HikariCP) 可能被压垮，需同步提高 HPA 上限或临时降级。

### 7.3 CORS 硬化回滚 (P2-7)

> **触发场景**: 白名单遗漏合法前端域名，导致前端跨域请求被拒。

**方案 A: 临时添加遗漏域名**

```bash
# 编辑 ConfigMap，添加遗漏的 origin
kubectl edit configmap portal-bff-config -n edu-prod
# spring.web.cors.allowed-origins 下追加: - https://new-domain.example.com

kubectl rollout restart deploy/portal-bff -n edu-prod
```

**方案 B: 临时恢复通配符 (高风险，仅 P0 应急)**

```bash
# 编辑 ConfigMap
kubectl edit configmap portal-bff-config -n edu-prod
# spring.web.cors.allowed-origins: ['*']

kubectl rollout restart deploy/portal-bff -n edu-prod
```

**风险**: 通配符 `*` 允许任意来源跨域，违反 D-154 P2-7 安全要求。仅限 P0 应急，必须在 4h 内修复白名单并恢复显式配置。

### 7.4 ws-gateway fail-closed 回滚 (P1-6)

> **触发场景**: 生产态 fail-closed 过于严格，导致合法请求被拒。

**方案 A: 临时降级为非生产态**

```bash
# 临时修改环境变量 (不推荐，仅应急)
kubectl set env deploy/ws-gateway -n edu-prod APP_ENV=staging
kubectl rollout restart deploy/ws-gateway -n edu-prod
```

**风险**: fail-closed 失效，未鉴权请求可能放行。仅限 P0 应急，必须在 1h 内恢复 `APP_ENV=production`。

### 7.5 Bean Validation / JWT decode-only 回滚

> **不建议回滚**: Bean Validation (P2-9) 与 JWT decode-only 移除 (P1-5) 为代码层修复，回滚需重新构建镜像并丢失安全增益。如确需回滚，通过 ArgoCD 回滚到 D-154 之前的镜像版本。

---

## 8. 回滚验证 (Rollback Verification)

### 8.1 验证清单

| # | 检查项 | 方法 | 通过标准 |
|---|--------|------|----------|
| 8.1 | 服务健康 | `kubectl get pods -n edu-prod` | 全部 Running 1/1 |
| 8.2 | 健康端点 | `curl http://<svc>:port/actuator/health` | 200 UP |
| 8.3 | 网关路由 | `curl https://gateway/health` | 200 |
| 8.4 | 冒烟测试 | 自动化脚本 (登录/下单/查询) | 全 PASS |
| 8.5 | 错误率 | Grafana/Prometheus | <0.1% 持续 10min |
| 8.6 | P95 延迟 | Grafana | <1s 持续 10min |
| 8.7 | TiKV 健康 | `tiup cluster display` | 全绿 |
| 8.8 | Redis 一致性 | 缓存与 DB 抽样比对 | 0 差异 |
| 8.9 | Pulsar 积压 | `pulsar-admin topics stats` | 无异常积压 |
| 8.10 | 数据完整性 | 关键表行数对账 | 与备份一致 |
| 8.11 | Snowflake ID (如未回滚) | `SELECT id FROM edu.course LIMIT 5` | 19 位数字 |
| 8.12 | Actuator 鉴权 (如未回滚) | `curl -i /actuator/prometheus` | 401 (无凭据) |

### 8.2 冒烟测试脚本

```bash
# 自动化冒烟 (登录→查询课程→下单→支付→评分)
kubectl exec -n edu-prod deploy/data-platform -- \
  ./smoke-test.sh --env=prod --target=ws-gateway:8081
```

---

## 9. 回滚决策树 (Rollback Decision Tree)

### 9.1 决策流程

```
事件发生
  │
  ├─ 是否 P0 (全站不可用)?
  │     └─ YES → 立即回滚 (ArgoCD) → 通知 CTO → 事后复盘
  │
  ├─ 是否数据损坏/丢失?
  │     └─ YES → 停止写入 → BR 快照恢复 → 通知 DBA Lead
  │
  ├─ 是否 DB Schema 不可逆变更?
  │     └─ YES → BR 恢复 (无法 GitOps 回滚) → 数据对账
  │
  ├─ 是否 D-154 安全变更导致 (限流/鉴权/CORS)?
  │     └─ YES → §7 对应方案回滚 → 验证 → 通知 Security Lead
  │
  ├─ 是否 Snowflake ID 异常?
  │     └─ YES → §6.3 回退至 IDENTITY → 监控 TiKV 热点
  │
  ├─ 是否单服务异常 (其他正常)?
  │     └─ YES → 单服务 ArgoCD 回滚 → 缓存清理 → 冒烟
  │
  ├─ 是否配置/密钥问题?
  │     └─ YES → ConfigMap/Secret 回滚 → 重启
  │
  ├─ 是否可快速热修复 (<15min)?
  │     └─ YES → Fix-Forward (前滚修复) → 加强监控
  │     └─ NO → 回滚
  │
  └─ 默认: 评估后回滚 (保守优先)
```

### 9.2 严重度矩阵

| 严重度 | 现象 | 决策 | 时效 |
|--------|------|------|------|
| P0 | 全站不可用 / 数据丢失 | 立即回滚 + BR 恢复 | 5 min 决策, 30 min 恢复 |
| P1 | 核心功能 (登录/下单/支付) 不可用 | 立即回滚单服务 | 15 min 决策 |
| P2 | 部分功能降级 (AI 检索/管理后台) | 评估 fix-forward vs 回滚 | 30 min 决策 |
| P3 | 体验问题 (慢/样式) | 工单跟进，下次发版修 | 不回滚 |

### 9.3 回滚 vs Fix-Forward 决策表

| 条件 | 回滚 | Fix-Forward |
|------|------|-------------|
| 有 DB 不可逆变更 | ✗ (需 BR) | - |
| 根因明确且修复 <15min | ✗ | ✓ |
| 根因不明 | ✓ | ✗ |
| 影响范围扩散中 | ✓ | ✗ |
| 仅前端问题 | CDN 切换 | ✓ (快) |
| 缓存脏数据 | 清缓存 + 回滚 | - |
| D-154 限流误杀 | §7.2 调高阈值 | - |
| D-154 CORS 误拦 | §7.3 加白名单 | ✓ (快) |

### 9.4 压测基线参考 (回滚后容量验证)

| 指标 | 1K VU | 10K VU | 100K VU | 1M VU |
|------|-------|--------|---------|-------|
| ws-gateway 可用性 | 100% | 100% | ~100% | 52% |
| ws-gateway P95 | <500ms | <1s | <1.5s | 2.33s |
| Redis 缓存路径错误率 | 0% | 0% | 0% | 0% |
| TiKV 课程查询错误率 | 0% | <5% | 90-100% | 100% |
| ai-platform 错误率 | 0% | 0% | 0% | 93% |
| TiKV 重启数 | 0 | 0 | 8 (稳定) | 8 (稳定) |

> **回滚后容量基线**: 回滚后应至少恢复到 100K VU 级别的可用性 (ws-gateway ~100%, Redis 缓存 0% error)。若未恢复，继续排查。

---

## 附录: 回滚操作快速参考卡

| 场景 | 命令 | 耗时 |
|------|------|------|
| ArgoCD 回滚 | `argocd app rollback <app> <rev>` | 5 min |
| Git revert | `git revert <sha> && git push` | 10 min |
| TiDB BR 恢复 | `tiup br restore full -s <path>` | 30 min |
| Redis 清缓存 | `redis-cli -c -n <db> FLUSHDB` | <1 min |
| Pulsar 重放 | `pulsar-admin topics reset-cursor ...` | 5 min |
| 前端 CDN 切换 | `aws cloudfront create-invalidation ...` | 5 min |
| ConfigMap 回滚 | `git checkout <prev> -- config && push` | 5 min |
| Actuator 鉴权关闭 (D-154) | `ep.actuator.security.enabled=false` + restart | 3 min |
| Rate Limit 调高 (D-154) | 改 `limitForPeriod` + restart | 3 min |
| CORS 临时通配符 (D-154) | `allowed-origins: ['*']` + restart (仅 P0) | 3 min |
| Snowflake 回退 IDENTITY | 改注解 + 重新构建镜像 | 15 min |

---

> **关联文档**: [go-live-checklist.md](./go-live-checklist.md) | [production-runbook.md](./production-runbook.md) | [production-readiness-report.md](../testing/production-readiness-report.md)
