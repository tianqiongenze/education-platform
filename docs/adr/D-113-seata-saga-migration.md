| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-113 | v1.0 | 2026-08-09 | 已批准 | SP-03 业务中台 | 架构委员会 |

# D-113: Seata AT → Saga 迁移 (长事务协调)

> 关联：铁律 0（无静默失败）、铁律 8（≥3 方案对比）；D-077（sp05 @GlobalTransactional 剥离）、D-048（Seata 全局事务接线）、D-066（库存电路熔断）。

## 1. 问题陈述与约束条件

SP-03 订单中台在 `OrderServiceImpl` 中使用 Seata AT 模式
(`@GlobalTransactional`) 包裹 `createOrder` / `createOrderWithGlobalTx`
长流程（订单写入 → 库存扣减 → 支付记录）。AT 模式存在两个不可接受的问题：

1. **全局锁持有时间过长**：order → stock → payment 跨服务链路可能长达数秒
   （支付网关往返、库存 RPC），AT 模式在整个全局事务期间持有全局锁，
   阻塞其他并发订单对同一 course / user 的写入，长事务下吞吐急剧下降。
2. **dev/CI 无可用 TC**：本地与 CI 环境无 Seata TC Server
   （与 D-077 在 sp05 发现的同一 gap），`@GlobalTransactional` 启动时尝试
   注册分支事务即失败，导致测试链路不可用。

约束：

- **不得连接真实 Seata TC**（本次为纯代码变更，TC 部署留给运维）。
- **保留 AT 模式用于短事务**（单服务本地写，全局锁窗口可忽略）。
- **匹配现有技术栈**：Java 21、Spring Boot 4.0.7、Dubbo 3.3.6、Seata 2.0.0。
- **Iron Law 8**：≥3 候选方案对比后定论。
- **Iron Law 0**：无静默失败 — Saga 引擎失败必须显式抛出，不得静默吞掉。

## 2. 候选方案 (Iron Law 8: ≥3)

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Seata Saga 状态机编排** | JSON 定义状态机，`StateMachineEngine` 编排 forward/compensation，TC 协调补偿回放 |
| B | **TCC 三阶段提交** (Try-Confirm-Cancel) | 每个分支显式实现 Try/Confirm/Cancel 三个接口，TC 两阶段协调，业务无感回滚 |
| C | **事件驱动 Choreography + Saga** | 无中心编排，各服务订阅领域事件自行推进/补偿，最终一致 |

## 3. 对比维度表

| 维度 | A. Saga 状态机 | B. TCC 三阶段 | C. Event Choreography |
|------|----------------|---------------|------------------------|
| **一致性模型** | 最终一致 + 补偿语义 | 强一致（Try 预留 → Confirm/Cancel） | 最终一致（事件溯源 + 幂等） |
| **全局锁持有** | 不持有 — 每分支本地提交后即释放，仅保留补偿契约 | 短期持有（Try 阶段预留资源锁） | 无全局锁 |
| **业务侵入** | 中 — 每分支写 forward + compensation 两个方法 | 高 — 每分支写 Try/Confirm/Cancel 三个接口 + 预留资源 | 高 — 每服务实现事件消费者 + 幂等 + 重试 |
| **可观测性** | 高 — 状态机实例可查、可重放、可人工干预 | 中 — TC 记录分支状态，但无业务流可视化 | 低 — 散落在各服务事件处理器中，排查需链路追踪 |
| **长事务适配** | 优 — 设计目标即长流程补偿 | 差 — Try 预留资源在长流程下占用过久 | 优 — 异步天然适配长流程 |
| **与 Seata 生态** | 原生（`seata-saga-starter`） | 原生（`seata-tcc`） | 需自行实现，脱离 Seata TC |
| **dev/CI 无 TC** | 引擎可在进程内编排（forward/compensation 为普通 Bean 调用），TC 缺失时补偿回放仍可运行 | 无 TC 则 Try/Confirm 协调失败，分支状态无法推进 | 无需 TC，但需消息中间件（Pulsar）就绪 |
| **复杂度** | 中 — JSON 状态机 + 补偿 Bean | 高 — 三接口 + 资源预留 + 资源释放边界条件 | 高 — 事件顺序、幂等、重试、死信全需自管 |
| **与现有代码契合** | 高 — `OrderServiceImpl` 现有 pay/refund 逻辑可直接复用为 compensation | 中 — 需重构库存/支付为 Try/Confirm/Cancel | 低 — 需引入事件总线编排，重构量大 |
| **回滚语义** | 补偿（业务级反向操作，非自动 undo） | Cancel（业务级反向操作） | 补偿事件（业务级反向操作） |
| **失败恢复** | 状态机实例持久化，可断点续跑 / 人工干预 | TC 记录分支状态，超时自动 Cancel | 依赖事件重试 + 死信队列 |
| **多租户透传** | Saga 上下文携带 tenantId | TCC 上下文携带 tenantId | 事件 payload 携带 tenantId |

## 4. 结论与理由

**采纳方案 A：Seata Saga 状态机编排**。

理由：

1. **长事务全局锁问题根治**：Saga 每个分支本地提交后立即释放锁，仅保留
   补偿契约。order → stock → payment 长流程不再阻塞并发订单，吞吐问题解决。
2. **dev/CI 无 TC 可用**：`StateMachineEngine` 在进程内编排，
   `@SagaCompensable` 动作是普通 Spring Bean 调用；TC 缺失时 forward /
   compensation 仍可运行（与 D-077 在 sp05 的"剥离 @GlobalTransactional"
   同源问题，但 Saga 保留了跨服务协调能力而非简单降级为本地事务）。
3. **业务侵入可控**：每分支仅需 forward + compensation 两个方法，库存/
   支付/订单的现有 `pay`/`refund`/`deductInventory` 逻辑可直接复用为
   compensation，无需 TCC 的三接口资源预留。
4. **可观测性优于事件编排**：状态机实例持久化在 `seata_state_machine`
   表，可查询、可重放、可人工干预；事件驱动 Choreography 的失败排查需
   跨服务链路追踪，运维成本高。
5. **与 Seata 生态原生契合**：`seata-saga-starter` 2.0.0 与现有
   `seata-spring-boot-starter` 2.0.0 同版本，无版本冲突；未来 TC 部署后
   无需改代码即可获得跨进程协调。
6. **AT 模式保留作短事务兜底**：单服务本地写（如 `getOrderById`、
   `createOrder` 仅写订单行）仍用 `@Transactional`，全局锁窗口可忽略，
   不引入 Saga 的补偿复杂度。

## 5. 实现要点

### 5.1 Saga 状态机定义

- `services/sp03-order/sp03-order-service/src/main/resources/saga/create-order-saga.json`
  - Forward: `reserveStock` → `createPayment` → `confirmOrder`
  - Compensate (LIFO): `releaseStock` → `refundPayment` → `cancelOrder`
  - `CompensationTrigger` 状态集中触发补偿回放
- `services/sp03-order/sp03-order-service/src/main/resources/saga/cancel-order-saga.json`
  - Forward: `cancelOrder` → `releaseStock` → `refundPayment`
  - 该流程本身即 create-order saga 的补偿，不再注册二级补偿

### 5.2 @SagaCompensable 动作 Bean

| Bean (`ServiceName`) | Forward | Compensation |
|----------------------|---------|--------------|
| `stockCompensableAction` | `reserveStock` | `compensateReserveStock` |
| `paymentCompensableAction` | `createPayment` | `compensateCreatePayment` |
| `orderCompensableAction` | `confirmOrder` | `cancelOrder` |

- 库存 forward 保留 D-066 的 `@CircuitBreaker` 熔断兜底。
- 支付 compensation 保留"best-effort"语义（refund 失败不传播，由对账兜底），
  与原 `refundOrder` 行为一致。
- 订单 forward (`confirmOrder`) 复用 `OrderModel.pay()` 聚合状态机。

### 5.3 OrderServiceImpl 改造

- 移除 `@GlobalTransactional`（`createOrder` 与 `createOrderWithGlobalTx`）。
- `createOrderWithGlobalTx` 改为：先本地 `@Transactional` 写 PENDING 订单行
  （短事务，AT 模式保留），再 `sagaEngine.startWithBusinessKey(...)` 驱动
  跨服务 Saga。
- 新增 `cancelOrderWithSaga(Long, String)` 驱动 cancel-order saga。
- `StateMachineEngine` 通过构造器注入（便于测试替换）。

### 5.4 依赖与配置

- `services/sp03-order/sp03-order-service/pom.xml` 新增
  `io.seata:seata-saga-starter:2.0.0`（与现有 `seata-spring-boot-starter`
  同版本，对齐 sp05-itbackend / integration-tests 的 `seata.version=2.0.0`）。
- `SagaStateMachineConfig` 加载两个 JSON 定义并暴露
  `StateMachineEngine` Bean。

### 5.5 测试

- `services/integration-tests/src/test/java/com/ep/integration/SagaTransactionTest.java`
  — `@Tag("integration")`，用 `FakeSagaEngine` 验证 forward 顺序、
  compensation LIFO 回放、首步失败无补偿、补偿异常不阻断剩余回放、
  上下文透传。CI 无 TC 亦可绿。
- `services/integration-tests/src/test/java/com/ep/live/SeataLiveIT.java`
  — 在原 AT 探活旁新增 Saga 端点探活（`@Order(4)`，只读）与 Saga
  定义存在性静态守卫（`@Order(5)`，不依赖 TC）。

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 补偿非自动 undo，需业务级反向操作 | 补偿逻辑错误会留下脏状态 | 每个补偿方法加单元测试 + 幂等保护（`cancelOrder` 已实现幂等） |
| 支付 compensation best-effort，可能 refund 失败 | 短暂资金不一致 | 对账兜底（与原 `refundOrder` 一致），`compensateCreatePayment` 记录 `REFUND_FAILED_BEST_EFFORT` 状态供对账抓取 |
| 无 TC 时状态机实例不持久化 | 进程重启丢失进行中 Saga | dev/CI 接受；生产部署 TC 后由 `seata_state_machine` 表持久化，可断点续跑 |
| Saga 引擎 Bean 加载失败导致服务启不来 | 启动阻塞 | `SagaStateMachineConfig` 在 JSON 缺失时 fail-fast（Iron Law 0），不静默降级 |
| AT 与 Saga 并存可能混淆开发者 | 误用 `@GlobalTransactional` 包裹长流程 | `OrderServiceImpl` 类级 JavaDoc 明确："AT 模式保留用于短事务"；本 ADR 记录边界 |

## 7. 替代方案不选理由

- **B. TCC 三阶段**：业务侵入高（每分支三接口 + 资源预留），且 Try 阶段
  资源预留本身在长流程下又退化成"长持有锁"，未根治本 ADR 的核心问题。
  TCC 更适合短而强一致的金融扣减，不适合 order → stock → payment 长链路。
- **C. 事件驱动 Choreography**：无中心编排，失败排查需跨服务链路追踪，
  运维成本高；且需引入消息中间件编排层，重构量大。当前 Pulsar 仅用于
  领域事件发布（post-commit），引入事件编排会改变架构边界。可作未来
  演进方向，但非本次迁移的合理选择。

## 8. 后续演进

- TC 部署后：无需改代码即获得跨进程 Saga 协调；`seata_state_machine`
  表持久化状态机实例，支持断点续跑与人工干预。
- 若未来 order → stock → payment 链路进一步拉长（加入优惠券、积分），
  仅在 JSON 状态机中新增状态即可，无需改 Java 代码。
- 若强一致扣减需求出现（如余额账户），可在该分支局部引入 TCC，
  与 Saga 共存于同一 Seata 2.x TC。

## 9. 关联文件

- `services/sp03-order/sp03-order-service/src/main/java/com/ep/order/service/OrderServiceImpl.java`
- `services/sp03-order/sp03-order-service/src/main/java/com/ep/order/saga/StockCompensableAction.java`
- `services/sp03-order/sp03-order-service/src/main/java/com/ep/order/saga/PaymentCompensableAction.java`
- `services/sp03-order/sp03-order-service/src/main/java/com/ep/order/saga/OrderCompensableAction.java`
- `services/sp03-order/sp03-order-service/src/main/java/com/ep/order/saga/SagaStateMachineConfig.java`
- `services/sp03-order/sp03-order-service/src/main/resources/saga/create-order-saga.json`
- `services/sp03-order/sp03-order-service/src/main/resources/saga/cancel-order-saga.json`
- `services/sp03-order/sp03-order-service/pom.xml`
- `services/integration-tests/src/test/java/com/ep/integration/SagaTransactionTest.java`
- `services/integration-tests/src/test/java/com/ep/live/SeataLiveIT.java`
