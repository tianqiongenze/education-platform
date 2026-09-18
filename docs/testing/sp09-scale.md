# SP-09 容量天花板验证方案

> **D-200 Naming Note:** This document's filename retains the legacy code `sp09` for reference continuity. Per D-137, sp09 → boardgame-service. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP09-SCALE-001 | v1.0 | 2026-08-04 | Draft | ZCode(性能架构师) | SP01-SAD-001 / SP01-DEPLOY-001 / D-032 / EP-TOGAF-001 §6 / G-01~G-10 SLO |

> 本文是教育平台的**容量天花板验证基线**，面向 30 亿注册用户 / 100 万并发的长期目标，按组件级、单元(Cell)级、平台级三个层次定义容量模型、压测方案、瓶颈识别方法、弹性扩缩容策略与极端场景验证。所有容量数字均以 D-032 技术栈、单元化架构(每 Cell ~10 万并发)、读写分离(TiDB)、Redis Cluster、Pulsar 事件总线为前提。

---

## 目录

1. [容量规划总览](#1-容量规划总览)
2. [组件级天花板分析](#2-组件级天花板分析)
3. [单元Cell容量模型](#3-单元cell容量模型)
4. [压力测试方案](#4-压力测试方案)
5. [瓶颈识别与优化](#5-瓶颈识别与优化)
6. [容量水位监控](#6-容量水位监控)
7. [弹性扩缩容](#7-弹性扩缩容)
8. [极端场景验证](#8-极端场景验证)
9. [容量评估报告模板](#9-容量评估报告模板)
10. [灾备容量](#10-灾备容量)

---

## 1. 容量规划总览

### 1.1 总体目标

| 指标 | 当前(v1.0) | 中期(v1.5) | 远期(v2.0) | 备注 |
|------|-----------|-----------|-----------|------|
| 注册用户总量 | 5,000 万 | 5 亿 | 30 亿 | 含学员/教师/家长多角色 |
| DAU(日活) | 500 万 | 5,000 万 | 3 亿 | DAU ≈ 注册量 × 10% |
| 峰值并发(PCU) | 50 万 | 200 万 | 100 万(国内) + 海外 50 万 | 100 万为国内 PCU 目标 |
| 峰值 QPS(读) | 50 万 | 200 万 | 1,000 万 | 含缓存命中 |
| 峰值 QPS(写) | 5 万 | 20 万 | 100 万 | 学习记录/事件/订单 |
| 单 Cell 并发 | 10 万 | 10 万 | 10 万 | 单元容量恒定,横向扩 Cell |
| Cell 数量(国内) | 5 | 20 | 10(稳态) / 15(峰值) | 峰值靠弹性 Cell |
| 数据总量 | 50 TB | 500 TB | 5 PB | 含视频元数据/学习记录/审计 |

### 1.2 增长曲线与容量里程碑

```
注册量(亿)
 30 ┤                                              ★ v2.0 远期(30亿)
    │                                        ╱
 20 ┤                                  ╱
    │                            ╱
 10 ┤                      ╱
    │                ╱
  5 ┤          ★ v1.5 中期(5亿)
    │    ╱
  1 ┤★ v1.0 当前(0.5亿)
    └──────────────────────────────────────────────
     2026Q3      2027Q4      2028Q4      2030Q4
```

| 里程碑 | 时间 | 注册量 | 并发 | Cell 数 | 关键容量动作 |
|--------|------|--------|------|---------|------------|
| M1 | 2026Q3 | 0.5 亿 | 50 万 | 5 | 基线压测,建立容量模型 |
| M2 | 2027Q2 | 2 亿 | 100 万 | 10 | 首次 100 万并发验证 |
| M3 | 2027Q4 | 5 亿 | 200 万 | 20 | 海外 Cell 接入,跨域容量 |
| M4 | 2028Q4 | 10 亿 | 300 万 | 30 | 多活容量,灾备 N+1 |
| M5 | 2030Q4 | 30 亿 | 150 万稳态 | 15+灾备 | 平台稳态,优化成本 |

### 1.3 各组件吞吐量目标(平台级,100 万并发场景)

| 组件 | 读 QPS | 写 QPS | 连接数 | 备注 |
|------|--------|--------|--------|------|
| API Gateway(Go) | 200 万 | 20 万 | 100 万 TCP | L7 入口,JWT 验签 |
| BFF(Java/Netty) | 150 万 | 15 万 | 50 万 | Disruptor ingest |
| Dubbo Providers | 100 万 | 10 万 | 5 万 RPC | Triple 协议 |
| Redis Cluster | 400 万 | 40 万 | - | 30 节点(15 主 15 从) |
| TiDB | 80 万 | 8 万 | - | 读写分离,15 TiKV |
| Pulsar | - | 20 万 msg/s | - | 事件总线 + CDC |
| WebSocket Gateway | - | - | 100 万 WS | 长连接,心跳 30s |
| 对象存储(OSS) | 30 万 | 1 万 | - | 视频分片/作业附件 |

### 1.4 延迟预算(SLO @ P99)

| 链路 | 预算(P99) | 拆分 |
|------|----------|------|
| 静态页面 | 100 ms | CDN 30ms + 边缘 20ms + 渲染 50ms |
| API 读(缓存命中) | 50 ms | GW 5ms + BFF 10ms + Redis 5ms + 业务 20ms + 网络 10ms |
| API 读(穿透DB) | 200 ms | GW 5ms + BFF 10ms + TiDB 80ms + 业务 80ms + 网络 25ms |
| API 写 | 300 ms | GW 5ms + BFF 10ms + DB 50ms + Pulsar 20ms + 业务 200ms |
| WebSocket 推送 | 80 ms | GW 10ms + Pulsar 20ms + WS 下行 50ms |
| 视频起播 | 500 ms | CDN 100ms + 调度 100ms + 首片 300ms |
| 作业评测反馈 | 5 s | 沙箱排队 + 编译运行 |

### 1.5 容量规划原则

1. **单元化隔离**:每 Cell 独立承载 ~10 万并发,故障域隔离;跨 Cell 仅共享全局域(UCM/路由)
2. **读写分离**:写走 Primary TiDB,读走 follower/只读副本;热点读走 Redis L2
3. **无状态水平扩展**:BFF/Dubbo Provider 无状态,K8s HPA 按 CPU/QPS 扩缩
4. **有状态分片**:Redis Cluster/Pulsar 按 shard/topic 分片,扩容靠加 shard
5. **预留 30% 水位**:稳态运行水位 ≤70%,触发扩容水位 75%,强制限流水位 90%
6. **N+1 灾备**:每 Cell 容量按 N+1 配置,任一节点故障不降级
7. **成本与容量平衡**:峰值靠弹性 Cell(短时拉起),稳态按需保留,避免长期空跑

---

## 2. 组件级天花板分析

本节逐一分析各核心组件的**理论最大容量**与**瓶颈点**,作为压测目标与扩容依据。所有数字基于 D-032 技术栈在 16C/32G 标准容器规格下的压测基线。

### 2.1 API Gateway(Go,基于 Gin/自研)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 Pod 并发连接(TCP) | 100,000 | 70,000 | fd 上限/内存 | 调高 ulimit -n 至 1,048,576 |
| 单 Pod QPS(短连接) | 80,000 | 56,000 | CPU(syscall) | 复用连接,减少 TLS 握手 |
| 单 Pod QPS(长连接+keepalive) | 200,000 | 140,000 | CPU | JWT 验签批量化 |
| 单 Pod goroutine 数 | 500,000 | 350,000 | 调度开销/GC | 限制每连接 goroutine,用 epoll 池 |
| 单 Pod 内存 | 32 GiB | 24 GiB | GC pause | GOGC=100, GOMEMLIMIT=28GiB |
| 集群(10 Pod)连接 | 1,000,000 | 700,000 | 上游 LB/conntrack | LB conntrack 表扩容 |
| JWT 验签(RS256) | 50,000/s | 35,000/s | CPU(非对称运算) | JWKS 缓存 + 预验证 |

**瓶颈分析**:
- **连接数天花板**:Linux 单 Pod fd 软上限默认 65536,需调至 1M;conntrack 表(LB 侧)默认 262144 需扩至 2M
- **goroutine 天花板**:每个活跃连接 1 goroutine,100 万连接 = 100 万 goroutine,GC 压力骤增;建议引入 `gnet`/evio epoll 模型,每 Pod goroutine 控制在 10 万内
- **JWT 验签**:RS256 验签单核约 5,000 QPS,16C 单 Pod 约 50,000 QPS;建议 JWKS 公钥预热 + L1 缓存(5min TTL)
- **TLS 握手**:短连接场景 TLS 握手占 30% CPU,强制 keepalive + session resumption

**压测验证点**:
1. 单 Pod 连接数从 1 万 → 10 万 → 50 万,记录 GC pause / P99 延迟
2. 单 Pod QPS 阶梯加载至 20 万,定位 CPU/GC 瓶颈
3. 集群 10 Pod 联合压测,验证 LB conntrack 与上游连接复用

### 2.2 BFF(Java/Netty + Disruptor + Spring WebMVC)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 Pod QPS(读) | 50,000 | 35,000 | Netty event loop | eventLoopThreads = CPU×2 |
| 单 Pod QPS(写) | 10,000 | 7,000 | Disruptor 入队 + DB | 批量写、异步落库 |
| Netty event loop 线程 | 32 | 32 | CPU | 禁阻塞操作 |
| WebMVC 请求线程池 | 200 | 140 | 线程上下文切换 | 限流保护,队列 1000 |
| Disruptor RingBuffer | 1,048,576 | 700,000 | 背压 | 满则降级,不丢数据 |
| Disruptor 吞吐(单生产者) | 2,000 万/s | 1,400 万/s | CPU/缓存行 | 单生产者场景 |
| Disruptor 吞吐(多生产者) | 500 万/s | 350 万/s | CAS 竞争 | MultiProducer,批发布 |
| 单 Pod 堆内存 | 8 GiB | 5.6 GiB | GC pause | G1GC, -Xmx6g, -XX:MaxGCPauseMillis=100 |
| 集群(20 Pod)QPS | 1,000,000 | 700,000 | 下游 DB/Redis | 读写分离 + 缓存 |

**瓶颈分析**:
- **Netty event loop 阻塞**:任何阻塞调用(JDBC/同步 RPC)会吃掉 event loop,必须强制异步化或丢到业务线程池
- **Disruptor 背压**:RingBuffer 满时生产者自旋等待,建议 `ProducerType.MULTI` + `BlockingWaitStrategy`(低 CPU 场景)或 `YieldingWaitStrategy`(高吞吐场景)
- **GC pause**:G1GC 在 8G 堆下单次 pause 50-100ms,P99 敏感链路需切 ZGC(-XX:+UseZGC),pause < 10ms
- **线程上下文切换**:WebMVC 200 线程 + Netty 32 线程 + Disruptor 消费线程,总线程数 > 300,需监控上下文切换次数(`ctxt_switches/s`)

**压测验证点**:
1. Disruptor RingBuffer 从 16K → 1M,测多生产者吞吐曲线
2. Netty event loop 阻塞注入(模拟慢 DB),观察延迟劣化
3. G1GC vs ZGC 在 8G 堆下的 P99 对比

### 2.3 Dubbo Providers(Triple RPC)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 Pod QPS(提供者) | 30,000 | 21,000 | 业务线程池 | fixed 线程池 200 |
| 单 Pod 并发调用 | 200 | 140 | 线程池队列 | 队列 1000,FIFO |
| 单 Pod 连接(消费侧) | 100 | 70 | 连接复用 | 共享长连接 |
| Triple 序列化(Protobuf) | 100 万/s | 70 万/s | CPU | 禁 STRICT(ADR-T09) |
| 集群(40 Pod)QPS | 1,200,000 | 840,000 | 下游 DB/Redis | 读写分离 |
| 调用链超时 | 300 ms | - | 级联超时 | Sentinel 熔断 |

**瓶颈分析**:
- **业务线程池打满**:Dubbo 默认 fixed 200 线程,队列 0;高并发下直接拒绝。建议队列 1000 + Sentinel 限流
- **连接数**:Triple 默认共享长连接,单 Pod 100 连接足够;消费侧连接数 = 提供者 Pod 数,需注意大集群(>100 Pod)连接扇出
- **序列化**:Protobuf 性能优于 Hessian2,禁 STRICT 序列化(ADR-T09);大对象(>64KB)建议分片或走对象存储
- **级联超时**:网关 → BFF → Dubbo → DB 链路,每层超时递减(GW 3s → BFF 2s → Dubbo 1s → DB 500ms)

**压测验证点**:
1. 单 Provider 阶梯 QPS 1万 → 3万 → 5万,观察线程池排队与拒绝
2. 40 Pod 集群联合压测,验证负载均衡均匀度(<5% 偏差)
3. 慢调用注入(Provider sleep 500ms),验证 Sentinel 熔断

### 2.4 Redis Cluster(8.0,30 节点 = 15 主 15 从)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 shard(主)QPS | 100,000 | 70,000 | 单线程 CPU | 大 key 拆分,慢查询 |
| 单 shard(主)内存 | 64 GiB | 45 GiB | RDB/AOF fork | 大实例拆分 |
| 集群(15 主)QPS | 1,500,000 | 1,050,000 | 热点 shard | 一致性哈希 + 热点 key 分散 |
| 单 key 最大 | 512 MiB | 1 MiB | 阻塞主线程 | 禁止大 key,列表分片 |
| Pipeline 批量 | 100 万/s | 70 万/s | 网络带宽 | 批量 100 条/pipeline |
| Pub/Sub 单节点 | 50 万 msg/s | 35 万 msg/s | 订阅者扇出 | 用 Pulsar 替代大扇出 |
| 主从复制延迟 | < 1 ms | - | 大 key/全量同步 | 限制 write QPS,小 key |

**瓶颈分析**:
- **单线程模型**:Redis 6.5+ 多线程 IO 但命令执行仍单线程,单 shard CPU 100% 即天花板
- **热点 shard**:用户维度 hash 可能集中(如热门课程),需热点 key 探测 + 本地缓存(Caffeine L1)
- **大 key / 慢查询**:`KEYS *`、`SMEMBERS` 大集合会阻塞主线程;禁用 KEYS,用 SCAN;大集合拆分或用 HSCAN
- **fork 延迟**:RDB/AOF rewrite 时 fork 子进程,64G 内存 fork 可达 100ms+;建议 AOF everysec + 关闭 RDB,或拆小实例(≤16G)
- **集群 reshard**:扩容 reshard 期间性能下降 20-30%,需低峰执行

**压测验证点**:
1. 单 shard memtier_benchmark 压至 10 万 QPS,记录 CPU/延迟
2. 热点 key 注入(80% 流量打 20% key),验证 L1 缓存命中率
3. reshard 期间压测,验证性能影响与数据一致性

### 2.5 TiDB(8.5 LTS,读写分离)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 TiDB Server QPS | 50,000 | 35,000 | CPU/SQL 解析 | prepared statement |
| 单 TiKV 节点 QPS(读) | 80,000 | 56,000 | RocksDB | 热点 region 分裂 |
| 单 TiKV 节点 QPS(写) | 20,000 | 14,000 | Raft + RocksDB | 批量写 |
| 单 TiKV 存储量 | 2 TB | 1.4 TB | RocksDB compaction | region 平衡 |
| 集群(3 TiDB + 15 TiKV)读 QPS | 800,000 | 560,000 | TiDB Server | 加 TiDB 节点 |
| 集群写 QPS | 200,000 | 140,000 | TiKV Raft | 加 TiKV 节点 |
| Region 大小 | 96 MiB | - | 分裂开销 | 默认 96MB,可调 |
| 单事务大小 | 100 MB | 10 MB | 大事务锁 | 拆分小事务 |
| 读写分离只读副本 | 5 | - | 复制延迟 | follower read |

**瓶颈分析**:
- **TiDB Server CPU**:SQL 解析/优化/执行计划生成占 40% CPU;启用 prepared statement 可降 20%
- **TiKV 热点 region**:自增主键 / 时间戳写入集中在单 region,需 `SHARD_ROW_ID_BITS` + `PRE_SPLIT_REGIONS` 预分裂
- **大事务**:单事务 > 100MB 报错;> 10MB 影响延迟;批量写拆分 1MB/事务
- **compaction 写放大**:RocksDB L0→L1 compaction 占 IO,写入陡增时 compaction 跟不上,需调 `write-buffer-size` / `max-bytes-for-level-base`
- **follower read**:只读副本承接读流量,但复制延迟(Raft)可能 100-500ms,强一致读需走 leader

**压测验证点**:
1. sysbench oltp_read_write 压至 20 万 QPS,定位 TiDB/TiKV 瓶颈
2. 自增主键热点写入,验证 pre-split 效果
3. 大事务(50MB)注入,观察延迟与锁冲突

### 2.6 Pulsar(3.3.x,事件总线 + CDC)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 topic 吞吐(partition=1) | 50,000 msg/s | 35,000 | Broker CPU | 分区扩容 |
| 单 topic 吞吐(partition=16) | 800,000 | 560,000 | BookKeeper IO | 分区 + Broker 扩容 |
| 单消息最大 | 5 MB | 256 KB | 内存/网络 | 大消息走对象存储 |
| 单 Broker 吞吐 | 200,000 msg/s | 140,000 | CPU/网卡 | 25G 网卡 |
| BookKeeper 写延迟 | 5 ms | - | fsync | 异步刷盘 + 副本数 2 |
| 积压(backlog)上限 | 无硬限 | 1,000 万 msg | 内存/存储 | TTL 7d + 死信队列 |
| 订阅者扇出 | 1,000 | 200 | Broker 推送 CPU | 共享订阅 + 批量 |
| 跨机房复制 | 50,000 msg/s | 35,000 | 带宽 | 专线带宽预留 |

**瓶颈分析**:
- **分区数**:单 partition 5 万 msg/s 是天花板;高吞吐 topic 必须分区,partition 数 = 目标QPS / 5万
- **BookKeeper fsync**:每消息 fsync 延迟高,建议 `ensemble=3, writeQuorum=2, ackQuorum=2` + 异步刷盘
- **积压**:消费者处理不过来导致积压,触发 OOM;需监控 `backlog_size`,超阈值告警 + 自动扩消费者
- **大消息**:单消息 > 1MB 影响 Broker 内存,建议 > 256KB 走对象存储 + Pulsar 传元数据
- **CDC 总线**:TiDB CDC → Pulsar,大表全量同步瞬时高吞吐,需限速 + 分片

**压测验证点**:
1. 单 topic partition 1/4/16,测吞吐曲线
2. 消费者慢处理(模拟 100ms 处理),验证积压与告警
3. 跨机房复制延迟压测,验证 RPO < 1s

### 2.7 WebSocket Gateway(Go,长连接)

| 维度 | 理论上限 | 建议水位(70%) | 瓶颈点 | 优化方向 |
|------|---------|--------------|--------|---------|
| 单 Pod WS 连接 | 100,000 | 70,000 | fd/内存 | epoll 模型 |
| 单 Pod 心跳处理 | 200,000/s | 140,000 | CPU | 心跳 30s,批量检测 |
| 单 Pod 消息推送 | 50,000/s | 35,000 | CPU/网卡 | 批量推送 |
| 单 Pod 内存(每连接 4KB) | 400 MB | 280 MB | - | 精简连接上下文 |
| 集群(10 Pod)连接 | 1,000,000 | 700,000 | LB/conntrack | L4 LB + 直连 |
| 消息广播(Pulsar→WS) | 100,000/s | 70,000 | Pulsar 消费 | 共享订阅 |

**瓶颈分析**:
- **fd 上限**:单 Pod 10 万连接 = 10 万 fd,需 ulimit 1M
- **心跳风暴**:100 万连接 × 心跳 30s = 3.3 万/s 心跳包;批量检测 + 心跳合并
- **广播扇出**:Pulsar 单消息广播到 10 万 WS 连接,CPU 暴涨;按 Cell 分组订阅,减少广播
- **内存**:每连接上下文 4-8KB,10 万连接 400-800MB;禁用大 buffer,读写 buffer 4KB
- **graceful upgrade**:滚动升级时 WS 连接重连风暴,需 drain 策略(旧 Pod 60s 排空 + 客户端指数退避重连)

**压测验证点**:
1. 单 Pod 连接 1万 → 10万,记录内存/CPU/GC
2. 100 万连接广播压测,验证 Pulsar → WS 端到端延迟
3. 滚动升级重连风暴模拟

---

## 3. 单元(Cell)容量模型

### 3.1 Cell 容量定义

单个 Cell 是最小故障域与容量单元,独立承载约 **10 万并发用户**,包含完整的 BFF、Dubbo Provider、Redis 分片、TiDB 接入、Pulsar 接入、WS Gateway 子集。

| 资源 | 单 Cell 配置 | 承载能力 | 备注 |
|------|------------|---------|------|
| API Gateway Pod | 4 × 16C/32G | 28 万 QPS | 入口,多 Cell 共享 |
| BFF Pod | 8 × 8C/16G | 28 万 QPS(读) | Netty + Disruptor |
| Dubbo Provider Pod | 12 × 8C/16G | 25 万 QPS | UCM/OPM/学习记录 |
| Redis 分片(本 Cell 专属) | 4 主 4 从 × 32G | 28 万 QPS | L2 缓存 + 会话 |
| TiDB 接入 | 共享集群,follower read | 8 万 QPS | 读写分离 |
| Pulsar Broker | 2 × 8C/16G | 14 万 msg/s | 事件总线 |
| WS Gateway Pod | 4 × 8C/16G | 28 万 WS 连接 | 长连接 |
| 可观测 Collector | 2 × 4C/8G | - | OTel 双层 Collector |

### 3.2 Cell 资源汇总(单 Cell)

| 资源类型 | CPU(核) | 内存(GB) | Pod 数 | 备注 |
|---------|--------|---------|--------|------|
| API Gateway | 64 | 128 | 4 | 16C/32G |
| BFF | 64 | 128 | 8 | 8C/16G |
| Dubbo Provider | 96 | 192 | 12 | 8C/16G |
| Redis | 32 | 256 | 8 | 8C/32G |
| Pulsar Broker | 16 | 32 | 2 | 8C/16G |
| WS Gateway | 32 | 64 | 4 | 8C/16G |
| 可观测 | 8 | 16 | 2 | 4C/8G |
| 基础设施(网络/日志) | 16 | 32 | - | DaemonSet |
| **合计** | **328** | **848** | **40** | 单 Cell 总量 |

### 3.3 Cell 水位与余量

| 指标 | 设计容量 | 70% 水位 | 90% 水位 | 余量 |
|------|---------|---------|---------|------|
| 并发用户 | 10 万 | 7 万 | 9 万 | 1 万(10%) |
| 读 QPS | 28 万 | 19.6 万 | 25.2 万 | 2.8 万 |
| 写 QPS | 2.8 万 | 1.96 万 | 2.52 万 | 0.28 万 |
| Redis QPS | 28 万 | 19.6 万 | 25.2 万 | 2.8 万 |
| WS 连接 | 28 万 | 19.6 万 | 25.2 万 | 2.8 万 |
| CPU 利用率 | - | 70% | 90% | 10% headroom |

### 3.4 Cell 扩容触发矩阵

| 触发条件 | 阈值(持续 3min) | 动作 |
|---------|----------------|------|
| CPU 利用率 | > 75% | HPA 扩 Pod(上限 +50%) |
| 内存利用率 | > 80% | HPA 扩 Pod + 排查内存泄漏 |
| Redis QPS | > 75% shard | 扩 Redis 分片(reshard) |
| P99 延迟 | > SLO 1.5 倍 | 扩 Pod + 限流降级 |
| 并发用户 | > 9 万 | 触发新 Cell 预热(CellRouter 分流) |
| 连续 5min 队列深度 | > 1000 | 扩 Pod + 限流 |

### 3.5 100 万并发 Cell 部署拓扑

```
                    ┌─────────────────────┐
                    │   全局域(UCM/路由)    │
                    │   2 Cell × 10万并发  │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        ▼                      ▼                      ▼
   ┌──── Cell 0 ────┐    ┌──── Cell 1 ────┐  ...  ┌──── Cell 9 ────┐
   │ BFF×8 Dubbo×12 │    │ BFF×8 Dubbo×12 │      │ BFF×8 Dubbo×12 │
   │ Redis 4主4从   │    │ Redis 4主4从   │      │ Redis 4主4从   │
   │ Pulsar×2       │    │ Pulsar×2       │      │ Pulsar×2       │
   │ WS GW×4        │    │ WS GW×4        │      │ WS GW×4        │
   │ 10万并发       │    │ 10万并发       │      │ 10万并发       │
   └────────────────┘    └────────────────┘      └────────────────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
                    ┌─────────────────────┐
                    │  共享 TiDB 集群      │
                    │  3 TiDB + 15 TiKV   │
                    │  读写分离 follower   │
                    └─────────────────────┘
```

| Cell 编号 | 地域 | 并发目标 | 状态 |
|----------|------|---------|------|
| Cell 0-1 | 北京(全局域) | 20 万 | 常驻 |
| Cell 2-4 | 北京(用户域) | 30 万 | 常驻 |
| Cell 5-7 | 上海(用户域) | 30 万 | 常驻 |
| Cell 8-9 | 深圳(用户域) | 20 万 | 常驻 |
| Cell 10-14 | 弹性(峰值) | 50 万 | 按需拉起 |

---

## 4. 压力测试方案

### 4.1 压测工具选型

| 工具 | 场景 | 优势 | 限制 |
|------|------|------|------|
| k6 | HTTP/WS API 压测 | Go 高性能,JS 脚本,CI 集成 | 单机 5 万 VU |
| wrk2 | 恒定吞吐压测 | C 实现,延迟精确(协调世界时) | 仅 HTTP,无 UI |
| JMeter | 复杂场景/协议 | 插件丰富,GUI | JVM 重,单机 1 万线程 |
| Locust | 分布式 Python | 灵活,Web UI | Python GIL,单机 1 万 |
| ghz | gRPC/Triple 压测 | 专为 gRPC | 仅 gRPC |
| memtier_benchmark | Redis 压测 | Redis 官方 | 仅 Redis |
| sysbench | DB 压测 | MySQL/TiDB 兼容 | 仅 DB |
| Pulsar Perf Client | Pulsar 压测 | 官方 | 仅 Pulsar |

### 4.2 压测集群拓扑

压测流量必须从**独立压测集群**发出,避免污染生产可观测数据,且需穿透真实链路(LB→GW→BFF→Provider→DB)。

| 角色 | 规格 | 数量 | 备注 |
|------|------|------|------|
| 压测发压机 | 16C/32G | 20 | k6 cluster 模式,单机 5 万 VU,集群 100 万 VU |
| 流量影子库 | TiDB(压测专用) | 3 TiDB + 6 TiKV | 与生产同构,数据脱敏快照 |
| 压测 Redis | Redis Cluster | 8 节点 | 独立,不污染生产缓存 |
| 流量录制回放 | gor | - | 录制生产流量,脱敏后回放 |

### 4.3 测试场景矩阵

| 场景 | 目标 | 工具 | 加载策略 | 通过标准 |
|------|------|------|---------|---------|
| 单 Pod API GW QPS | 20 万 | wrk2 | 阶梯 1万→20万,每档 5min | P99 < 50ms,错误率 < 0.1% |
| 单 Pod BFF 读 QPS | 5 万 | k6 | 阶梯 1万→5万 | P99 < 100ms |
| 单 Pod Disruptor 吞吐 | 350 万/s | JMH | 恒定 10min | 无背压 |
| 单 Dubbo Provider QPS | 3 万 | ghz | 阶梯 0.5万→3万 | P99 < 50ms |
| Redis 单 shard QPS | 10 万 | memtier | 阶梯 2万→10万 | P99 < 2ms |
| TiDB 集群读 QPS | 80 万 | sysbench | 阶梯 10万→80万 | P99 < 20ms |
| Pulsar 单 topic 吞吐 | 50 万 msg/s | Pulsar Perf | 恒定 30min | 积压 < 1万 |
| WS 单 Pod 连接 | 10 万 | k6(WS) | 线性 10min | 心跳成功率 > 99.9% |
| 单 Cell 10 万并发 | 10 万 VU | k6 cluster | 线性 ramp 30min | P99 < SLO,错误率 < 0.5% |
| 10 Cell 100 万并发 | 100 万 VU | k6 cluster | 线性 ramp 60min | P99 < SLO,错误率 < 1% |
| 秒杀峰值 | 50 万 QPS 瞬时 | k6 | 脉冲 0→50万 1s | 限流生效,系统不崩 |
| 24h 稳定性 | 50 万并发持续 | k6 | 恒定 24h | 无内存泄漏,P99 稳定 |

### 4.4 Ramp-up 策略

**阶梯加载(容量探索)**:
```
VU
100万 ┤                                    ┌───┐(100万,30min)
      │                              ┌─────┘   │
 75万 ┤                        ┌─────┘         │
      │                  ┌─────┘               │
 50万 ┤            ┌─────┘                     │
      │      ┌─────┘                           │
 25万 ┤┌─────┘                                 │
      ││                                       │
   0 └└────────────────────────────────────────┘
      0    10   20   30   40   50   60   90   120  min
```

**脉冲加载(秒杀模拟)**:
```
QPS
50万 ┤  ┌──┐(瞬时脉冲,持续30s)
      │  │  │
      │  │  │
   0 ┤──┘  └──────────────────────────────────
      0  1  2  3  4  5  6  7  8  9  10  min
```

**恒定加载(稳定性)**:
```
VU
50万 ┤─────────────────────────────────────────
      │
      │
   0 ┤─────────────────────────────────────────
      0    6    12   18   24  h
```

### 4.5 测试数据生成

| 数据类型 | 量级 | 生成方式 | 脱敏要求 |
|---------|------|---------|---------|
| 用户数据 | 3 亿 | Faker + 业务规则 | 手机号/身份证脱敏 |
| 课程数据 | 100 万 | 生产快照脱敏 | 标题/讲师保留 |
| 学习记录 | 50 亿 | 按用户×课程生成 | 仅关联 ID |
| 订单数据 | 1 亿 | 按用户行为模拟 | 金额/支付脱敏 |
| 实时事件 | 10 亿/天 | Pulsar 压测客户端 | 内容为模板填充 |

### 4.6 k6 压测脚本示例(单 Cell 10 万并发)

```javascript
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const wsLatency = new Trend('ws_latency', true);

export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 20000 },   // 5min 升至 2万
        { duration: '5m', target: 50000 },   // 5min 升至 5万
        { duration: '5m', target: 80000 },   // 5min 升至 8万
        { duration: '10m', target: 100000 }, // 10min 升至 10万
        { duration: '30m', target: 100000 }, // 持续 30min
        { duration: '5m', target: 0 },       // 5min 降回 0
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<200'],   // P99 < 200ms
    errors: ['rate<0.01'],              // 错误率 < 1%
  },
};

export default function () {
  // 模拟用户学习行为:看课 + 进度上报 + WS 心跳
  const courseId = `course_${__VU % 1000000}`;
  const res = http.get(`https://cell0.ep.example.com/api/v1/courses/${courseId}`,
    { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } });

  check(res, { 'status 200': (r) => r.status === 200 });
  errorRate.add(res.status !== 200);

  // 进度上报(写)
  http.post('https://cell0.ep.example.com/api/v1/progress',
    JSON.stringify({ courseId, progress: Math.random() }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${__ENV.TOKEN}` } });

  sleep(1 + Math.random() * 2); // 1-3s 思考时间
}

// WS 长连接场景(独立 scenario)
export function wsHandler() {
  ws.connect('wss://cell0.ep.example.com/ws', {}, (socket) => {
    socket.on('open', () => socket.setInterval(() => socket.send('ping'), 30000));
    socket.on('message', (data) => wsLatency.add(Date.now() - JSON.parse(data).ts));
    socket.setTimeout(() => socket.close(), 600000); // 10min
  });
}
```

---

## 5. 瓶颈识别与优化

### 5.1 瓶颈识别方法论

采用 **USE 方法**(Utilization / Saturation / Errors)逐层定位瓶颈:

| 层次 | Utilization | Saturation | Errors |
|------|------------|------------|--------|
| 基础设施 | CPU/内存/磁盘/网卡利用率 | 队列深度/上下文切换 | 硬件错误/OOM |
| 容器 | Pod CPU/内存 limit | 调度等待/重启 | OOMKill/Throttle |
| JVM(Go) | GC 频率/堆占用 | 线程阻塞/goroutine 数 | OOM/panic |
| 中间件 | Redis CPU/TiDB QPS | 连接池满/队列积压 | 超时/拒绝 |
| 业务 | 接口 QPS/RT | 限流触发/降级 | 业务错误码 |

**定位流程**:
```
压测发现 P99 劣化
  → 查 Grafana 全链路:哪一层 RT 上升?
    → 查该层 Utilization:CPU/内存/连接 是否打满?
      → 查 Saturation:队列/线程池/goroutine 是否积压?
        → 查 Errors:超时/OOM/拒绝?
          → Profiling(火焰图)定位代码热点
            → 优化 + 回归压测
```

### 5.2 Profiling 工具

| 工具 | 适用组件 | 能力 | 用法 |
|------|---------|------|------|
| async-profiler | Java(BFF/Dubbo) | CPU/堆/锁火焰图 | `./asprof -d 60 -f flame.html <pid>` |
| pprof | Go(GW/WS) | CPU/堆/goroutine 火焰图 | `go tool pprof http://gw:6060/debug/pprof/profile` |
| Arthas | Java | 在线诊断,方法级 RT | `trace com.ep.bff.CourseController get` |
| TiDB Dashboard | TiDB | 慢 SQL/执行计划 | `https://tidb:2379/dashboard` |
| Redis SLOWLOG | Redis | 慢命令 | `SLOWLOG GET 10` |
| Pulsar Broker Stats | Pulsar | topic 吞吐/积压 | `pulsar-admin topics stats` |
| FlameGraph | 通用 | 火焰图可视化 | `flamegraph.pl` |

### 5.3 常见瓶颈与优化模式

#### 5.3.1 CPU 瓶颈

| 现象 | 根因 | 优化 |
|------|------|------|
| CPU 100%,RT 陡升 | 热点循环/序列化 | 火焰图定位,算法优化 |
| JWT 验签占 40% CPU | RS256 非对称运算 | JWKS 缓存 + L1(Caffeine) |
| JSON 序列化占 30% | Jackson 反射 | 换 Protobuf/Jsoniter |
| GC 占 20% CPU | 频繁 young GC | 调大 young gen / 换 ZGC |

#### 5.3.2 内存瓶颈

| 现象 | 根因 | 优化 |
|------|------|------|
| 堆占用持续上升 | 内存泄漏 | MAT/async-profiler dump 堆 |
| Full GC 频繁 | 老年代膨胀 | G1GC 调参,大对象排查 |
| OOMKill | Pod limit 不足 | 调大 limit / 排查泄漏 |
| Disruptor RingBuffer 占 2G | 容量过大 | 降容量至 256K + 批消费 |

#### 5.3.3 IO 瓶颈

| 现象 | 根因 | 优化 |
|------|------|------|
| DB 慢查询 | 缺索引/大表扫描 | 加索引 + explain 优化 |
| TiKV compaction 跟不上 | 写入陡增 | 调 RocksDB 参数 / 加节点 |
| Redis 慢命令 | KEYS/大集合 | 禁 KEYS,用 SCAN |
| 网卡带宽打满 | 25G 网卡饱和 | 升 50G / 多网卡 bonding |

#### 5.3.4 连接池瓶颈

| 现象 | 根因 | 优化 |
|------|------|------|
| Dubbo 线程池满 | 并发 > 200 | 扩线程池 + 队列 + 限流 |
| DB 连接池满 | 连接数上限 | HikariCP maximumPoolSize = CPU×2 |
| Redis 连接打满 | 连接未复用 | Lettuce 共享连接 + pipeline |
| LB conntrack 满 | 短连接过多 | 开 keepalive + 调 conntrack |

### 5.4 优化决策树

```
RT 上升
├── CPU 利用率高?
│   ├── 是 → Profiling 火焰图 → 算法/序列化优化
│   └── 否 → 查 IO
├── 内存利用率高?
│   ├── 是 → GC 日志 + 堆 dump → 泄漏/调 GC
│   └── 否 → 查连接池
├── IO 利用率高?
│   ├── DB → 慢查询 + 索引 + 读写分离
│   ├── Redis → 慢命令 + 大 key + 分片
│   └── 网络 → 带宽 + conntrack
└── 队列积压?
    ├── 线程池满 → 扩池 + 限流
    ├── RingBuffer 满 → 扩容 + 批消费
    └── Pulsar 积压 → 扩消费者 + 分区
```

---

## 6. 容量水位监控

### 6.1 容量指标体系

| 维度 | 指标 | 采集源 | 告警阈值 |
|------|------|--------|---------|
| 流量 | QPS(读/写) | Prometheus | > 75% 设计容量 |
| 流量 | 并发连接数 | API GW | > 75% 设计容量 |
| 资源 | CPU 利用率 | cAdvisor | > 70%(warn) / 85%(crit) |
| 资源 | 内存利用率 | cAdvisor | > 75% / 90% |
| 资源 | 磁盘 IO | node_exporter | > 70% |
| 资源 | 网卡带宽 | node_exporter | > 70% |
| 饱和度 | Pod 调度等待 | kube-scheduler | > 10s |
| 饱和度 | 线程池队列深度 | Micrometer | > 500 |
| 饱和度 | Disruptor 待处理 | 自定义 metric | > 10万 |
| 饱和度 | Pulsar 积压 | Pulsar Exporter | > 10万 msg |
| 错误 | HTTP 5xx | GW | > 0.1% |
| 错误 | 超时率 | 链路追踪 | > 0.5% |
| 错误 | 限流拒绝 | Sentinel | > 1% |
| 延迟 | P99 RT | Tempo | > SLO 1.5x |
| 容量 | Cell 并发用户 | CellRouter | > 9 万 |

### 6.2 水位分级

| 等级 | 水位区间 | 颜色 | 动作 |
|------|---------|------|------|
| L0 空闲 | 0-30% | 蓝色 | 考虑缩容降本 |
| L1 正常 | 30-60% | 绿色 | 稳态运行 |
| L2 偏高 | 60-75% | 黄色 | 关注,准备扩容 |
| L3 高位 | 75-90% | 橙色 | 触发扩容 + 限流预热 |
| L4 危险 | 90-100% | 红色 | 强制限流 + 紧急扩容 |
| L5 超限 | > 100% | 黑色 | 降级保核心 + 熔断 |

### 6.3 Sentinel 自适应流控

| 流控策略 | 参数 | 适用场景 |
|---------|------|---------|
| QPS 限流(快速拒绝) | threshold = 设计容量×80% | 入口网关,保护后端 |
| 并发线程数限流 | threshold = 线程池×80% | Dubbo Provider |
| 关联资源限流 | 关联资源达阈值时限流本资源 | 写依赖读场景 |
| 链路限流 | 按调用链入口限流 | 网关→BFF→Provider |
| 热点参数限流 | 按 courseId/userId 维度 | 热门课程/秒杀 |
| 系统自适应(BBR) | 根据 CPU/RT 自适应 | 突发流量 |

### 6.4 容量看板(Grafana)

**单 Cell 容量看板**:
- 面板 1:并发用户实时曲线 + 水位色带
- 面板 2:各组件 QPS 堆叠图 + 设计容量红线
- 面板 3:CPU/内存利用率热力图(Pod × 时间)
- 面板 4:P99/P999 延迟趋势
- 面板 5:Sentinel 限流/降级计数
- 面板 6:Disruptor/Pulsar 队列深度
- 面板 7:错误率(5xx/超时/拒绝)

**平台容量看板**:
- 面板 1:全 Cell 并发汇总 + 色带
- 面板 2:Cell 维度水位排行
- 面板 3:TiDB 集群 QPS + region 分布
- 面板 4:Redis Cluster 各 shard QPS + 热点
- 面板 5:跨 Cell 流量分布(CellRouter)

---

## 7. 弹性扩缩容

### 7.1 K8s HPA 配置

| 组件 | 扩容指标 | 目标值 | 扩容比例 | 冷却 |
|------|---------|--------|---------|------|
| API Gateway | CPU + QPS | CPU 60% / QPS 70% | Max 200% / 一次 | 60s |
| BFF | CPU + P99 | CPU 65% / P99 < 150ms | Max 100% | 90s |
| Dubbo Provider | CPU + 线程池 | CPU 65% / 池 70% | Max 100% | 90s |
| WS Gateway | 连接数 | 7 万/Pod | Max 50% | 120s |
| Pulsar Broker | 吞吐 + 积压 | 70% / 积压 < 5万 | Max 100% | 180s |

**HPA 示例(BFF)**:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: bff-hpa
  namespace: prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: bff
  minReplicas: 8
  maxReplicas: 24          # 单 Cell BFF 上限
  metrics:
  - type: Resource
    resource:
      name: cpu
      target: { type: Utilization, averageUtilization: 60 }
  - type: Pods
    pods:
      metric: { name: http_requests_per_second }
      target: { type: AverageValue, averageValue: "35000" }
  - type: Pods
    pods:
      metric: { name: http_request_duration_p99 }
      target: { type: AverageValue, averageValue: "150" }  # ms
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - { type: Percent, value: 100, periodSeconds: 60 }
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300   # 缩容慢,避免抖动
      policies:
      - { type: Percent, value: 25, periodSeconds: 120 }
```

### 7.2 VPA(垂直扩缩容)

仅用于有状态组件(Redis/Pulsar)的内存/CPU 调整,且需重启 Pod,谨慎使用。

| 组件 | VPA 策略 | 备注 |
|------|---------|------|
| Redis | Off(仅推荐,手动) | 避免重启丢缓存 |
| Pulsar BookKeeper | Off(手动) | 数据本地性 |
| TiDB | 不适用 | 独立集群 |

### 7.3 Cluster Autoscaler

节点池自动扩缩,配合 HPA 新 Pod 触发节点扩容。

| 节点池 | 最小 | 最大 | 扩容触发 | 缩容策略 |
|--------|------|------|---------|---------|
| 计算节点(无状态) | 50 | 200 | Pod Pending > 30s | 空闲 10min 缩 |
| 内存节点(Redis) | 10 | 30 | 手动 | 不自动缩 |
| 存储节点(TiKV) | 6 | 20 | 手动 | 不自动缩 |

### 7.4 Cell 自动供给

**新 Cell 拉起流程**:
```
CellRouter 监测:全平台并发 > 90万(90% 设计容量)
  → 触发 Cell 10 预热
    → K8s 部署 Cell 10 全套资源(40 Pod)
    → 等待探针就绪(startup + readiness)
    → Redis 预热(热 key 从相邻 Cell 复制)
    → Nacos 注册 Cell 10 路由表
    → CellRouter 灰度分流:5% → 20% → 50%
    → 全量承接
  → 总耗时 < 5min
```

| 阶段 | 耗时 | 验证点 |
|------|------|--------|
| Pod 调度 + 拉镜像 | 60s | 镜像预热到本地 |
| 应用启动 | 60s | startupProbe 通过 |
| Redis 预热 | 120s | 热 key 命中率 > 80% |
| 路由注册 + 灰度 | 60s | 流量无 5xx |
| **总计** | **< 5min** | 新 Cell 承接 10 万并发 |

### 7.5 扩缩容策略矩阵

| 场景 | 扩容速度 | 缩容速度 | 备注 |
|------|---------|---------|------|
| 日常波动 | 慢(5min) | 慢(10min) | HPA 默认 |
| 大促预热 | 手动提前 1h | 手动延后 2h | 营销活动 |
| 秒杀 | 预扩 + 限流 | 不缩(保 1h) | 防二次脉冲 |
| 突发流量 | 快(60s) | 慢(10min) | 防抖动 |
| 故障转移 | 立即(其他 Cell 承接) | 不缩 | 灾备优先 |

### 7.6 缩容保护

- **PDB(PodDisruptionBudget)**:minAvailable = 副本数 - 1,保证至少 N-1 存活
- **优雅下线**:preStop sleep 10s + 连接排空 60s,确保 WS 连接重连
- **缩容窗口**:仅低峰(02:00-06:00)允许缩容,高峰只扩不缩
- **Redis 不缩**:Redis 缩容涉及 reshard,风险高,仅手动

---

## 8. 极端场景验证

### 8.1 秒杀(Flash Sale)场景

**特征**:瞬时 50 万 QPS 涌入单个课程页,持续 30s。

| 验证项 | 目标 | 方案 |
|--------|------|------|
| 入口限流 | 50 万 QPS 仅 5 万穿透 | Sentinel 网关层 QPS 限流 + 排队 |
| 缓存击穿 | 热点 key 不穿透 DB | Caffeine L1 + Redis 互斥锁 + 热点探测 |
| 库存一致性 | 不超卖 | Redis 原子扣减 + Lua 脚本 |
| WS 广播 | 10 万在线不积压 | Pulsar 分区 + WS 批量推送 |
| 降级 | 非核心降级 | 课程评论/推荐降级,保下单 |

**压测脚本(k6 脉冲)**:
```javascript
export const options = {
  scenarios: {
    flash: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 500000,
      stages: [
        { duration: '1s', target: 500000 },   // 1s 内到 50万 QPS
        { duration: '30s', target: 500000 },  // 持续 30s
        { duration: '10s', target: 0 },       // 10s 降回 0
      ],
    },
  },
};
```

**通过标准**:下单成功率 > 99%,不超卖,P99 < 500ms,系统无雪崩。

### 8.2 病毒内容飙升(Viral Spike)

**特征**:某课程/直播 10 分钟内并发从 1 万飙至 50 万。

| 验证项 | 目标 | 方案 |
|--------|------|------|
| 自动扩容 | 5min 内扩容到位 | HPA + Cluster Autoscaler + Cell 预热 |
| 热点分散 | 单 shard 不热点 | 热点 key 探测 + L1 缓存 + 多副本 |
| 直播 WS | 50 万连接不拒 | WS Gateway 扩 Pod + Pulsar 分区 |
| CDN 回源 | 回源率 < 5% | 边缘缓存预热 + CDN 降级 |
| 级联保护 | 下游不被打垮 | Sentinel 熔断 + 读写分离 |

### 8.3 DDoS 模拟

| 攻击类型 | 模拟方式 | 防护 |
|---------|---------|------|
| SYN Flood | hping3 -S | LB SYN cookie |
| HTTP Flood | k6 慢速请求 | GW 连接速率限制 + WAF |
| 慢速攻击 | slowloris | GW 请求超时 5s |
| WS 长连接耗尽 | k6 建 100万 WS 不发数据 | WS 心跳超时 60s 踢出 |
| 缓存击穿 | 大量不存在 key | 布隆过滤器 + 空值缓存 |

**验证标准**:DDoS 期间核心业务可用性 > 99.9%,非核心降级。

### 8.4 级联故障防范

| 故障注入 | 预期表现 | 验证点 |
|---------|---------|--------|
| DB 主节点宕机 | 30s 内切换 follower | RTO < 30s,无数据丢失 |
| Redis 主宕机 | 10s 内从提升 | 缓存 miss 短暂上升,不崩 |
| Pulsar Broker 宕机 | 消费者重连其他 Broker | 积压 30s 内消化 |
| 单 Cell 整体宕机 | CellRouter 流量迁移 | 60s 内迁移至相邻 Cell |
| 网络分区 | 脑裂保护 | TiDB PD 仲裁,Redis 多数派 |
| 下游依赖超时 | 熔断保护 | Sentinel 熔断,不级联 |

### 8.5 长尾延迟治理

| 场景 | 根因 | 治理 |
|------|------|------|
| P99 vs P50 差 10 倍 | GC pause | ZGC + 调 GC 参数 |
| 偶发 1s+ 尖刺 | JIT 编译 | 预热脚本触发 JIT |
| TiDB 慢查询拖累 | 统计信息过期 | 定期 ANALYZE + 绑定执行计划 |
| Redis 大 key | 单次操作阻塞 | 大 key 拆分 + 异步删除 |
| 网络抖动 | 宿主机噪声邻居 | 独占节点 + 网络 QoS |

---

## 9. 容量评估报告模板

### 9.1 报告结构

```
《XX 季度容量评估报告》
1. 执行摘要(1页)
   - 当前容量水位概览
   - 风险与建议
2. 容量基线
   - 各组件实测容量 vs 设计容量
   - 水位利用率趋势(环比/同比)
3. SLO 达成情况
   - 可用性 / 延迟 / 错误率
   - SLI 趋势图
4. 压测结果
   - 本季度压测摘要
   - 瓶颈定位与优化
5. 容量预测
   - 未来 3/6/12 个月增长预测
   - 扩容需求清单
6. 风险与待办
   - 高风险项 + 责任人 + 截止
7. 附录
   - 压测原始数据
   - Grafana 截图
```

### 9.2 SLO 跟踪表

| SLO | 目标 | 本季度实测 | 达成 | 趋势 |
|-----|------|----------|------|------|
| 可用性 | 99.95% | 99.97% | 是 | ↑ |
| API 读 P99 | 200ms | 150ms | 是 | → |
| API 写 P99 | 300ms | 280ms | 是 | → |
| WS 推送 P99 | 80ms | 75ms | 是 | → |
| 视频起播 P99 | 500ms | 480ms | 是 | → |
| 作业评测 P99 | 5s | 4.2s | 是 | ↑ |

### 9.3 容量预测表

| 组件 | 当前水位 | 6个月预测 | 12个月预测 | 扩容动作 | 时间 |
|------|---------|----------|----------|---------|------|
| API Gateway | 65% | 80% | 95% | 扩 Pod + Cell | 6个月内 |
| BFF | 60% | 75% | 90% | 扩 Pod | 6个月内 |
| Redis Cluster | 55% | 70% | 85% | 加 shard | 12个月内 |
| TiDB | 50% | 65% | 80% | 加 TiKV | 12个月内 |
| Pulsar | 40% | 55% | 70% | 加 Broker | 暂不 |
| WS Gateway | 60% | 75% | 90% | 扩 Pod + Cell | 6个月内 |

### 9.4 容量规划路线图

| 季度 | 里程碑 | 容量动作 | 预算 |
|------|--------|---------|------|
| 2026Q3 | M1 基线 | 5 Cell,50万并发 | - |
| 2026Q4 | 压测验证 | 100 万并发压测,瓶颈优化 | - |
| 2027Q1 | M2 100万 | 扩至 10 Cell,100万并发 | +30% |
| 2027Q3 | M3 海外 | 海外 Cell 接入,跨域容量 | +50% |
| 2028Q1 | M4 多活 | N+1 灾备,多活容量 | +40% |
| 2030Q4 | M5 稳态 | 30 亿用户稳态优化 | 成本优化 |

---

## 10. 灾备容量

### 10.1 灾备架构

采用 **N+1 冗余 + 跨地域多活**:

```
┌─────── 北京主地域 ───────┐         ┌─────── 上海备地域 ───────┐
│ Cell 0-4(用户域)        │         │ Cell 5-7(用户域,热备)   │
│ Cell 8-9(全局域)        │  Pulsar  │ Cell 8'(全局域,热备)    │
│ TiDB 主集群              │ ───────→ │ TiDB 同步从 + 异步复制   │
│ Redis 主 Cluster         │  CDC     │ Redis 从 Cluster         │
│ Pulsar 主集群            │ 复制     │ Pulsar 跨地域复制        │
└─────────────────────────┘         └─────────────────────────┘
```

### 10.2 灾备容量要求

| 维度 | 主地域(北京) | 备地域(上海) | 备注 |
|------|------------|------------|------|
| Cell 数 | 10 | 7(热备) + 3(冷备) | N+1 |
| 并发容量 | 100 万 | 100 万 | 备地域可全量承接 |
| TiDB | 3 TiDB + 15 TiKV | 3 TiDB + 15 TiKV | 同步复制(RPO=0) |
| Redis | 30 节点 | 30 节点 | 异步复制(RPO < 1s) |
| Pulsar | 6 Broker | 6 Broker | 跨地域复制(RPO < 1s) |
| WS 连接 | 100 万 | 100 万 | 故障时重连至备 |
| 资源预留 | 70% 使用 | 30% 预留 | 随时承接 |

### 10.3 N+1 冗余模型

| 层次 | N | +1 | 含义 |
|------|---|----|------|
| Cell | 10 | 1(弹性) | 任一 Cell 故障,弹性 Cell 承接 |
| API Gateway Pod | 40 | 4 | 4 Pod 冗余,任 4 Pod 故障不降级 |
| BFF Pod | 80 | 8 | 8 Pod 冗余 |
| Redis shard | 15 | 2 | 2 shard 冗余 |
| TiDB Server | 3 | 1 | 1 节点冗余 |
| TiKV | 15 | 3 | 3 副本(Raft) |
| Pulsar Broker | 6 | 2 | 2 Broker 冗余 |
| 节点池 | 50 | 5 | 5 节点冗余 |

### 10.4 跨地域容量预留

| 地域 | 角色 | 常驻容量 | 弹性容量 | 备注 |
|------|------|---------|---------|------|
| 北京 | 主 | 100 万并发 | +50 万 | 大促弹性 |
| 上海 | 备 | 100 万并发 | +50 万 | 灾备 + 弹性 |
| 深圳 | 边缘 | 20 万并发 | - | 就近接入 |
| 海外(预留) | 边缘 | 50 万并发 | - | v1.5 接入 |

### 10.5 故障切换容量验证

| 场景 | 切换目标 | RTO | RPO | 容量验证 |
|------|---------|-----|-----|---------|
| 单 Pod 故障 | K8s 自愈 | 10s | 0 | HPA 自动补 Pod |
| 单 Cell 故障 | 相邻 Cell 承接 | 60s | < 1s | CellRouter 流量迁移 |
| 主地域故障 | 备地域全量承接 | 5min | < 1s | DNS 切换 + WS 重连 |
| TiDB 主集群故障 | 同步从提升 | 30s | 0 | PD 自动选主 |
| Redis 主集群故障 | 从 Cluster 提升 | 10s | < 1s | Cluster Failover |

### 10.6 容量保留策略

- **热备**:备地域常驻 100 万并发资源,随时可承接(CPU 利用率 < 30%)
- **温备**:弹性 Cell 资源 5min 内拉起,承接 50 万并发
- **冷备**:云厂商按量付费资源,1h 内拉起,承接突发
- **成本优化**:热备资源承接低优先级离线任务(批处理/分析),故障时抢占式驱逐

### 10.7 灾备容量压测

| 场景 | 目标 | 频率 |
|------|------|------|
| 主地域全量切备地域 | 100 万并发 RTO < 5min | 每季度 |
| 单 Cell 故障切换 | 10 万并发 RTO < 60s | 每月 |
| TiDB 主从切换 | RTO < 30s,数据零丢失 | 每月 |
| Redis Cluster Failover | RTO < 10s | 每月 |
| Pulsar 跨地域复制 | RPO < 1s | 每周 |

---

## 附录 A:容量计算公式

| 指标 | 公式 |
|------|------|
| Cell 数 | 并发目标 / 10 万 |
| API GW Pod 数 | 并发目标 / 7 万(单 Pod 70% 水位) |
| BFF Pod 数 | 读 QPS / 3.5 万 |
| Redis shard 数 | Redis QPS / 7 万 |
| TiDB Server 数 | DB QPS / 3.5 万 |
| TiKV 节点数 | max(DB QPS / 5.6 万, 存储量 / 1.4TB) |
| Pulsar partition 数 | topic QPS / 3.5 万 |
| WS Pod 数 | WS 连接 / 7 万 |

## 附录 B:压测通过标准汇总

| 场景 | P99 延迟 | 错误率 | 资源水位 | 持续 |
|------|---------|--------|---------|------|
| 单组件压测 | < 组件 SLO | < 0.1% | < 85% | 10min |
| 单 Cell 10万并发 | < 平台 SLO | < 0.5% | < 85% | 30min |
| 10 Cell 100万并发 | < 平台 SLO | < 1% | < 85% | 30min |
| 秒杀脉冲 | < 500ms | 下单成功率 > 99% | < 95% | 30s |
| 24h 稳定性 | < SLO 1.2x | < 0.1% | 内存稳定 | 24h |
| 灾备切换 | - | - | - | RTO 达标 |

## 附录 C:引用与依据

| 编号 | 名称 | 关联 |
|------|------|------|
| D-032 | 技术栈锁定 | 全文组件选型依据 |
| SP01-SAD-001 | 架构设计基线 | 组件架构、ADR |
| SP01-DEPLOY-001 | 部署运维文档 | K8s 配置、HPA、探针 |
| EP-TOGAF-001 | 企业架构 | 容量目标对齐 |
| G-01~G-10 | SLO 目标 | 延迟预算、可用性 |
| ADR-T05 | 探针三分离 | 压测期间探针配置 |
| ADR-T07 | BFF 技术栈(rev2) | BFF 容量基线 |
| ADR-T09 | Dubbo Triple | RPC 容量基线 |
| ADR-T11 | Pulsar 3.3.x | 事件总线容量基线 |

---

> 文档版本:v1.0 | 维护人:性能架构师 | 评审周期:每季度 | 下次评审:2026Q4
