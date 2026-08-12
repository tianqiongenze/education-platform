| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-02 | v2.0 | 2026-08-06 | 已批准（修订） | 基础设施架构组 | 架构委员会 |

> 关联：替代 v1.0（2026-08-05，已废止）；D-053 图工作负载拆分决策；D-041 多 Region 多活铁律；SP-07 知识中台知识图谱域；SP-02 智能数据目录 GNN 字段级血缘；SP-04 AI 中台 Agent 拓扑；ep-memory L2 过程记忆技能图谱。

# ADR-INF-02: 图数据库选型（修订） — 终止 Neo4j，采用 NebulaGraph + TiDB 递归 CTE

## 1. 问题陈述与约束条件

教育平台以下场景需要图数据能力：

- **SP-07 知识中台**：知识图谱（课程-知识点-作业-学员关系），用于学习路径推荐与知识溯源。重图负载。
- **SP-02 智能数据目录（D-042 / ADR-D05）**：字段级血缘图，OpenLineage 采集 → 图存储 → GNN 推理。重图负载。
- **SP-04 AI 中台**：Agent 关系图谱（多 Agent 协作拓扑）。重图负载。
- **ep-memory L2 过程记忆**：技能图谱（技能-子技能-前置依赖），有界深度（≤5 跳）查询。轻图负载。

约束条件：

1. **分布式 HA 从 Day 1 起步（D-041 强制）**：D-041 多 Region 多活铁律要求所有持久化组件必须原生支持分布式高可用，而非"规模到了再加集群"。图方案必须在上线首日即具备多活能力，单点故障不可接受。
2. **分布式扩展性**：字段级血缘可达千万节点/亿边规模，需水平分片。
3. **多租户隔离**：租户级子图隔离，禁止跨租户查询泄漏。
4. **与 Java 栈集成**：SP-07 为 Java 21 + Spring Boot 4.0，需成熟 Java 驱动。
5. **开源协议合规**：GPL v3 与平台商业发行冲突，优先 Apache 2.0 / 商业友好协议。
6. **与 Pulsar/Flink 生态集成**：血缘/图谱增量写入经 Pulsar。
7. **成本约束**：企业版年费 $100K-$250K+/cluster 不可接受，避免供应商锁定。
8. **铁律 8**：三方对比后定论。

## 2. v1.0 致命缺陷：为何终止 Neo4j Community

v1.0 选择 Neo4j 5.x Community Edition，经架构委员会复审认定为**关键错误**：

1. **GPL v3 协议冲突**：Neo4j Community 采用 GPL v3，与教育平台商业发行计划存在协议传染风险，法律层面不可接受。
2. **无集群/HA 支持**：Community Edition **不支持集群、不支持高可用、不支持读副本**，仅能单机运行。这是产品线的硬性限制，非配置问题。
3. **单点故障，直接违反 D-041**：单机 Neo4j 即单点故障，违反 D-041 多 Region 多活铁律。D-041 要求"所有持久化组件 Day 1 即多活"，Neo4j Community 从架构上无法满足。
4. **企业版代价不可承受**：Neo4j Enterprise Edition 可提供 HA/Causal Cluster，但年费 $100K-$250K+/cluster，且闭源供应商锁定，与平台开源优先战略冲突。
5. **零代码迁移成本**：经核查，当前代码库中**无任何 Neo4j 实现代码**，v1.0 仅为文档层选型。终止 Neo4j 无需代码迁移，纯文档修订。

结论：Neo4j Community 因协议与 HA 双重硬伤终止；Neo4j Enterprise 因成本与锁定排除。重开图数据库选型。

## 3. 候选方案（铁律 8 三方对比）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **NebulaGraph 3.x** | Apache 2.0，原生 Raft 分布式，Day 1 多活，万亿边规模，GraphScope GNN 集成 |
| B | **TiDB 递归 CTE** | 零新组件，原生多活，适用于有界深度（≤5 跳）过程记忆技能图谱 |
| C | **Neo4j Enterprise** | 可满足 HA 需求，但 $100K-$250K+/年，闭源供应商锁定（因成本否决） |

## 4. 对比维度表

| 维度 | NebulaGraph 3.x（A） | TiDB 递归 CTE（B） | Neo4j Enterprise（C） |
|------|------------------------|---------------------|--------------------------|
| **开源协议（关键）** | Apache 2.0，商业友好 | Apache 2.0（TiDB 本体） | 商业闭源，Community 部分为 GPL v3 |
| **Day 1 分布式 HA（D-041 合规）** | 原生 Raft，shared-nothing，三副本强一致，Day 1 即多活 | TiDB 已在栈内，原生多 Region 多活，Day 1 合规 | Causal Cluster 支持 HA，但需付费授权 |
| **多 Region 多活** | 原生支持，跨 DC Raft | 原生支持（TiDB 多 Region 已落地） | 支持但需 Enterprise 授权，跨 Region 拓扑复杂 |
| **规模** | 万亿边，线性水平扩展 | 有界深度（≤5 跳）OLTP 查询；非通用图引擎 | 亿级节点单集群；超大规模需多集群 |
| **Java 栈集成** | nebula-java 客户端，无 Spring Data，需自封装 | JDBC 原生，Spring Data JPA 直接可用 | Spring Data Neo4j 原生，集成最成熟 |
| **查询语言** | nGQL（自有）+ openCypher（部分兼容） | SQL 递归 CTE（`WITH RECURSIVE`） | 原生 Cypher（最全） |
| **GNN 集成** | GraphScope 原生集成，GNN 特征工程顺畅 | 不适用 GNN，仅 OLTP 有界查询 | GDS 库支持，但与 GraphScope 生态弱 |
| **运维复杂度** | 三服务（graphd/metad/storaged），K8s Helm 成熟 | 零新增组件（TiDB 已在栈内运维） | 单二进制，但集群授权管理增加运维负担 |
| **成本** | 开源免费 | 开源免费（复用现有 TiDB） | $100K-$250K+/年/cluster，供应商锁定 |
| **适用负载** | 重图：知识图谱、血缘、Agent 拓扑 | 轻图：有界深度过程记忆技能图谱 | 通用图（成本否决） |

## 5. 决策（D-053）：图工作负载拆分

采纳**拆分图工作负载**策略，按负载特征分流：

### 5.1 重图负载 → NebulaGraph 3.x

适用场景：
- **SP-07 知识中台知识图谱**：课程-知识点-作业-学员关系，学习路径推荐与知识溯源。
- **SP-02 智能数据目录字段级血缘（D-042）**：OpenLineage 采集 → NebulaGraph 存储 → GraphScope GNN 推理。
- **SP-04 AI 中台 Agent 协作拓扑**：多 Agent 关系图谱。

理由：Apache 2.0 协议合规；原生 Raft 分布式 HA 从 Day 1 满足 D-041；万亿边线性扩展；GraphScope GNN 集成打通血缘与知识图谱的图神经网络推理链路。

### 5.2 轻图负载 → TiDB 递归 CTE

适用场景：
- **ep-memory L2 过程记忆**：技能图谱（技能-子技能-前置依赖），查询深度有界（≤5 跳）。

理由：技能图谱查询为有界深度 OLTP，非通用图遍历，TiDB 递归 CTE（`WITH RECURSIVE`）完全胜任；**零新增组件**，复用已在栈内的 TiDB，原生多 Region 多活直接满足 D-041；铁律 8 反对为有界查询引入新分布式组件。

### 5.3 Neo4j 终止

Neo4j（Community 与 Enterprise 均不再采用）终止。当前代码库**零 Neo4j 实现代码**，v1.0 仅为文档选型，终止为纯文档修订，无代码迁移。

## 6. 理由

1. **开源协议合规**：NebulaGraph（Apache 2.0）与 TiDB（Apache 2.0）均商业友好，消除 Neo4j GPL v3 传染风险。
2. **Day 1 分布式 HA（D-041 合规）**：NebulaGraph 原生 Raft 三副本强一致，TiDB 已落地多 Region 多活，两者从上线首日即满足 D-041，消除 Neo4j Community 单点故障硬伤。
3. **生态契合**：NebulaGraph + GraphScope 打通 SP-02 血缘 GNN 推理与 SP-07 知识图谱图神经网络链路，生态协同优于 Neo4j 自成一系。
4. **过程记忆零新组件**：ep-memory L2 技能图谱为有界深度（≤5 跳）查询，TiDB 递归 CTE 足够，避免为轻负载引入新分布式组件，符合架构简约原则。
5. **成本与去锁定**：NebulaGraph + TiDB 全栈开源免费，规避 Neo4j Enterprise $100K-$250K+/年费用与供应商锁定。

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| NebulaGraph 三服务（graphd/metad/storaged）运维复杂 | K8s Helm Chart 部署，nebula-operator 管理生命周期；纳入统一可观测平台 |
| nGQL 学习曲线陡，团队熟悉度低 | openCypher 部分兼容可平滑过渡；编写 nGQL 速查文档；重图查询封装为 SP-07/SP-02/SP-04 内部 SDK 屏蔽底层 |
| TiDB 递归 CTE 深度限制，不适用于通用图遍历 | 严格限定 TiDB CTE 仅用于 ep-memory ≤5 跳有界查询；通用图/深遍历一律走 NebulaGraph，禁止越界 |
| NebulaGraph 跨 Region Raft 性能 | 重图写入走本地 Region，跨 Region 走异步复制；延迟敏感查询指定本地副本优先 |

## 8. 迁移计划

由于当前代码库**零 Neo4j 实现代码**，本次修订为**纯文档变更**，无代码迁移：

1. **文档层（本 ADR 生效即完成）**：废止 v1.0，SP-07/SP-02/SP-04 设计文档中 Neo4j 引用替换为 NebulaGraph；ep-memory L2 设计文档补充 TiDB 递归 CTE 方案。
2. **NebulaGraph 部署（M3 阶段）**：K8s Helm 部署 NebulaGraph 3.x 集群（3 graphd / 3 metad / 3 storaged），接入统一可观测平台；nebula-java 客户端集成 SP-07 Java 服务。
3. **TiDB 递归 CTE（M2 阶段）**：ep-memory L2 技能图谱表结构落地，`WITH RECURSIVE` 查询接口上线，复用现有 TiDB 集群。
4. **GraphScope 集成（M4 阶段）**：SP-02 血缘 GNN 推理打通 NebulaGraph → GraphScope 链路。

## 9. 铁律 8 合规说明

本 ADR 对比三个独立候选方案（NebulaGraph 3.x / TiDB 递归 CTE / Neo4j Enterprise），覆盖协议、HA、规模、集成、成本等关键维度，满足铁律 8 三方对比要求。✓

---

**文档结束**。本 ADR v2.0 终止 v1.0 的 Neo4j Community 选型，改为 NebulaGraph 3.x（重图）+ TiDB 递归 CTE（轻图有界查询）拆分策略，消除 GPL v3 协议风险与单点故障 D-041 违规，决策依据 D-053。
