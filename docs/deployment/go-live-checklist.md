# Go-Live 上线检查清单 (Go-Live Checklist)

> **文档版本**: v1.1 | **更新日期**: 2026-08-12 | **适用环境**: Production (K8s 共享集群)
>
> 本清单共 **128 项** 检查条目，覆盖 9 大类。所有条目必须全部勾选 `[x]` 且状态为 **PASS** 方可执行上线。
>
> 代码就绪: 15/128项 [x], 需运行验证: 4/128项 [~], 待完成: 109/128项 [ ] (截至 2026-08-12)
>
> 服务范围: portal-bff(8090), core-business(8083), order-platform(8084), payment-platform(8087), grading-service(8085), ai-platform(8086), ws-gateway(8081), retrieval-service/agent-service/tenant-service/knowledge-bff(8070-8074), knowledge-service(8071), data-platform(8082), portal-web, admin-web (共 13 个服务)

---

## 1. 代码就绪 (Code Readiness) — 20 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 1.1 | [~] | 所有单元测试通过 (Java/Go/Python 覆盖率 ≥80%) | Backend Lead | 代码就绪, CI 未在 GitHub Actions 运行 |
| 1.2 | [~] | 所有集成测试通过 (13 服务契约测试) | QA Lead | Testcontainers 代码就绪, core-business→portal-bff→ws-gateway 链路 |
| 1.3 | [~] | E2E 测试通过 (核心业务场景 50+) | QA Lead | Playwright 代码就绪, 待运行验证 |
| 1.4 | [ ] | 生产代码路径无 TODO/FIXME 残留 | Backend Lead | `grep -rn "TODO\|FIXME" --exclude-dir=test` |
| 1.5 | [ ] | 代码审查 (Code Review) 全部完成 | Tech Lead | PR 全部 Approved |
| 1.6 | [ ] | 主干分支 (main/release) 受保护，禁止直推 | DevOps Lead | Git branch protection |
| 1.7 | [ ] | 语义化版本号 (SemVer) 已打 tag | Release Manager | `v1.x.x` |
| 1.8 | [ ] | 构建产物 (JIB 镜像) 已推送至制品仓库 (D-115) | DevOps Lead | harbor registry |
| 1.9 | [ ] | 镜像签名验证通过 (Cosign) | DevOps Lead | D-115 JIB 构建 |
| 1.10 | [ ] | CI/CD 流水线 (D-116) 全绿 | DevOps Lead | 含安全扫描门禁 |
| 1.11 | [ ] | 数据库迁移脚本 (Flyway/Liquibase) 已审查 | DBA | 向前兼容，可回滚 |
| 1.12 | [ ] | API 契约 (OpenAPI 3.0) 与实现一致 | Backend Lead | portal-bff/ws-gateway/各业务服务 |
| 1.13 | [ ] | 幂等性逻辑验证 (seckill 场景 50K VU) | Backend Lead | 无重复、无超卖 |
| 1.14 | [ ] | 熔断/限流/降级策略已配置 (Sentinel) | Backend Lead | ws-gateway + core-business |
| 1.15 | [x] | 分布式事务补偿 (Saga) 流程验证 | Backend Lead | D-138 Saga补偿集成测试已验证, order↔payment↔business |
| 1.16 | [x] | 错误码体系统一 (GlobalExceptionHandler 全量对齐) | Backend Lead | D-154 后前后端一致 |
| 1.17 | [ ] | 日志规范统一 (traceId 贯穿，JSON 结构化) | Backend Lead | OTel (D-118) |
| 1.18 | [ ] | 前端构建产物已部署 CDN (portal-web/admin-web) | Frontend Lead | React/TS 产物 |
| 1.19 | [ ] | Go gateway 路由表与后端服务映射正确 | Backend Lead | D-114 GitOps 同步 |
| 1.20 | [ ] | Python AI 服务模型文件已加载 (ai-platform) | AI Lead | 推理模型 + 向量索引 |
| 1.21 | [x] | Actuator 端点鉴权 (D-154) | Security Lead | /health 放行, 其余 401 |
| 1.22 | [x] | Bean Validation (D-154) | Backend Lead | GlobalExceptionHandler 拦截, 400 + field 明细 |
| 1.23 | [x] | Rate Limiting (D-154) | Security Lead | 登录/秒杀接口超阈值 429 |
| 1.24 | [x] | NetworkPolicy (D-133c) | Security Lead | 服务间最小权限隔离 |
| 1.25 | [x] | Snowflake 主键 (D-132a) | Backend Lead | 替代自增, 防分片热点 |
| 1.26 | [x] | Payment JPA 持久化 (D-132b) | Backend Lead | 支付单据落库可追溯 |
| 1.27 | [x] | OTel Go/Python traceparent (D-133a/D-152) | Backend Lead | ws-gateway/ai-platform traceparent 透传 |

## 2. 配置就绪 (Configuration Readiness) — 20 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 2.1 | [ ] | 生产 Profile (prod) 已激活，非 dev/test | DevOps Lead | Spring `--spring.profiles.active=prod` |
| 2.2 | [ ] | 所有密钥已外部化至 K8s Secret/Vault (D-117) | DevOps Lead | 无硬编码密钥 |
| 2.3 | [ ] | JWT 签名密钥已配置且与各服务一致 | Security Lead | D-117 JWT 外置 |
| 2.4 | [ ] | TiDB 连接串、账号、密码已注入 | DBA | jdbc url + Secret |
| 2.5 | [ ] | Redis 集群地址 (6 pod) 已配置 | DevOps Lead | 含哨兵/Cluster 模式 |
| 2.6 | [ ] | Pulsar 集群地址 (3+3+3) 及 Topic 已配置 | DevOps Lead | broker + namespace |
| 2.7 | [ ] | OTel Collector 地址已配置 (D-118) | DevOps Lead | exporter endpoint |
| 2.8 | [ ] | ArgoCD Application 已同步 (D-114) | DevOps Lead | hz/sz/bj/sg 四 cell |
| 2.9 | [ ] | K8s ConfigMap 各环境隔离 (无串配置) | DevOps Lead | namespace 隔离 |
| 2.10 | [ ] | 服务端口配置正确 (8081/8083-8087/8090) | DevOps Lead | 见服务清单 |
| 2.11 | [x] | 数据库连接池参数调优 (HikariCP) | Backend Lead | D-132c 统一 maxPoolSize=50 |
| 2.12 | [ ] | 线程池参数调优 (Java/Go/Python) | Backend Lead | AI worker≥32，避免 1M 级线程耗尽 |
| 2.13 | [ ] | 超时配置统一 (网关 30s, DB 5s, Redis 1s) | Backend Lead | 防雪崩 |
| 2.14 | [ ] | 重试策略配置 (指数退避，最多 3 次) | Backend Lead | Pulsar 消费者 |
| 2.15 | [x] | CORS 白名单配置 (仅生产域名) | Security Lead | D-154 加固, 无通配符 |
| 2.16 | [~] | 日志级别设为 INFO (非 DEBUG) | DevOps Lead | 代码就绪, 运行时待验证 |
| 2.17 | [ ] | JVM 参数调优 (堆内存/GC) | Backend Lead | G1GC, -Xmx2g |
| 2.18 | [ ] | Go gateway GOMAXPROCS 与 CPU 限额对齐 | Backend Lead | GOMEMLIMIT |
| 2.19 | [ ] | Python AI 服务 worker 数 (gunicorn) ≥4 | AI Lead | 防 1M 级线程耗尽 |
| 2.20 | [ ] | 前端环境变量 (API base url) 指向生产网关 | Frontend Lead | VITE_API_BASE |

## 3. 基础设施就绪 (Infrastructure Readiness) — 20 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 3.1 | [ ] | TiKV 节点数 ≥3 (当前单节点为瓶颈，587 RPS) | DBA | **必须扩容** |
| 3.2 | [ ] | TiDB v8.5.5 集群健康 (PD/TiDB/TiKV 全绿) | DBA | `tiup cluster display` |
| 3.3 | [ ] | Redis 6-pod 集群健康 (主从同步正常) | DevOps Lead | `redis-cli cluster info` |
| 3.4 | [ ] | Pulsar 3+3+3 (broker+bookie+zk) 集群健康 | DevOps Lead | broker 全在线 |
| 3.5 | [ ] | K8s 共享集群资源配额充足 (CPU/Mem) | DevOps Lead | namespace quota |
| 3.6 | [ ] | Karmada 多活调度 (D-041) hz/sz/bj/sg 四 cell 就绪 | DevOps Lead | 跨 cell 健康检查 |
| 3.7 | [ ] | 各服务 HPA/VPA 已配置 | DevOps Lead | CPU>70% 扩容 |
| 3.8 | [ ] | Pod 反亲和性 (Anti-Affinity) 配置 | DevOps Lead | 跨节点分散 |
| 3.9 | [ ] | PV/PVC 持久化卷已绑定 | DevOps Lead | 日志/数据卷 |
| 3.10 | [ ] | Ingress/TLS 证书有效 (≥90 天) | DevOps Lead | cert-manager |
| 3.11 | [ ] | DNS 解析正常 (内部 service + 外部域名) | DevOps Lead | CoreDNS |
| 3.12 | [ ] | 网络策略 (NetworkPolicy) 已应用 | Security Lead | 服务间最小权限 |
| 3.13 | [ ] | 节点池充足 (含缓冲节点 ≥2) | DevOps Lead | 故障转移预留 |
| 3.14 | [ ] | 镜像仓库 (Harbor) 可达且镜像已缓存 | DevOps Lead | 拉取时长 <30s |
| 3.15 | [ ] | 备份存储 (S3/NAS) 已配置 | DBA | TiDB BR 备份 |
| 3.16 | [ ] | 时钟同步 (NTP) 偏差 <50ms | DevOps Lead | 分布式事务依赖 |
| 3.17 | [ ] | 内部负载均衡 (SLB) 健康检查通过 | DevOps Lead | ws-gateway 入口 |
| 3.18 | [ ] | CDN 回源配置正确 (前端静态资源) | Frontend Lead | portal/admin |
| 3.19 | [ ] | OTel Collector + Tempo/Loki 后端就绪 (D-118) | DevOps Lead | 链路/日志/指标 |
| 3.20 | [ ] | Pulsar Topic 全量创建 (订单/支付/课程事件) | DevOps Lead | 含 DLQ/Retry Topic |

## 4. 安全就绪 (Security Readiness) — 15 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 4.1 | [ ] | Aikido/42Crunch 安全扫描 0 高危 (D-077 8/8 已闭) | Security Lead | SAST+SCA+Secret |
| 4.2 | [ ] | JWT 签名密钥已轮换 (生产新密钥) (D-117) | Security Lead | 上线前最后一次 |
| 4.3 | [x] | Actuator 端点已保护 (仅 /health 对外) | Security Lead | D-154 鉴权生效, 其余端点 401 |
| 4.4 | [x] | CORS 仅允许生产域名 | Security Lead | D-154 加固, 无通配符 `*` |
| 4.5 | [x] | API 鉴权 (ws-gateway) 全量启用 | Security Lead | D-154 fail-closed, 白名单 ≤3 接口 |
| 4.6 | [ ] | SQL 注入防护 (预编译/ORM) 全量覆盖 | Security Lead | 无字符串拼接 SQL |
| 4.7 | [ ] | XSS/CSRF 防护已启用 (前端) | Security Lead | React + SameSite Cookie |
| 4.8 | [ ] | 敏感数据脱敏 (手机号/身份证/密码) | Security Lead | 日志 + 响应 |
| 4.9 | [ ] | HTTPS 全链路强制 (TLS 1.2+) | Security Lead | 无 HTTP 明文 |
| 4.10 | [ ] | 密钥/证书无泄露 (git history 已扫描) | Security Lead | truffleHog/gitleaks |
| 4.11 | [ ] | RBAC 权限模型已验证 (admin/teacher/student) | Security Lead | 越权测试通过 |
| 4.12 | [x] | 速率限制 (Rate Limit) 已配置 | Security Lead | D-154 登录/秒杀/验证码超阈值 429 |
| 4.13 | [ ] | 审计日志 (Audit Log) 全量记录 | Security Lead | 关键操作留痕 |
| 4.14 | [ ] | 第三方依赖无已知 CVE 高危 | Security Lead | Snyk/OWASP Dep |
| 4.15 | [ ] | 渗透测试 (Pentest) 高危项已修复 | Security Lead | 含 BOLA/BFLA 测试 |

## 5. 监控告警就绪 (Monitoring & Alerting) — 15 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 5.1 | [ ] | Grafana 仪表盘已创建 (13 服务 + 基础设施) | DevOps Lead | 含压测基线对比 |
| 5.2 | [ ] | Prometheus 采集目标全部 UP | DevOps Lead | `prometheus targets` |
| 5.3 | [ ] | SLO 已定义 (可用性 99.9%, P95 <1s) | DevOps Lead | 4 黄金信号 |
| 5.4 | [ ] | 错误率告警 (>1% 持续 5min) | DevOps Lead | Java portal-bff/core-business 重点 |
| 5.5 | [ ] | 延迟告警 (P95 >2s 持续 5min) | DevOps Lead | ws-gateway P95 基线 2.33s |
| 5.6 | [ ] | TiKV 节点数告警 (<3 立即告警) | DBA | **核心风险项** |
| 5.7 | [ ] | Redis 内存/驱逐告警 (>80% / evict>0) | DevOps Lead | 6-pod 集群 |
| 5.8 | [ ] | Pulsar 积压告警 (backlog >10K) | DevOps Lead | 订单/支付 Topic |
| 5.9 | [ ] | Pod 重启告警 (单 pod 1h 内 >3 次) | DevOps Lead | Java 服务 crash 历史 |
| 5.10 | [ ] | 磁盘使用告警 (>85%) | DevOps Lead | 日志/数据卷 |
| 5.11 | [ ] | 证书过期告警 (<30 天) | DevOps Lead | TLS 证书 |
| 5.12 | [ ] | 告警通知渠道 (钉钉/电话/邮件) 已验证 | DevOps Lead | 含升级机制 |
| 5.13 | [ ] | 值班排班表 (On-Call Schedule) 已发布 | DevOps Lead | 7×24 覆盖 |
| 5.14 | [ ] | OTel 链路追踪 (D-118) 全链路打通 | DevOps Lead | 13 服务 traceId 贯穿 |
| 5.15 | [ ] | 压测基线指标已录入 (1K/10K/100K/1M) | QA Lead | 用于回归对比 |

## 6. 数据就绪 (Data Readiness) — 10 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 6.1 | [ ] | 数据库 Schema 迁移已执行 (Flyway) | DBA | 含向前回滚脚本 |
| 6.2 | [ ] | 种子数据 (Seed Data) 已加载 | DBA | 字典/配置/管理员 |
| 6.3 | [ ] | 课程/题库基础数据已导入 | DBA | 含 AI 向量索引 |
| 6.4 | [ ] | Redis 缓存预热脚本已执行 | DevOps Lead | 评分查询缓存 (0% error 验证) |
| 6.5 | [ ] | 缓存与 DB 数据一致性校验通过 | DBA | 抽样比对 |
| 6.6 | [ ] | 数据脱敏规则已应用到生产数据 | DBA | 真实用户数据隔离 |
| 6.7 | [ ] | 数据备份 (全量+增量) 已执行 | DBA | BR 备份 + 验证可恢复 |
| 6.8 | [ ] | 历史数据归档完成 (冷热分离) | DBA | 超 6 个月数据归档 |
| 6.9 | [ ] | AI 模型 + 向量库 (ai-platform/knowledge-platform) 已加载 | AI Lead | 检索/Agent 就绪 |
| 6.10 | [ ] | 数据一致性巡检脚本就绪 | DBA | 上线后定时执行 |

## 7. 回滚就绪 (Rollback Readiness) — 10 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 7.1 | [ ] | 回滚计划文档已评审 (见 rollback-plan.md) | Release Manager | 含决策树 |
| 7.2 | [ ] | ArgoCD 回滚演练已执行 (D-114) | DevOps Lead | history 回滚 <5min |
| 7.3 | [ ] | 数据库快照备份已执行 (上线前) | DBA | TiDB BR snapshot |
| 7.4 | [ ] | 数据库迁移回滚脚本已验证 | DBA | 向后兼容 |
| 7.5 | [ ] | Redis 缓存清理脚本就绪 | DevOps Lead | FLUSHDB per DB |
| 7.6 | [ ] | Pulsar DLQ/重放流程已验证 | DevOps Lead | 消息不丢 |
| 7.7 | [ ] | ConfigMap/Secret 回滚版本已留存 | DevOps Lead | git history |
| 7.8 | [ ] | 前端回滚 (CDN 版本切换) 已验证 | Frontend Lead | 上一版本可秒切 |
| 7.9 | [ ] | GitOps 历史 (D-114) 清洁，可一键 revert | DevOps Lead | 无脏 commit |
| 7.10 | [ ] | 回滚验证 (健康检查/冒烟测试) 脚本就绪 | QA Lead | 自动化 |

## 8. 压测验证就绪 (Load Test Verification) — 10 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 8.1 | [ ] | 1K VU 压测报告已评审 (基线) | QA Lead | deploy/load-tests/results/ |
| 8.2 | [ ] | 10K VU 压测报告已评审 | QA Lead | 容量拐点 |
| 8.3 | [ ] | 100K VU 压测报告已评审 | QA Lead | Java 服务开始 crash |
| 8.4 | [ ] | 1M VU 压测报告已评审 (极限) | QA Lead | TiKV 587 RPS 瓶颈确认 |
| 8.5 | [ ] | TiKV 单节点瓶颈 (587 RPS) 已记录并制定扩容计划 | DBA | **上线前必须扩至 ≥3 节点** |
| 8.6 | [ ] | Go gateway 韧性 (1M 时 52% 可用, P95=2.33s) 已确认 | QA Lead | 最优 |
| 8.7 | [ ] | Python AI (1M 时 93% error) 线程耗尽已制定缓解方案 | AI Lead | 扩 worker + 限流 |
| 8.8 | [ ] | Redis 缓存路径 (评分查询) 0% error 全量级别已确认 | QA Lead | 缓存隔离有效 |
| 8.9 | [ ] | 秒杀幂等 (50K VU, 无重复/无超卖) 已验证 | QA Lead | order-platform |
| 8.10 | [ ] | TiKV 重启数稳定 8 (共享集群未受影响) 已确认 | DBA | 隔离有效 |

## 9. 文档就绪 (Documentation Readiness) — 8 项

| # | 状态 | 检查项 | 负责人 | 备注 |
|---|------|--------|--------|------|
| 9.1 | [ ] | 生产运维手册 (runbook) 已发布 (见 production-runbook.md) | DevOps Lead | 含 10 章节 |
| 9.2 | [ ] | 回滚计划已发布 (见 rollback-plan.md) | Release Manager | 含决策树 |
| 9.3 | [ ] | 上线就绪报告已发布 (见 production-readiness-report.md) | QA Lead | 含风险评估 |
| 9.4 | [ ] | API 文档 (OpenAPI 3.0) 已发布至内部 Portal | Backend Lead | 13 服务全量 |
| 9.5 | [ ] | 架构图 (C4 Model) 已更新 | Tech Lead | 含 K8s/多活拓扑 |
| 9.6 | [ ] | 服务清单 (13 服务端口/依赖) 已更新 | DevOps Lead | 见文档头 |
| 9.7 | [ ] | 值班交接模板 + 升级矩阵已发布 | DevOps Lead | 见 runbook 第 10 章 |
| 9.8 | [ ] | 压测报告索引 (4 份) 已归档 | QA Lead | deploy/load-tests/results/ |

---

## 签字确认 (Sign-off)

| 角色 | 姓名 | 签字 | 日期 |
|------|------|------|------|
| Tech Lead | | | |
| Backend Lead | | | |
| Frontend Lead | | | |
| AI Lead | | | |
| QA Lead | | | |
| DevOps Lead | | | |
| Security Lead | | | |
| DBA | | | |
| Release Manager | | | |
| 产品负责人 (Product Owner) | | | |

> **上线决策**: 上述 128 项全部 `[x]` 且签字完成后，由 Release Manager 下发上线指令。
