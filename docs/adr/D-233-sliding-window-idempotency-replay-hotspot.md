# D-233: 风控滑动窗口 + 幂等响应重放 + 缓存热点探测（v7 大厂标准对齐重构）

> **日期**: 2026-10-26
> **类型**: 安全/性能重构（Security & Performance Refactor）
> **触发**: v7 全项目群复审（联网对标 2025-2026 大厂风控/缓存最佳实践）
> **状态**: ✅ 已实施并测试通过（ep-web-common 74/74 + portal-bff 186/186 + ep-cache 95/95 + 全仓编译 0 错误）

---

## 1. 背景与动机

v7 复审对项目群做了逐项目打分（见 `docs/architecture/project-group-scorecard-v7.md`），并联网对标 2025-2026 行业最佳实践（美团/腾讯 5 层防御标准、Redis 官方限流模式、Stripe/IETF draft-ietf-httpapi-idempotency、多级缓存命中率闭环实践）。识别出风控与缓存两大体系的 4 个可代码级修复的 gap：

| # | Gap | 大厂标准 | 风险 |
|---|-----|---------|------|
| G1 | 限流为固定窗口（INCR+EXPIRE） | 滑动窗口/令牌桶（美团/腾讯 2025 标准；固定窗口存在 59s+1s 边界 2 倍流量突刺） | 攻击者可在窗口边界打出 2× 预算流量 |
| G2 | X-Forwarded-For 无条件信任第一段 | 可信代理链解析（只信任配置的可信代理数；默认 0 = 忽略 XFF） | 伪造 XFF 可无限轮换"IP 身份"绕过限流 |
| G3 | 幂等拦截器重复请求只返回 409，不缓存响应 | Stripe/IETF 标准：重放缓存响应（IETF draft-ietf-httpapi-idempotency §4.1）；PENDING 短 TTL 防崩溃僵尸 | 客户端重试永远拿不到第一次结果；进程崩溃后重试被锁 24h |
| G4 | 缓存命中率有观测无闭环 | 探测→观测→调参闭环（热点 key 探测 + 综合命中率 SLO ≥99% 告警） | 命中率不可提升：不知道哪个 key 在反复回源、为何回源 |

## 2. 方案对比（Iron Law 8）

### 2.1 限流窗口算法

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A. 固定窗口（现状） | O(1) 内存，Redis INCR 单命令 | 边界突刺 2×；不满足大厂标准 | ❌ |
| B. 滑动窗口（ZSET） | 任意 trailing 窗口精确计数；Redis 官方推荐模式；实现清晰 | O(W·QPS) 内存（每个请求一个 member） | ✅ **采用**（限流维度 QPS 低，member 量可控；ZSET 过期自清理） |
| C. 令牌桶（Lua） | 支持突发、内存 O(1) | 语义变化（突发放行 vs 拒绝），测试与阈值全量重调 | ⏳ 备选（P2：若 ZSET 内存成为瓶颈再迁移） |

### 2.2 幂等响应重放

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A. 保持拦截器 + 409（现状） | 实现简单 | 重试拿不到结果；拦截器无法包装 response（无法捕获 body） | ❌ |
| B. Filter + ContentCachingResponseWrapper 重放 | Stripe 标准；客户端重试获得幂等语义 | 从拦截器迁移到 Filter（注册方式变更） | ✅ **采用** |
| C. 业务层手工缓存 | 精确控制 | 每个写接口重复实现，违反 DRY | ❌ |

### 2.3 热点 key 处理

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A. 探测 + 观测（Micrometer + 日志） | 证据驱动调参；零运行时风险 | 需要人工调参 | ✅ **采用**（2025 行业实践：热点探测供容量规划） |
| B. 自动 pin 到 L1 | 自动化 | 有界 L1 中 pin 会饿死冷 key，再制造淘汰风暴 | ❌ |
| C. 本地热点缓存旁路（独立小 L1） | 与主 L1 隔离 | 双缓存一致性复杂化 | ⏳ 备选（P2） |

## 3. 实施内容

### 3.1 风控（ep-web-common）

| 变更 | 文件 | 内容 |
|------|------|------|
| 滑动窗口内存实现 | `RiskControlFilter.java` | `MemoryWindowCounter`（固定）→ `MemorySlidingWindowCounter`（双桶滑动窗口，O(1)/key）；旧实现保留为 `@Deprecated` 兼容 |
| 滑动窗口 Redis 实现 | `RedisSlidingWindowCounter.java`（新增） | ZSET ZADD + ZREMRANGEBYSCORE + EXPIRE + ZCARD；**半开自愈**：degrade 后 30s 冷却期重探 Redis，成功即恢复集群级限流（旧实现 sticky 到重启）；旧 `RedisWindowCounter` 删除 |
| XFF 可信代理链 | `RiskControlFilter.clientIp()` | `ep.risk.trusted-proxies`（默认 0 = 忽略 XFF 防伪造）；>0 时从链右端取第 N 个为客户端 IP，链长不足回退 socket |
| 自动配置 | `RiskControlAutoConfiguration.java` | 注入 `trusted-proxies`；Redis 分支指向新计数器 |
| 测试 | `SlidingWindowCounterTest.java`（新增 6 例） | trailing 窗口计数、边界突刺消除、ZSET 调用契约、半开自愈、XFF 防伪造、可信链解析 |

### 3.2 幂等（portal-bff）

| 变更 | 文件 | 内容 |
|------|------|------|
| Filter 化重写 | `IdempotencyFilter.java`（新增，替代 `IdempotentInterceptor`） | ContentCachingResponseWrapper 捕获响应；2xx → Lua CAS `PENDING→DONE+缓存响应`（TTL 24h）；4xx/5xx/异常 → 释放 key；重放：DONE 直接返回原 status+body，PENDING 返回 409+Retry-After |
| PENDING 处理超时 | 同上 | SETNX TTL 从 24h 降为 **5 分钟**（进程崩溃后 key 快速自愈，客户端可重试） |
| Lua CAS | 同上 | `if GET == PENDING then SET DONE` 原子转换，杜绝并发覆盖已缓存响应 |
| 注册方式 | `GatewayInterceptorConfig.java` | WebMVC Interceptor → Servlet Filter（order = HIGHEST+20，路径 `/api/*`） |
| 测试 | `IdempotencyFilterTest.java`（重写 10 例） | 首请求放行、DONE 重放原文、PENDING 409+Retry-After、GET 跳过、无 key 跳过、异常释放、非 2xx 释放、PENDING 短 TTL、DELETE 检查、损坏记录兜底 409 |

### 3.3 缓存（ep-cache）

| 变更 | 文件 | 内容 |
|------|------|------|
| 热点探测 | `HotspotDetector.java`（新增） | L2 miss → loader fall-through 按 `(cacheName,key)` 双桶滑动 60s 窗口计数；≥5 次/窗 → 晋升热点（每窗口恰好一次）；`promotedTotal()` / `hotKeys()` 诊断接口；上限 50k key 防内存 |
| 接入点 | `CacheService.java` | L2 miss 回源路径调用 `recordFallThrough`；新增 `getHotspotDetector()` |
| 综合命中率 SLO | `CacheStats.compositeHitRate()` | `(L1hits+L2hits)/(L1hits+L1misses)`；SLO 目标 ≥99%（两 tier 缓存行业基线） |
| 指标导出 | `CacheMetrics.java` | 新增 `ep_cache_hotspot_promoted_total`（FunctionCounter）+ `ep_cache_composite_hit_rate`（Gauge） |
| SLO 告警 | `CacheStatsLogger.java` | 周期日志中综合命中率 <99%（样本 ≥1000）时 WARN 并输出调参建议 |
| 测试 | `HotspotDetectorTest.java`（新增 7 例） | 阈值下不晋升、恰好晋升一次、窗口滑动后再武装、key 独立、快照、null 安全、综合命中率算术 |

## 4. 效能预期（数字化）

| 指标 | 重构前 | 重构后（预期） | 依据 |
|------|--------|--------------|------|
| 窗口边界最大放行 | 2× limit（突刺） | **1.0× limit**（精确） | 滑动窗口定义 |
| XFF 伪造绕过 | 无限轮换身份 | **不可行**（默认 0 信任） | 可信代理链 |
| 崩溃后重试等待 | ≤24h（PENDING TTL） | **≤5min** | PENDING 短 TTL |
| 重试响应语义 | 409（无结果） | **原响应重放**（幂等） | Stripe/IETF |
| Redis 故障恢复 | 需重启（sticky） | **≤30s 自动**（半开探测） | 自愈设计 |
| 命中率提升闭环 | 无探测 | 探测→指标→SLO 告警→调参 | HotspotDetector |

## 5. 影响与兼容

- **API 兼容**：`RiskControlFilter.WindowCounter` 接口签名不变；`MemoryWindowCounter` 保留 `@Deprecated`；构造函数新增重载（旧签名保留，trustedProxies 默认 0）。
- **破坏性变更**：`IdempotentInterceptor` 删除（唯一消费者 `GatewayInterceptorConfig` 同步迁移）；重复请求行为从"恒 409"变为"重放/409+Retry-After"——**更符合幂等键的初衷**，客户端无感改善。
- **配置新增**：`ep.risk.trusted-proxies`（默认 0，部署在有可信代理前面时按代理数配置）。
- **Redis 键演进**：限流键从 String（INCR）变为 ZSET——旧键自然 TTL 过期，无迁移需求。
- **验证**：ep-web-common 74/74、portal-bff 186/186、ep-cache 95/95、全仓 `mvn compile` EXIT=0。

## 6. 关联

- 评分依据：`docs/architecture/project-group-scorecard-v7.md`（v7 逐项目群评分矩阵）
- 上游分析：`docs/architecture/risk-control-and-architecture-deep-analysis.md` §1.1（幂等）/ §1.2（防滥用）
- 行业对标：美团/腾讯 2025 分层防御（网关三维限流）、Redis 官方 rate limiter pattern、IETF draft-ietf-httpapi-idempotency、Stripe Idempotency API
