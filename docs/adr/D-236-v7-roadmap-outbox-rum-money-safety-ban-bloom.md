# D-236: v7 P1/P2 路线图落地——通用 Outbox + 前端性能/RUM + 资金安全修复 + 风控封禁 + 布隆过滤器

> **日期**: 2026-10-26
> **类型**: 可靠性/安全/性能批量落地（v7 评分卡 P1/P2 行动路线图执行轮）
> **状态**: ✅ 已实施并全量测试通过（ep-cache 102 + ep-web-common 88 + portal-bff 193 + order 63 + core-business 105 + grading 231 + payment 59 全绿；前端 24/24 任务全绿；全仓编译 EXIT=0）

---

## 1. 背景

v7 逐项目群评分报告（`project-group-scorecard-v7.md` §5）列出 P1/P2 行动路线图。本轮按 ROI 顺序落地全部六项（含 D-234/D-235/D-236 三个子决策号，统一记 D-236 留痕）。

## 2. P1① 通用 Outbox SPI（D-234）

**Gap**：Outbox 仅 order-platform 独有；core-business / grading-service 事件直发 Pulsar（best-effort），存在双写窗口事件丢失风险。payment 已有磁盘 spool 降级链（事件永不静默丢失），**诚实评估后不重复接入**。

**方案对比**：

| 方案 | 结论 |
|------|------|
| A. 通用 Outbox SPI 抽取至 ep-web-common，JPA EntityScan 共享 | ❌ 侵入宿主 @EntityScan |
| B. **JdbcTemplate 轻量实现 + 自带守护线程 relay（采用）** | ✅ 零 EntityScan 耦合、零 @EnableScheduling 侵入、宿主仅提供 `OutboxPublisherPort` adapter |
| C. 各服务复制 order 的实现 | ❌ DRY 违规 |

**实施**：
- `ep-web-common/outbox/`：`OutboxWriter`（REQUIRES_NEW 落库 + 环境事务跟随）、`OutboxRelay`（独立 daemon 线程池，bounded retry 5 次）、`OutboxPublisherPort`（SPI）、`OutboxAutoConfiguration`（`ep.outbox.enabled=true` 显式开启 + portable DDL `CREATE TABLE IF NOT EXISTS ep_outbox`）
- core-business / grading：`publish()` 桥接为 persist-first（outbox 写失败降级直发 + 响亮日志），新增 `publishRaw()` 供 relay 回调，`OutboxBridgeConfig`（ArchUnit 守卫强制归位 config 包——被规则精准拦截后修正）
- order 的参考实现保持不动（无破坏）

**效能**：事件丢失窗口从「Pulsar 直发失败即丢」收敛为「outbox 落库失败才丢（DB 级可靠性 + 显式异常）」，发布失败自动重试 5 次。

## 3. P1② 前端路由级 code-splitting + RUM（D-235）

**Gap**：3 个 Vite web 应用全部静态 import 页面（20+ 页面 admin SPA 单 bundle）；零真实用户性能数据。

**实施**：
- **路由懒加载**：portal-web（HomePage eager 保 FCP，其余 6 页 lazy）、admin-web（Login/Dashboard eager，其余 20+ 页 lazy，具名导出经 `lazyNamed` 包装同 barrel 单 chunk）、knowledge-admin（落地页 eager，6 页 lazy）；统一轻量 `PageFallback` 骨架
- **RUM 闭环**：`@ep/shared/rum`（web-vitals 4.x + `sendBeacon` fire-and-forget + 10% 采样 + 零 PII）→ portal-bff `RumController`（`POST /api/v1/rum`）→ Micrometer `ep_rum_web_vital` 分布直方图（**route 不作为 tag——基数安全**，大厂 Prometheus 标准）
- 三个 app main.tsx 接入 `initRUM`

**效能**：首屏后路由按需加载（admin 20+ 页拆独立 chunk）；LCP/INP/CLS/TTFB/FCP 真实用户 p95 首次可视。

## 4. P1③ 资金域安全修复与测试加密（D-236）

**发现真实资金安全漏洞**：`PaymentServiceImpl.refund()` 逐次校验退款额但无累计校验——多次部分退款可**联合超过支付总额**（100 元支付可被 60+60 超额退 120），违反 SDD 规格「成功退款总和等于支付金额才转 REFUNDED」的字面语义。

**修复**：
- `RefundJpaRepository.sumActiveRefundAmountByOrderId()`（JPQL SUM，FAILED 不占额度）
- `refund()` 累计守卫：`alreadyRefunded + amount > paymentAmount` → 抛 `Refund overdraw rejected`；全退判定改用累计口径（60/40 分两次退完后正确转 REFUNDED）

**测试加密**：`PaymentRefundMoneySafetyTest` 11 例（双路径 in-memory + JPA mock）：跨次超额拒绝、累计满额翻转、部分退保持 SUCCESS、单次超额参数化拒绝、FAILED 不占额度、JPA DB 侧 SUM 守卫、幽灵订单拒绝、非法金额、缓存失效。**PITest 扩展**：payment pom 增加 mutation 配置（targetClasses=service/entity，CI nightly 生效；本机环境 pitest minion 异常为环境级限制，core-business 对照冒烟同样异常，配置保留）。

## 5. P2④ GraalVM 元数据归位（铁律 9 诚实化）

v7 复审核实：knowledge/order/payment 三处父级顶层 `src/` 的 native-image 元数据**零构建引用**（不在任何模块打包路径，从未被消费）。按「配置存在 ≠ 能力生效」教训归位：迁至 `deploy/graalvm-metadata/`（参考制品）+ README 说明「未来启用 AOT 的正确位置是各模块自身 resources」，services/ 目录树消除顶层 `src/` 歧义（v7 评分卡 D5 残留项闭环）。

## 6. P2⑤ IP 自动封禁（大厂风控第④层补强）

`IpBanService`（ep-web-common/risk）：429 违规 3 次 → 自动封禁 30 分钟（Redis SET + TTL，内存降级镜像）→ 后续请求 403 快速拒绝（封禁 IP 的请求成本从全过滤链降为 1 次 Redis GET）。集成点：`RiskControlFilter` 前置封禁检查 + IP 维度超限时记违规（User 维度不触发——防 NAT 误伤）。运维逃生口：`unban()`。配置 `ep.risk.ban.enabled`（默认 true）/ `violations-to-ban`（3）/ `ttl-minutes`（30）。

## 7. P2⑥ 布隆过滤器防穿透（可插拔）

`BloomFilterGuard`（ep-cache）：Redis bitmap + FNV-1a 双重哈希（跨 JVM 确定性），最优容量/哈希数公式（100k 元素 @1% fpp → ~958k bits / 7 hashes），TTL 滑动重建，**fail-open**（基础设施故障退化为常规 loader 路径，零正确性风险）。`CacheService` 新增 bloom 重载——**opt-in per cache**（守卫必须由数据写入侧喂给，未喂的过滤器绝不隐式接线——文档写明误杀风险）。指标：`shortCircuitedTotal()` 垃圾流量拦截计数。

## 8. 验证汇总

| 模块 | 结果 |
|------|------|
| ep-web-common | 88/88（+7 Outbox，+7 IpBan） |
| ep-cache | 102/102（+7 Bloom） |
| portal-bff | 193/193（+7 Rum） |
| order-platform | 63/63（共享化无破坏） |
| core-business | 105/105（Outbox 桥接） |
| grading-service | 231/231（Outbox 桥接，ArchUnit 归位修正） |
| payment-platform | 59/59（+11 资金安全） |
| 前端（12 typecheck + 12 test 任务） | 24/24 |
| 全仓编译 | EXIT=0 |

## 9. 关联

- 上游：v7 评分报告 §5 路线图、D-233（v7 风控/缓存重构）、D-217（order Outbox 参考实现）
- 行业对标：Stripe/IETF 幂等、美团/腾讯风控第④层自动封禁、Google CWV RUM 采样指引、Prometheus 标签基数纪律
