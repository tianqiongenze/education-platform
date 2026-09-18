# SP-01 技术中台 —— 软件需求规格说明书(SRS)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp01` for reference continuity. Per D-137, sp01 → user. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP01-SRS-001 | v1.0 | 2026-08-03 | Approved | ZCode(文档工程师) | EP-TOGAF-001 §1-§6 / EP-SPEC-001 §3 |

> 本文是 education-platform 项目群 SP-01 技术中台的**需求基线**。所有功能/非功能需求以顶层 TOGAF 文档 G-01..G-10 SLO 为量化锚点，与下游 SAD/SDD/测试/部署文档双向追溯。

---

## 目录

1. [引言](#1-引言)
2. [角色与用户故事](#2-角色与用户故事)
3. [功能需求(逐组件)](#3-功能需求逐组件)
4. [非功能需求(SLO 量化表)](#4-非功能需求slo-量化表)
5. [约束条件](#5-约束条件)
6. [依赖关系](#6-依赖关系)
7. [验收标准](#7-验收标准)
8. [追溯矩阵](#8-追溯矩阵)

---

## 1. 引言

### 1.1 文档目的

为 SP-01 技术中台确立**完整可测试的需求基线**，覆盖 13 个核心组件（API 网关/BFF、WS/实时网关、CellRouter、读写分离 SDK、Disruptor ingest、多级缓存、事件总线 EDA、服务治理、可观测、配置中心、限流降级、Rust 热点、跨语言契约对齐）的功能需求、非功能需求、用户故事、约束、依赖、验收标准。

### 1.2 范围

**In Scope**：

- 网关层(Java 自建 Netty edge + Disruptor + Spring WebMVC + Go WS 网关)：鉴权、限流、路由、长连接、广播  ← D-032/ADR-T07(rev2) 禁 SCG
- 单元路由器 CellRouter：流量纠正、写挂起、路由表外置
- 读写分离 SDK(ep-rw-java，已实现 v1.0.0)：4 模式 READ/WRITE/READ+consistent/READ+tiflash
- 高并发 ingest SDK(ep-disruptor，已实现 v1.0.0)：LMAX Disruptor RingBuffer 65536、drop-on-full、batch flush
- 多级缓存 SDK(ep-cache，已实现 v1.0.0)：L1 Caffeine + L2 Redis Cluster + Pulsar 广播失效 + 延迟双删
- 事件总线 EDA：Pulsar topic 规范、lazy-init、降级
- 服务治理：Dubbo Triple RPC + 契约先行 + Pact 测试
- 可观测：OTel + Loki + Prometheus + Tempo，双日志轨，tail-sampling
- 配置中心：Nacos 路由表/特性开关/限流规则
- 限流降级：Sentinel 网关 + 服务双层
- Rust 热点服务：风控规则引擎 + 计费
- 跨语言契约对齐：JWT RS256 + JWKS、缓存 key/value 纯 JSON

**Out of Scope**：

- 数据中台/业务中台/AI 中台的领域需求(各自独立 SRS)
- 事业群业务实现
- 生产环境实际部署(本 SRS 为设计基线，部署见部署方案)

### 1.3 术语

| 术语 | 含义 |
|------|------|
| CCU | 并发用户数(Concurrent Users) |
| SLO | 服务等级目标(Service Level Objective) |
| BFF | Backend For Frontend，面向前端的后端聚合层 |
| JWKS | JSON Web Key Set，JWT 验签公钥集 |
| Cell | 单元，混合单元化架构的最小自治部署单元 |
| CellRouter | 单元路由器，负责流量路由到正确 cell |
| misroute | 错误路由，请求到达错误 cell 的情况 |
| FGAC | Fine-Grained Access Control，细粒度访问控制 |
| EDA | Event-Driven Architecture，事件驱动架构 |
| ADR | Architecture Decision Record，架构决策记录 |

### 1.4 参考文档

- EP-TOGAF-001 顶层企业架构文档(§1 愿景/§6 技术架构/§13 追溯矩阵)
- EP-SPEC-001 §3 技术中台 SAD
- ADR-T01..T05(技术中台关键决策)
- D-004/D-008/D-012/D-014/D-015(架构级决策)
- 已实现 SDK 源码：`services/ep-rw-java/`、`services/ep-disruptor/`、`services/ep-cache/`

---

## 2. 角色与用户故事

### 2.1 利益相关者

| 角色 | 关注点 | 验收方式 |
|------|--------|---------|
| 事业群业务团队(BU) | 中台能力稳定可用、契约清晰、接入快 | 契约测试 + 接入文档 |
| 学员/教师 | API 低延迟、直播互动流畅、登录稳定 | SLO 监控 + UAT |
| SRE/运维 | 可观测全、灰度可控、故障自愈、运维可操作 | Runbook 演练 + 故障注入 |
| 架构委员会 | 架构原则遵守、ADR 完整、技术债可控 | ADR review |
| 安全合规队 | 鉴权不可绕过、沙箱不可逃逸、数据不出境 | 安全测试 + 审计 |
| 数据中台/AI 中台 | 事件总线/可观测/缓存契约对齐 | 跨中台集成测试 |

### 2.2 用户故事

**US-01 事业群接入中台**
> 作为 IT 后端事业群开发，我希望通过注册领域服务 + 调用中台 SDK，就能获得鉴权、路由、读写分离、缓存、事件能力，而不必自建共享基础设施，从而专注业务逻辑。

**US-02 学员直播互动**
> 作为学员，我希望在直播中发送弹幕/连麦请求，能在 800ms 内被其他观众看到，即使百万并发也不卡顿，从而获得流畅的学习体验。

**US-03 教师直播授课**
> 作为教师，我希望推流后学员能秒级拉流，互动信令延迟低，从而保证授课质量。

**US-04 SRE 故障切换**
> 作为 SRE，当某单元故障时，我希望 CellRouter 在 30s 内完成流量切换，期间无脏写，从而保证高可用且数据一致。

**US-05 安全合规审计**
> 作为安全合规人员，我希望所有鉴权走 JWT RS256 + JWKS，禁止任何伪造头绕过，沙箱逃逸测试零通过，从而满足等保2.0合规。

**US-06 运维限流降级**
> 作为运维，当突发流量超过 SLO 时，我希望 Sentinel 在网关层秒级触发限流/降级，核心交易不被拖垮，从而保护系统稳定。

**US-07 数据中台消费事件**
> 作为数据中台开发，我希望技术中台的事件总线按 `{domain}.{action}` topic 规范发布，Avro schema 注册，从而稳定消费 CDC 与业务事件。

**US-08 跨语言一致性**
> 作为 Java/Go/Python 多语言团队，我希望缓存 key/value 是纯 JSON snake_case、JWT 验签跨语言一致，从而避免跨语言反序列化炸弹。

---

## 3. 功能需求(逐组件)

### 3.1 API 网关/BFF(FR-GW)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-GW-01 | 基于自建 Netty edge + Disruptor ingress + Spring WebMVC 实现统一接入网关(禁 Spring Cloud Gateway — D-032/铁律6)，路由配置由 Nacos 动态下发 | P0 | 路由变更不重启生效 |
| FR-GW-02 | JWT RS256 签发：BFF 在登录成功后用私钥签发 JWT，header.kid 指向 JWKS 公钥 | P0 | 验签测试 |
| FR-GW-03 | JWKS 端点 `//.well-known/jwks.json` 暴露公钥，下游缓存 5min，轮换时 kid 变更触发重新拉取 | P0 | 密钥轮换测试 |
| FR-GW-04 | AuthFilter 强制校验 Authorization 头，禁止任何 `X-User-Id`/`X-Tenant-Id` 伪造头透传到下游 | P0 | 伪造头注入测试 |
| FR-GW-05 | Sentinel 网关层限流：QPS/并发线程数双维度，规则由 Nacos 下发，秒级生效 | P0 | 压测触发限流 |
| FR-GW-06 | 灰度发布：基于 header/user_id 哈希灰度分流，金丝雀 1%→10%→100% | P1 | 灰度路由测试 |
| FR-GW-07 | 注入 W3C traceparent 头，传递到下游 OTel 链路 | P0 | 全链路 trace 可见 |
| FR-GW-08 | 双日志轨：access log(请求级) + business log(业务事件级)，结构化 JSON 落 Loki | P0 | 日志检索验证 |

### 3.2 WS/实时网关(FR-WS)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-WS-01 | 基于 Go 实现长连接网关，单节点支撑 50 万连接，集群百万级 | P0 | 连接压测 |
| FR-WS-02 | WS 广播按 `fnv1a(session)%N` 分片，避免单连接广播风暴 | P0 | 分片验证 |
| FR-WS-03 | 跨 pod 通过 Pulsar 广播消息（统一消息基础设施，替代 Redis Pub/Sub），pod 内本地直发 | P0 | 跨 pod 消息送达 |
| FR-WS-04 | 心跳保活：客户端 30s ping，服务端 60s 无响应判定断连 | P0 | 断连清理测试 |
| FR-WS-05 | 鉴权：WS 握手时校验 JWT(从 query/cookie/subprotocol 提取)，失败拒连 | P0 | 未授权拒连测试 |
| FR-WS-06 | 房间概念：直播房间消息仅广播给房间订阅者，避免全局广播 | P0 | 房间隔离测试 |
| FR-WS-07 | 背压：消费慢时丢弃非关键消息(如弹幕)，保留关键消息(如信令) | P1 | 背压降级测试 |

### 3.3 单元路由器 CellRouter(FR-CR)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-CR-01 | 路由算法：`cell = hash(user_id) % N`，N 与映射由 Nacos 外置，灰度可调(ADR-T01) | P0 | 路由表变更生效 |
| FR-CR-02 | 流量纠正：请求 context 内嵌纠正计数器(上限 3)，检测 misroute 自动重路由到正确 cell | P0 | misroute 纠正测试 |
| FR-CR-03 | 超限返 403：纠正次数超 3 返 403 Forbidden，记录告警 | P0 | 超限拒绝测试 |
| FR-CR-04 | 跨单元写挂起：failover 期间该 user_id 写操作挂起(AppActive 模式)，防止脑裂脏写 | P0 | failover 写挂起测试 |
| FR-CR-05 | 存储层拦截：按 user_id 校验写操作的 cell 归属，错单元写拒绝 | P0 | 错单元写拒绝测试 |
| FR-CR-06 | 就近写：请求路由到就近区副本，Raft 多数派写成功即返回 | P1 | 就近路由验证 |
| FR-CR-07 | 路由表缓存：本地缓存路由表 5s，Nacos 变更时主动推送失效 | P0 | 缓存失效验证 |

### 3.4 读写分离 SDK ep-rw-java(FR-RW，已实现 v1.0.0)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-RW-01 | 提供 `@DataSource` 注解，4 模式：READ / WRITE / READ+consistent / READ+tiflash(ADR-T02) | P0 | 注解模式测试(已实现) |
| FR-RW-02 | READ 路由到 Follower 池，WRITE 路由到 Primary | P0 | RoutingDataSource 测试(已实现) |
| FR-RW-03 | consistent=true 强制走 Primary，防读后写不一致 | P0 | consistent 模式测试(已实现) |
| FR-RW-04 | tiflash=true 路由到 TiFlash 列存(OLAP)，通过 SQL hint | P1 | tiflash 模式测试 |
| FR-RW-05 | ThreadLocal 路由上下文，支持嵌套调用(READ 恢复 prev，WRITE 清残留防脏读) | P0 | 嵌套调用测试(已实现) |
| FR-RW-06 | 基于 AbstractRoutingDataSource + LazyConnectionDataSourceProxy，延迟连接获取 | P0 | 配置加载测试(已实现) |
| FR-RW-07 | HikariCP 连接池，write/read 独立池配置，`ep.rw.enabled` 开关 | P0 | 配置开关测试(已实现) |
| FR-RW-08 | 配合 `SET SESSION tidb_read_consistency='strong'` + `@Version` 乐观锁 | P1 | 强一致读测试 |

### 3.5 高并发 ingest SDK ep-disruptor(FR-DIS，已实现 v1.0.0)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-DIS-01 | 基于 LMAX Disruptor，RingBuffer 默认 65536，可配置 | P0 | bufferSize 测试(已实现) |
| FR-DIS-02 | drop-on-full：`tryNext()` 失败返 DROPPED，不阻塞生产者(ADR-T03) | P0 | drop 测试(已实现) |
| FR-DIS-03 | PublishResult 枚举：PUBLISHED / DROPPED，调用方可观测 | P0 | 结果测试(已实现) |
| FR-DIS-04 | WaitStrategy 5 种：BLOCKING / SLEEPING / YIELDING / BUSY_SPIN / LITE_BLOCKING | P0 | 策略测试(已实现) |
| FR-DIS-05 | ProducerType MULTI/ SINGLE，默认 MULTI | P0 | 多生产者测试(已实现) |
| FR-DIS-06 | DisruptorPipeline 多阶段串联：stage→forward→next stage，逆序 shutdown | P0 | pipeline 测试(已实现) |
| FR-DIS-07 | DisruptorStats：published/dropped/consumed/backlog 指标可观测 | P0 | stats 测试(已实现) |
| FR-DIS-08 | 计数 EventHandler 前置：消费计数独立于业务 handler | P1 | 计数测试(已实现) |
| FR-DIS-09 | batch flush：200 条或 500ms 触发批量落 DB(由调用方 handler 实现) | P1 | 批量测试 |

### 3.6 多级缓存 SDK ep-cache(FR-CA，已实现 v1.0.0)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-CA-01 | L1 Caffeine(maximumSize=10000, expireAfterWrite=60s) + L2 Redis Cluster(ADR-T04) | P0 | 双层测试(已实现) |
| FR-CA-02 | key 规范 `{cacheName}:{key}`，跨语言值=纯 JSON snake_case，禁 DefaultTyping | P0 | 序列化测试(已实现) |
| FR-CA-03 | EMPTY_SENTINEL `__EP_CACHE_EMPTY__` 防穿透，TTL 30s | P0 | 空值缓存测试(已实现) |
| FR-CA-04 | getOrLoad：L1 miss→L2 miss→loader→回填 L1+L2 | P0 | 三级回填测试(已实现) |
| FR-CA-05 | evict：L1 evict + L2 delete + Pulsar 广播 + 延迟双删 100ms | P0 | 失效广播测试(已实现) |
| FR-CA-06 | CacheBroadcast：Pulsar producer lazy-init，无 Pulsar 时降级 no-op | P0 | 降级测试(已实现) |
| FR-CA-07 | EventPublisher：topic→Producer 缓存，sendAsync 异步失败不阻塞 | P0 | 异步发布测试(已实现) |
| FR-CA-08 | PulsarClientHolder：单例延迟获取，@PreDestroy 关闭 | P1 | 生命周期测试(已实现) |
| FR-CA-09 | Redis 异常降级：L2 操作失败 warn 日志，不抛异常，L1 仍可用 | P0 | 降级测试(已实现) |
| FR-CA-10 | 全局 cache_name 注册表防碰撞(M0 人工登记，M1 自动校验) | P1 | 碰撞检测测试 |

### 3.7 事件总线 EDA(FR-EDA)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-EDA-01 | topic 规范 `{domain}.{action}`，如 `order.paid`、`learning.progress` | P0 | topic 命名测试 |
| FR-EDA-02 | lazy-init：producer 按需创建，`PULSAR_ENABLED` 默认关，开启才连 | P0 | 开关测试(已实现) |
| FR-EDA-03 | 降级：Pulsar 不可用时 publish no-op + warn，不阻塞主流程 | P0 | 降级测试(已实现) |
| FR-EDA-04 | Avro schema 注册(由数据中台 Schema Registry 强制)，EVOLVE 模式 | P0 | schema 演化测试 |
| FR-EDA-05 | geo-rep：跨单元 Pulsar geo-replication 聚合到全局 namespace | P1 | 跨单元事件测试 |
| FR-EDA-06 | 至少一次语义：producer sendAsync 失败重试 3 次，最终失败入死信 | P0 | 重试测试 |
| FR-EDA-07 | 消费者幂等：业务侧基于 event_id 去重，中台提供 EventIdGenerator | P0 | 幂等测试 |

### 3.8 服务治理 Dubbo Triple(FR-RPC)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-RPC-01 | 基于 Dubbo Triple(HTTP/2 + Protobuf)，契约先行 IDL | P0 | IDL 编译测试 |
| FR-RPC-02 | 服务注册到 Nacos，消费者按需发现 | P0 | 服务发现测试 |
| FR-RPC-03 | Pact 契约测试：消费者驱动，CI 硬门(P-06) | P0 | Pact 验证 |
| FR-RPC-04 | 超时/重试：默认超时 3s，重试 2 次(幂等接口) | P0 | 超时测试 |
| FR-RPC-05 | 跨单元禁止直接 RPC，走 Pulsar 异步(P-02) | P0 | 跨单元 RPC 拒绝测试 |

### 3.9 可观测(FR-OBS)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-OBS-01 | OTel SDK 注入 W3C traceparent，全链路 trace 可见 | P0 | 链路验证 |
| FR-OBS-02 | 两级 Collector：edge(tail-sampling 10%) → gateway → 后端 | P0 | 采样验证 |
| FR-OBS-03 | 三后端：Loki(日志) / Tempo(链路) / Prometheus(指标) | P0 | 三端写入验证 |
| FR-OBS-04 | 双日志轨：access log + business log，独立采集检索 | P0 | 双轨验证 |
| FR-OBS-05 | Prometheus 指标：histogram(P95/P99)、counter、gauge，Grafana 看板 | P0 | 看板验证 |
| FR-OBS-06 | AlertManager 告警：SLO 违规、pod 重启、限流触发等 | P0 | 告警触发验证 |

### 3.10 配置中心 Nacos(FR-CFG)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-CFG-01 | 路由表配置：cell 数量 N、user_id→cell 映射，灰度可调 | P0 | 路由变更测试 |
| FR-CFG-02 | 特性开关：feature flag 动态开关，不重启生效 | P0 | 开关测试 |
| FR-CFG-03 | 限流规则：Sentinel 规则由 Nacos 下发，秒级生效 | P0 | 规则生效测试 |
| FR-CFG-04 | 配置版本历史 + 回滚 | P1 | 回滚测试 |

### 3.11 限流降级 Sentinel(FR-RL)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-RL-01 | 网关层限流：QPS/并发线程数双维度，按 API/user_id 维度 | P0 | 压测限流 |
| FR-RL-02 | 服务层限流：Dubbo provider 限流，保护下游 | P0 | 服务限流 |
| FR-RL-03 | 熔断降级：慢调用比例/异常比例触发熔断，降级返默认 | P0 | 熔断测试 |
| FR-RL-04 | 热点参数限流：按 user_id/course_id 等热点参数限流 | P1 | 热点测试 |

### 3.12 Rust 热点服务(FR-RUST)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-RUST-01 | 风控规则引擎：高频规则匹配 P95 <1ms | P0 | 基准压测 |
| FR-RUST-02 | 计费热点：订单计费高频路径 P95 <1ms | P0 | 基准压测 |
| FR-RUST-03 | 通过 FFI/gRPC 与 Java 业务中台对接 | P0 | 跨语言集成 |

### 3.13 跨语言契约对齐(FR-XLANG)

| ID | 需求 | 优先级 | 验证 |
|----|------|--------|------|
| FR-XLANG-01 | JWT RS256 + JWKS 跨语言统一验签(Java/Go/Python) | P0 | 跨语言验签 |
| FR-XLANG-02 | 缓存 key 明文、值纯 JSON snake_case，禁 sha256/DefaultTyping(ADR-T04) | P0 | 跨语言反序列化 |
| FR-XLANG-03 | 事件 Avro schema 跨语言统一 | P0 | schema 一致性 |

---

## 4. 非功能需求(SLO 量化表)

### 4.1 性能 SLO(对齐顶层 G-01..G-10)

| NFR ID | 指标 | 天花板(M3) | MVP(M1) | 测量方法 | 关联 G |
|--------|------|-----------|---------|----------|--------|
| NFR-P-01 | 峰值并发用户 | 1,000,000 CCU | 10,000 CCU | 分布式 Locust | G-01 |
| NFR-P-02 | 注册用户容量 | 3,000,000,000 | 1,000,000 | TiDB 容量规划 | G-02 |
| NFR-P-03 | 核心 API P95 | <200ms | <200ms | Prometheus histogram | G-03 |
| NFR-P-04 | 核心 API P99 | <500ms | <500ms | Prometheus histogram | G-04 |
| NFR-P-05 | 直播/互动 WS 延迟 P95 | <800ms | <800ms | WS 端到端压测 | G-05 |
| NFR-P-06 | 学习事件 ingestion 吞吐 | ≥500,000 ev/s | ≥10,000 ev/s | Disruptor 基准 + Pulsar 吞吐 | G-06 |
| NFR-P-07 | 可用性 | 99.99% | 99.9% | PromQL up + 故障注入 | G-07 |
| NFR-P-08 | 单元故障切换 | <30s | <60s | AppActive 模式演练 | G-08 |
| NFR-P-09 | Rust 热点 P95 | <1ms | <1ms | Rust bench | — |

### 4.2 组件级性能 SLO

| 组件 | 指标 | 目标 | 测量 |
|------|------|------|------|
| API 网关/BFF | 单实例 QPS | ≥50,000 | Locust 压测 |
| API 网关/BFF | 网关自身开销 P95 | <20ms | histogram |
| WS 网关 | 单节点连接数 | 500,000 | 连接压测 |
| WS 网关 | 消息广播延迟 P95 | <100ms(同 pod)/<500ms(跨 pod) | WS 压测 |
| CellRouter | 路由决策 P99 | <5ms | 链路追踪 |
| ep-rw-java | 路由切换开销 | <1ms | 微基准 |
| ep-disruptor | 单 worker publish P99 | <10μs | JMH 基准 |
| ep-disruptor | 65536 RingBuffer 吞吐 | ≥1,000,000 ev/s | JMH 基准 |
| ep-cache | getOrLoad P99(L1 hit) | <0.1ms | 微基准 |
| ep-cache | getOrLoad P99(L2 hit) | <2ms | 微基准 |
| Pulsar 事件 | publish P99 | <10ms | Pulsar 监控 |

### 4.3 可用性 SLO

| NFR ID | 指标 | 目标 | 验证 |
|--------|------|------|------|
| NFR-A-01 | 整体可用性 | 99.99%(M3)/99.9%(M1) | PromQL up 滚动 30 天 |
| NFR-A-02 | 单组件可用性 | 99.99% | 各组件独立 SLO |
| NFR-A-03 | RTO(故障恢复) | <30s(M3)/<60s(M1) | 故障注入演练 |
| NFR-A-04 | RPO(数据丢失) | 0(事务数据)/可接受(缓存) | TiDB Raft + Redis 最终一致 |
| NFR-A-05 | 无单点故障 | 所有组件多副本 + 自动 failover | 拓扑审查 |

### 4.4 安全 SLO

| NFR ID | 指标 | 目标 | 验证 |
|--------|------|------|------|
| NFR-S-01 | 鉴权绕过 | 0(任何伪造头不可绕过) | 渗透测试 |
| NFR-S-02 | JWT 破解 | RS256 2048bit 不可破解 | 密钥强度审计 |
| NFR-S-03 | 沙箱逃逸 | 0(Firecracker microVM) | 沙箱逃逸测试 |
| NFR-S-04 | 数据出境 | 海外数据 0 回流国内 | PIPL 合规审计 |
| NFR-S-05 | 审计日志完整性 | 100%(不可篡改) | 审计链验证 |

### 4.5 可维护性 SLO

| NFR ID | 指标 | 目标 | 验证 |
|--------|------|------|------|
| NFR-M-01 | 公共库测试覆盖率 | ≥80%，安全核心 100% | JaCoCo 报告(P-05) |
| NFR-M-02 | 契约测试覆盖率 | 100% 消费者驱动(P-06) | Pact 验证 |
| NFR-M-03 | 探针三分离 | 100% pod 配置(P-07) | 部署审查 |
| NFR-M-04 | ADR 完整性 | 所有架构决策有 ADR | ADR review |

### 4.6 可观测性 SLO

| NFR ID | 指标 | 目标 | 验证 |
|--------|------|------|------|
| NFR-O-01 | 全链路 trace 覆盖 | 100% 核心链路 | trace 采样验证 |
| NFR-O-02 | 日志检索延迟 | P95 <2s | Loki 查询 |
| NFR-O-03 | 指标采集延迟 | <15s | Prom scrape |
| NFR-O-04 | 告警触发延迟 | <60s | AlertManager |

---

## 5. 约束条件

### 5.1 架构原则约束(不可违反)

| 约束 ID | 约束 | 来源 |
|---------|------|------|
| C-01 | 跨单元不直接 RPC，走 Pulsar 异步 | P-02 |
| C-02 | 禁 app 层拼 tenant_id，用 JWT FGAC | P-03 |
| C-03 | 在线 TiDB 只留热数据，冷数据归 Iceberg | P-04 |
| C-04 | 公共库 ≥80% 覆盖，安全核心 100% | P-05 |
| C-05 | 契约先行(OpenAPI/IDL)，Pact 测试 | P-06 |
| C-06 | 探针三分离，timeout≥3s | P-07 |
| C-07 | 海外数据不回流国内 | P-09 / D-004 |
| C-08 | 会话状态外化 Redis，80% 上下文压缩 | P-10 / D-006 |

### 5.2 技术选型约束

| 约束 | 说明 |
|------|------|
| Java 主语言 | Disruptor/Pulsar/TiDB/Redis 重业务用 Java |
| Go 网关 | WS/实时网关用 Go(连接数优势) |
| Rust 热点 | 风控/计费热点用 Rust(P95 <1ms) |
| 复用基础设施 | TiDB/Redis/Pulsar(CDC+事件统一)/Milvus/K8s/可观测复用，不重建 |

### 5.3 合规约束

| 法规 | 约束 |
|------|------|
| PIPL(中国) | 个人信息不出境，海外独立集群 |
| GDPR(欧盟) | 数据可删除/导出接口 |
| 等保2.0 | 审计日志 + 加密 + 访问控制 |

---

## 6. 依赖关系

### 6.1 上游依赖(技术中台依赖)

| 依赖 | 提供方 | 用途 |
|------|--------|------|
| K8s 集群 | 基础设施 | 容器编排 |
| TiDB/TiFlash | 基础设施 | HTAP 在线库 |
| Redis Cluster | 基础设施 | L2 缓存/会话/路由表 |
| Pulsar | 基础设施 | 事件总线 |
| Nacos | 基础设施 | 配置中心/服务发现 |
| Loki/Prom/Tempo | 基础设施 | 可观测后端 |

### 6.2 下游依赖(依赖技术中台)

| 依赖方 | 依赖能力 | 子项目 |
|--------|---------|--------|
| 数据中台 | 事件总线/可观测/缓存契约 | SP-02 |
| 业务中台 | 鉴权/路由/读写分离/缓存/EDA/RPC | SP-03 |
| AI 中台 | 鉴权/可观测/事件总线 | SP-04 |
| IT 后端 MVP | 全套中台能力 | SP-05 |
| 三端前端 | API 契约/WS 网关 | SP-06 |
| 单元化部署 | CellRouter | SP-07 |
| 测试体系 | 可观测/契约测试基线 | SP-08 |

### 6.3 依赖关系图

```
基础设施(K8s/TiDB/Redis/Pulsar/Nacos/可观测)
        │
        ▼
   SP-01 技术中台 ──────┬──► SP-02 数据中台
        │               ├──► SP-03 业务中台
        │               ├──► SP-04 AI 中台(间接，经 SP-02)
        │               ├──► SP-05 IT 后端 MVP
        │               ├──► SP-06 三端前端(契约)
        │               ├──► SP-07 单元化部署(CellRouter)
        │               └──► SP-08 测试体系(基线)
```

---

## 7. 验收标准

### 7.1 M0 底座验收

| 验收项 | 标准 | 关联需求 |
|--------|------|---------|
| 3 个 SDK 可用 | ep-rw-java / ep-disruptor / ep-cache 单元测试全通过，覆盖率达标 | FR-RW/FR-DIS/FR-CA |
| API 网关/BFF | JWT RS256 签发+JWKS 验签链路通，AuthFilter 拦截伪造头 | FR-GW-02/03/04 |
| WS 网关 | 单节点 1 万连接稳定，跨 pod 广播送达 | FR-WS-01/03 |
| CellRouter | 路由决策正确，misroute 纠正，超限 403 | FR-CR-01/02/03 |
| 事件总线 | Pulsar 连通，topic 命名规范，lazy-init 降级 | FR-EDA-01/02/03 |
| 可观测 | 全链路 trace 可见，三后端写入 | FR-OBS-01/02/03 |
| 配置中心 | 路由表/特性开关/限流规则 Nacos 下发生效 | FR-CFG-01/02/03 |

### 7.2 M1 MVP 验收(1 万 CCU)

| 验收项 | 标准 | 关联 SLO |
|--------|------|---------|
| 1 万 CCU 压测 | Locust 分布式压测达标，核心 API P95<200ms/P99<500ms | NFR-P-01/03/04 |
| 整套架构跑通 | 三端→BFF→CellRouter→服务→中台→基础设施全链路 | — |
| 单元故障切换 | <60s 完成，无脏写 | NFR-P-08 |
| 可用性 | 99.9% 滚动 30 天 | NFR-A-01 |
| 契约测试 | Pact 全消费者验证通过 | NFR-M-02 |
| 安全测试 | 鉴权绕过 0、沙箱逃逸 0 | NFR-S-01/03 |

### 7.3 M3 天花板验收

| 验收项 | 标准 | 关联 SLO |
|--------|------|---------|
| 100 万 CCU | Locust 多节点压测达标 | NFR-P-01 |
| 30 亿用户 | TiDB 容量规划验证 | NFR-P-02 |
| 50 万 ev/s | Disruptor + Pulsar 吞吐达标 | NFR-P-06 |
| 99.99% 可用性 | 滚动 30 天 | NFR-A-01 |
| 故障切换 <30s | AppActive 演练 | NFR-P-08 |

---

## 8. 追溯矩阵

### 8.1 SLO → 需求 → 实现

| SLO | 驱动需求 | 实现组件 | ADR |
|-----|---------|---------|-----|
| G-01(100万CCU) | NFR-P-01 | CellRouter/网关/WS | ADR-T01 |
| G-02(30亿用户) | NFR-P-02 | CellRouter+TiDB 分表 | D-008/D-015 |
| G-03/04(API P95/P99) | NFR-P-03/04 | 读写分离/多级缓存 | ADR-T02/T04 |
| G-05(直播延迟) | NFR-P-05 | WS 网关 | — |
| G-06(50万ev/s) | NFR-P-06 | Disruptor | ADR-T03 |
| G-07(99.99%) | NFR-A-01 | 探针三分离/多副本 | ADR-T05 |
| G-08(故障<30s) | NFR-P-08 | CellRouter 流量纠正 | ADR-T01 |

### 8.2 ADR → 需求

| ADR | 驱动需求 |
|-----|---------|
| ADR-T01 | FR-CR-01..05 |
| ADR-T02 | FR-RW-01..08 |
| ADR-T03 | FR-DIS-01..09 / FR-EDA-01..07 |
| ADR-T04 | FR-CA-01..10 / FR-XLANG-02 |
| ADR-T05 | NFR-A-01/NFR-M-03 |

---

**文档结束**

> 本 SRS 是 SP-01 技术中台的需求基线。任何需求变更须经架构委员会评审，更新本文档并同步 ADR。下游 SAD/SDD/测试/部署文档以本 SRS 为需求锚点。
