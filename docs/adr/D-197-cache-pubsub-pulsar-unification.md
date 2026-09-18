# D-197: 缓存 Pub/Sub 统一为 Pulsar + 跨语言载荷对齐

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-197 | v1.0 | 2026-08-12 | Done | 平台架构组 | 架构委员会 |

> 关联：D-066（Pulsar 事件总线）、D-182~D-184（Java/Go/Python 三语言 ep-cache 统一接入）、D-195（Pulsar 4.0 升级就绪）、铁律 4（Pulsar Avro）、铁律 8（决策对比分析 ≥3 方案）、铁律 9（真实实现无桩）、铁律 10（设计-实现可追溯性）。

## 1. 问题陈述与约束条件

D-182~D-184 完成了三语言多级缓存（L1+L2）的统一接入，但跨 pod 的缓存失效广播（Pub/Sub）后端在三语言间不一致：

- **Java ep-cache**：默认使用 Redis Pub/Sub 广播，Pulsar 仅作为可选后端。
- **Go ep-common-go**：仅支持 Redis Pub/Sub 广播，无 Pulsar 支持。
- **Python ep-common-python**：仅支持 Redis Pub/Sub 广播，无 Pulsar 支持。
- **跨语言载荷字段名不一致**：Java 使用 `cacheName`/`key`，Python 使用 snake_case `cache_name`/`key`，Go 使用 `cacheName`/`key`。跨语言广播消息无法正确反序列化。

用户指令：统一多级缓存 Pub/Sub 广播后端到 Pulsar MQ（铁律 4），消除 Redis-only 依赖与字段名不一致。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Pulsar 默认 + Redis 可选 fallback（已选）** | `ep.cache.broadcast=pulsar`（`matchIfMissing=true`）默认 Pulsar；`ep.cache.broadcast=redis` 显式 opt-in Redis fallback |
| B | **Redis 默认 + Pulsar 可选** | 维持现状，Redis 作为默认广播后端，Pulsar 作为可选增强 |
| C | **Kafka 统一** | 全量迁移到 Kafka 作为缓存广播 MQ，替代 Redis 与 Pulsar |

## 3. 对比维度表

| 维度 | A. Pulsar 默认 + Redis fallback（已选） | B. Redis 默认 + Pulsar 可选 | C. Kafka 统一 |
|------|--------------------------------------|---------------------------|---------------|
| **持久化消息** | Pulsar 持久化 + replay 能力 | Redis 无持久化（fire-and-forget） | Kafka 持久化 + replay |
| **广播语义** | 每实例唯一 subscription → 每 pod 接收全量消息 | Redis Pub/Sub channel 天然广播 | Kafka consumer group 需特殊配置才能广播 |
| **统一中间件** | 与事件总线（D-066）统一为 Pulsar | 引入 Redis + Pulsar 双 MQ | 引入 Kafka + Pulsar 双 MQ |
| **跨语言一致性** | Java/Go/Python 三语言统一 Pulsar 客户端 | 三语言仍依赖 Redis 客户端（已存在但非统一 MQ） | 三语言需新增 Kafka 客户端 |
| **降级能力** | Pulsar 不可用时 no-op 降级（EP_DEV_ONLY），Redis 可作 fallback | Redis 不可用时无降级 | Kafka 不可用时无降级 |
| **运维栈** | 单一 Pulsar 集群 | Redis + Pulsar 双栈 | Kafka + Pulsar 双栈 |
| **铁律 4 合规** | ✅ Pulsar Avro | ❌ Redis 非统一 MQ | ❌ 引入第二 MQ 违反单一中间件 |

## 4. 决策

**选 A：Pulsar 默认 + Redis 可选 fallback，跨语言载荷统一为 JSON `{"cacheName":"...","key":"..."}`。**

理由：

1. **用户指令统一 Pulsar MQ**（铁律 4）：平台事件总线已统一为 Pulsar（D-066），缓存广播应同栈统一，消除 Redis-only 依赖。
2. **Pulsar 持久化与 replay**：Pulsar 提供持久化消息与更好的 replay 能力，Redis Pub/Sub 是 fire-and-forget，无消息可靠性保证。
3. **广播语义清晰**：Pulsar 每实例唯一 subscription name 实现广播语义（每 pod 接收全量消息），比 Redis Pub/Sub channel 更可控。
4. **EP_DEV_ONLY 降级**：Pulsar 不可用时 no-op 降级（开发环境无 Pulsar 集群时不阻塞），Redis Pub/Sub 可作为显式 fallback。
5. **跨语言载荷对齐**：三语言统一 `cacheName`/`key` 字段名（camelCase），消除 Python 旧 snake_case 不一致。

## 5. 实现细节

### 5.1 配置

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `ep.cache.broadcast=pulsar` | **默认**（`matchIfMissing=true`） | Pulsar 持久化消息，更好 replay，统一中间件 |
| `ep.cache.broadcast=redis` | 可选 fallback | Redis Pub/Sub，显式 opt-in |
| Pulsar topic | `ep-cache-invalidate` | 统一失效广播 topic |
| Pulsar subscription | 每实例唯一 subscription name | 广播语义：每 pod 接收全量消息 |
| 消息载荷（三语言统一） | JSON `{"cacheName":"...","key":"..."}` | Java `InvalidateMsg` / Go `cache.InvalidateMsg` / Python `cache.InvalidateMsg` 字段名一致 |
| EP_DEV_ONLY 降级 | Pulsar 不可用 → no-op | Redis Pub/Sub 可作为 fallback |

### 5.2 三语言实现

- **Java ep-cache**：`CacheBroadcastConfig`（`@ConditionalOnProperty` 选择 Pulsar 默认 / Redis 可选）+ `PulsarCacheBroadcast` + `RedisCacheBroadcast` + consumer 订阅
- **Go ep-common-go**：`cache` 模块 Pulsar 支持新增（producer + consumer），字段名对齐 `cacheName`/`key`
- **Python ep-common-python**：`cache` 模块 Pulsar 支持新增 + 字段名修复（`cacheName`/`key`，修复旧 snake_case 不一致）

## 6. Iron Law 合规

| Iron Law | 合规 | 说明 |
|----------|------|------|
| IL4（Pulsar Avro） | ✅ | Pulsar 作为统一 MQ |
| IL8（≥3 alternatives） | ✅ | Redis Pub/Sub / Pulsar / Kafka 三方案评估，选 Pulsar |
| IL9（run-verified） | ✅ | 三语言 cache 模块测试全绿（Java/Go/Python） |
| IL10（doc synced） | ✅ | SDD §3.2.3/§3.2.4 + SAD §3.4 ADR-T04 已标注 `[IMPLEMENTED D-197]` |

## 7. 文档同步

- SDD §3.2.3 CacheBroadcast：标注 `[IMPLEMENTED D-197]`，更新为 Pulsar-default + Redis-fallback
- SDD §3.2.4 InvalidateMsg：标注 `[IMPLEMENTED D-197]`，跨语言字段名对齐
- SAD §3.4 ADR-T04：标注 `[IMPLEMENTED D-197]`，新增 D-197 重构实现细节子节
