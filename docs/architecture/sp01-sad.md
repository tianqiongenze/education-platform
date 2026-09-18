# SP-01 技术中台 —— 软件架构设计文档(SAD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp01` for reference continuity. Per D-137, sp01 → user. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP01-SAD-001 | v1.0 | 2026-08-03 | Approved | ZCode(文档工程师) | EP-TOGAF-001 §6 技术架构 / EP-SPEC-001 §3 / SP01-SRS-001 |

> 本文是 SP-01 技术中台的**架构设计基线**，按 TOGAF ADM 四架构域(业务/数据/应用/技术)对齐顶层企业架构，定义组件架构、关键 ADR(T01-T05)、接口契约、部署拓扑、安全设计。下游 SDD/测试/部署文档以本文为架构锚点。

---

## 目录

1. [TOGAF 四域对齐](#1-togaf-四域对齐)
2. [组件架构图](#2-组件架构图)
3. [关键 ADR(T01-T05)](#3-关键-adrt01-t05)
4. [接口契约](#4-接口契约)
5. [部署拓扑](#5-部署拓扑)
6. [安全设计](#6-安全设计)
7. [可观测架构](#7-可观测架构)
8. [追溯矩阵](#8-追溯矩阵)

---

## 1. TOGAF 四域对齐

### 1.1 业务架构对齐(Business)

技术中台不直接承载业务能力，而是为**所有事业群业务能力**提供通用技术底座。业务能力 → 技术中台能力映射：

| 业务能力(EP-TOGAF §3.1) | 中台/BU 归属 | 技术中台提供能力 |
|------------------------|------------|----------------|
| 注册登录与多端会话 | UCM 中台 | JWT RS256 签发/验签、WS 网关会话 |
| 选课购课 | OPM 中台 | API 网关路由、读写分离、限流 |
| 视频学习与进度跟踪 | 学习记录(单元化) | Disruptor ingest、读写分离、缓存 |
| 代码作业提交与评测 | 作业+评分平台 | 沙箱(经 IT 后端)、事件总线 |
| 直播学习与互动 | 直播子平台 | WS 网关、Pulsar 跨pod广播、事件总线 |
| AI 答疑与辅导 | AI 中台 | API 网关、可观测、事件总线 |
| 权限/审计/风控 | 平台管理 | 鉴权、审计日志、Rust 热点风控 |
| 单元运维与流量调度 | 平台管理 | CellRouter、Nacos 路由表 |

### 1.2 数据架构对齐(Data)

技术中台自身不持有业务数据，但管理以下**数据流转与存储**：

| 数据资产 | 归属 | 技术中台角色 |
|---------|------|------------|
| 路由表(cell 映射) | 技术中台 | Nacos 配置 + Redis 缓存 |
| JWKS 公钥集 | 技术中台 | Redis 缓存 + 端点暴露 |
| 会话状态 | 技术中台 | Redis Cluster(`zcode:session:*`，D-006) |
| 缓存数据(L1+L2) | 技术中台 | Caffeine + Redis Cluster |
| 事件流 | 技术中台 | Pulsar topic `{domain}.{action}` |
| 审计日志 | 技术中台 | Loki(不可篡改) |
| 链路 trace | 技术中台 | Tempo |

技术中台**不持有**业务实体(用户/订单/课程等)，这些归各业务中台/事业群，技术中台仅提供数据访问通道(读写分离 SDK、缓存契约)。

### 1.3 应用架构对齐(Application)

技术中台在 EP-TOGAF §5.1 应用组件总览中对应**BFF/网关层**与**四大中台之技术中台**层：

```
多端接入层(web/h5/小程序)
        │
        ▼
┌──── BFF / API 网关层(技术中台) ────┐
│  JWT RS256+JWKS │ CellRouter │ Sentinel │ 灰度 │
│  Java(Netty edge + Disruptor + Spring WebMVC) + Go WS 网关 │  ← D-032 / ADR-T07(rev2)
└────────────────┬───────────────────┘
                 │
        ┌────────┼────────┐
        ▼        ▼        ▼
   全局域服务  用户域服务  实时域服务
        │        │        │
        └────────┼────────┘
                 ▼
   四大中台(技术/数据/业务/AI) —— 技术中台是底座之一
                 │
                 ▼
            事业群业务服务
                 │
                 ▼
            基础设施层
```

### 1.4 技术架构对齐(Technology)

对齐 EP-TOGAF §6 技术架构，技术中台复用全部基础设施：

| 基础设施 | 复用方式 | 技术中台组件 |
|---------|---------|------------|
| K8s 1.28+ | Deployment/HPA/PDB | 所有组件 |
| TiDB 8.5 LTS | 读写分离目标(对齐 ESP v8.5.7 验证) | ep-rw-java |
| Redis Cluster 8.0 | L2 缓存/会话/路由表/JWKS(真集群 3主3从→30节点, 对齐 ESP 实际 redis:8.0-alpine) | ep-cache/网关/CellRouter |
| Pulsar 3.3.x | 事件总线+CDC 统一消息基础设施(承接 ESP ADR-02, CDC 总线亦走 Pulsar, 彻底不引入 Kafka; 4.x 升级延迟至 v2.0 — ADR-T11) | ep-cache 广播/EDA/CDC |
| Nacos 2.4.3 | 配置/服务发现(pin ESP 验证版, 不升 v3) | CellRouter/网关/Sentinel |
| Dubbo 3.3.6 | Triple RPC(契约先行, 禁 STRICT 序列化 — ADR-T09, pin ESP 验证版) | 服务间 RPC |
| Loki/Prom/Tempo | 可观测后端(承接 ESP 双层 Collector) | OTel SDK |
| Sentinel 1.8+ | 限流降级(规则走 Nacos 分布式下发, 禁本地内存) | 网关+服务双层 |
| Spring Cloud | 2025.1.2 + SCA 2025.1.0.0（D-032 全栈锁定 SCA+Dubbo, 禁 Spring Cloud Netflix/SCG 原生组件） | BFF/服务治理 |
| Litestar+Granian | Python AI 服务(承接 ESP ADR-09 Rust ASGI, Granian ≥1.6.0 — ESP 实际解析 2.7.9) | AI 中台 |

---

## 2. 组件架构图

### 2.1 技术中台整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          多端接入(web/h5/小程序)                          │
│                  共享 API 契约(OpenAPI 3) + W3C traceparent              │
└───────────────────────────────────────┬─────────────────────────────────┘
                                        │ HTTPS/WSS
┌───────────────────────────────────────▼─────────────────────────────────┐
│                        API 网关/BFF(Java Netty edge + Disruptor + Spring WebMVC) │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │
│  │AuthFilter│ │RouteFilter│ │Sentinel │ │TraceFilter│ │GrayFilter│       │
│  │JWT RS256 │ │Nacos 动态 │ │QPS/并发 │ │OTel 注入 │ │灰度分流  │       │
│  │JWKS 验签 │ │路由配置   │ │限流降级 │ │traceparent│ │1%→10%→100│      │
│  └────┬─────┘ └────┬─────┘ └────┬────┘ └────┬─────┘ └────┬────┘       │
│       │            │            │           │            │             │
│       └────────────┴────────────┴───────────┴────────────┘             │
│                                    │                                    │
│  access log(JSON) + business log(JSON) → Loki                          │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
            ┌────────────────────────┼────────────────────────┐
            │                        │                        │
┌───────────▼──────────┐  ┌──────────▼──────────┐  ┌──────────▼──────────┐
│  WS/实时网关(Go)      │  │  CellRouter(Java)    │  │  服务治理 Dubbo     │
│  长连接 50万/节点      │  │  hash(user_id)%N     │  │  Triple(HTTP/2)     │
│  fnv1a(session)%N     │  │  流量纠正(上限3)      │  │  Nacos 注册发现     │
│  分片广播             │  │  写挂起(AppActive)   │  │  Pact 契约测试      │
│  Pulsar 跨pod广播     │  │  路由表 Nacos+Redis  │  │  超时/重试          │
│  JWT 握手鉴权         │  │  就近写              │  │  跨单元禁直接RPC    │
└───────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘
            │                        │                        │
            └────────────────────────┼────────────────────────┘
                                     │
        ┌────────────────────────────┼────────────────────────────┐
        │                            │                            │
┌───────▼───────┐  ┌────────────────▼────────────────┐  ┌────────▼────────┐
│ 读写分离 SDK   │  │ 多级缓存 SDK                     │  │ 高并发 ingest   │
│ ep-rw-java    │  │ ep-cache                         │  │ ep-disruptor    │
│ (已实现 v1.0) │  │ (已实现 v1.0)                    │  │ (已实现 v1.0)   │
│               │  │                                  │  │                 │
│ @DataSource   │  │ L1 Caffeine(10000,60s)           │  │ RingBuffer 65536│
│ READ/WRITE/   │  │ L2 Redis Cluster                 │  │ tryNext drop    │
│ consistent/   │  │ EMPTY_SENTINEL 防穿透             │  │ batch flush     │
│ tiflash       │  │ Pulsar 广播失效                   │  │ PublishResult   │
│               │  │ 延迟双删 100ms                    │  │ DisruptorPipeline│
│ ThreadLocal   │  │ 降级 no-op                        │  │ DisruptorStats  │
│ AbstractRouting│ │ EventPublisher                   │  │ 5 WaitStrategy  │
│ LazyConnProxy │  │                                  │  │                 │
└───────┬───────┘  └────────────────┬─────────────────┘  └────────┬────────┘
        │                           │                             │
        ▼                           ▼                             ▼
   TiDB Primary/Follower      Redis Cluster                  Pulsar
   TiFlash(OLAP)              (L2/会话/路由/JWKS)            (事件总线)
        │                           │                             │
        │                    ┌──────▼──────┐                      │
        │                    │ CacheBroadcast│                     │
        │                    │ (Pulsar 失效) │                     │
        │                    └──────┬──────┘                      │
        │                           │                             │
        └───────────────────────────┴─────────────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
┌───────▼───────┐  ┌────────────────▼────────────────┐  ┌────────▼────────┐
│ 配置中心 Nacos │  │ 可观测 OTel+Loki+Prom+Tempo     │  │ Rust 热点服务    │
│ 路由表/开关/   │  │ edge Collector(tail-sampling)   │  │ 风控规则 P95<1ms │
│ 限流规则       │  │ gateway Collector                │  │ 计费热点 P95<1ms│
│ 版本+回滚      │  │ Grafana+AlertManager             │  │ FFI/gRPC 对接   │
└───────────────┘  └─────────────────────────────────┘  └─────────────────┘
```

### 2.2 请求处理流程

```
客户端请求
    │
    ▼
[Netty edge + Disruptor ingress + Spring WebMVC]
    │
    ├─ AuthFilter: 提取 JWT → JWKS 验签 → 注入 user_id/tenant_id 到 header(覆盖任何客户端头)
    ├─ TraceFilter: 注入/传递 W3C traceparent
    ├─ GrayFilter: 按 user_id hash 灰度分流
    ├─ SentinelFilter: QPS/并发限流
    ├─ RouteFilter: 路由到目标服务
    │
    ▼
[CellRouter] (用户域请求才经过)
    │
    ├─ cell = hash(user_id) % N (Nacos 路由表)
    ├─ 检查 misroute: 请求到达的 cell vs 计算 cell
    │   ├─ 一致 → 放行
    │   └─ 不一致 → 纠正计数器++ (上限3)
    │       ├─ <3 → 重路由到正确 cell
    │       └─ >=3 → 403 Forbidden + 告警
    ├─ failover 检查: 该 user_id 是否在写挂起名单
    │   ├─ 否 → 放行
    │   └─ 是 → 写操作挂起(返 503 + Retry-After)
    │
    ▼
[目标服务(用户域/全局域/实时域)]
    │
    ├─ @DataSource(READ/WRITE/consistent/tiflash) → RoutingDataSource 路由
    ├─ @Cacheable / CacheService.getOrLoad → L1 Caffeine → L2 Redis → loader
    ├─ 高频写 → DisruptorWorker.publish → batch flush
    ├─ 跨服务事件 → EventPublisher.publish → Pulsar
    ├─ Dubbo Triple RPC → Nacos 发现 → 调用
    └─ OTel trace 全链路记录
    │
    ▼
[基础设施: TiDB/Redis/Pulsar/Nacos]
```

---

## 3. 关键 ADR(T01-T05)

### 3.1 ADR-T01 单元路由器内置流量纠正(字节模式)

| 项 | 内容 |
|----|------|
| **背景** | failover/路由表变更期，misroute 会导致请求到达错误 cell，引发跨单元脏写 |
| **决策** | CellRouter 在请求 context 内嵌纠正计数器(上限 3)，检测错路由自动重路由到正确 cell；超限返 403；存储层按 user_id 拦截错单元写 |
| **理由** | failover 期间必须防止脏写；纠正而非直接拒绝可提升用户体验；存储层兜底是最后防线 |
| **收益** | failover 不脏写；恢复 <30s(G-08)；纠正对用户透明 |
| **依据** | 字节 InfoQ 单元架构、阿里 AppActive |
| **状态** | Accepted |
| **关联需求** | FR-CR-01..05 |

### 3.2 ADR-T02 读写分离 4 模式契约

| 项 | 内容 |
|----|------|
| **背景** | 单库在 100 万并发下不可行，TiDB Primary/Follower 分离需明确路由规则 |
| **决策** | 4 模式：READ→Follower 池；WRITE→Primary；READ+consistent→强制 Primary(防读后写)；READ+tiflash→TiFlash 列存(OLAP) |
| **理由** | 读多写少场景读分散到 Follower；强一致读场景走 Primary；OLAP 走 TiFlash 不影响在线 |
| **实现** | `@DataSource` 注解 + ThreadLocal + AbstractRoutingDataSource + LazyConnectionDataSourceProxy |
| **配合** | `SET SESSION tidb_read_consistency='strong'`(读池) + `@Version` 乐观锁 |
| **依据** | ESP esp-rw-java 验证(已实现 v1.0.0，11 tests pass，97.7% coverage) |
| **状态** | Accepted |
| **关联需求** | FR-RW-01..08 |

### 3.3 ADR-T03 Disruptor 用于高频写 ingest，Pulsar 用于跨服务事件

| 项 | 内容 |
|----|------|
| **背景** | 学习事件/互动等高频写(目标 50 万 ev/s)直接落 DB 会压垮；跨服务事件需解耦 |
| **决策** | 高频写走进程内 Disruptor(无网络跳)，批量 flush 到 DB；跨服务事件走 Pulsar |
| **边界** | Disruptor=单服务内削峰；Pulsar=跨服务解耦。两者不可混淆 |
| **实现** | RingBuffer 65536，tryNext drop-on-full，batch 200 条/500ms flush |
| **依据** | ESP ep-disruptor 验证(已实现 v1.0.0，9 tests pass，93% coverage) + D-011/D-013 |
| **状态** | Accepted |
| **关联需求** | FR-DIS-01..09 / FR-EDA-01..07 |

### 3.4 ADR-T04 多级缓存契约(跨语言对齐)

| 项 | 内容 |
|----|------|
| **背景** | 多语言(Java/Go/Python)共享 Redis 缓存，若 key/value 格式不一致会引发跨语言反序列化炸弹 |
| **决策** | key 明文 `{bu}:{svc}:{entity}:{id}`(禁 sha256，不可调试)；值=纯 JSON snake_case(禁 Java DefaultTyping)；失效=有状态多副本走 Pulsar 广播+延迟双删，无状态走本地驱逐+TTL；全局 cache_name 注册表防碰撞 |
| **理由** | ESP 曾因 DefaultTyping 导致定时跨语言炸弹；sha256 key 不可调试 |
| **实现** | L1 Caffeine(maximumSize=10000, 60s) + L2 Redis Cluster；EMPTY_SENTINEL 防穿透；CacheBroadcast via Pulsar |
| **依据** | ESP ep-cache 验证(已实现 v1.0.0，31 tests pass，95.8% coverage) + ESP 多级缓存架构对齐文档 |
| **状态** | Accepted |
| **关联需求** | FR-CA-01..10 / FR-XLANG-02 |

### 3.5 ADR-T05 探针三分离 + HPA 稳定窗(ESP 血泪)

| 项 | 内容 |
|----|------|
| **背景** | ESP 早期 liveness timeout=1s/failureThreshold=1 曾级联杀 pod，导致雪崩 |
| **决策** | startup/liveness/readiness 三探针分离；timeout≥3s；failureThreshold≥5；HPA autoscaling/v2：scaleUp stabilization=0(激进扩)，scaleDown=120s/25%(防抖缩) |
| **理由** | 慢启动需 startup 容忍；liveness 不轻易杀(防雪崩)；readiness 控流量；HPA 防抖避免反复扩缩 |
| **实现** | K8s manifest 三探针 + HPA v2 |
| **依据** | ESP 架构评分与重构方向 + EP-TOGAF §6.6 |
| **状态** | Accepted |
| **关联需求** | NFR-A-01 / NFR-M-03 |

### 3.6 ADR-T06 不引入 Seata，分布式一致性由幂等键 + 乐观锁 + Pulsar at-least-once 保证(ESP 踩坑)

| 项 | 内容 |
|----|------|
| **背景** | ESP 引入 Seata AT 模式后，SCA 2025.1.0.0 → spring-boot-webclient:4.0.0 → CodecsAutoConfiguration 与 SB3.5.16 bean 冲突，需 `allow-bean-definition-overriding=true` 绕过；Seata 2.5.0+ 升级受阻（需 jdk17 variant），与当前 SB 4.0.7/Jetty 17 体系不兼容。Seata 全局锁在高并发写场景成为吞吐瓶颈。 |
| **决策** | **不引入 Seata**。分布式一致性通过以下组合保证：<br>① **幂等键**：写请求携带 client-side UUID，Redis SETNX + TiDB 唯一索引双写去重；<br>② **乐观锁**：领域聚合根 `@Version` 字段，CAS 更新，冲突重试 ≤3 次；<br>③ **Pulsar at-least-once + 幂等消费者**：事件 `event_id` 雪花 ID 去重，消费者维护 24h 已处理集合（Redis Bitmap）；<br>④ **Saga 编排**：跨服务长事务用 Pulsar 事件 choreography（非 Seata TCC/Saga），补偿事件显式定义。 |
| **理由** | Seata AT 的全局锁在 100 万 CCU 场景下是单点瓶颈；SCA bean 冲突是 Spring 生态已知问题，override 仅是 workaround；Pulsar at-least-once + 幂等消费者已能覆盖 99.99% 的一致性需求，且无侵入式代理；乐观锁对领域模型友好（DDD 聚合根天然支持 Version）。 |
| **实现** | `IdempotentInterceptor`（Java）/ `idempotency_middleware`（Python Litestar）/ `IdempotencyFilter`（Go）；`@Version` JPA 注解；Pulsar consumer `dedup_window=24h`；Saga 事件定义在 `EventTopics.java`。 |
| **依据** | ESP ADR-11（Seata SCA bean 冲突踩坑）+ EP-TOGAF §6.1 中间件选型 |
| **状态** | Accepted |
| **关联需求** | NFR-A-01 / NFR-S-01..03 |

### 3.7 ADR-T07(rev2) BFF 使用自建 Netty edge + Disruptor + Spring WebMVC，禁用 Spring Cloud Gateway(D-032)

| 项 | 内容 |
|----|------|
| **背景** | EP 初版 ADR-T07(已废弃) 曾沿用 SCG 2025.1.2 作为 BFF 网关，与 ESP 的 Netty edge 路线不一致。2026-08-04 用户指令(D-032/铁律6)：整个项目群必须使用 Spring Cloud Alibaba + Dubbo 及其组件/中间件，**禁用 Spring Cloud Gateway 等 Spring Cloud 原生组件**。ESP 项目已验证移除 SCG、改用自建 Netty edge 接入层 + Spring WebMVC(非 WebFlux)的可行性。 |
| **决策** | BFF 层架构变更为：<br>① **自建 Netty edge 接入层**：替代 SCG 作为 HTTP 边缘入口，负责 SSL 终止、连接管理、协议升级；<br>② **LMAX Disruptor ingress**：Netty → Disruptor RingBuffer(65536) → batch dispatch，线程模型无锁化、零拷贝；<br>③ **Spring WebMVC**(非 WebFlux)：Filter 链(AuthFilter/JWKS/Sentinel/Trace/GrayFilter)基于 WebMVC HandlerInterceptor，对 Dubbo Triple 事务传播友好(WebFlux 响应式栈与 Dubbo 阻塞式 RPC 混用易踩坑)；<br>④ **WS/实时网关仍用 Go 1.25 独立部署**(SCG/Netty 不擅长长连接，Go goroutine 模型更适合 50 万连接/节点)。 |
| **理由** | ① **SCG 属 Spring Cloud 原生组件**，与铁律6"禁用 Spring Cloud 原生、统一 SCA+Dubbo"冲突；SCG 依赖 spring-cloud-gateway-server 与 Reactor Netty，引入额外响应式栈依赖；<br>② **WebMVC 对 Dubbo 事务传播友好**：Dubbo Triple 是阻塞式 RPC，WebMVC 的线程模型天然兼容；WebFlux 响应式栈下 Dubbo 调用需 `.block()` 包裹，易触发 `IllegalStateException: block()/blockFirst()/blockLast() are blocking` 报错(ESP 踩坑)；<br>③ **Disruptor ingress 已在 ep-disruptor 4.0.0 验证**：65536 RingBuffer + drop-on-full + batch flush，ingest 性能远超 SCG Filter 链逐请求处理；<br>④ **Netty edge 性能 ≥ SCG**：SCG 底层即 Reactor Netty，自建 Netty edge 省去 SCG Filter 编排开销，直接掌探单连接级背压；<br>⑤ ESP 验证 Netty edge + WebMVC 可行，EP 复用其 bff-java 骨架。 |
| **实现** | `bff-java/` 模块：Netty edge(`NettyEdgeServer`)+ Disruptor ingress(`GatewayRingBuffer`)+ WebMVC Filter 链；路由配置仍由 Nacos 动态下发(dataId=`ep-gateway-routes`)，但解析逻辑从 SCG `RouteLocator` 迁移为自建 `RouteTable`；Go WS 网关 `gateway-go/` 不变。详见 `docs/design/sp01-sdd.md §5 API 网关/BFF`。 |
| **依据** | 用户指令 D-032(2026-08-04) + ESP `services/bff-java/pom.xml` 验证(Netty 4.1.136.Final, 无 SCG 依赖) + 铁律6 SCA+Dubbo 技术栈锁定 |
| **状态** | Accepted (supersedes ADR-T07 rev1) |
| **关联需求** | FR-GW-01..08 / NFR-A-01 |
| **版本矩阵** | Netty 4.1.136.Final + Disruptor 4.0.0 + Spring WebMVC(Spring Boot 4.0.7) + SCA 2025.1.0.0 + Dubbo 3.3.6(D-032) |

### 3.8 ADR-T08 Sentinel 限流规则走 Nacos 分布式下发，禁本地内存(ESP 踩坑)

| 项 | 内容 |
|----|------|
| **背景** | ESP 早期 Sentinel 规则仅存本地内存，多实例规则不一致导致部分实例过载。后改为 Nacos 动态数据源，但曾因 Nacos 配置格式错误导致规则全量失效。 |
| **决策** | Sentinel 1.8+ 规则**必须走 Nacos 分布式数据源**，禁止 `loadRules()` 本地内存加载。规则变更经 Nacos 控制台 → 全实例秒级生效。CI 校验 Sentinel 规则 JSON schema 合法性。 |
| **理由** | 本地规则在多实例环境下不可维护；Nacos 数据源提供 watch 机制，规则变更秒级推送；但需 schema 校验防止格式错误导致全量失效（ESP 血泪）。 |
| **实现** | `sentinel-datasource-nacos` 依赖；`@SentinelResource` 注解；Nacos dataId=`ep-sentinel-rules.json`；CI 步骤 `sentinel-rule-validator`。 |
| **依据** | ESP 踩坑记录 P-14 + EP-TOGAF §6.1 |
| **状态** | Accepted |
| **关联需求** | NFR-A-01 / NFR-S-04 |

### 3.9 ADR-T09 Dubbo Triple 禁用 STRICT 序列化，统一 Protobuf 契约(ESP 踩坑)

| 项 | 内容 |
|----|------|
| **背景** | ESP 使用 Dubbo 3.3.6 Triple 协议时，默认序列化在某些场景退化为 `STRICT`（严格 Java 原生序列化），导致 Go/Python 消费方反序列化失败（`ClassCastException` / `ClassNotFoundException`），且 STRICT 模式携带 Java 类信息存在反序列化攻击面。ESP 踩坑：Java 服务间工作正常，跨语言 Go 网关消费 Triple 报 `java.lang.ClassNotFoundException`。 |
| **决策** | Dubbo Triple RPC **统一使用 Protobuf 契约**（`.proto` IDL → 多语言生成），禁用 `STRICT` 序列化与 Java 原生序列化。dubbo 配置 `serialization=protobuf`，Triple stub 由 `protoc` 生成。用户 ID 等跨语言字段统一 `string` 类型（对齐 ADR-T04 user_id string 铁律）。 |
| **理由** | Protobuf 是跨语言中立契约，Go/Python/Java 均可生成 stub；STRICT 模式仅限 Java 生态且存在安全风险。ESP 验证 Protobuf Triple 可行但配置易错，EP 从 Day 1 固化 `.proto` 契约先行流程。 |
| **实现** | `proto/` 目录集中管理 IDL；CI `proto-compile` 步骤生成 Java/Go/Python stub；Dubbo `application.yml` 强制 `dubbo.protocol.serialization=protobuf`；禁止 `generic` 调用使用 Java 序列化。 |
| **依据** | ESP 踩坑 P-10 Dubbo Triple STRICT + EP-TOGAF §6.1 Dubbo 3.3.6 pin |
| **状态** | Accepted |
| **关联需求** | FR-RPC-01 / NFR-S-04 |

### 3.10 ADR-T10 大表分区策略：高频写表强制 RANGE 分区(ESP session_turns 82M OOM 血泪)

| 项 | 内容 |
|----|------|
| **背景** | ESP `session_turns` 表（AI 对话轮次记录）日均增长 82M 行，单表无分区导致 TiDB 查询 OOM 16 次（TiDB Server 内存溢出重启），临时用 8-shard×2-worker 停损但未根治。根因：单表无分区，TiDB GC 压缩扫描全表，查询计划退化为全表扫。 |
| **决策** | EP 所有高频写表（日志/对话轮次/事件审计/行为流水）**Day 1 强制 RANGE 分区**，分区键为时间（`created_at`），按日/周分区。TiDB 建表 DDL 包含 `PARTITION BY RANGE (UNIX_TIMESTAMP(created_at))`。分区保留策略：热分区（7d）+ 温分区（30d）+ 冷分区归档 Iceberg。 |
| **理由** | TiDB 分区裁剪（partition pruning）将查询限制在单分区，避免全表扫 OOM；ESP 82M 行单表是前车之鉴。EP 天花板 30 亿用户，对话轮次表日均增长可达 10 亿级，无分区必然 OOM。 |
| **实现** | ① DDL 规范：`ep-ddl-convention` CI 检查所有建表语句，高频写表无 `PARTITION BY` → 编译失败；② 分区管理：`tidb-partition-manager` 定时任务自动创建未来分区 + 归档过期分区；③ 监控：单分区行数 >5000 万告警。 |
| **涉及表** | `session_turns`（AI 对话）、`event_audit_log`（事件审计）、`learning_behavior`（行为流水）、`grading_record`（批改记录）、`live_chat_log`（直播弹幕） |
| **依据** | ESP 踩坑 P-17 session_turns 82M OOM + EP 数据中台 sp02-sad §分区策略 |
| **状态** | Accepted |
| **关联需求** | NFR-P-01 / NFR-D-02 |

### 3.11 ADR-T11 Pulsar 3.3.x 当前版本 + 4.x 升级路线图(ESP 延迟决策)

| 项 | 内容 |
|----|------|
| **背景** | ESP 使用 Pulsar 3.3.x 作为事件总线（ADR-02），验证可行。Pulsar 4.0 LTS 已发布，引入轻量级 broker、改进的 schema registry、增强的 geo-replication。ESP 在升级评估中因资源限制未完成，留为 TODO。EP 已将 CDC 总线从 Kafka 统一到 Pulsar（ADR-D01），消息基础设施单一化，升级影响面更集中。EP 需明确当前版本与升级路径。 |
| **决策** | EP 当前**锁定 Pulsar 3.3.x**（对齐 ESP 验证版本，不冒险升 4.x），在 v2.0 大版本（预计 2027 Q1）评估升级 Pulsar 4.x LTS。升级前提：① Pulsar 4.x 至少有一个 minor release 稳定运行 6 个月；② CDC sink（TiCDC→Pulsar）兼容性验证通过；③ Geo-replication 行为不变；④ 内置 Schema Registry 向后兼容（Avro/JSON）。 |
| **理由** | 3.3.x 已被 ESP 验证，EP 复用集群基础设施不应冒险。4.x 的改进（轻量 broker）对 EP 当前规模非关键路径。升级风险 > 收益，延迟到 v2.0 降低项目初期不确定性。 |
| **实现** | Helm chart `pulsar-3.3.x-pinned`；`upgrade-pulsar-4x.md` 路线图文档（v2.0 backlog）；CI 冒烟测试覆盖 Pulsar producer/consumer/geo-rep。 |
| **依据** | ESP ADR-02 Pulsar 选型 + ESP TODO 升级评估 + EP-TOGAF §6.1 |
| **状态** | Accepted（3.3.x 锁定）/ Deferred（4.x 升级） |
| **关联需求** | FR-EDA-01 / NFR-A-01 |

### 3.12 ADR-T12 全球部署数据库选型评估：TiDB 为主，CockroachDB 为海外可选替代（用户指令新增）

| 项 | 内容 |
|----|------|
| **背景** | EP 目标 30 亿用户 / 100 万并发，国内多活（华东/华北/华南三角）+ 海外独立集群（新加坡/法兰克福，PIPL 数据不回流）。当前全球数据库策略为 TiDB 8.5 LTS（D-015 用户域 `user_id` 分表 + Placement Rules 跨区 Raft，Primary + 2 Followers 跨 2/3 区域）。用户指令要求同步评估 CockroachDB 作为全球部署数据库基础设施的可选方案。 |
| **决策** | **国内主库继续锁定 TiDB 8.5 LTS**（已对齐 ESP v8.5.7 3-region Raft 验证，HTAP 一体化 TiFlash + TiCDC→Pulsar CDC 链路已落地）；**海外独立集群可选 CockroachDB** 作为 TiDB 的替代方案，在 SP-07（M2-M3）多区域阶段做 PoC 验证后定夺。海外集群无论选 TiDB 还是 CockroachDB，均**独立部署、数据不回流**（PIPL 合规），跨集群数据同步走 Pulsar geo-replication + Flink 聚合，**不依赖数据库层跨集群复制**。 |
| **CockroachDB 优势** | ① **原生多区域 SQL 抽象**：`REGIONAL BY ROW` 按行归属区域、`GLOBAL` 表跨区强一致低延迟读、`SURVIVE REGION FAILURE` 生存目标，声明式管理远比 TiDB Placement Rules 简洁；② **PostgreSQL 协议兼容**：海外团队若已有 PG 技术栈，接入成本低（TiDB 兼容 MySQL 协议）；③ **Follower Reads**：就近副本读，跨区读延迟可降至 <10ms（需 `AS OF SYSTEM TIME`）；④ **Super Regions + Data Domiciling**：原生数据驻留能力，天然适配 PIPL/GDPR 合规场景；⑤ **多活写入**：multi-active 模型，任意节点可读写，无单点 Primary 瓶颈（TiDB 写入集中在 Primary Region）。 |
| **CockroachDB 劣势/风险** | ① **无 HTAP 一体化**：无 TiFlash 等列存引擎，OLAP 分析需外挂（EP 已有 Flink+Iceberg 湖仓，影响可控但在线 HTAP 查询缺失）；② **无原生 CDC → Pulsar**：CockroachDB CDC（Changefeeds）直发 Kafka/Webhook，需适配层对接 Pulsar（增加运维复杂度）；③ **写入延迟**：跨区 Raft 写入延迟 = 2×RTT（`REGIONAL` 表）或 3×RTT（`GLOBAL` 表），`--max-offset` 限制 250ms，跨大洲写入 P99 可能 >500ms；TiDB Placement Rules 可精确控制副本位置，写入延迟更可控；④ **生态成熟度**：TiDB 在国内互联网场景验证更充分（ESP 已验证），CockroachDB 国内大规模生产案例较少；⑤ **单元化适配**：EP 混合单元化架构（D-012）以 `user_id` 分片为核心，TiDB `AUTO_RANDOM` + `PRE_SPLIT_REGIONS` + `SPLIT TABLE` 已有完整方案，CockroachDB 需重新设计 range 分裂策略。 |
| **TiDB 保持主选的理由** | ① ESP v8.5.7 已验证 3-region Raft 强一致；② TiFlash HTAP 在线列存，SP-02 数据中台冷热分层依赖；③ TiCDC→Pulsar CDC 链路已落地（ADR-D01），换 CockroachDB 需重建 CDC；④ Placement Rules 精细副本控制适配单元化；⑤ 国内技术支持生态成熟。 |
| **海外可选 CockroachDB 的理由** | ① 海外集群独立部署，无 HTAP 需求（分析走 Pulsar→Flink→Iceberg 统一链路）；② PG 协议兼容降低海外团队接入门槛；③ `REGIONAL BY ROW` + Super Regions 天然适配 PIPL 数据驻留；④ 多活写入适合海外多区域低延迟写入场景；⑤ PoC 验证不阻塞主线路径（M2-M3 才启动）。 |
| **PoC 验证项（SP-07 M2-M3）** | ① CockroachDB 22.2+ LTS 部署新加坡+法兰克福双区域；② `REGIONAL BY ROW` 按 `user_id` 分片性能（对标 TiDB `AUTO_RANDOM` 分表）；③ Changefeeds → Pulsar 适配层开发与验证；④ 跨区写入 P99 延迟基准（目标 <300ms）；⑤ 100 万连接压测（对标 gateway-go 单节点 50 万连接）；⑥ PIPL 数据驻留审计验证。 |
| **结论** | 国内主库 TiDB 8.5 LTS 不变；海外集群在 SP-07 阶段做 CockroachDB PoC，PoC 通过则海外用 CockroachDB，否则海外也用 TiDB 独立集群。**不改变 MVP（M1）单 cell TiDB 路径**。 |
| **依据** | CockroachDB 官方文档（multi-region overview / survival goals / regional by row / super regions）+ TiDB 8.5 LTS Placement Rules + ESP v8.5.7 验证 + ADR-D01 CDC 链路 + D-012 混合单元化 + D-004 PIPL 海外隔离 |
| **状态** | Accepted（TiDB 国内主选）/ Proposed（CockroachDB 海外 PoC，SP-07 M2-M3 验证） |
| **关联需求** | NFR-P-01（100 万 CCU）/ NFR-P-07（99.99%）/ D-004（PIPL 海外隔离）/ D-012（混合单元化） |

### 3.13 ADR-T13 Spring Boot 4.0 迁移铁律清单 + 依赖 scope 规范（ESP 7 大破坏性变更穷尽吸收）

| 项 | 内容 |
|----|------|
| **背景** | ESP 从 SB3.5 迁移到 SB4.0.7 + SCA 2025.1.0.0 时实测踩过 **7 个破坏性变更**（PROGRESS.md L1108-1156 记录），每个都导致编译失败或运行时 CrashLoop。EP 从 Day 1 即用 SB4.0.7，必须预先规避所有已知坑。 |
| **决策** | EP 全项目群 Java 模块强制遵守以下 SB4 迁移铁律清单（CI 编译检查 + 依赖 scope 检查）： |
| **SB4 破坏性变更清单（7 项，全部从 ESP 吸收）** | |
| ① Undertow 移除 → Jetty 12.1.10 | SB4 移除 `spring-boot-starter-undertow`，Web 容器统一 Jetty 12.1.10（Eclipse EE 10 API）。EP 全量用 `spring-boot-starter-web`（内含 Jetty），**禁引 Undertow/Starter-Undertow**。 |
| ② `@MockBean` → `@MockitoBean` | SB4 废弃 `@MockBean`/`@SpyBean`，改用 `@MockitoBean`/`@MockitoSpyBean`（Spring Framework 6.2+ 新注解）。EP 测试代码 CI 扫描禁用 `@MockBean`，统一 `@MockitoBean`。 |
| ③ `@WebMvcTest` 包迁移 | SB4 中 `@WebMvcTest` 等切片注解的 auto-config 类从 `org.springframework.boot.test.autoconfigure.web.servlet` 迁移到 `org.springframework.boot.test.context.SpringBootTest` 命名空间。EP 测试 import 语句 CI 检查。 |
| ④ `aspectjweaver` 替换 | SB4 中 `spring-boot-starter-aop` 不再传递 `aspectjweaver`，需显式声明 `aspectjweaver 1.9.25.1`（或 `aspectjrt`）。EP SDK 的 `provided` scope 依赖表已显式列出。 |
| ⑤ `DataSourceBuilder` 拆分 | SB4 中 `DataSourceBuilder` 从 `spring-boot` 拆到 `spring-boot-jdbc`，且 API 变化（`.type()` 泛型化）。EP 读写分离 SDK 用 `HikariConfig` + `HikariDataSource` 直接构建，**不用 `DataSourceBuilder`**。 |
| ⑥ TiDBDialect 移除 → MariaDBDialect | SB4 Hibernate 6.5 移除 `TiDBDialect`（Hibernate 社区停止维护），改用 `MariaDBDialect`（TiDB 兼容 MySQL/MariaDB 协议）。EP JPA `spring.jpa.database-platform` 不设或设 `org.hibernate.dialect.MariaDBDialect`。 |
| ⑦ Seata webclient 冲突 → `allow-bean-definition-overriding` | SCA 2025.1.0.0 → `spring-boot-webclient:4.0.0` → `CodecsAutoConfiguration` 与 SB bean 冲突。EP 不用 Seata（ADR-T06）故无此冲突；若 SCA 其他组件触发 bean 冲突，设 `spring.main.allow-bean-definition-overriding=true` 绕过。 |
| **byte-buddy compile scope 铁律** | |
| ⑧ byte-buddy `compile`（非 `test`）| Hibernate 6.5（SB4 传递）运行时依赖 `byte-buddy` 做代理生成，若仅 `test` scope 则运行时 `ClassNotFoundException`。EP 强制 `byte-buddy` 为 `compile`（或 `provided`，由 SB4 BOM 传递）。**CI 检查 byte-buddy 不得为 `test` scope**。 |
| **依赖 scope 规范（SP-01 全 SDK 统一）** | |
| 规范 | SDK 的 Spring/HikariCP/aspectj 依赖统一 `provided`（由宿主应用 BOM 传递）；测试依赖 `test`；`byte-buddy` `compile`（运行时需要）；Lombok `provided`（编译时需要，运行时不含）。CI 检查 scope 合规性。 |
| **理由** | ESP 7 个破坏性变更每个都导致 CrashLoop 或编译失败，EP 从 Day 1 预规避成本远低于事后修复；byte-buddy scope 是隐蔽坑（编译通过但运行时 ClassNotFoundException）。 |
| **实现** | ① sp01-sdd 依赖表统一标注 scope；② CI 规则：grep 禁 `@MockBean`/`undertow`/`DataSourceBuilder`/`TiDBDialect`；③ CI 规则：grep `byte-buddy` scope 非 test；④ JPA 配置注明 MariaDBDialect。 |
| **依据** | ESP PROGRESS.md L1108-1156（7 个破坏性变更全修记录）+ Spring Boot 4.0 Migration Guide + Hibernate 6.5 Release Notes |
| **状态** | Accepted |
| **关联需求** | NFR-A-01 / D-032（SCA+Dubbo 锁） |

---

### 3.14 ADR-T14 K8s 部署运维铁律清单（ESP 运维经验穷尽吸收）

| 项 | 内容 |
|----|------|
| **背景** | ESP 在 K8s 部署运维中踩过多个坑（rollout 超时、镜像拉取、PDB 阻塞、NetworkPolicy CNI 不执行等），EP 需从 Day 1 在部署文档中预设对策。 |
| **决策** | EP 全项目群 K8s 部署强制遵守以下运维铁律： |
| **① Java 服务 rollout timeout ≥ 900s** | SB4 Java 服务慢启动（JIT 编译 + Disruptor RingBuffer 预热 + Nacos 注册 + Dubbo Triple 连接池建立），`progressDeadlineSeconds` 默认 600s 不够。EP Deployment/Argo Rollouts 设 `progressDeadlineSeconds: 900`（15min），`strategy.rollingUpdate.maxUnavailable: 0`（先扩后缩，零中断）。 |
| **② Docker 镜像源替代** | 海外镜像（`docker.io`/`gcr.io`/`quay.io`）在国内拉取不稳定。EP 部署策略：①优先用内网 Harbor 仓库（`harbor.ep.local/<repo>:<tag>`）；②K8s 节点配 containerd registry mirror（`/etc/containerd/certs.d/`）；③基础镜像统一 `eclipse-temurin:21-jre-alpine`（Java）/`golang:1.25-alpine`（Go 构建）/`python:3.12-slim`（Python）；④CI 构建推 Harbor，禁止直接拉公网镜像。 |
| **③ PDB 不阻塞 drain** | 生产多节点环境 PDB `minAvailable` 正常工作；单节点 RD 环境会阻塞 drain。EP 部署文档注明"PDB 需 ≥2 副本才能 drain；单节点环境用 `maxUnavailable: 1` 替代 `minAvailable`"。 |
| **④ NetworkPolicy 需 Calico/Cilium CNI** | K8s 默认 CNI（如 Flannel）不执行 NetworkPolicy，`default-deny` 实际不生效。EP 部署文档注明"NetworkPolicy 需 Calico 或 Cilium CNI 才生效；生产环境强制 Calico/Cilium，RD 环境降级为不依赖 NetworkPolicy"。 |
| **⑤ OTel tail-sampling 防 Tempo OOM** | ESP 曾因全量 trace 打爆 Tempo（OOM 3 次）。EP OTel 双层 Collector：DaemonSet 层 tail-sampling 10%（按 `http.status_code>=500` 或 `latency>1s` 采样），Gateway 层聚合后发 Tempo。**禁全量 trace 进 Tempo**。 |
| **⑥ ConfigMap 必须 in manifest** | ESP 曾用 `kubectl create configmap` 临时创建，重装集群后丢失 → CrashLoop。EP 所有 ConfigMap 必须在 GitOps 仓库（ArgoCD manifest）中声明，**禁止 kubectl 临时创建**。 |
| **⑦ 混沌脚本 env 恢复验证** | ESP Chaos Mesh 脚本曾有 `${Dur}` typo 导致 `REDIS_HOST` 环境变量残留，Pod 卡死。EP 混沌 Runbook 注明"每次混沌实验结束后必须验证 Pod env 恢复（`kubectl exec -- env | grep` 关键变量）"。 |
| **⑧ Go 依赖 vendor 策略** | 网络受限环境 `go mod tidy` 可能因 `go.sum` 不一致失败。EP Go 服务（gateway-go）部署文档注明"CI 构建时 `go mod vendor`，镜像内含 vendor 目录，不依赖运行时下载"。 |
| **理由** | ESP 8 个运维坑每个都导致生产事故或部署失败，EP 在 Day 1 预设对策避免重蹈覆辙。 |
| **实现** | ① sp01-deploy K8s manifest 注释 + Argo Rollouts 配置；② CI 检查 ConfigMap 是否在 GitOps 仓库；③ 混沌 Runbook 模板含 env 恢复验证步骤。 |
| **依据** | ESP PROGRESS.md 运维经验 + Chaos Mesh 教训 + Argo Rollouts 文档 |
| **状态** | Accepted |
| **关联需求** | NFR-A-01 / NFR-M-03 / NFR-O-02 |

---



### 4.1 JWT RS256 契约

```
Header:
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "ep-key-2026-08-03"   // 指向 JWKS 公钥
}

Payload:
{
  "sub": "user_id",             // 用户ID(单元路由依据)
  "iss": "education-platform",
  "aud": ["bu-service"],
  "exp": 1735689600,
  "iat": 1735686000,
  "tenant_id": "it-backend",    // 事业群/租户
  "roles": ["learner"],
  "session_id": "uuid",
  "cell": 3                     // 路由到的 cell(可选,优化)
}

Signature: RS256(header + "." + payload, private_key)
```

**JWKS 端点**：`GET https://bff.example.com/.well-known/jwks.json`

```json
{
  "keys": [
    {
      "kty": "RSA",
      "kid": "ep-key-2026-08-03",
      "use": "sig",
      "alg": "RS256",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

**轮换策略**：kid 变更 → 下游检测到新 kid → 重新拉取 JWKS(缓存 5min) → 旧 token 在 exp 前仍可验签。

### 4.2 API 网关路由契约

> **D-032/ADR-T07(rev2) 变更**：网关从 Spring Cloud Gateway 迁移到自建 Netty edge + Spring WebMVC。路由配置不再使用 SCG 的 `spring.cloud.gateway.routes` 格式，改为自建 `RouteTable` JSON schema，仍由 Nacos 动态下发(dataId=`ep-gateway-routes`)，变更秒级生效不重启。

```yaml
# Nacos 路由配置(dataId: ep-gateway-routes) — 自建 Netty edge RouteTable 格式
# 替代 SCG spring.cloud.gateway.routes(D-032 禁用 SCG)
routes:
  - id: ucm-service
    uri: dubbo://ucm-service        # Dubbo Triple 服务发现(Nacos)
    path: /api/ucm/**
    stripPrefix: 2
    filters:
      - AuthFilter
      - SentinelFilter:
          resource: ucm-api
          qps: 5000
  - id: live-room
    uri: dubbo://live-room
    path: /api/live/**
    stripPrefix: 2
    filters:
      - AuthFilter
      - CellRouteFilter              # 用户域请求经 CellRouter
```

### 4.3 CellRouter 路由表契约

```json
// Nacos dataId: ep-cell-router-table
{
  "version": "2026-08-03-v3",
  "cellCount": 10,
  "hashAlgorithm": "murmur3",
  "cells": [
    { "cellId": 0, "regions": ["huadong", "huabei"], "status": "active", "weight": 100 },
    { "cellId": 1, "regions": ["huabei", "huanan"], "status": "active", "weight": 100 },
    { "cellId": 2, "regions": ["huanan", "huadong"], "status": "failover", "weight": 0, "writeHeld": ["user_id_123", "user_id_456"] }
  ],
  "globalCell": { "cellId": "global", "regions": ["huabei"], "status": "active" }
}
```

### 4.4 读写分离 SDK 契约(已实现)

```java
// 注解使用
@DataSource(READ)                  // 路由到 Follower 池
public Course getCourse(Long id) { ... }

@DataSource(WRITE)                 // 路由到 Primary
public void saveOrder(Order o) { ... }

@DataSource(value = READ, consistent = true)  // 强制 Primary(防读后写)
public Order getOrderAfterPay(Long uid) { ... }

@DataSource(value = READ, tiflash = true)     // 路由到 TiFlash 列存(OLAP)
public Stats getStats(Long id) { ... }
```

### 4.5 Disruptor SDK 契约(已实现)

```java
// Pipeline 构建
DisruptorPipeline pipeline = DisruptorPipeline.named("learning-events")
    .stage("ingest", LearningEvent::new, EventTranslator, this::onIngest)
    .stage("enrich", LearningEvent::new, EventTranslator, this::onEnrich)
    .stage("persist", LearningEvent::new, EventTranslator, this::onPersist);
pipeline.start();

// 发布
PublishResult r = pipeline.publish("ingest", event);
if (r == PublishResult.DROPPED) {
    // 降级：落本地文件/Pulsar 兜底
}

// 指标
DisruptorStats s = pipeline.stats(0);  // published/dropped/consumed/backlog
```

### 4.6 多级缓存 SDK 契约(已实现)

```java
// getOrLoad
Course c = cacheService.getOrLoad("course", "course:" + id, () -> {
    return courseRepo.findById(id);
}, 3600, true);  // TTL 3600s, cacheEmpty=true

// 主动失效
cacheService.evict("course", "course:" + id);
// → L1 evict + L2 delete + Pulsar 广播 + 延迟双删 100ms
```

### 4.7 事件总线契约

```java
// 发布事件
eventPublisher.publish("order.paid", new OrderPaidEvent(orderId, userId, amount));

// topic 规范: {domain}.{action}
//   order.paid / order.refunded
//   learning.progress / learning.complete
//   risk.flagged
```

### 4.8 Dubbo Triple RPC 契约

```protobuf
// ucm-service.proto
syntax = "proto3";
package com.ep.ucm;
option java_multiple_files = true;

service UcmService {
  rpc GetUser(GetUserRequest) returns (GetUserResponse);
  rpc CreateUser(CreateUserRequest) returns (CreateUserResponse);
}

message GetUserRequest { string user_id = 1; }
message GetUserResponse {
  string user_id = 1;
  string name = 2;
  string tenant_id = 3;
}
```

---

## 5. 部署拓扑

### 5.1 单元内部署拓扑

```
┌────────────────────── Cell-X(华东+华北)──────────────────────────┐
│                                                                  │
│  K8s Namespace: ep-tech-platform                                 │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ bff-java Deployment (3 replicas)                          │    │
│  │   HPA: min=3, max=20, CPU 70%                            │    │
│  │   PDB: minAvailable=1                                     │    │
│  └──────────────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ gateway-go Deployment (3 replicas)                        │    │
│  │   HPA: min=3, max=30, CPU 70% + 自定义 conn metric       │    │
│  └──────────────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ cell-router Deployment (2 replicas, 无状态)               │    │
│  └──────────────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ rust-hotspot Deployment (2 replicas, 风控+计费)           │    │
│  └──────────────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ otel-collector DaemonSet(edge) + Deployment(gateway)      │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                  │
│  共享基础设施(本单元副本):                                         │
│  ┌─────────────┐ ┌──────────────┐ ┌────────────────────────┐    │
│  │ Redis Cluster│ │ Pulsar ns-X  │ │ Nacos(本区)            │    │
│  │ (分片X)      │ │              │ │                        │    │
│  └─────────────┘ └──────────────┘ └────────────────────────┘    │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ TiDB 分片X(跨2区 Raft: Primary+2 Follower)                │    │
│  └──────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
```

### 5.2 多区域拓扑(对齐 EP-TOGAF §6.2)

```
┌────────────────────── 国内多活(华东/华北/华南三角)──────────────────┐
│                                                                     │
│   ┌─────── Cell-0 (华东+华北) ───────┐  ┌──── Cell-1 (华北+华南) ──┐│
│   │ 技术中台副本(bff/gateway/router)  │  │ 技术中台副本              ││
│   │ Redis分片0  Pulsar ns-0           │  │ Redis分片1  Pulsar ns-1   ││
│   │ TiDB分片0(跨2区Raft)              │  │ TiDB分片1                 ││
│   └───────────────────────────────────┘  └──────────────────────────┘│
│                                                                     │
│   ┌──── 全局域(华北,单写) ──────────┐  ┌──── Pulsar geo-rep ──────┐│
│   │ Nacos 主 / 全局服务 / JWKS 主    │  │ 跨单元事件聚合             ││
│   └──────────────────────────────────┘  └───────────────────────────┘│
└────────────────────────────────┬────────────────────────────────────┘
                                 │ 海外用户不进国内(PIPL)
┌────────────────────────────────▼──── 海外独立集群 ──────────────────┐
│ 新加坡 Cell + 法兰克福 Cell │ 独立技术中台副本 │ 数据不回流            │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.3 容量规划

| 资源 | 单 Cell 配置 | 10 Cell(M3) | 备注 |
|------|------------|-------------|------|
| bff-java | 3-20 pod(HPA) | 30-200 pod | 2C4G/pod |
| gateway-go | 3-30 pod(HPA) | 30-300 pod | 4C8G/pod(连接密集) |
| cell-router | 2 pod | 20 pod | 1C2G/pod |
| rust-hotspot | 2 pod | 20 pod | 2C4G/pod |
| Redis | 6 节点(3主3从) | 60 节点 | 16C64G/节点 |
| Pulsar broker | 3 broker | 30 broker | 8C32G |
| TiDB | 3 节点(跨2区) | 30 节点 | 16C64G |

---

## 6. 安全设计

### 6.1 安全分层(对齐 EP-TOGAF §6.5)

```
┌─────────────────────────────────────────────────────────────┐
│ 接入层: TLS 1.3 / WAF / DDoS 防护 / 速率限制                 │
├─────────────────────────────────────────────────────────────┤
│ 鉴权层: JWT RS256 + JWKS(BFF签发,下游验签) —— ADR-T01       │
│   AuthFilter 强制覆盖任何 X-User-Id/X-Tenant-Id 客户端头     │
│   禁止 app 层拼 tenant_id,用 JWT claim —— P-03               │
├─────────────────────────────────────────────────────────────┤
│ 授权层: RBAC + ABAC(资源级)                                  │
│   JWT roles claim → RBAC 角色                                │
│   资源 owner 校验 → ABAC(如学员只能查自己的学习记录)          │
├─────────────────────────────────────────────────────────────┤
│ 多租户隔离: JWT FGAC(禁 app 拼 tenant_id) —— ADR-A02        │
│   Milvus namespace + JWT tenant_id 强隔离                    │
├─────────────────────────────────────────────────────────────┤
│ 沙箱隔离: Firecracker microVM(评测Judge) —— ADR-I02          │
│   不信任代码强隔离,125-200ms 启动,预热池 5-30ms              │
├─────────────────────────────────────────────────────────────┤
│ 数据加密: 传输 TLS 1.3 / 静态 AES-256                        │
│   TiDB at-rest encryption / Redis TLS / Pulsar TLS           │
├─────────────────────────────────────────────────────────────┤
│ 数据合规: PIPL 海外隔离 / 国内不出境 —— D-004                 │
│   海外独立集群,数据不回流                                     │
├─────────────────────────────────────────────────────────────┤
│ 审计层: 全操作审计日志(不可篡改) → Loki + append-only        │
│   鉴权/路由/限流/熔断/配置变更全审计                           │
├─────────────────────────────────────────────────────────────┤
│ 风控层: Rust 热点规则 + Flink 实时行为 —— ADR-B02            │
│   高频规则 P95<1ms / 实时行为分析                             │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 JWT 安全细节

| 项 | 策略 |
|----|------|
| 算法 | RS256(RSA 2048bit) |
| 签发方 | BFF(唯一持私钥) |
| 验签方 | 所有下游服务(持 JWKS 公钥) |
| JWKS 端点 | `/.well-known/jwks.json`(公开) |
| JWKS 缓存 | 下游缓存 5min，kid 变更触发重拉 |
| 密钥轮换 | 季度轮换 + 紧急轮换(泄露时)；新旧 kid 并存至旧 token 全过期 |
| claim | sub(user_id) / tenant_id / roles / session_id / cell |
| 过期 | access token 15min / refresh token 7d |
| 存储 | 客户端 HttpOnly Secure Cookie / 移动端安全存储 |

### 6.3 AuthFilter 防伪造头

```java
@Component
public class AuthFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 提取 Authorization: Bearer <jwt>
        String jwt = extractJwt(exchange);
        if (jwt == null) return unauthorized(exchange);

        // 2. JWKS 验签
        Claims claims = jwksVerifier.verify(jwt);
        if (claims == null) return unauthorized(exchange);

        // 3. 强制覆盖任何客户端伪造头(关键!)
        ServerHttpRequest req = exchange.getRequest().mutate()
            .header("X-User-Id", claims.getSubject())           // 覆盖
            .header("X-Tenant-Id", claims.get("tenant_id"))     // 覆盖
            .header("X-Roles", claims.get("roles"))             // 覆盖
            .header("X-Trace-Id", traceId)                      // 注入
            .build();

        return chain.filter(exchange.mutate().request(req).build());
    }
}
```

---

## 7. 可观测架构

### 7.1 可观测分层(对齐 EP-TOGAF §6.4)

```
服务 pod ──OTel SDK──► Edge Collector(DaemonSet, tail-sampling 10%)
                              │
                    ┌─────────┼─────────┐
                    │         │         │
                Loki      Tempo    Prometheus
               (日志)    (链路)     (指标)
                    │         │         │
                    └─────────┼─────────┘
                              │
                        Grafana(统一看板)
                              │
                        AlertManager(告警)
```

### 7.2 双日志轨

| 日志轨 | 内容 | 格式 | 检索 |
|--------|------|------|------|
| access log | 请求级(method/path/status/latency/user_id/trace_id) | JSON | Loki |
| business log | 业务事件级(订单创建/学习进度等) | JSON structured | Loki |

### 7.3 指标清单

| 指标 | 类型 | 用途 |
|------|------|------|
| http_server_requests_seconds | histogram | API P95/P99 |
| gateway_request_total | counter | QPS |
| sentinel_block_total | counter | 限流次数 |
| cell_router_misroute_total | counter | misroute 次数 |
| disruptor_published_total | counter | Disruptor 发布数 |
| disruptor_dropped_total | counter | Disruptor 丢弃数 |
| cache_hit_total / cache_miss_total | counter | 缓存命中率 |
| pulsar_publish_total | counter | 事件发布数 |
| pod_restart_total | counter | pod 重启 |

### 7.4 告警规则

| 告警 | 条件 | 严重度 |
|------|------|--------|
| API P95 违规 | histogram_quantile(0.95) > 200ms 持续 5min | P1 |
| API 可用性 | up == 0 持续 1min | P0 |
| 限流触发率 | sentinel_block_rate > 5% 持续 5min | P2 |
| Disruptor 丢弃率 | drop_rate > 1% 持续 5min | P2 |
| 缓存命中率 | hit_rate < 80% 持续 10min | P3 |
| pod 重启 | restart_total > 3 in 10min | P1 |

### 7.5 app_logs 表设计（TiDB 应用日志表，对齐 ADR-T10 / ADR-T14 ⑤ / PIPL）

> **背景**：ADR-T14 ⑤ 已解决 OTel tail-sampling 防 Tempo OOM，但应用级结构化日志在 TiDB 中可查询的表设计此前缺失。`app_logs` 表用于落库 OTel Collector 采集的应用日志，支持 Grafana Loki 数据源 + TiDB SQL 双路径检索，并与 Tempo 链路通过 `trace_id` 交叉关联。

#### 7.5.1 DDL

```sql
CREATE TABLE `app_logs` (
  `id`         BIGINT       NOT NULL AUTO_RANDOM(5)  COMMENT '主键，TiDB 自动分片避免写热点',
  `ts`         TIMESTAMP(3) NOT NULL                 COMMENT '日志时间戳（毫秒精度）',
  `level`      ENUM('TRACE','DEBUG','INFO','WARN','ERROR','FATAL') NOT NULL COMMENT '日志级别',
  `service`    VARCHAR(64)  NOT NULL                 COMMENT '来源服务名，如 bff-java / gateway-go',
  `trace_id`   VARCHAR(64)  NOT NULL                 COMMENT 'OTel trace ID，用于与 Tempo 链路交叉关联',
  `span_id`    VARCHAR(32)  DEFAULT NULL             COMMENT 'OTel span ID',
  `host`       VARCHAR(128) DEFAULT NULL             COMMENT 'Pod/主机名',
  `message`    TEXT         DEFAULT NULL             COMMENT '日志正文',
  `attrs`      JSON         DEFAULT NULL             COMMENT '结构化属性键值对；禁止含 PII 字段（见 7.5.4）',
  `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  PRIMARY KEY (`id`),
  KEY `idx_trace_id`   (`trace_id`),
  KEY `idx_service_ts` (`service`, `ts`),
  KEY `idx_level_ts`   (`level`, `ts`)
) PARTITION BY RANGE (UNIX_TIMESTAMP(`ts`)) (
  PARTITION `p202601` VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01 00:00:00')),
  PARTITION `p202602` VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01 00:00:00')),
  PARTITION `p202603` VALUES LESS THAN (UNIX_TIMESTAMP('2026-04-01 00:00:00')),
  PARTITION `p202604` VALUES LESS THAN (UNIX_TIMESTAMP('2026-05-01 00:00:00'))
);
```

#### 7.5.2 设计依据

| 维度 | 决策 | 依据 |
|------|------|------|
| RANGE 分区 | 按 `ts` 月分区，分区裁剪将查询限制在单分区，避免全表扫 OOM；旧分区可直接 `DROP PARTITION` 低成本清理 | ADR-T10（高频写表 Day 1 强制 RANGE 分区） |
| `attrs` JSON 列 | 灵活承载 OTel 结构化属性，但 CI 静态检查禁止 PII 字段名（见 7.5.4），应用层日志脱敏器写入前剥离 PII | ADR-T14 ⑤ + PIPL（ADR-A02 安全分层） |
| `trace_id` 索引 | 支撑 Tempo ↔ TiDB 链路交叉检索：Grafana 点击 Tempo span → 用 `trace_id` 反查 TiDB 落库的完整日志行 | ADR-T14 ⑤ OTel 全链路 |
| `AUTO_RANDOM(5)` | TiDB 写入自动打散 region，避免单 region 写热点（ESP `session_turns` 自增主键热点教训） | ADR-T10 / ADR-T12 |
| 月分区 + 90 天保留 | 默认 90 天保留，按事业群（BU）可配置；超出窗口的分区整体 `DROP`，无 GC 全表扫描压力 | ADR-T10 热分区 7d / 温分区 30d 策略对齐 |
| 写入路径 | OTel Collector → TiDB sink（批量插入 1000 行/批，5s flush）；批量写入降低 TiDB 写放大 | ADR-T14 ⑤ Collector 双层架构 |
| 读取路径 | ① Grafana Loki 数据源用于全文检索（access/business 双日志轨）；② TiDB SQL 用于按 `trace_id` / `service` / `level` + 时间窗的结构化查询 | §7.2 双日志轨 |

#### 7.5.3 分区管理

| 项 | 内容 |
|----|------|
| 自动分区 | 启用 `tidb_partition_management = ON`（TiDB 8.x），自动按月创建未来分区，避免人工遗漏导致写入失败 |
| 预建窗口 | 初始预建 3 个月分区（如 `p202602/p202603/p202604`），后续按月自动延伸 |
| 过期清理 | `tidb-partition-manager` 定时任务（对齐 ADR-T10 实现②）按 90 天默认保留期 `DROP PARTITION`；保留期按 BU 可配置（如合规审计 BU 可设 180 天） |
| 行数监控 | 单分区行数 > 5000 万告警（对齐 ADR-T10 实现③），触发分区分裂或保留期复核 |

#### 7.5.4 PIPL 合规列约束

> `app_logs` 表存储应用日志，PII 泄露风险高（日志中最易误打手机号/身份证/邮箱）。此节为 PIPL 合规强约束。

| 约束 | 内容 |
|----|------|
| 禁止字段名 | `attrs` JSON 不得包含匹配以下任一键的字段（大小写不敏感）：`phone`、`mobile`、`id_card`、`idcard`、`email`、`password`、`token`、`secret`、`key` |
| 应用层脱敏器 | 各服务 OTel LogRecordAppender 之前强制经过 `PiiLogSanitizer`，按字段名 + 正则（手机号 11 位 / 身份证 18 位 / 邮箱）脱敏；未经过脱敏器的日志写入路径禁止上线 |
| CI DDL 检查 | `ep-ddl-convention` CI（对齐 ADR-T10 实现①）校验：① `app_logs` DDL 必须含 `PARTITION BY RANGE`，无则编译失败；② `attrs` 列注释必须含 "禁止含 PII 字段" 字样 |
| 安全分层 | 日志脱敏属 ADR-A02 安全分层中"数据层 - 静态脱敏"控制项；TiDB FGAC 不覆盖日志表（日志无租户隔离需求，依赖字段级脱敏） | ADR-A02 |
| 审计 | 每月抽查 `app_logs.attrs` 采样 1000 行，扫描是否含 PII 模式；命中则触发 P1 告警 + 回溯脱敏器配置 | PIPL 第 55 条审计要求 |

---

## 8. 追溯矩阵

### 8.1 SRS 需求 → SAD 组件

| SRS 需求 | SAD 组件 | ADR |
|---------|---------|-----|
| FR-GW-01..08 | API 网关/BFF | — |
| FR-WS-01..07 | WS/实时网关 | — |
| FR-CR-01..07 | CellRouter | ADR-T01 |
| FR-RW-01..08 | ep-rw-java(已实现) | ADR-T02 |
| FR-DIS-01..09 | ep-disruptor(已实现) | ADR-T03 |
| FR-CA-01..10 | ep-cache(已实现) | ADR-T04 |
| FR-EDA-01..07 | 事件总线 | ADR-T03 |
| FR-RPC-01..05 | Dubbo Triple | — |
| FR-OBS-01..06 | 可观测 | — |
| FR-CFG-01..04 | Nacos | — |
| FR-RL-01..04 | Sentinel | — |
| FR-RUST-01..03 | Rust 热点 | — |
| FR-XLANG-01..03 | 跨语言对齐 | ADR-T04 |

### 8.2 顶层 TOGAF → 本文

| EP-TOGAF 章节 | 本文对应 |
|--------------|---------|
| §6.1 基础设施栈 | §1.4 技术架构对齐 |
| §6.2 部署拓扑 | §5 部署拓扑 |
| §6.3 单元化机制 | §3.1 ADR-T01 / §4.3 路由表 |
| §6.4 可观测架构 | §7 可观测架构 |
| §6.5 安全架构 | §6 安全设计 |
| §6.6 K8s 部署规范 | §5 部署拓扑 / ADR-T05 |

---

**文档结束**

> 本 SAD 是 SP-01 技术中台的架构基线。任何架构变更须经架构委员会评审，更新本文档并同步 ADR。下游 SDD/测试/部署文档以本文为架构锚点。
