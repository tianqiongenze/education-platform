# SP-07 多地多中心部署方案

> **D-200 Naming Note:** This document's filename retains the legacy code `sp07` for reference continuity. Per D-137, sp07 → knowledge-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP07-DEPLOY-001 | v1.0 | 2026-08-04 | Approved | ZCode(DevOps架构师) | SP07-SAD-001 / D-032 / D-004 / D-012 / G-01~G-10 SLO |

> 本文定义 education-platform 项目群的多地多中心部署架构，覆盖国内异地多活(华东/华南/华北)、海外独立单元(东南亚/北美/欧洲)、单元化(Cell)部署、跨地域数据同步、流量调度、容灾切换与合规隔离。技术栈严格遵循铁律 D-032：Spring Boot 4.0.7、Spring Cloud 2025.1.2、Spring Cloud Alibaba 2025.1.0.0、Dubbo 3.3.6、Nacos 3.1.1、Sentinel 1.8.9、Seata 2.5.0、Pulsar 3.3.9。

---

## 目录

1. [部署架构总览](#1-部署架构总览)
2. [多地多活架构](#2-多地多活架构)
3. [流量调度](#3-流量调度)
4. [数据同步策略](#4-数据同步策略)
5. [单元化Cell部署](#5-单元化cell部署)
6. [海外隔离方案](#6-海外隔离方案)
7. [容灾与切换](#7-容灾与切换)
8. [网络架构](#8-网络架构)
9. [Kubernetes 集群规划](#9-kubernetes-集群规划)
10. [监控与告警](#10-监控与告警)
11. [CICD 多区域发布](#11-cicd-多区域发布)
12. [安全与合规](#12-安全与合规)

---

## 1. 部署架构总览

### 1.1 设计目标与约束

| 维度 | 目标 | 依据 |
|------|------|------|
| 用户规模 | 30 亿注册 / 100 万峰值并发 | G-01, G-02 |
| 可用性 SLO | 99.99%(年宕机 < 52.6min) | G-07 |
| 单元故障切换 | <30s | G-08 |
| 核心 API P95/P99 | <200ms / <500ms | G-03, G-04 |
| 直播延迟 P95 | <800ms | G-05 |
| 合规 | 国内 PIPL + 海外 GDPR/CCPA 数据主权隔离 | D-004 |
| 技术栈 | SB4.0.7 / SC2025.1.2 / SCA2025.1.0.0 / Dubbo3.3.6 / Nacos3.1.1 / Sentinel1.8.9 / Seata2.5.0 / Pulsar3.3.9 | D-032 |

### 1.2 全球区域拓扑

平台采用"国内三地多活 + 海外三地独立单元"的混合部署模型。国内为异地多活对等架构，海外为独立单元(Active-Standby / Active-Active 视区域而定)，国内外通过专线 + 加密通道进行有限的管理面同步，数据面严格隔离。

```
                            +-----------------------------------+
                            |        全局 DNS GSLB (F5/阿里云)    |
                            |  国内: ctldns.ep-cn.com            |
                            |  海外: ctldns.ep-global.com        |
                            +-----------------+-----------------+
                                              |
            +---------------------------------+-----------------------------------+
            |                                 |                                   |
   +--------v---------+          +-----------v----------+             +-----------v----------+
   |   国内华东 (HZ)   |          |   国内华南 (SZ/GZ)    |             |   国内华北 (BJ)      |
   |   主中心 cell-HZ  |          |   主中心 cell-SZ     |             |   主中心 cell-BJ     |
   |   active-active   |          |   active-active      |             |   active-active      |
   +--------+----------+          +----------+-----------+             +----------+-----------+
            |                                |                                    |
            +------------ TiDB Raft + Pulsar Geo-Replication + Redis CRDT -------+
                                             |
            +--------------------------------+--------------------------------+
            |                                |                                |
   +--------v---------+         +-----------v----------+           +-----------v----------+
   |  东南亚 (SG)      |         |   北美 (US-Virginia)  |           |   欧洲 (DE-Frankfurt) |
   |  cell-SG 独立单元 |         |  cell-US 独立单元     |           |  cell-EU 独立单元     |
   |  active-standby   |         |  active-standby       |           |  active-standby       |
   +-------------------+         +----------------------+           +----------------------+
```

### 1.3 区域职责矩阵

| 区域 | 角色 | 单元 ID | 主要覆盖 | 多活模式 | 数据主权 |
|------|------|---------|----------|----------|----------|
| 华东(杭州) | 国内主中心 | cell-HZ | 华东/华中用户 | Active-Active | 中国大陆 |
| 华南(深圳) | 国内主中心 | cell-SZ | 华南/港澳用户 | Active-Active | 中国大陆 |
| 华北(北京) | 国内主中心 | cell-BJ | 华北/东北用户 | Active-Active | 中国大陆 |
| 东南亚(新加坡) | 海外单元 | cell-SG | 东南亚/港澳台/印度 | Active-Standby | 新加坡/东盟 |
| 北美(弗吉尼亚) | 海外单元 | cell-US | 北美/南美 | Active-Standby | 美国 |
| 欧洲(法兰克福) | 海外单元 | cell-EU | 欧洲/中东/非洲 | Active-Standby | 欧盟(GDPR) |

### 1.4 技术栈版本基线 (D-032)

| 组件 | 版本 | 部署形态 | 跨地域能力 |
|------|------|----------|------------|
| Spring Boot | 4.0.7 | K8s Pod | 无状态 |
| Spring Cloud | 2025.1.2 | K8s Pod | 无状态 |
| Spring Cloud Alibaba | 2025.1.0.0 | K8s Pod | 无状态 |
| Dubbo | 3.3.6 | K8s Pod (Triple协议) | 注册中心集群路由 |
| Nacos | 3.1.1 | 3节点Raft/区域 | 跨地域配置同步 |
| Sentinel | 1.8.9 | SDK嵌入 + Dashboard | 集群限流令牌服务器 |
| Seata | 2.5.0 | TC集群/区域 | 跨单元禁用(单元内强一致) |
| Pulsar | 3.3.9 | BookKeeper集群 | Geo-Replication |
| TiDB | 7.5.x | PD/TiKV/TiDB | Raft + CDC |
| Redis | 7.2 Cluster | 3主3从/单元 | Cell内Cluster, 跨Cell CRDT |
| Kubernetes | 1.30 | 多集群 + Karmada 1.12 | Cluster Federation |
| Karmada | 1.12 | 控制面/区域 | 多集群调度 |
| Prometheus | 2.55 | 联邦集群 | 全局联邦查询 |
| Cilium | 1.16 | CNI + eBPF | 跨集群网络策略 |

---

## 2. 多地多活架构

### 2.1 多活模式分层

```
+--------------------------------------------------------------------------------+
|                          流量接入层 (DNS GSLB + Anycast)                        |
+------------------------------------+-------------------------------------------+
                                     |
+------------------------------------v-------------------------------------------+
|                   CellRouter (基于 UserID 路由的单元化网关)                      |
|   路由键: UserID (Shard-by-UserID, D-012) → cell-{HZ,SZ,BJ,SG,US,EU}            |
+--------+-------------------+-------------------+-------------------+------------+
         |                   |                   |                   |
+--------v------+    +-------v------+    +-------v------+    +-------v------+
|  cell-HZ App  |    | cell-SZ App  |    | cell-BJ App  |    | cell-SG/US/EU|
|  K8s Cluster  |    | K8s Cluster  |    | K8s Cluster  |    |  K8s Cluster |
+--------+------+    +-------+------+    +-------+------+    +-------+------+
         |                   |                   |                   |
+--------v-------------------v-------------------v-------------------v-----------+
|                数据同步层 (TiDB Raft / CDC / Pulsar Geo / Redis CRDT)           |
+--------------------------------------------------------------------------------+
```

### 2.2 国内异地多活 (Active-Active)

国内三个主中心采用**对等多活**模式，三地同时承载读写流量：

| 特征 | 实现 |
|------|------|
| 流量分配 | 按 UserID 哈希分片，GSLB + CellRouter 将用户固定到所属单元 |
| 数据强一致 | TiDB PD 跨三地部署 Raft Group，3副本跨地域多数派写入 |
| 写冲突避免 | 单元化路由保证同一 UserID 的写操作只落在其归属单元 |
| 跨单元读 | 通过 TiDB Follower Read 就近读取，降低跨地域延迟 |
| 会话同步 | Redis Cluster 跨单元 CRDT(最终一致)，保证切流后会话连续 |

国内三地 TiDB 部署拓扑(3 Region, 每地 3 AZ 级别):

```
cell-HZ (杭州)   cell-SZ (深圳)   cell-BJ (北京)
  PD Leader        PD Follower      PD Follower
  TiKV x9          TiKV x9          TiKV x9
  TiDB x6          TiDB x6          TiDB x6
  --- Raft Group: 3副本跨3地, 多数派 = 2 ---
```

### 2.3 海外独立单元 (Active-Standby)

海外单元采用**主备模式**，每个区域单写主中心，灾备在同区域另一可用区：

| 区域 | 主 | 备 | 切换 RTO | 数据同步 |
|------|----|----|----------|----------|
| 东南亚 | 新加坡-AZ-a | 新加坡-AZ-b | <60s | TiDB 同步Raft(同AZ低延迟) |
| 北美 | 弗吉尼亚-AZ-a | 弗吉尼亚-AZ-b | <60s | TiDB 同步Raft |
| 欧洲 | 法兰克福-AZ-a | 法兰克福-AZ-b | <60s | TiDB 同步Raft |

海外单元之间**不进行数据实时同步**，仅通过 Pulsar Geo-Replication 同步必要的全局元数据(课程目录、配置变更)，学员数据严格留在本区域。

### 2.4 数据同步策略总览

| 数据类型 | 同步机制 | 一致性 | 延迟 | 冲突解决 |
|----------|----------|--------|------|----------|
| 用户核心数据(账户/订单) | TiDB Raft 跨地域 | Strong | <1s(国内) | Raft多数派，无冲突 |
| 配置/元数据 | Nacos 集群同步 | Eventual | <5s | 版本号 LWW |
| 会话状态 | Redis CRDT | Eventual | <2s | CRDT合并 |
| 学习事件流 | Pulsar Geo-Replication | Eventual | <10s | 事件溯源，无更新冲突 |
| 全局搜索索引 | CDC → Elasticsearch | Eventual | <30s | 全量重建 |
| 跨单元业务事件 | Pulsar Geo-Replication | Eventual | <10s | 事件ID幂等去重 |

### 2.5 冲突解决机制

| 场景 | 冲突类型 | 解决策略 |
|------|----------|----------|
| 跨单元同时写同一用户 | 写冲突 | 单元化路由保证同一用户写只落一个单元(D-012) |
| Redis 会话合并 | 状态冲突 | CRDT(OR-Set / LWW-Register)自动合并 |
| 跨单元事件重复 | 重复消费 | Pulsar 消息ID + 业务幂等键去重 |
| 配置变更竞争 | 更新冲突 | Nacos 版本号 CAS，后写覆盖 + 审计告警 |
| TiDB Raft 分区 | 分区冲突 | Raft 多数派可用性优先，少数派分区只读 |

---

## 3. 流量调度

### 3.1 三级流量调度体系

```
用户请求
   |
   v
[第1级] DNS GSLB (F5 GTM / 阿里云HTTP-DNS)
   |  - 按地理就近 + 单元健康度返回单元VIP
   |  - 国内: ep-cn.com → cell-HZ/SZ/BJ
   |  - 海外: ep-global.com → cell-SG/US/EU
   v
[第2级] CellRouter (基于 UserID 的单元化网关, Spring Cloud Gateway)
   |  - 解析 JWT 取 UserID
   |  - UserID % cellCount → 目标单元
   |  - 跨单元请求通过 Dubbo 集群路由转回归属单元
   v
[第3级] 单元内 Nacos + Dubbo 路由 + Sentinel 限流
      - Nacos 服务发现(单元内优先)
      - Dubbo Cluster Router 跨单元白名单
      - Sentinel 集群限流令牌服务器
```

### 3.2 DNS GSLB 策略

| 策略 | 说明 | 权重 |
|------|------|------|
| 地理就近 | 按用户 EDCNS 出口判定就近单元 | 40% |
| 单元健康度 | Prometheus 健康分(错误率/延迟/容量) | 30% |
| 容量水位 | 各单元当前 CCU / 容量上限 | 20% |
| 灾备预留 | 预留灾备单元不承载常态流量 | 10% |

GSLB 健康检查间隔 5s，单元连续 3 次检查失败自动摘除，TTL 设置 30s(国内)/60s(海外)。

### 3.3 CellRouter 路由规则

CellRouter 基于 Spring Cloud Gateway 实现，核心路由逻辑：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: cell-router
          uri: lb://cell-router-service
          predicates:
            - Path=/api/**
          filters:
            - name: CellRouting
              args:
                cellCount: 6           # 6个单元
                cellPrefix: "cell-"    # 单元前缀
                hashKey: "userId"      # 路由键
                fallbackCell: "cell-HZ"
                healthCheckInterval: 5s
```

CellRouter 路由表(通过 Nacos 动态下发):

| UserID 分片范围 | 归属单元 | 备用单元 | 状态 |
|----------------|----------|----------|------|
| hash % 6 == 0 | cell-HZ | cell-SZ | active |
| hash % 6 == 1 | cell-SZ | cell-HZ | active |
| hash % 6 == 2 | cell-BJ | cell-HZ | active |
| hash % 6 == 3 | cell-HZ | cell-BJ | active |
| hash % 6 == 4 | cell-SZ | cell-BJ | active |
| hash % 6 == 5 | cell-BJ | cell-SZ | active |

海外 UserID 独立分片空间，通过地理前缀(GEO-US-/GEO-EU-/GEO-SG-)路由至对应海外单元。

### 3.4 Nacos 路由表

Nacos 3.1.1 服务发现采用"单元内优先 + 跨单元白名单"策略：

```properties
# Dubbo 集群路由配置 (dubbo3.3.6)
dubbo.registry.address=nacos://nacos.cell-hz:8848?registry-type=service
dubbo.registry.parameters.namespace=cell-hz
dubbo.registry.parameters.subscribe-remote-namespaces=cell-sz,cell-bj
dubbo.provider.parameters.cell=cell-hz
dubbo.consumer.cluster=cell-aware
dubbo.application.parameters.cell-routing=true
```

Dubbo CellRouter SPI 扩展优先调用本单元 Provider，跨单元调用仅限白名单服务(全局配置、跨单元聚合)。

### 3.5 Sentinel 跨区域限流

Sentinel 1.8.9 部署集群限流令牌服务器(Token Server)：

| 层级 | 限流维度 | 阈值 | 策略 |
|------|----------|------|------|
| 单元级 | 单元总 QPS | 300,000 QPS/单元 | 集群令牌 |
| 服务级 | 单服务 QPS | 50,000 QPS/服务 | 集群令牌 |
| 接口级 | 关键接口 QPS | 10,000 QPS/接口 | 单机滑窗 |
| 跨单元 | 跨单元调用 QPS | 5,000 QPS | 集群令牌 + 降级 |
| 灾备切换 | 切流单元承载 | 容量 150% | 令牌预占 |

Sentinel Dashboard 部署在每个单元，通过 Nacos 同步规则，集群限流 Token Server 每单元 3 节点 HA。

---

## 4. 数据同步策略

### 4.1 TiDB 跨地域复制

国内三地采用 TiDB 原生 Raft 跨地域部署，PD 调度器配置 `location-labels` 实现副本地理分布：

```sql
-- PD 配置: 副本跨3地分布
pd-ctl config set location-labels region,zone
pd-ctl config set max-replicas 3
-- Region 副本分布: cell-HZ / cell-SZ / cell-BJ 各1副本
```

| 参数 | 国内值 | 海外值 | 说明 |
|------|--------|--------|------|
| max-replicas | 3 | 3 | 跨AZ副本数 |
| location-labels | region,zone | zone | 副本分布标签 |
| leader-transfer | 自动均衡 | 固定主AZ | Leader 调度策略 |
| follower-read | true | true | 就近读 |
| raft heartbeat | 100ms | 50ms | 同AZ低延迟 |

海外单元与国内之间通过 **TiDB CDC (Change Data Capture)** 单向同步必要的全局只读数据(课程目录、配置字典)，业务数据不同步以遵守数据主权。

### 4.2 CDC 数据管道

```
[国内 TiDB] --CDC--> [Kafka Connect] --过滤--> [Pulsar Geo] --> [海外 TiDB (只读副本)]
                       |                                    |
                       +-- 全局元数据(课程/配置)             +-- 海外只读, 禁止写
```

CDC 管道配置(TiCDC 7.5.x):

```yaml
ticdc:
  changefeed:
    id: "global-metadata-sync"
    sink-uri: "kafka://kafka-connect.cell-hz:9092/global-metadata"
    filter:
      rules:
        - "ep_course.*"           # 课程目录
        - "ep_config.*"           # 全局配置
        - "ep_dict.*"             # 数据字典
      ignore:
        - "ep_user.*"             # 用户数据不同步
        - "ep_order.*"            # 订单数据不同步
    sync-point: true
    sync-interval: 5m
```

### 4.3 Redis Cluster 跨单元同步

会话状态采用 Redis Cluster 7.2 + CRDT(通过 RedisGears / 自研模块)实现跨单元最终一致：

| 数据类型 | 同步方式 | 冲突解决 | 延迟 |
|----------|----------|----------|------|
| Session | CRDT OR-Set | 自动合并 | <2s |
| 用户在线状态 | LWW-Register | 时间戳 LWW | <1s |
| 验证码 | 不同步(单元本地) | N/A | N/A |
| 热点缓存 | 单元本地重建 | N/A | N/A |

Redis Cluster 每单元 6 节点(3主3从)，跨单元通过 Pulsar 异步广播 CRDT 操作日志。

### 4.4 Pulsar Geo-Replication

Pulsar 3.3.9 Geo-Replication 用于跨地域事件同步：

```
cell-HZ Pulsar ──geo──> cell-SZ Pulsar ──geo──> cell-BJ Pulsar
      │                                              │
      └────────────── 双向全互联 ────────────────────┘

cell-HZ/SZ/BJ ──geo(单向, 仅元数据)──> cell-SG/US/EU
```

| Topic 命名空间 | 复制方向 | 说明 |
|----------------|----------|------|
| persistent://ep/global-events | 三地双向 | 全局业务事件 |
| persistent://ep/learning-events | 国内三地双向 | 学习行为事件 |
| persistent://ep/config-change | 全球单向(国内→海外) | 配置变更通知 |
| persistent://ep/audit-log | 区域本地, 不复制 | 审计日志留本区域 |

Pulsar Geo 配置:

```properties
brokerDeleteInactiveTopicsEnabled=false
replicationConnectionsPerBroker=16
replicationProducerQueueSize=1000
# 跨地域延迟容忍
defaultRetentionTimeInMinutes=10080
```

### 4.5 数据同步延迟监控

| 指标 | 目标 | 告警阈值 | 检测方式 |
|------|------|----------|----------|
| TiDB Raft 跨地延迟 | <1s | >3s | PD metrics |
| CDC 管道延迟 | <30s | >120s | TiCDC checkpoint |
| Redis CRDT 延迟 | <2s | >5s | 自研 heartbeat |
| Pulsar Geo 延迟 | <10s | >30s | replication backlog |

---

## 5. 单元化Cell部署

### 5.1 Cell 定义

单元(Cell)是平台部署的最小自治单位，包含完整的应用栈(接入/应用/数据)，可独立承载流量。单元化遵循 D-012 (Shard-by-UserID)。

```
+---------------------------------------------------+
|                    Cell 结构                       |
|  +-------+  +-------+  +-------+  +-------+      |
|  |接入层 |  |应用层 |  |中间件 |  |数据层 |      |
|  |GSLB   |  |K8s    |  |Nacos  |  |TiDB   |      |
|  |LB     |  |Pods   |  |Sentinel| |Redis  |      |
|  |CDN    |  |       |  |Seata  |  |Pulsar |      |
|  +-------+  +-------+  +-------+  +-------+      |
|  域名: cell-XX.ep-cn.com                          |
|  单元ID: cell-{HZ,SZ,BJ,SG,US,EU}                 |
+---------------------------------------------------+
```

### 5.2 单元实例规格

以国内单元 cell-HZ 为例(支撑 33万 CCU, 单元占总容量 1/3):

| 组件 | 实例数 | 规格 | 说明 |
|------|--------|------|------|
| K8s Worker | 60 | 32C 128G | 应用 Pod 节点 |
| TiDB Server | 6 | 16C 64G | SQL 层 |
| TiKV Node | 9 | 32C 128G + 4T NVMe | 存储层 |
| PD Node | 3 | 8C 16G | 调度层 |
| Redis Cluster | 6 | 16C 64G + 512G | 会话/缓存 |
| Pulsar Broker | 6 | 16C 64G | 消息 |
| BookKeeper | 9 | 16C 64G + 2T | 消息存储 |
| Nacos | 3 | 8C 16G | 注册/配置 |
| Sentinel Token Server | 3 | 8C 16G | 集群限流 |
| Seata TC | 3 | 8C 16G | 事务协调 |
| Ingress/Gateway | 6 | 16C 32G | 流量入口 |

海外单元 cell-SG/US/EU 规格(支撑 5万 CCU):

| 组件 | 实例数 | 规格 |
|------|--------|------|
| K8s Worker | 12 | 32C 128G |
| TiDB Server | 3 | 16C 64G |
| TiKV Node | 3 | 32C 128G + 4T |
| Redis Cluster | 6 | 16C 64G |
| Pulsar Broker | 3 | 16C 64G |
| BookKeeper | 3 | 16C 64G + 2T |
| Nacos | 3 | 8C 16G |

### 5.3 单元路由

单元路由基于 UserID 一致性哈希，路由信息存储在 Nacos 配置中心，GSLB + CellRouter 双层路由保证用户请求到达正确单元。

```
请求路径:
  User(UID=12345) 
    → DNS GSLB(就近 + 健康) 
    → CellRouter(UID hash → cell-SZ) 
    → cell-SZ K8s Ingress 
    → cell-SZ App Pod
```

如果用户请求落在非归属单元(如出差到北京访问 cell-BJ)，CellRouter 通过 Dubbo 集群路由将 RPC 转发至归属单元 cell-SZ，HTTP 层通过 302 重定向至归属单元。

### 5.4 单元隔离

| 隔离维度 | 实现 |
|----------|------|
| 网络隔离 | 每单元独立 VPC, K8s NetworkPolicy (Cilium) |
| 数据隔离 | TiDB 独立集群, 跨单元无共享存储 |
| 配置隔离 | Nacos 独立 namespace, cell-XX |
| 流量隔离 | 独立 Ingress, 独立 LB VIP |
| 故障隔离 | 单元故障不影响其他单元, 熔断器隔离 |
| 资源隔离 | K8s ResourceQuota + LimitRange |

### 5.5 单元故障切换(Failover)

单元故障切换流程(G-08: <30s):

```
T+0s:   cell-HZ 健康检查连续 3 次失败 (15s)
T+3s:   GSLB 标记 cell-HZ 为不可用, TTL 30s
T+5s:   CellRouter 更新路由表, cell-HZ 用户 → cell-SZ/cell-BJ
T+10s:  Redis CRDT 会话已同步, 用户无感切换
T+15s:  TiDB Leader 切换至 cell-SZ (Raft 重新选举)
T+30s:  全部流量切换完成, 切换耗时 <30s

T+300s: cell-HZ 恢复, 进入预热(灰度回切)
T+600s: cell-HZ 恢复承载流量
```

切流容量校验: 备用单元须预留 50% 容量余量，Sentinel 限流令牌预占机制保证切流后不雪崩。

---

## 6. 海外隔离方案

### 6.1 数据主权原则

遵循 D-004 海外独立集群 + PIPL 合规决策，海外数据严格不回流国内：

| 法规 | 适用区域 | 要求 | 实现 |
|------|----------|------|------|
| PIPL | 中国大陆 | 个人信息出境需审批 | 海外数据不入境 |
| GDPR | 欧盟 | 数据本地化, 被遗忘权 | cell-EU 独立, 支持删除权 |
| CCPA | 加州 | 消费者数据权 | cell-US 独立, 支持opt-out |
| PDPA | 新加坡 | 数据保护 | cell-SG 独立 |

### 6.2 区域数据隔离架构

```
+-------------------+        +-------------------+        +-------------------+
|    cell-SG        |        |    cell-US        |        |    cell-EU        |
|  东南亚数据(PII)  |        |  北美数据(PII)    |        |  欧洲数据(PII)    |
|                   |        |                   |        |                   |
| TiDB(独立)        |        | TiDB(独立)        |        | TiDB(独立)        |
| Redis(独立)       |        | Redis(独立)       |        | Redis(独立)       |
| Pulsar(独立)      |        | Pulsar(独立)      |        | Pulsar(独立)      |
| 对象存储(独立)    |        | 对象存储(独立)    |        | 对象存储(独立)    |
+--------+----------+        +--------+----------+        +--------+----------+
         |                            |                            |
         +----- 仅元数据单向同步 -----+----------------------------+
         |  (课程目录/配置, 不含PII)  |
         v                            v                            v
+--------------------------------------------------------------------------------+
|              国内全球元数据中心 (cell-HZ, 只读分发源)                            |
+--------------------------------------------------------------------------------+
```

### 6.3 独立基础设施清单

海外每个单元(cell-SG/US/EU)部署完全独立的基础设施，与国内无共享：

| 组件 | 国内共享? | 海外独立? | 说明 |
|------|-----------|-----------|------|
| K8s 集群 | 是(三地多活) | 是 | 海外独立集群 |
| TiDB | 是(三地Raft) | 是 | 海外独立TiDB |
| Redis | 是(三地CRDT) | 是 | 海外独立Cluster |
| Pulsar | 是(三地Geo) | 是 | 海外独立Pulsar, 仅元数据Geo |
| Nacos | 是(三地同步) | 是 | 海外独立Nacos |
| 镜像仓库 | 共享(国内源) | 独立副本 | 海外本地镜像仓库, 异步同步 |
| KMS | 独立 | 独立 | 区域独立密钥管理 |
| 日志中心 | 区域本地 | 区域本地 | 审计日志不跨境 |
| 监控 | 联邦查询 | 区域本地采集 | 海外本地Prometheus |

### 6.4 跨境数据传输白名单

仅以下数据允许跨境(国内 ↔ 海外)，且必须脱敏/不含 PII：

| 数据 | 方向 | 内容 | 合规依据 |
|------|------|------|----------|
| 课程目录 | 国内→海外 | 课程元数据(无PII) | 业务必要 |
| 全局配置 | 国内→海外 | 平台配置 | 业务必要 |
| 软件版本 | 国内→海外 | 镜像/制品 | 业务必要 |
| 聚合统计 | 海外→国内 | 脱敏聚合指标 | 业务必要 |
| 审计日志 | 不跨境 | N/A | 合规禁止 |

所有跨境传输通过专用加密专线(TLS 1.3 + mTLS)，并记录完整审计日志。

---

## 7. 容灾与切换

### 7.1 RTO/RPO 目标

| 场景 | RTO | RPO | 依据 |
|------|-----|-----|------|
| 单元内 AZ 故障 | <30s | 0 | 同步副本 |
| 单元故障(国内) | <30s | 0 | G-08, Raft多数派 |
| 区域故障(国内三地之一) | <5min | <1s | 切流至其余两地 |
| 海外单元主备切换 | <60s | 0 | 同AZ同步Raft |
| 国内全域故障 | <30min | <10s | 切至海外(仅元数据) |
| 数据中心级灾难 | <4h | <1min | 灾难恢复演练 |

### 7.2 故障切换矩阵

| 故障级别 | 故障范围 | 切换策略 | 负责人 |
|----------|----------|----------|--------|
| L1 | 单 Pod | K8s 自愈 | 自动 |
| L2 | 单节点 | K8s 重调度 | 自动 |
| L3 | 单 AZ | 单元内 AZ 切换 | 自动 |
| L4 | 单元 | 跨单元切流 | SRE值班 |
| L5 | 区域 | 跨区域切流 | 架构师 + SRE |
| L6 | 数据中心 | 灾难恢复 | CTO + 灾备团队 |

### 7.3 健康检查机制

| 检查项 | 方式 | 间隔 | 失败阈值 | 动作 |
|--------|------|------|----------|------|
| Pod 存活 | livenessProbe | 10s | 5次 | 重启 Pod |
| Pod 就绪 | readinessProbe | 5s | 5次 | 摘除流量 |
| 服务健康 | /actuator/health | 5s | 3次 | Nacos 摘除 |
| 单元健康 | GSLB HTTP 检查 | 5s | 3次 | GSLB 摘除 |
| 数据库健康 | TiDB metrics | 10s | 3次 | 触发切流 |
| 跨单元链路 | Dubbo 心跳 | 15s | 3次 | 熔断 |

### 7.4 熔断器配置

Sentinel 熔断规则(跨单元调用):

```java
@SentinelResource(value = "cross-cell-call", blockHandler = "crossCellFallback")
@DubboReference(cluster = "cell-aware", check = false)
private GlobalConfigService globalConfigService;

// Sentinel 熔断规则
DegradeRule rule = new DegradeRule("cross-cell-call")
    .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
    .setCount(0.5)           // 错误率 50%
    .setTimeWindow(30)        // 熔断 30s
    .setMinRequestAmount(20)  // 最小请求 20
    .setStatIntervalMs(10000); // 统计窗口 10s
```

### 7.5 切流 Runbook (L4 单元故障)

```
1. 确认故障 (Prometheus 告警 + GSLB 健康检查)
   - cell-HZ 连续 3 次健康检查失败
   - 错误率 > 5%, P99 > 2s

2. 决策切流
   - SRE 值班确认无误报
   - 通知架构师, 启动 L4 切流

3. 执行切流
   - GSLB 标记 cell-HZ 不可用
   - CellRouter 路由表更新 (Nacos 下发)
   - TiDB Leader 切换 (pd-ctl transfer-leader)
   - Sentinel 限流阈值调整 (备用单元提额)

4. 验证切换
   - 核心接口可用性 > 99.99%
   - 切换耗时 < 30s
   - 用户会话无丢失

5. 故障恢复
   - cell-HZ 修复后灰度回切
   - 10% → 30% → 100% 流量回切
   - 观察 30min 无异常后全量恢复
```

---

## 8. 网络架构

### 8.1 全球网络拓扑

```
+------------------------------------------------------------------+
|                      全球网络骨干                                 |
|   +----------+     专线     +----------+     专线    +----------+ |
|   | 国内华东 |<===========>| 国内华南 |<==========>| 国内华北 | |
|   |  VPC-HZ  |   10Gbps    |  VPC-SZ  |   10Gbps   |  VPC-BJ  | |
|   +----+-----+             +----+-----+            +----+-----+ |
|        |                        |                       |       |
|        +------- 专线 2Gbps ------+------- 专线 2Gbps ----+       |
|                 (仅元数据, 加密)                                   |
|        |                        |                       |       |
|   +----v-----+            +----v-----+            +----v-----+   |
|   | 东南亚SG |            | 北美US   |            | 欧洲EU   |   |
|   | VPC-SG   |            | VPC-US   |            | VPC-EU   |   |
|   +----------+            +----------+            +----------+   |
+------------------------------------------------------------------+
```

### 8.2 VPC Peering 与专线

| 链路 | 带宽 | 延迟 | 用途 | 加密 |
|------|------|------|------|------|
| HZ ↔ SZ | 10Gbps | <15ms | 数据同步+RPC | IPsec |
| HZ ↔ BJ | 10Gbps | <20ms | 数据同步+RPC | IPsec |
| SZ ↔ BJ | 10Gbps | <25ms | 数据同步+RPC | IPsec |
| HZ ↔ SG | 2Gbps | <60ms | 元数据同步 | mTLS |
| HZ ↔ US | 1Gbps | <150ms | 元数据同步 | mTLS |
| HZ ↔ EU | 1Gbps | <180ms | 元数据同步 | mTLS |

国内三地采用 VPC Peering + 专线直连(主)/VPN(备), 海外采用专线(主)/公网IPsec VPN(备)。

### 8.3 CDN 策略

| 区域 | CDN 提供商 | 边缘节点 | 回源 |
|------|-----------|----------|------|
| 国内 | 阿里云CDN + 腾讯云CDN(双CDN) | 30+ 省级节点 | 就近单元 |
| 东南亚 | Cloudflare + 阿里云国际 | SG/HK/JP/ID | cell-SG |
| 北美 | Cloudflare + AWS CloudFront | US-VA/US-CA | cell-US |
| 欧洲 | Cloudflare + AWS CloudFront | DE/FR/UK | cell-EU |

CDN 缓存策略:

| 资源类型 | 缓存时间 | 回源策略 |
|----------|----------|----------|
| 静态资源(JS/CSS/图片) | 30天 | 版本号哈希, 永不回源 |
| 课程视频(HLS) | 7天 | 边缘缓存, 回源单元 |
| 直播流 | 不缓存 | 直接回源单元 |
| API 响应 | 按接口 | Cache-Control 协商 |
| 用户内容(头像等) | 1天 | CDN + 对象存储直连 |

### 8.4 边缘节点

在用户密集区域部署边缘计算节点(K3s 轻量集群), 承担部分边缘逻辑:

| 边缘节点 | 位置 | 用途 |
|----------|------|------|
| edge-HK | 香港 | 港澳台接入加速 |
| edge-JP | 东京 | 日本用户接入 |
| edge-IND | 孟买 | 印度用户接入 |
| edge-BR | 圣保罗 | 南美用户接入 |

边缘节点仅部署接入层(Spring Cloud Gateway + 静态资源), 业务逻辑回源至主单元。

---

## 9. Kubernetes 集群规划

### 9.1 多集群拓扑

采用 Karmada 1.12 进行多集群联邦管理:

```
                    +---------------------------+
                    |   Karmada Control Plane    |
                    |   (部署在 cell-HZ, 备BJ)   |
                    +-------------+-------------+
                                  |
          +-----------------------+-----------------------+
          |                       |                       |
+---------v---------+  +----------v---------+  +----------v---------+
|  K8s cell-HZ       |  |  K8s cell-SZ       |  |  K8s cell-BJ       |
|  v1.30, 60 worker  |  |  v1.30, 60 worker  |  |  v1.30, 60 worker  |
+--------------------+  +--------------------+  +--------------------+
          |                       |                       |
+---------v---------+  +----------v---------+  +----------v---------+
|  K8s cell-SG       |  |  K8s cell-US       |  |  K8s cell-EU       |
|  v1.30, 12 worker  |  |  v1.30, 12 worker  |  |  v1.30, 12 worker  |
+--------------------+  +--------------------+  +--------------------+
```

### 9.2 Karmada 联邦配置

```yaml
apiVersion: policy.karmada.io/v1alpha1
kind: PropagationPolicy
metadata:
  name: ep-service-propagation
  namespace: prod
spec:
  resourceSelectors:
    - apiVersion: apps/v1
      kind: Deployment
  placement:
    clusterAffinity:
      clusterNames:
        - cell-hz
        - cell-sz
        - cell-bj
    replicaScheduling:
      replicaSchedulingType: Divided
      replicaDivisionPreference: Weighted
      weightPreference:
        staticWeightList:
          - targetCluster:
              clusterNames: [cell-hz]
            weight: 1
          - targetCluster:
              clusterNames: [cell-sz]
            weight: 1
          - targetCluster:
              clusterNames: [cell-bj]
            weight: 1
```

### 9.3 Namespace 规划

| Namespace | 用途 | 资源配额(CPU/内存) |
|-----------|------|-------------------|
| prod | 生产业务服务 | 800核 / 1600Gi |
| prod-middleware | 中间件(TiDB/Redis/Pulsar) | 600核 / 2400Gi |
| prod-monitoring | 监控(Prometheus/Grafana) | 100核 / 200Gi |
| prod-ingress | 接入层(Gateway/LB) | 200核 / 400Gi |
| prod-logging | 日志(Fluentd/Loki) | 80核 / 160Gi |
| prod-security | 安全(Audit/Falco) | 40核 / 80Gi |
| canary | 金丝雀发布 | 100核 / 200Gi |

### 9.4 资源配额(ResourceQuota)

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: prod-quota
  namespace: prod
spec:
  hard:
    requests.cpu: "800"
    requests.memory: 1600Gi
    limits.cpu: "1600"
    limits.memory: 3200Gi
    persistentvolumeclaims: "50"
    pods: "500"
    services.loadbalancers: "10"
---
apiVersion: v1
kind: LimitRange
metadata:
  name: prod-limits
  namespace: prod
spec:
  limits:
    - type: Container
      default:
        cpu: "2"
        memory: 4Gi
      defaultRequest:
        cpu: "500m"
        memory: 1Gi
      max:
        cpu: "8"
        memory: 16Gi
      min:
        cpu: "100m"
        memory: 256Mi
```

### 9.5 多集群网络 (Cilium Cluster Mesh)

采用 Cilium 1.16 Cluster Mesh 实现跨集群 Pod 通信与网络策略:

```yaml
# Cilium Cluster Mesh 配置
cluster:
  name: cell-hz
  id: 1
clustermesh:
  enable: true
  clusters:
    - name: cell-sz
      address: cilium-external.cell-sz.ep-cn.com
    - name: cell-bj
      address: cilium-external.cell-bj.ep-cn.com
```

跨集群网络策略:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cross-cell-allow
  namespace: prod
spec:
  podSelector:
    matchLabels:
      tier: backend
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: prod
          podSelector:
            matchLabels:
              tier: gateway
        # 跨集群 Pod CIDR (Cilium 自动识别)
        - ipBlock:
            cidr: 10.244.0.0/16  # cell-sz pod cidr
        - ipBlock:
            cidr: 10.245.0.0/16  # cell-bj pod cidr
```

### 9.6 节点池规划

以 cell-HZ (60 worker) 为例:

| 节点池 | 节点数 | 规格 | 污点 | 用途 |
|--------|--------|------|------|------|
| system-pool | 6 | 16C 32G | CriticalAddonsOnly | 系统组件 |
| app-pool | 40 | 32C 128G | 无 | 业务 Pod |
| middleware-pool | 8 | 32C 256G + NVMe | middleware-only | TiDB/Redis |
| ingress-pool | 4 | 16C 32G | ingress-only | 网关 |
| gpu-pool | 2 | 8C 64G + A10 | gpu-only | AI 推理 |

---

## 10. 监控与告警

### 10.1 跨区域监控架构

采用 Prometheus 2.55 联邦(Federation)架构，每单元本地 Prometheus + 全局联邦 Prometheus:

```
+------------------+  +------------------+  +------------------+
| cell-HZ Prom     |  | cell-SZ Prom     |  | cell-BJ Prom     |
| (本地采集, 15s)   |  | (本地采集, 15s)   |  | (本地采集, 15s)   |
+--------+---------+  +--------+---------+  +--------+---------+
         |                     |                     |
         +-------federate------+-------federate------+
                               |
                  +------------v------------+
                  |  Global Prometheus       |
                  |  (联邦查询, 30s)         |
                  |  部署在 cell-HZ (备BJ)   |
                  +------------+------------+
                               |
                  +------------v------------+
                  |   Grafana / AlertManager |
                  |   (全局视图 + 告警路由)   |
                  +-------------------------+
```

海外单元(cell-SG/US/EU)部署独立 Prometheus + Grafana, 仅聚合指标(无PII)上报至全局联邦。

### 10.2 Prometheus 联邦配置

```yaml
# Global Prometheus 联邦抓取配置
scrape_configs:
  - job_name: federate-cell-hz
    honor_labels: true
    metrics_path: /federate
    params:
      match[]:
        - '{job="kubernetes-pods"}'
        - '{__name__=~"haproxy_.*|dubbo_.*|tidb_.*|pulsar_.*"}'
    static_configs:
      - targets: ['prometheus.cell-hz:9090']
        labels:
          cell: cell-hz
  - job_name: federate-cell-sz
    honor_labels: true
    metrics_path: /federate
    params:
      match[]:
        - '{job="kubernetes-pods"}'
    static_configs:
      - targets: ['prometheus.cell-sz:9090']
        labels:
          cell: cell-sz
  - job_name: federate-cell-bj
    honor_labels: true
    metrics_path: /federate
    params:
      match[]:
        - '{job="kubernetes-pods"}'
    static_configs:
      - targets: ['prometheus.cell-bj:9090']
        labels:
          cell: cell-bj
```

### 10.3 关键指标与告警规则

| 指标 | PromQL | 告警阈值 | 持续 | 级别 |
|------|--------|----------|------|------|
| 单元可用性 | `up{job="cell-gateway"} == 0` | 1次 | 0s | P0 |
| API 错误率 | `rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m])` | >1% | 2min | P0 |
| API P99 延迟 | `histogram_quantile(0.99, http_request_duration_seconds_bucket)` | >500ms | 5min | P1 |
| 单元 CCU | `redis_connected_clients{job="session"}` | >330000 | 5min | P1 |
| TiDB 副本数 | `pd_cluster_status{type="store_up_count"}` | <3 | 0s | P0 |
| Raft 延迟 | `raftstore_propose_wait_time` | >1s | 5min | P1 |
| Pulsar 积压 | `pulsar_backlog_size` | >100000 | 5min | P1 |
| 跨单元延迟 | `dubbo_consumer_latency{cell!="local"}` | >200ms | 5min | P2 |
| K8s 节点就绪 | `kube_node_status_condition{condition="Ready"} != 1` | 1次 | 0s | P0 |
| 磁盘使用 | `node_filesystem_avail_bytes / node_filesystem_size_bytes` | <20% | 10min | P1 |

### 10.4 SLO 仪表盘

| 仪表盘 | 数据源 | 刷新 | 受众 |
|--------|--------|------|------|
| 全球总览 | Global Prometheus | 30s | CTO/架构师 |
| 单元详情 | 本地 Prometheus | 15s | SRE |
| 业务SLI | Global Prometheus | 30s | 业务方 |
| 容灾状态 | 联邦 + GSLB API | 10s | SRE值班 |
| 合规审计 | 本地 Loki | 5min | 安全合规 |

---

## 11. CI/CD 多区域发布

### 11.1 发布流水线架构

```
开发提交 → CI构建 → 镜像推送 → 多区域部署
            |          |              |
            v          v              v
         单元测试    全球镜像仓库   Karmada 调度
         集成测试    (国内主+海外副本) (蓝绿/金丝雀)
```

### 11.2 镜像分发策略

| 区域 | 镜像仓库 | 同步方式 | 延迟 |
|------|----------|----------|------|
| 国内 | registry.cn-hangzhou.ep-cn.com (主) | CI 直推 | 实时 |
| 国内 | registry.cn-shenzhen.ep-cn.com | P2P 同步 | <5min |
| 国内 | registry.cn-beijing.ep-cn.com | P2P 同步 | <5min |
| 东南亚 | registry.ap-southeast-1.ep-global.com | 异步同步 | <30min |
| 北美 | registry.us-east-1.ep-global.com | 异步同步 | <30min |
| 欧洲 | registry.eu-central-1.ep-global.com | 异步同步 | <30min |

海外镜像仓库采用 Harbor 复制策略, 仅同步已审批的发布版本。

### 11.3 蓝绿部署

国内单元采用蓝绿部署, 通过 Karmada + Service Selector 切换:

```yaml
# 蓝绿部署 - 绿色版本
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ucm-service-green
  namespace: prod
  labels:
    app: ucm-service
    version: green
    cell: cell-hz
spec:
  replicas: 4
  selector:
    matchLabels:
      app: ucm-service
      version: green
---
# Service 指向绿色
apiVersion: v1
kind: Service
metadata:
  name: ucm-service
spec:
  selector:
    app: ucm-service
    version: green   # 切换: green <-> blue
```

### 11.4 金丝雀发布(跨区域)

跨区域金丝雀发布顺序(从单单元小流量到全量):

| 阶段 | 单元 | 流量比例 | 观察期 | 通过条件 |
|------|------|----------|--------|----------|
| 1 | cell-HZ(金丝雀单元) | 5% | 30min | 错误率<0.1%, P99<500ms |
| 2 | cell-HZ | 25% | 30min | 错误率<0.1% |
| 3 | cell-HZ | 100% | 1h | SLO 达标 |
| 4 | cell-SZ + cell-BJ | 25% | 30min | 错误率<0.1% |
| 5 | cell-SZ + cell-BJ | 100% | 1h | SLO 达标 |
| 6 | cell-SG | 100% | 1h | 海外验证 |
| 7 | cell-US + cell-EU | 100% | 1h | 全球完成 |

金丝雀流量切分通过 Istio VirtualService (或 Spring Cloud Gateway weight) 实现。

### 11.5 回滚策略

| 回滚类型 | 触发条件 | 策略 | 耗时 |
|----------|----------|------|------|
| 镜像回滚 | 错误率>1% | K8s rollout undo | <2min |
| 蓝绿回滚 | 严重故障 | Service selector 切换 | <30s |
| 配置回滚 | 配置错误 | Nacos 历史版本 | <10s |
| 数据回滚 | 数据损坏 | TiDB flashback | <15min |
| 全量回滚 | 全球故障 | Karmada 批量回滚 | <10min |

回滚自动化: Prometheus 告警 → AlertManager → Webhook → Argo Rollouts 自动回滚(可配置自动/人工确认)。

### 11.6 Argo Rollouts 金丝雀配置

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: ucm-service
  namespace: prod
spec:
  replicas: 4
  strategy:
    canary:
      canaryService: ucm-service-canary
      stableService: ucm-service-stable
      trafficRouting:
        istio:
          virtualService:
            name: ucm-service-vs
            routes: [primary]
      steps:
        - setWeight: 5
        - pause: { duration: 30m }
        - setWeight: 25
        - pause: { duration: 30m }
        - setWeight: 100
        - pause: { duration: 1h }
      analysis:
        templates:
          - templateName: success-rate
        args:
          - name: service-name
            value: ucm-service
```

---

## 12. 安全与合规

### 12.1 跨区域 TLS 体系

```
[客户端]
   |
   v  (TLS 1.3, 公网证书: Let's Encrypt / DigiCert)
[CDN 边缘]
   |
   v  (mTLS, 内部CA: cfssl)
[CellRouter / Gateway]
   |
   v  (mTLS, 服务网格 Istio / Cilium)
[应用 Pod]
   |
   v  (TLS, 内部CA)
[TiDB / Redis / Pulsar]
```

| 通信链路 | 加密 | 证书 | 轮换 |
|----------|------|------|------|
| 客户端 ↔ CDN | TLS 1.3 | 公网CA | 90天 |
| CDN ↔ 单元入口 | TLS 1.3 | 公网CA | 90天 |
| 单元内服务间 | mTLS | 内部CA(Istio) | 24h自动 |
| 跨单元 RPC | mTLS | 内部CA | 24h自动 |
| 应用 ↔ 数据库 | TLS | 内部CA | 90天 |
| 跨地域专线 | IPsec + mTLS | 内部CA | 90天 |

### 12.2 KMS 密钥管理

每区域独立 KMS (HashiCorp Vault / 阿里云KMS / AWS KMS), 密钥不跨境:

| 密钥用途 | 国内KMS (cell-HZ) | 海外KMS | 轮换 |
|----------|-------------------|---------|------|
| 数据库加密 | ep-tidb-tde-key | 区域独立 | 365天 |
| 对象存储加密 | ep-oss-sse-key | 区域独立 | 365天 |
| JWT 签名 | ep-jwt-sign-key | 区域独立 | 90天 |
| Pulsar 加密 | ep-pulsar-enc-key | 区域独立 | 90天 |
| mTLS CA | ep-internal-ca | 区域独立 | 365天 |

### 12.3 数据加密

| 数据状态 | 加密方式 | 算法 |
|----------|----------|------|
| 传输中(in transit) | TLS 1.3 / mTLS | X25519 + AES-256-GCM |
| 静态存储(at rest) | 磁盘加密 + 应用加密 | AES-256-GCM |
| 数据库 TDE | TiDB 透明数据加密 | AES-256-CTR |
| 备份加密 | 异地备份加密 | AES-256-GCM |
| PII 字段加密 | 应用层字段加密 | RSA-2048 + AES-256 |

### 12.4 审计日志

审计日志严格区域本地存储, 不跨境:

| 日志类型 | 存储 | 保留 | 合规 |
|----------|------|------|------|
| 访问日志 | 区域本地 ELK | 90天 | PIPL/GDPR |
| 操作审计 | 区域本地 Loki | 365天 | SOX |
| 安全审计 | 区域本地 WAF | 365天 | 等保2.0 |
| 数据访问审计 | TiDB Audit Log | 365天 | GDPR |

### 12.5 合规检查清单

| 检查项 | 国内 | 海外EU | 海外US | 海外SG |
|--------|------|--------|--------|--------|
| 数据本地化 | ✅ | ✅ GDPR | ✅ CCPA | ✅ PDPA |
| 数据出境审批 | N/A | 禁止出境 | 禁止出境 | 禁止出境 |
| 被遗忘权 | ✅ 注销 | ✅ GDPR Art.17 | ✅ CCPA | ✅ |
| 数据可携带权 | ✅ | ✅ GDPR Art.20 | ✅ | ✅ |
| 加密传输 | ✅ | ✅ | ✅ | ✅ |
| 加密存储 | ✅ | ✅ | ✅ | ✅ |
| 审计日志 | ✅ 本地 | ✅ 本地 | ✅ 本地 | ✅ 本地 |
| 密钥独立 | ✅ | ✅ | ✅ | ✅ |

### 12.6 安全扫描与合规门

CI/CD 流水线集成安全扫描门:

| 阶段 | 工具 | 阻断条件 |
|------|------|----------|
| 代码扫描 | Aikido SAST | 高危漏洞 |
| 依赖扫描 | Snyk / Aikido SCA | CVSS>7 |
| 镜像扫描 | Trivy | 高危CVE |
| 密钥扫描 | GitGuardian | 任何泄露 |
| IaC扫描 | Checkov | 高危配置 |
| 合规扫描 | OPA Gatekeeper | 策略违规 |
| DAST | OWASP ZAP | 高危漏洞 |

任何安全门未通过, 流水线自动中止, 禁止部署至多区域环境。

---

## 13. 附录

### 13.1 技术栈版本完整清单 (D-032)

| 组件 | 版本 | 部署位置 |
|------|------|----------|
| Spring Boot | 4.0.7 | 全部单元 App Pod |
| Spring Cloud | 2025.1.2 | 全部单元 App Pod |
| Spring Cloud Alibaba | 2025.1.0.0 | 全部单元 App Pod |
| Dubbo | 3.3.6 | 全部单元 App Pod (Triple协议) |
| Nacos | 3.1.1 | 每单元 3 节点 Raft |
| Sentinel | 1.8.9 | SDK 嵌入 + Token Server |
| Seata | 2.5.0 | 每单元 3 节点 TC (单元内) |
| Pulsar | 3.3.9 | 每单元 6 Broker + 9 BookKeeper |
| TiDB | 7.5.x | 国内3地Raft, 海外独立 |
| Redis | 7.2 Cluster | 每单元 6 节点 |
| Kubernetes | 1.30 | 6 集群 |
| Karmada | 1.12 | 控制面 cell-HZ(备BJ) |
| Cilium | 1.16 | 全部集群 CNI |
| Istio | 1.23 | 服务网格(可选, mTLS) |
| Prometheus | 2.55 | 每单元 + 联邦 |
| Grafana | 11.2 | 全局 + 每单元 |
| Argo CD | 2.12 | GitOps 部署 |
| Argo Rollouts | 2.4 | 金丝雀发布 |
| Harbor | 2.11 | 镜像仓库 |
| HashiCorp Vault | 1.18 | KMS |

### 13.2 容量规划汇总

| 单元 | CCU 容量 | K8s节点 | TiDB节点 | Redis节点 | Pulsar节点 |
|------|----------|---------|----------|-----------|------------|
| cell-HZ | 330,000 | 60 | 18 | 6 | 15 |
| cell-SZ | 330,000 | 60 | 18 | 6 | 15 |
| cell-BJ | 330,000 | 60 | 18 | 6 | 15 |
| cell-SG | 50,000 | 12 | 6 | 6 | 6 |
| cell-US | 50,000 | 12 | 6 | 6 | 6 |
| cell-EU | 50,000 | 12 | 6 | 6 | 6 |
| **合计** | **1,140,000** | **216** | **72** | **36** | **63** |

总容量 114万 CCU, 满足 G-01 (100万并发) 目标并预留 14% 余量。

### 13.3 双向追溯矩阵

| 文档章节 | 依据 ADR/SLO | 说明 |
|----------|-------------|------|
| §1 部署架构总览 | D-032, G-01, G-02 | 技术栈版本与规模目标 |
| §2 多地多活 | D-012, D-014 | 单元化与多级缓存 |
| §3 流量调度 | D-012, ADR-T03 | Cell路由与跨单元RPC |
| §4 数据同步 | D-015, D-008 | TiDB跨地域与CDC |
| §5 单元化部署 | D-012 | Shard-by-UserID |
| §6 海外隔离 | D-004, PIPL/GDPR | 数据主权 |
| §7 容灾切换 | G-07, G-08 | 可用性与切流 |
| §8 网络架构 | G-03, G-05 | 延迟与CDN |
| §9 K8s规划 | ADR-T05 | 探针与HPA |
| §10 监控告警 | G-03, G-04, G-07 | SLO监控 |
| §11 CI/CD | ADR-T06 | 发布策略 |
| §12 安全合规 | D-004, PIPL, GDPR | 合规隔离 |

### 13.4 术语表

| 术语 | 说明 |
|------|------|
| Cell(单元) | 平台最小自治部署单位, 含完整应用栈 |
| CellRouter | 基于 UserID 路由的单元化网关 |
| GSLB | 全局服务器负载均衡, DNS 层流量调度 |
| Geo-Replication | Pulsar 跨地域消息复制 |
| CRDT | 无冲突复制数据类型, Redis 会话合并 |
| Karmada | Kubernetes 多集群联邦管理 |
| Raft | 分布式共识算法, TiDB 强一致基础 |
| CDC | 变更数据捕获, TiDB 增量同步 |
| RTO | 恢复时间目标(故障到恢复) |
| RPO | 恢复点目标(数据丢失上限) |
| PIPL | 中国个人信息保护法 |
| GDPR | 欧盟通用数据保护条例 |
| CCPA | 加州消费者隐私法 |
| PDPA | 新加坡个人数据保护法 |

---

> **文档结束** | SP-07 多地多中心部署方案 v1.0 | 依据 D-032 / D-004 / D-012 / G-01~G-10 | 2026-08-04
