# D-195: Pulsar 4.0 升级就绪评估

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-195 | v1.0 | 2026-08-12 | 就绪评估（不升级） | 平台架构组 | 架构委员会 |

> 关联：D-066（Pulsar 事件总线）、D-119（Testcontainers + CDC）、D-041（多区域多活）、铁律 8（决策对比分析 ≥3 方案）、D-040（GraalVM AOT）。

## 1. 问题陈述与约束条件

平台 7 个服务使用 Pulsar 客户端（`pulsar-client`）进行事件发布与缓存广播：

| 服务 | 用途 | 版本管理 |
|------|------|---------|
| boardgame-service | 领域事件发布（比赛/房间） | `${pulsar.version}` = 3.3.9 |
| core-business | 业务事件发布 | `${pulsar.version}` = 3.3.9 |
| data-platform | 数据摄入 fallback | `${pulsar.version}` = 3.3.9（provided） |
| ep-cache | 缓存广播（可选后端） | `${pulsar.version}` = 3.3.9（optional） |
| grading-service | 评分事件发布 | `${pulsar.version}` = 3.3.9 |
| order-platform-service | 订单领域事件发布 | `${pulsar.version}` = 3.3.9 |
| senior-care | 养老事件发布 | `${pulsar.version}` = 3.3.9 |

**问题**：Pulsar 4.0 已发布，带来 schema registry 独立化、ReplicationProtocol v2、性能改进。但 7 个服务的客户端版本此前分散管理（部分硬编码 `3.3.9`），升级时需逐一修改，风险高。

**决策**：本次不升级到 4.0，但完成「升级就绪」准备工作：
1. 统一所有服务的 `pulsar.version` 到 `${pulsar.version}` 属性。
2. 在 ep-cache 引入 `PulsarVersionAdapter` 隔离版本相关 API。
3. 文档化 3.x → 4.0 的 breaking changes 与兼容性矩阵。
4. 提供就绪检查脚本 `scripts/check-pulsar-4-ready.sh`。

**目标升级时间**：2027 Q1（待 Pulsar 4.x 稳定版发布 2 个 minor 版本后）。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **就绪准备 + 延迟升级（已选）** | 统一版本属性 + PulsarVersionAdapter 隔离 + 文档化 breaking changes，2027 Q1 升级 |
| B | **立即升级到 4.0** | 全部 7 个服务同步升级 pulsar-client 到 4.x，一次性切换 |
| C | **渐进式升级（按服务逐个）** | 先升级 ep-cache（可选依赖），再逐步升级其他服务 |

## 3. 对比维度表

| 维度 | A. 就绪准备 + 延迟（已选） | B. 立即升级 4.0 | C. 渐进式升级 |
|------|--------------------------|-----------------|---------------|
| **风险** | 低（不改变运行时行为） | 高（7 服务同时变更，broker + client 需同步） | 中（版本不一致期间可能协议不兼容） |
| **工作量** | 低（统一属性 + 1 个适配类 + ADR） | 高（7 服务 × 测试 + broker 升级 + 回滚预案） | 中（分阶段，但需维护多版本兼容） |
| **版本一致性** | 是（统一 3.3.9） | 是（统一 4.x） | 否（过渡期 3.x + 4.x 混存） |
| **回滚能力** | N/A（不升级） | 难（broker 已升级，client 回滚需 broker 兼容） | 中（按服务回滚） |
| **生态成熟度** | 3.3.9 生产验证 | 4.0 刚发布，社区反馈少 | 混合 |
| **AOT 影响（D-040）** | 无（3.3.9 已有 native-image 配置） | 未知（4.0 native-image 兼容性待验证） | 过渡期需双份 native 配置 |

## 4. 决策

**选 A：就绪准备 + 延迟升级。**

理由：

1. **Pulsar 4.0 刚发布**：社区反馈与生产案例不足，7 个核心服务同时升级风险不可接受。延迟到 2027 Q1 待 4.x 稳定 2 个 minor 版本后再升级。
2. **版本统一是升级前提**：此前 7 个服务中只有 2 个使用 `${pulsar.version}` 属性，其余硬编码 `3.3.9`。统一后，未来升级只需改一个属性值 + 更新 `PulsarVersionAdapter.CLIENT_VERSION`。
3. **PulsarVersionAdapter 隔离 API**：将 PulsarClient/Producer/Consumer 创建集中到 `PulsarVersionAdapter`，3.x → 4.0 的 API 变更只需修改这一个类，7 个服务的调用代码不变。
4. **AOT 安全（D-040）**：Pulsar 3.3.9 已有 GraalVM native-image 配置（ep-cache 的 `META-INF/native-image`）。4.0 的 native 兼容性未知，延迟升级避免 AOT 回归。
5. **broker 不升级**：Pulsar broker 保持在 3.x，客户端 3.3.9 与 broker 3.x 完全兼容。4.0 客户端需要 4.0 broker 才能用新特性，当前无业务需求驱动。

## 5. Pulsar 3.x → 4.0 兼容性矩阵

### 5.1 Breaking Changes（需代码修改）

| 变更 | 3.x API | 4.0 API | 影响范围 | 迁移策略 |
|------|---------|---------|---------|---------|
| `PulsarClient.builder()` 废弃 | `PulsarClient.builder()` | `PulsarClient.newInstance()` (推荐) | 所有 PulsarClientConfig 类 | PulsarVersionAdapter 统一封装 |
| Schema registry 独立化 | 内置 `Schema.AUTO_CONSUME()` | 独立 schema-registry 服务 | 使用 AUTO_CONSUME 的消费者 | 当前全用 `Schema.BYTES`，不受影响 |
| ReplicationProtocol v2 | v1（隐式） | v2（opt-in） | geo-replication 命名空间 | 当前用 `replicationClusters`，opt-in 不影响 |

### 5.2 非破坏性变更（无需修改）

| 变更 | 说明 |
|------|------|
| `client.newProducer()` / `client.newConsumer()` | 4.0 保留，新增 `messageRoutingMode` 默认值 |
| `Schema.BYTES` / `Schema.STRING` | 4.0 完全兼容 |
| `Producer.sendAsync()` / `Producer.send()` | 4.0 API 不变 |
| `Consumer.subscribe()` / `Consumer.receive()` | 4.0 API 不变 |
| Topic 命名（persistent://...） | 4.0 完全兼容 |

### 5.3 各服务兼容性评估

| 服务 | 使用方式 | 3.x→4.0 兼容性 | 需修改 |
|------|---------|---------------|--------|
| boardgame-service | `PulsarClient.builder()` + `newProducer()` | `builder()` 废弃但可用 | PulsarVersionAdapter 封装后无需改 |
| core-business | 同上 | 同上 | 同上 |
| data-platform | `pulsar-client` provided，PulsarFallback TODO | 不影响运行时 | 无 |
| ep-cache | `ObjectProvider<PulsarClient>` + `newProducer()` | 已通过 PulsarVersionAdapter 封装 | 仅 PulsarVersionAdapter |
| grading-service | `PulsarClient.builder()` + `newProducer()` | 同 boardgame | PulsarVersionAdapter 封装后无需改 |
| order-platform-service | `PulsarClient.builder()` + `newProducer()` | 同上 | 同上 |
| senior-care | `PulsarClient.builder()` + `newProducer()` | 同上 | 同上 |

## 6. TiCDC → Pulsar CDC 链路

当前 CDC 链路（D-119）：
```
TiDB → TiCDC → Pulsar topic → data-platform consumer
```

Pulsar 4.0 对 CDC 链路的影响：
- **TiCDC 不直连 Pulsar**：TiCDC 输出到 Kafka 协议，由 data-platform 的 adapter 转发到 Pulsar。Pulsar 客户端升级不影响 TiCDC。
- **CockroachDB changefeeds（D-194）**：CockroachDB 原生 changefeeds 可直接 sink 到 Pulsar topic，Pulsar 4.0 的 schema registry 独立化可能影响 changefeed 消息格式——但当前用 `format=json`，不依赖 schema registry，4.0 兼容。

## 7. Geo-Replication（多区域多活 D-041）

当前 geo-replication 配置（cell-sg overlay）：
```
PULSAR_REPLICATION_CLUSTERS=sg
PULSAR_GLOBAL_TOPIC=persistent://ep/global-events
```

Pulsar 4.0 的 ReplicationProtocol v2：
- **opt-in**：不主动启用 v2，现有 v1 geo-replication 不受影响。
- **v2 优势**：支持跨集群事务一致性，但当前业务无此需求。
- **升级时机**：当 D-041 扩展到 3+ 区域且需要跨区域事务时评估 v2。

## 8. 升级前置条件清单

升级到 Pulsar 4.0 前需完成：

- [ ] Pulsar 4.x 发布 2 个 minor 版本（预计 2027 Q1）
- [ ] Pulsar 4.0 broker 在 staging 环境部署并运行 7 天无故障
- [ ] Pulsar 4.0 native-image（GraalVM）兼容性验证（D-040）
- [ ] `PulsarVersionAdapter` 升级为 4.0 API（`PulsarClient.newInstance()`）
- [ ] 7 个服务的 `pulsar.version` 属性统一改为 4.x
- [ ] 全量集成测试通过（Testcontainers + Pulsar 4.x 容器，D-119）
- [ ] TiCDC → Pulsar 4.x 兼容性验证
- [ ] CockroachDB changefeeds → Pulsar 4.x 兼容性验证（D-194）
- [ ] Geo-replication v1 → v2 评估（可选）
- [ ] 回滚预案：broker 降级 + client 降级步骤

## 9. 风险与回滚

| 风险 | 影响 | 缓解 |
|------|------|------|
| Pulsar 4.0 native-image 不兼容 | AOT 构建失败（D-040） | 延迟升级直到 native 兼容性验证通过 |
| 4.0 client 与 3.x broker 不兼容 | 服务启动失败 | 升级前先升 broker，保持 client 3.3.9 直到 broker 4.x 稳定 |
| Schema registry 独立化导致消息格式变更 | 消费者反序列化失败 | 当前全用 `Schema.BYTES`，不依赖 schema registry |
| Geo-replication v1 在 4.0 中废弃 | 跨区域复制中断 | v1 在 4.0 中仍支持（deprecated），v2 为 opt-in |

## 10. 实施工件

| 工件 | 路径 | 说明 |
|------|------|------|
| 版本统一 | 7 个服务 pom.xml | 全部使用 `${pulsar.version}` = 3.3.9 |
| 版本适配器 | `services/ep-cache/.../pulsar/PulsarVersionAdapter.java` | 隔离 PulsarClient/Producer/Consumer 创建 |
| 适配器测试 | `services/ep-cache/src/test/.../pulsar/PulsarVersionAdapterTest.java` | 18 个测试覆盖所有工厂方法 |
| 就绪脚本 | `scripts/check-pulsar-4-ready.sh` | 检查版本统一 + 适配器 + ADR 存在 |
| 本 ADR | `docs/adr/D-195-pulsar-4-upgrade-readiness.md` | 兼容性矩阵 + 升级前置条件 |

## 11. 后续工作

- [ ] 2027 Q1：评估 Pulsar 4.x 稳定性，决定是否升级
- [ ] 若升级：按第 8 节清单逐项执行
- [ ] 将 PulsarVersionAdapter 推广到其他 6 个服务（当前仅 ep-cache）
- [ ] 评估 ReplicationProtocol v2 对 D-041 多活的影响
