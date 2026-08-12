| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-03 | v1.0 | 2026-08-05 | 已批准 | 基础设施架构组 | 架构委员会 |

# ADR-INF-03: 消息中间件选型 — Pulsar vs RocketMQ vs Kafka

> 关联：D-028（CDC 总线统一 Pulsar）、D-029（WS 广播迁移 Pulsar）、铁律 4、铁律 8；SP-01/SP-02/SP-04/SP-07。

## 1. 问题陈述与约束条件

教育平台消息总线承担：

- **CDC 数据管道**（D-028）：TiCDC → Pulsar → Flink → Iceberg。
- **WS 广播**（D-029）：网关跨节点广播替代 Redis Pub/Sub。
- **AI 中台事件驱动**（D-046 Hermes）：Agent 事件总线 + DLQ。
- **跨区域复制**（D-041）：Pulsar Geo-Replication 实现异地多活数据同步。

约束条件：

1. **100 万并发**：峰值吞吐设计到 100 万 msg/s。
2. **多租户**：租户级 Topic 隔离 + 配额。
3. **分层存储**：冷数据下沉 S3/MinIO，降低存储成本。
4. **DLQ 内置**：Agent 事件失败需死信队列，铁律 8 反对手写 DLQ。
5. **与 Flink 集成**：CDC 管道用 Flink CDC 3.x YAML。
6. **跨区域复制**：异地多活（D-041）原生支持。
7. **铁律 8**：三方对比后定论。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Apache Pulsar 3.x**（已选，铁律 4） | 计算存储分离，原生多租户，分层存储 + Geo-Replication 内置 |
| B | **Apache RocketMQ 5.x** | 阿里系，Spring Cloud Alibaba 生态原生集成，DLQ/事务消息成熟 |
| C | **Apache Kafka 3.x** | 流处理王者，KRaft 去掉 ZK，Connect/Streams 生态最广 |

## 3. 对比维度表

| 维度 | Pulsar 3.x（A） | RocketMQ 5.x（B） | Kafka 3.x（C） |
|------|------------------|---------------------|------------------|
| **成熟度** | 2016 GA，Yahoo 大规模验证 | 2012 GA，阿里双十一验证 | 2011 GA，最成熟 |
| **社区活跃度** | 14k★+，Apache 顶级，国内增长快 | 21k★+，Apache 顶级，国内最活跃 | 28k★+，最活跃 |
| **架构** | 计算存储分离（Broker + BookKeeper），扩缩容独立 | 计算存储耦合（Broker 即存储），CommitLog | KRaft 去掉 ZK，计算存储耦合 |
| **100 万并发** | Broker 无状态水平扩展 + 分区级 fan-out，百万 msg/s 实测 | 单 Broker 性能强，百万 msg/s 需多节点 | 百万 msg/s 单集群可达成 |
| **多租户** | 原生 tenant/namespace/topic 三级，配额/ACL 内置 | Topic 级隔离，配额需自研 | Topic 级 + 配额需 KRaft/外部 |
| **分层存储** | Tiered Storage 原生支持 S3/MinIO/Aliyun OSS | 5.x 引入 Tiered Storage，成熟度低 | 无原生分层，靠 Kafka Connect 归档 |
| **DLQ 内置** | DeadLetterPolicy 原生 | 死信队列原生（%DLQ%TopicGroup） | 无原生 DLQ，需 Streams 自实现 |
| **跨区域复制** | Geo-Replication 原生（异步/同步可选） | 原生集群间复制（DC replication） | MirrorMaker2（外部工具） |
| **与 Flink 集成** | Flink Pulsar Source/Sink 一等公民（Flink 1.18+） | Flink RocketMQ Connector（社区维护） | Flink Kafka Source/Sink 最成熟 |
| **与 SCA 生态** | 无原生 SCA starter，需自封装 | Spring Cloud Alibaba RocketMQ starter 原生 | Spring Kafka 原生 |
| **事务消息** | 事务支持，较弱 | 事务消息成熟（半事务+回查） | 事务支持，幂等+事务生产者 |
| **延迟** | 毫秒级（BookKeeper ack quorum） | 毫秒级 | 毫秒级 |
| **运维复杂度** | Broker + BookKeeper + ZK（3.x 仍需），组件多 | NameServer + Broker，中等 | KRaft 去 ZK，简单（3.x+） |
| **AOT/GraalVM** | Java 客户端兼容，service 层不内嵌 broker | 同 | 同 |

## 4. 结论与理由

**采纳方案 A：Apache Pulsar 3.x**（与铁律 4 / D-028 / D-029 一致，记录对比理由）。

理由：

1. **计算存储分离**：Broker 无状态可独立扩缩容，匹配 100 万并发弹性需求；RocketMQ/Kafka 的 Broker-存储耦合在弹性扩缩容上劣势。
2. **原生多租户**：tenant/namespace/topic 三级隔离 + 配额 + ACL，天然契合 D-044 SaaS 多租户；RocketMQ/Kafka 多租户需 Topic 命名约定 + 外部配额。
3. **分层存储内置**：Tiered Storage 原生支持 S3/MinIO，冷数据下沉成本可控；Kafka 无原生方案，RocketMQ 5.x 才引入且成熟度低。
4. **DLQ 内置**：`DeadLetterPolicy` 原生，D-046 Hermes 的 P1-2（手写 DLQ → Pulsar DLQ）整改正是基于此；Kafka 需自实现。
5. **Geo-Replication 原生**：D-041 异地多活数据同步直接用 Pulsar Geo-Replication；Kafka 需 MirrorMaker2 外部组件。
6. **与 Flink 集成**：Flink 1.18+ Pulsar Source/Sink 一等公民，D-028 CDC 管道已验证。

**取舍**：放弃 SCA 原生 starter（RocketMQ 优势），代价是 Pulsar 的 SCA starter 需自封装（仅 producer/consumer 配置层，非造轮子）。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| BookKeeper + ZK 组件多，运维重 | K8s Helm Chart 统一部署；3.x 后续升级去 ZK（已规划） |
| 无 SCA 原生 starter | 自封装 Pulsar producer/consumer starter，仅配置层 |
| Pulsar 中文社区比 RocketMQ 小 | 与 Apache Pulsar 中国社区共建，参考携程/Bigo 落地经验 |

---

**文档结束**。本 ADR 补齐 D-048 审计 P2-1 的消息中间件选型对比缺失，记录 Pulsar 已选理由（铁律 4），满足铁律 8 三方对比要求。
