# SP-01 技术中台 —— 软件架构设计文档(SAD)

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
| 直播学习与互动 | 直播子平台 | WS 网关、Redis Pub/Sub、事件总线 |
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
│  Java Spring Cloud Gateway + Go WS 网关 │
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
| TiDB 7.x | 读写分离目标 | ep-rw-java |
| Redis Cluster 7.x | L2 缓存/会话/路由表/JWKS | ep-cache/网关/CellRouter |
| Pulsar 3.3.x | 事件总线 | ep-cache 广播/EDA |
| Nacos 2.x | 配置/服务发现 | CellRouter/网关/Sentinel |
| Loki/Prom/Tempo | 可观测后端 | OTel SDK |
| Sentinel 1.8+ | 限流降级 | 网关+服务双层 |

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
│                        API 网关/BFF(Java Spring Cloud Gateway)            │
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
│  Redis Pub/Sub 跨pod  │  │  路由表 Nacos+Redis  │  │  超时/重试          │
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
[Spring Cloud Gateway]
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

---

## 4. 接口契约

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

```yaml
# Nacos 路由配置(dataId: ep-gateway-routes)
spring.cloud.gateway.routes:
  - id: ucm-service
    uri: lb://ucm-service
    predicates:
      - Path=/api/ucm/**
    filters:
      - StripPrefix=2
      - name: AuthFilter
      - name: SentinelFilter
        args:
          resource: ucm-api
          qps: 5000
  - id: live-room
    uri: lb://live-room
    predicates:
      - Path=/api/live/**
    filters:
      - StripPrefix=2
      - name: AuthFilter
      - name: CellRouteFilter   # 用户域请求经 CellRouter
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
