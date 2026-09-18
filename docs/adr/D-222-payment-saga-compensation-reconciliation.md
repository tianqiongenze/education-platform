# D-222: Payment Saga 退款补偿失败持久化 + 对账闭环

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-222 | v1.0 | 2026-09-06 | Done | 平台架构组 | 架构委员会 |

> 关联：D-113（Saga 状态机）、D-197（Outbox）、风控深度分析 §3（best-effort 补偿资金风险）、铁律 8、铁律 9、铁律 10。

## 1. 问题陈述与约束条件

风控深度分析确认：`PaymentCompensableAction.compensateCreatePayment()` 是**尽力而为退款**——补偿失败仅打日志、不传播、不留持久痕迹，Saga 照常"成功"结束。若 OPM 退款 RPC 失败，**资金缺口对系统不可见**，只能靠人工翻日志发现，存在真实资金损失风险。

约束：不改变 happy path；不破坏 Saga LIFO 补偿链（`RefundPayment` 抛异常会阻断后续 `ReleaseStock`，造成库存滞留——把资金损失换成库存损失不可接受）；离线可测试（无 Pulsar/OPM 依赖）。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **持久化失败记录 + 结构化告警日志 + 管理端对账接口（已选）** | 失败时写入 `ep_payment_compensation_failure` 开放行 + `[ALERT][PAYMENT_COMPENSATION_FAILED]` 结构化日志 + `/api/v1/payment-compensations` 查询/关闭端点 |
| B | **补偿失败向上抛出（传播到 Saga 引擎）** | `compensateCreatePayment` 重抛异常，Saga 实例置 FAILED，由恢复策略重试 |
| C | **自动重试 worker（Outbox 模式推广）** | 失败记录进入重试队列，后台 worker 指数退避重试退款直到成功 |

## 3. 对比维度表

| 维度 | A. 持久化+告警+对账（已选） | B. 向上抛出 | C. 自动重试 worker |
|------|---------------------------|------------|-------------------|
| **LIFO 链完整性** | ✅ 不中断（ReleaseStock 照常执行） | ❌ 中断，库存滞留 | ✅ 不中断 |
| **资金缺口可见性** | ✅ 持久行 + 告警日志 + 管理端查询 | Saga 实例状态 FAILED（仍需人工查） | ✅ |
| **资金一致性** | 人工退款后关闭行，闭环明确 | 取决于恢复策略正确性 | ⚠️ `PaymentService.refund` 幂等性未证明，自动重试有**双倍退款**风险 |
| **实现复杂度** | 低（1 实体 + 1 仓储 + 1 服务 + 1 控制器） | 最低但引入新问题 | 高（worker + 幂等退款保障 + 死信） |
| **离线可测** | ✅ 纯单元测试（mock 仓储） | ✅ | ❌ 需调度器集成测试 |

## 4. 决策

选择 **方案 A**。方案 B 用库存滞留换资金可见性，方向错误；方案 C 在 `PaymentService.refund` 缺少退款幂等键证明的前提下自动重试会引入双倍退款风险（比原问题更严重），列为后续演进（前置条件：OPM 退款接口增加 `refundRequestId` 幂等键，见 §6）。

### 实现要点

- 新实体 `PaymentCompensationFailure`（`ep_payment_compensation_failure` 表）：orderId/tenantId/amount/reason/failedAt/resolved/resolvedNote/resolvedAt，UUID 主键（低流量表，避免 TiDB 自增热点，与 OrderModel Snowflake 思路一致）
- `PaymentCompensationReconcileService`：`recordFailure()`（开放行 + 告警日志）、`listUnresolved()`、`resolve()`（实体状态机防重复关闭）
- `PaymentCompensableAction.compensateCreatePayment()`：失败路径持久化开放行；**记录持久化自身失败时升级告警**（双失败场景）；返回 map 携带 `compensationFailed=true`/`failureId` 供 Saga 上下文下游可见
- `PaymentCompensationController`（`/api/v1/payment-compensations`）：`GET /unresolved` + `POST /{failureId}/resolve`，**双端点 admin 鉴权**（RoleChecker，与 OrderController.refund 同约定）；返回 `PaymentCompensationView` DTO（api 不依赖 model，避免 ArchUnit 包循环）
- 补偿语义变化：`REFUND_FAILED_BEST_EFFORT` → `REFUND_FAILED_REQUIRES_MANUAL_INTERVENTION`（语义诚实化）

## 5. 影响与验证证据（铁律 9）

| 模块 | 变更 | 验证命令 | 结果 |
|------|------|---------|------|
| order-platform-service | 5 新文件（实体/仓储/服务/控制器/DTO）+ action 改造 + 3 测试类 16 测试 | `mvn -o test -pl order-platform-service` | **Tests run: 60, Failures: 0 — BUILD SUCCESS**（基线 44 + 16，含 ArchUnit 6 规则通过） |

测试覆盖：补偿成功路径不变（`compensationFailed=false`）、失败路径持久化开放行且不传播（LIFO 链延续）、记录持久化自身失败仍不传播且告警升级、对账服务 CRUD/截断/异常、控制器 admin 鉴权（403/200）。

## 6. 后续演进（P2）

1. OPM `PaymentService.refund` 增加 `refundRequestId` 幂等键（对齐 IETF HTTP Idempotency 草案语义）
2. 幂等键就绪后引入方案 C 的自动重试 worker，人工对账降级为兜底
3. `ep_payment_compensation_failure` 接入告警通道（当前为结构化日志，集群环境可由日志采集器触发告警规则）
