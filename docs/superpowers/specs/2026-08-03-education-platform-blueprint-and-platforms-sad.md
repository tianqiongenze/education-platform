# education-platform 项目群 —— 顶层蓝图与四大中台 SAD

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| EP-SPEC-001 | v1.0 | 2026-08-03 | Draft(待用户review) | ZCode(教授级高工模式) | 用户需求 2026-08-03 + ESP 参考项目经验 + 2025-2026 业界最佳实践调研 |

> **本 spec 兑现深度**：顶层架构蓝图 + 四大中台 SAD 级(架构/边界/接口契约/关键ADR/技术选型) + IT后端 MVP 选定 + 全项目群 WBS 与分阶段路线图。
> **不在本 spec 范围**(留作后续独立 spec→plan→实现 循环)：各中台 SDD 级详细设计(类/接口/表结构)与代码、各事业群业务实现、生产环境实际部署。
> **参考项目**：`/Users/joelgong/claude-Projects/English-Study-Platform`(ESP，5000 CCU 已验证的工程化全流程模式来源)。

---

## 0. 决策登记簿(ADR 摘要，决策即代码)

> 全部决策同步存于 `~/.zcode/sessions/education-platform/decisions.jsonl`。本表为可读快照。
> 注：D-007 为环境记录(K8s 集群当前离线)，非架构决策，故不入此表；D-001..D-006,D-008..D-020 连续。

| ID | 决策 | 理由 | 来源 |
|----|------|------|------|
| D-001 | 项目群而非单项目；每子项目独立 spec→plan→实现 | 范围跨4中台×多BU×30亿×多区域×多端，单 spec 必成注水文档 | brainstorming scope-check |
| D-002 | 本 spec=顶层蓝图+4中台SAD级+路线图；SDD级与代码后续循环 | 工程分阶段交付，避免在中台接口未冻结前写 SDD 返工 | 用户确认 |
| D-003 | 首个 MVP 事业群 = IT后端 | 最贴近 ESP 参考经验，最大化复用验证过的代码/测试/部署模式 | 用户确认 |
| D-004 | 多区域=国内多活+海外独立数据隔离 | 满足 PIPL 数据出境合规；海外用户走海外集群 | 用户确认 |
| D-005 | 30亿/100万并发=设计天花板，分阶段上线(M0→M3) | Day-1 全量建设不可行(成本/风险) | 用户确认 |
| D-006 | 全局铁律：会话状态外化 Redis Cluster(`zcode:session:*`)+本地兜底；80%主动压缩 | 用户指令防上下文溢出 | 用户指令 |
| D-008 | 分库分表=单元化(shard-by-userID)+逻辑分表 | 30亿+异地多活+就近写的唯一合理路径 | 用户确认 |
| D-009 | 语言=Java主(Disruptor/Pulsar/TiDB/Redis重业务)+Go(网关/实时/WS)+Python(AI/数据)+Rust(风控/计费热点) | 对齐 ESP 验证栈 + 用户需求点名 | 用户确认 |
| D-010 | AI中台=全栈(模型网关/RAG/批改/出题/答疑/学习路径/数字人) | 覆盖教育全AI需求；复用集群图+向量库 | 用户确认 |
| D-011 | 数据中台=Flink核心实时+CDC+特征工程+风控计费 | 对齐用户 Flink 要求；实时优先服务 AI 特征 | 用户确认 |
| D-012 | **根架构=混合单元化**：用户域(学习/互动/订单/直播)强单元化，全局域(课程目录/教师/客服/运营)共享+多副本 | 纯A过度设计全局数据，纯B在100万并发崩，C是务实落地 | 用户确认(根架构决策) |
| D-013 | 数据中台补强：Flink + **Apache Iceberg 湖仓一体**(流批一体Lakehouse) | 30亿历史数据不可能全压TiDB在线库；湖仓是冷热分层+AI训练数据底座标配 | 用户指令 |
| D-014 | 单元化采用字节/阿里验证模式：流量纠正+跨单元写挂起+Local/CentralService二分 | 2025-2026 hyperscale 共识；阿里 AppActive 恢复<30s | 联网调研 |
| D-015 | TiDB 仍需手动 user_id 分表：AUTO_RANDOM(5)+PRE_SPLIT_REGIONS+SPLIT TABLE | 分布式≠访问局部性；10B+行自动分片不保证 locality | 联网调研 |
| D-016 | CDC 路径=TiCDC→Kafka(Avro+SchemaRegistry)→Flink→Iceberg；Flink CDC 3.x YAML pipeline 分库分表合并 | 生产标准；避免 GC safepoint 阻塞 | 联网调研 |
| D-017 | Flink→Iceberg：checkpoint=commit(5min,target 256MB,zstd)，SinkV2 链式 compaction，保留 flink-job-id 快照 | Iceberg 官方+LakeOps 生产调优+字节 StreamShield | 联网调研 |
| D-018 | AI网关分层：Kong AI(吞吐)/LiteLLM(起步)+语义缓存(Redis LangCache)+模型路由(RouteLLM类)+LLM_MODE=mock | Kong 基准>800% LiteLLM RPS；成本栈 cache→route→cascade→fallback | 联网调研 |
| D-019 | RAG 多租户=namespace+pool+JWT驱动FGAC，禁 app 层拼 tenant_id | 跨租户泄漏是 #1 事故类；DB-per-tenant >10k 不可行 | 联网调研 |
| D-020 | IT代码批改=human-in-the-loop：LLM做形成性反馈，高风险评分教师复核 | 2025研究 LLM 自动评分未达人类一致性 | 联网调研 |
| D-021 | 直播=两层混合(互动WebRTC LiveKit+大众LL-HLS CDN);房间单cell,跨cell观众走CDN边缘; SRS v7 origin+Redis路由 | WebRTC大规模成本爆炸;被动观众必须CDN;SRS 1→320edge×2000=64万观众 | 联网调研 |
| D-022 | 在线编程=沙箱按信任分级: 教学IDE=WebContainer(WASM); 评测Judge=Firecracker microVM+judge0; 协作=Yjs CRDT; 预热池避冷启动 | Docker不够安全;Firecracker唯一GPU穿透;IDE vs Judge不同SLA | 联网调研 |
| D-023 | 作业=Canvas assignment-centric数据模型; 防作弊弱防御(假阳率30-50%仅flag不自动罚) | Canvas行业默认;纯AI监考假阳率太高 | 联网调研 |
| D-024 | 代码查重两层: Tier1 MinHash+LSH(O(n)十亿级)→Tier2 Dolos/JPlag桶内pairwise; MOSS弃用 | pairwise O(n²)百万不可行;LSHBloom证明十亿级 | 联网调研 |
| D-025 | 评分=分层沙箱pipeline(CodeGrade模式): ①IO/单元(实时)②静态③安全④Style ⑤LLM rubric(异步批,CoT+self-consistency) | LLM未达人类一致性;确定性可实时,LLM必须异步批 | 联网调研 |
| D-026 | spec §7 IT后端补强到SAD级,4子平台(直播/在线编程/作业/评分)各含架构+ADR | 用户点名问4块是IT核心深度,原一句话带过是深度缺口 | 用户追问+spec缺口修复 |

---

## 1. 项目愿景与量化目标(SLO)

### 1.1 愿景
构建支撑**大型在线教育公司多事业群**(IT后端/IT前端/考公/考研/养生…)的统一教育平台项目群，以**四大中台**(技术/数据/业务/AI)为共享底座，做到：
- **对需求开放**：新事业群接入 = 注册领域服务+调中台，不重造轮子
- **工业级规模**：天花板 30 亿用户 / 100 万并发 / 上万教师+客服，国内异地多活+海外独立
- **多端一致**：web/h5/小程序三端共 API，前后端联调联测
- **工程化全流程**：TDD+DDD+EDA+读写分离，全测试阶段可人工测试

### 1.2 量化 SLO 表(G-xx，每项绑定测量方法)

| ID | 指标 | 天花板目标 | MVP阶段目标(M1) | 测量方法 |
|----|------|-----------|----------------|----------|
| G-01 | 峰值并发用户 | 1,000,000 CCU | 10,000 CCU | 分布式 Locust(独立节点) |
| G-02 | 注册用户容量 | 3,000,000,000 | 1,000,000 | TiDB 容量规划+压测反推 |
| G-03 | 核心 API P95 | <200ms | <200ms | Prometheus histogram |
| G-04 | 核心 API P99 | <500ms | <500ms | Prometheus histogram |
| G-05 | 直播/互动延迟 | <800ms P95 | <800ms P95 | WS 端到端压测 |
| G-06 | 学习事件 ingestion | ≥500,000 ev/s | ≥10,000 ev/s | Disruptor 基准+Pulsar 吞吐 |
| G-07 | 可用性 | 99.99% | 99.9% | PromQL up+故障注入验证 |
| G-08 | 单单元故障切换 | <30s | <60s | 阿里 AppActive 模式演练 |
| G-09 | 数据中台实时延迟 | <5min(Iceberg入湖) | <5min | Flink checkpoint→commit 监控 |
| G-10 | AI 推理 P95(批改/答疑) | <3s | <3s | AI 网关 trace+mock模式压测 |

### 1.3 用户角色
- **学员**(30亿天花板)：购课/学习/作业/考试/直播互动/答疑
- **教师**(上万滚动授课)：开课/备课/批改/直播/答疑/学情
- **客服**：工单/IM会话/退款/投诉
- **运营**：课程上架/活动/营销/数据看板
- **管理员**：权限/审计/风控规则/单元运维

---

## 2. 顶层架构蓝图

### 2.1 总体分层

```
┌─────────────────────────────────────────────────────────────────┐
│  多端接入层  web(React) │ h5(React移动优先) │ 小程序(微信/Taro)  │
│             共享 API 契约(OpenAPI 3) + W3C traceparent 注入       │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│  BFF / API 网关层(Java/Spring Cloud Gateway + Go WS网关)          │
│  JWT RS256 签发+JWKS│单元路由器(CellRouter)│Sentinel限流│灰度      │
└────────────────────────────────┬────────────────────────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
┌───────▼────────┐    ┌──────────▼──────────┐    ┌────────▼──────────┐
│  全局域服务     │    │   用户域服务(单元内) │    │  实时域服务        │
│  共享+多副本    │    │   强单元化 user路由  │    │  WS/直播/IM        │
│  (见 §2.2)     │    │   (见 §2.2)         │    │  (见 §2.2)        │
└───────┬────────┘    └──────────┬──────────┘    └────────┬──────────┘
        │                        │                        │
        └────────────────────────┼────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│              四大中台(共享底座，每单元部署副本)                    │
│  技术中台 │ 数据中台 │ 业务中台 │ AI中台  (详见 §3-§6)            │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│              事业群业务服务(BU，各自独立调中台)                    │
│  IT后端(MVP首发) │ IT前端 │ 考公 │ 考研 │ 养生 ...               │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│  基础设施层(复用容器集群，不重建)                                  │
│  TiDB/TiFlash│Redis Cluster│Pulsar│图库│向量库(Milvus)│           │
│  对象存储(S3/MinIO)│K8s│Kafka(CDC总线)│可观测(Loki/Prom/Tempo)   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 DDD 限界上下文(战略设计，单元化边界依据)

| 限界上下文 | 归属 | 核心聚合 | 单元化策略 | 说明 |
|-----------|------|---------|-----------|------|
| 课程目录 Course | 全局域 | Course/CourseSection/Chapter | 共享+多副本读 | 万级课程全平台一份 |
| 题库 Question | 全局域(共享部分)+用户域(个人作答) | Question/QuestionBank | 共享库+个人作答落单元 | 题库共享，作答记录单元化 |
| 教师档案 Teacher | 全局域 | TeacherProfile/CourseDraft | 共享+多副本读 | 万级教师 |
| 运营活动 Ops | 全局域 | Campaign/CouponTemplate | 共享主库单写 | 写少读多 |
| 客服工单 Ticket | 全局域 | Ticket/Session | 共享(跨客服调度) | 客服池跨用户 |
| 全局风控规则 RiskRule | 全局域 | RiskRule/BlackList | 共享+广播缓存 | 规则全平台一致 |
| 学习记录 Learning | **用户域** | Enrollment/Progress/Assignment | **强单元化 user_id** | 高并发读写主体 |
| 订单/支付 Order | **用户域** | Order/Payment/Refund | **强单元化 user_id** | 事务核心 |
| 用户账户 Account | **用户域** | User/Auth/Credential | **强单元化 user_id** | user_id 来源 |
| 个人画像 Profile | **用户域** | LearnerProfile/Mastery | **强单元化 user_id** | AI推荐特征源 |
| 直播 Live | 实时域 | LiveRoom/Stream | 单元内(房间主单元) | 跨单元观众走CDN边缘 |
| 互动 Interact | 实时域 | Danmaku/Connect/IM | 单元内 | 数据落对应user单元 |

**边界判断准则**(承接 §1 ESP 经验，用于后续 SDD 逐域校验)：
- 主体身份是平台/教师/运营 → 全局域；主体身份是单用户 → 用户域
- 全平台强一致诉求 → 全局域；单元内强一致即可 → 用户域
- 读多写少 → 全局域候选；高并发读写 → 用户域候选

### 2.3 混合单元化路由(根架构 D-012)

```
用户请求(JWT含user_id) → BFF解析 → CellRouter: cell=hash(user_id)%N
                                          │
        ┌─────────────────┬───────────────┼───────────────┬─────────────────┐
        │                 │               │               │                 │
   ┌────▼────┐       ┌────▼────┐     ┌────▼────┐     ┌────▼────┐       ┌────▼────┐
   │ Cell-0  │       │ Cell-1  │     │ Cell-2  │ ... │Cell-(N-1)│      │ 海外Cell │
   │华东+华北 │       │华北+华南 │     │华南+华东 │     │  ...     │      │ 新加坡  │
   │TiDB分片0 │       │分片1    │     │分片2    │     │          │      │法兰克福 │
   │Redis分片0│       │分片1    │     │分片2    │     │          │      │数据隔离 │
   │Pulsar ns│       │ns1      │     │ns2      │     │          │      │         │
   │中台副本 │       │副本     │     │副本     │     │          │      │         │
   │BU副本   │       │副本     │     │副本     │     │          │      │         │
   └────┬────┘       └────┬────┘     └────┬────┘     └────┬────┘      └─────────┘
        │                 │               │               │
        └─────────┬───────┴───────┬───────┴───────┬───────┘
                  │               │               │
           ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
           │ 全局域主库   │ │ Redis多副本  │ │ Pulsar跨单元 │
           │ (华北,单写)  │ │ (异步复制)   │ │ (geo-rep聚合)│
           └─────────────┘ └─────────────┘ └─────────────┘
                  │
           ┌──────▼──────────────────────┐
           │ 异步聚合: Pulsar→Flink       │ 全局榜/对账/数据中台
           │ (不阻塞单元内实时链路)       │ 不直接单元间RPC
           └─────────────────────────────┘
```

**关键机制(D-014 流量纠正模式)**：
1. **路由表外置**(Nacos/etcd)：`hash(user_id)%N` 的 N 与映射灰度可调，re-sharding 不改代码
2. **misroute 自动纠正**：BFF/网关检测路由错误，按 context 内嵌纠正计数器重路由到正确单元(字节模式)
3. **跨单元写挂起**：failover 期间该 user_id 写操作挂起(阿里 AppActive 模式)，防止脑裂
4. **跨区 Raft**：每单元 TiDB 分片跨 2 区，任一区挂单元仍可用 → 真异地多活
5. **就近写**：请求路由到就近区副本，Raft 多数派写成功即返回
6. **全局域单写多读**：主库 1 份(华北)+2 区只读副本，写集中读分散
7. **跨单元走异步**：单元间不直接 RPC，靠 Pulsar 事件 + Flink 聚合

**容量规划(N 反推)**：
- 100万并发 / 单单元 5-10万 ≈ 10-20 单元(国内)
- 30亿用户 / 单单元 1.5-3亿 ≈ 10-20 单元
- M1 阶段 2-3 单元验证，M3 扩到 10-20

### 2.4 多区域拓扑(D-004)

```
┌──────────────── 国内多活(华东/华北/华南三角)────────────────┐
│ 每单元跨2区(Raft多数派) │ 全局域主库集中(华北)+只读副本    │
│ Redis跨区异步(eventual,仅缓存)│Pulsar每区独立集群+geo-rep │
│ 任一区挂,单元仍可用(真多活)                                 │
└────────────────────────┬───────────────────────────────────┘
                         │ 海外用户完全不进国内(PIPL合规)
┌────────────────────────▼─────────────── 海外独立集群 ──────┐
│ 新加坡+法兰克福单元 │ 海外课程目录隔离/白名单同步 │ 数据不回流│
└────────────────────────────────────────────────────────────┘
```

**诚实约束**(不美化)：
- TiDB 跨区 Raft 写付跨区 RTT → 单元跨区选地理位置近的(华东-华北-华南)，非跨国
- Redis 跨区最终一致(开源无CRDT) → 只存缓存/会话，关键事务数据在 TiDB
- Pulsar geo-rep 需每区独立集群 → 成本计入
- 全局域主库单点风险 → Quorum 读+TiDB 自动 failover，切换期全局写受影响

---

## 3. 技术中台 SAD

### 3.1 职责边界
为所有事业群提供**通用技术能力**，屏蔽底层基础设施差异：网关/鉴权/路由/读写分离/高并发ingest/多级缓存/EDA/可观测/配置/限流降级。

### 3.2 组件与选型

| 组件 | 选型 | 语言 | 复用ESP | 说明 |
|------|------|------|---------|------|
| API网关/BFF | Spring Cloud Gateway | Java | ✅ bff-java | JWT RS256签发+JWKS、Sentinel限流、AuthFilter防伪造头 |
| WS/实时网关 | 自研 hub(Go) | Go | ✅ gateway-go | 长连接、WS广播按 fnv1a(session)%N 分片、跨pod Redis Pub/Sub |
| 单元路由器 CellRouter | 自研(Spring组件) | Java | 新增 | D-014 流量纠正+写挂起 |
| 读写分离 | AbstractRoutingDataSource+@DataSource AOP | Java | ✅ esp-rw-java | 4模式:READ/WRITE/READ+consistent/READ+tiflash |
| 高并发 ingest | LMAX Disruptor | Java | ✅ esp-disruptor | RingBuffer 65536,tryNext drop,batch 200/500ms flush |
| 事件总线 EDA | Pulsar | - | ✅ | topic={domain}.{action}；lazy-init+降级+默认关(PULSAR_ENABLED) |
| 多级缓存 | L1(Caffeine)+L2(Redis Cluster) | Java/Go/Py | ✅ esp-cache | 统一key {bu}:{svc}:{entity}:{id}，EMPTY_SENTINEL防穿透 |
| 鉴权 | JWT RS256+JWKS | Java/Go | ✅ | BFF签发，下游验签；跨语言统一 |
| 可观测 | OTel+Loki+Prom+Tempo | - | ✅ esp-observability | 两级Collector(edge tail-sampling→gateway)，双日志轨 |
| 配置中心 | Nacos | - | ✅ | 路由表/特性开关/限流规则 |
| 限流降级 | Sentinel | Java | ✅ | 网关+服务双层 |
| 服务治理 | Dubbo Triple(RPC) | Java | ✅ esp-api | 契约先行，Pact契约测试 |
| Rust热点 | 自研(风控/计费) | Rust | 新增 | 高性能热点路径 |

### 3.3 关键 ADR

**ADR-T01 单元路由器内置流量纠正(字节模式)**
- 背景：failover/路由表变更期 misroute 导致跨单元脏写
- 决策：CellRouter 在请求 context 内嵌纠正计数器(上限3)，检测错路由自动重路由；超限返403；存储层按 user_id 拦截错单元写
- 收益：failover 不脏写；恢复<30s
- 依据：字节 InfoQ 单元架构、阿里 AppActive

**ADR-T02 读写分离4模式契约**
- READ → Follower池；WRITE → Primary；READ+consistent → 强制Primary(防读后写)；READ+tiflash → TiFlash列存(OLAP)
- 配合 `SET SESSION tidb_read_consistency='strong'`(读池) + `@Version`乐观锁
- 依据：ESP esp-rw-java 验证

**ADR-T03 Disruptor 用于高频写ingest，Pulsar 用于跨服务事件**
- 高频写(学习事件/互动)走进程内 Disruptor(无网络跳)，批量flush到DB
- 跨服务事件(订单→学习、风控触发)走 Pulsar
- 两者边界：Disruptor=单服务内削峰；Pulsar=跨服务解耦
- 依据：ESP esp-disruptor + D-011/D-013

**ADR-T04 多级缓存契约(跨语言对齐)**
- key 明文 `{bu}:{svc}:{entity}:{id}`，禁 sha256(ESP已弃用，不可调试)
- 跨语言值=纯 JSON snake_case，禁 Java DefaultTyping(ESP定时的跨语言炸弹)
- 失效：有状态多副本(Java)→Pulsar广播+延迟双删；无状态(Py/Go)→本地驱逐+TTL
- 全局 cache_name 注册表防碰撞
- 依据：ESP 多级缓存架构对齐文档

**ADR-T05 探针三分离+HPA稳定窗(ESP血泪)**
- startup/liveness/readiness 分离，timeout≥3s，failureThreshold≥5(ESP 1s 阈值曾级联杀pod)
- HPA autoscaling/v2：scaleUp stabilization=0激进，scaleDown=120s/25%防抖
- 依据：ESP 架构评分与重构方向

---

## 4. 数据中台 SAD(Flink + Iceberg 湖仓一体)

### 4.1 职责边界
服务全事业群的**实时+离线数据能力**：实时指标/特征工程/CDC入湖/冷热分层/AI训练数据/离线数仓/跨BU联邦。

### 4.2 架构分层

```
┌─────────────────────────────────────────────────────────────────┐
│ ① 实时层(Flink)                                                  │
│   Flink SQL/CEP 实时指标(学习/直播/交易/风控/计费)                 │
│   实时特征工程→AI中台(Feature Store)                              │
├─────────────────────────────────────────────────────────────────┤
│ ② CDC总线                                                        │
│   TiDB → TiCDC changefeed → Kafka(Avro+SchemaRegistry)           │
│   Flink CDC 3.x YAML pipeline 分库分表合并                        │
│   不直连 Flink-CDC-TiDB(避免 GC safepoint 阻塞)                  │
├─────────────────────────────────────────────────────────────────┤
│ ③ 湖仓层(Apache Iceberg + 对象存储 S3/MinIO)                     │
│   Flink CDC 实时写 Iceberg(checkpoint=commit,5min,256MB,zstd)    │
│   SinkV2 链式 compaction(rewrite+expire+orphan+convertEquality)  │
│   保留 flink-job-id 快照供状态恢复                                │
│   Time-travel / Schema evolution EVOLVE / ACID                   │
├─────────────────────────────────────────────────────────────────┤
│ ④ 冷热分层                                                       │
│   热: TiDB/TiFlash 在线 HTAP                                     │
│   温/冷: Iceberg + 对象存储(成本数量级下降)                       │
├─────────────────────────────────────────────────────────────────┤
│ ⑤ 服务层                                                         │
│   离线数仓: Spark + Iceberg(留存/画像/复杂报表)                   │
│   AI训练数据: Iceberg→Spark/Flink→模型训练 pipeline              │
│   跨BU联邦: Iceberg 统一落点                                      │
├─────────────────────────────────────────────────────────────────┤
│ ⑥ 查询层                                                         │
│   在线: TiDB/TiFlash HTAP                                        │
│   湖仓交互: Trino/Presto on Iceberg(ad-hoc/数据科学)              │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 关键 ADR

**ADR-D01 CDC=TiCDC→Kafka→Flink→Iceberg(非直连)**
- 直连 Flink-CDC-TiDB 会持 GC safefence，慢表阻塞全库
- TiCDC→Kafka 解耦，按表分组多 changefeed
- Avro+Schema Registry 支持演化(EVOLVE)
- 依据：TiCDC FAQ + Flink CDC YAML pipeline

**ADR-D02 Flink→Iceberg checkpoint 即提交**
- checkpoint interval 5min(平衡延迟与小文件)，target file 256MB
- SinkV2 后置链式维护，消除独立维护作业
- 保留 flink-job-id 标记快照，过期时跳过它，否则状态恢复断裂
- 依据：Iceberg 官方 Flink writes + LakeOps

**ADR-D03 Feature Store 在线/离线一致性**
- 特征定义一次，编译为 Spark(离线训练)+Flink(在线)双作业(Hopsworks/Feast 模式)
- 在线 store: Redis/TiKV(亚ms)；离线 store: Iceberg
- Avro schema + registry 防在线/离线 skew
- 依据：Hopsworks+Feast

**ADR-D04 30亿历史数据冷热分层强制**
- 在线 TiDB 只留热数据(近期+活跃用户)；温冷归档 Iceberg
- AI训练/历史报表/归档审计走 Iceberg，不回压在线库
- 依据：D-013

---

## 5. 业务中台 SAD

### 5.1 职责边界
跨事业群共享的**业务领域能力**，事业群调用而非自建：用户中心/订单支付/营销/通知/课程目录/搜索/工单。

### 5.2 组件

| 组件 | 归属 | 说明 |
|------|------|------|
| 用户中心 UCM | 用户域(单元化) | 注册/登录/资料/实名/多端会话 |
| 订单支付 OPM | 用户域(单元化) | 下单/支付/退款/对账，事务核心，Rust热点计费 |
| 营销 MMP | 全局域+用户域 | 券模板全局，个人券单元化 |
| 通知 NTF | 跨域 | 站内信/Push/短信/邮件，Pulsar 事件驱动 |
| 课程目录 CMP | 全局域 | 课程/章节/资料，多副本读 |
| 搜索 SRH | 全局域 | ES/课程+题库检索(可叠 RAG 语义) |
| 工单 TKT | 全局域 | 客服工单/IM会话，跨客服调度 |
| 风控 RSK | 全局域规则+用户域行为 | Rust 热点规则引擎，Flink 实时行为分析 |

### 5.3 关键 ADR

**ADR-B01 业务中台能力=共享领域服务，事业群调而非fork**
- 新 BU 接入 = 调中台 API + 自建本 BU 专属领域(如 IT后端的代码评测)
- 中台演进通过契约(OpenAPI/Dubbo Triple)+Pact 不破坏消费方
- 依据：D-001 开放性

**ADR-B02 订单/支付强单元化，风控/计费走 Rust 热点**
- 订单事务在 user 单元内闭环；计费/风控高频规则用 Rust 保证 P95
- 依据：D-009/D-012

---

## 6. AI 中台 SAD(全栈)

### 6.1 职责边界
服务全事业群 AI 能力：模型网关/RAG/批改Agent/出题Agent/答疑/学习路径/数字人。

### 6.2 架构

```
┌─────────────────────────────────────────────────────────────────┐
│ AI应用层(Agent，LangGraph 多步)                                   │
│  批改Agent │ 出题Agent │ 答疑Agent │ 学习路径Agent │ 数字人       │
│  IT代码批改=human-in-the-loop(D-020)                            │
├─────────────────────────────────────────────────────────────────┤
│ 模型网关(Kong AI / LiteLLM起步)                                  │
│  多LLM路由 │ 限流/计费 │ 语义缓存(Redis LangCache) │ 模型路由      │
│  (RouteLLM类分类器) │ 多provider fallback │ LLM_MODE=mock(压测/CI)│
├─────────────────────────────────────────────────────────────────┤
│ RAG层                                                            │
│  混合检索(BM25+向量+metadata) │ Reranker │ 课程知识库             │
│  Milvus(集群已有,知识库层) │ pgvector(租户小集合)                │
│  多租户: namespace+pool+JWT驱动FGAC，禁app拼tenant_id(D-019)    │
├─────────────────────────────────────────────────────────────────┤
│ 数据底座                                                         │
│  Feature Store(数据中台喂) │ Iceberg(训练数据) │ 向量库 │ 图库     │
└─────────────────────────────────────────────────────────────────┘
```

### 6.3 关键 ADR

**ADR-A01 模型网关分层+成本栈**
- 部署顺序：cache→intent route→confidence cascade→multi-provider fallback
- 语义缓存 60-73%命中；RouteLLM 类路由 45-85% 成本降
- 依据：D-018 + 联网调研成本数据

**ADR-A02 RAG 多租户隔离=namespace+FGAC**
- 共享 index + JWT 驱动 tenant_id 强隔离，禁 app 层拼接
- 跨租户泄漏是 #1 事故类
- 依据：D-019

**ADR-A03 IT代码批改 human-in-the-loop**
- LLM 做形成性反馈(rubric分解 CoT)；高风险评分教师复核
- StepGrade 2025 研究 MAE 0.44-0.62 未达人类一致性
- 依据：D-020

**ADR-A04 LangGraph 用于多步Agent，轻量状态机用于固定流程**
- 批改/答疑/学习路径=LangGraph(interrupt_before+Redis checkpointer+Command(resume))
- 简单固定流程=自研轻量状态机(避免跨语言RPC延迟)
- 依据：ESP writing-py LangGraph 经验

---

## 7. IT后端 MVP 事业群(首个端到端打通，含 4 子平台 SAD)

> IT 后端事业群的核心技术深度集中在**直播平台、在线编程、作业平台、评分平台**四个子平台。本节给出每个子平台的架构与关键 ADR；类/接口/表结构 SDD 级留作 SP-05 独立 spec。

### 7.1 MVP 范围(In Scope)
- 学员：注册登录/选课购课/视频学习/代码作业提交+评测/考试/直播+互动/答疑
- 教师：开课/备课/批改(含 LLM 辅助)/直播/学情看板
- 三端：web(React)/h5/小程序，共 API
- 全链路：三端→BFF→单元路由→用户域服务+4子平台→中台→基础设施
- 验证目标：整套架构能跑通(G-01 M1=1万CCU)，非天花板压测

### 7.2 IT后端 4 子平台总览

| 子平台 | 归属域 | 语言 | 复用基础设施 | 关键ADR |
|--------|--------|------|-------------|---------|
| 直播平台 Live | 实时域 | Go(SFU/origin)+Java(房间管理) | Redis(路由)+对象存储(录制)+CDN | ADR-I01 |
| 在线编程 IDE | 用户域 | Rust(沙箱)+TS(前端) | K8s(Firecracker CRD)+Milvus(查重) | ADR-I02 |
| 作业平台 Assignment | 用户域 | Java | TiDB+Redis+Pulsar | ADR-I03 |
| 评分平台 Grading | 用户域+AI中台 | Java+Python(LLM) | Firecracker+AI网关+Iceberg | ADR-I04/I05 |

### 7.3 直播平台 Live(ADR-I01)

```
┌────────────────────────────────────────────────────────────────┐
│ 推流层  OBS/ffmpeg → WHIP(WebRTC ingest) / RTMP / SRT            │
├────────────────────────────────────────────────────────────────┤
│ Origin层  SRS v7 origin cluster(无状态代理+Redis路由流定位)      │
│           转码(ffmpeg/NVENC) → 录制FLV/MP4→对象存储(回放/时移)   │
├────────────────────────────────────────────────────────────────┤
│ 边缘分发  互动主播/连麦: LiveKit SFU(Go,WebRTC,亚秒) [≤几百观众] │
│           大众观看: LL-HLS经CDN边缘(2-4s延迟,无上限观众)          │
│           SRS edge cluster(SRS edge不缓存HLS,前叠Squid/ATS)     │
├────────────────────────────────────────────────────────────────┤
│ 互动层   弹幕/聊天/连麦/投票: WS网关(技术中台)+Redis(会话路由)    │
│           +Kafka/Pulsar(削峰缓冲) → 单节点50万连接,集群百万级    │
└────────────────────────────────────────────────────────────────┘
```

**ADR-I01 直播两层混合 + 房间单cell化**
- 互动(主播+连麦)走 WebRTC SFU(LiveKit)，≤几百观众；被动大众走 LL-HLS+CDN，无上限 —— WebRTC 大规模成本爆炸，混合不可省
- **直播房间(主播+SFU状态)落单一 cell**；跨 cell 观众走 CDN 边缘(cell-agnostic，仅 CDN 成本)，不跨 cell 拉媒体流
- room→cell 路由用一致性哈希(room_id)；cell 故障仅影响本 cell 房间，客户端经路由器重连健康 cell
- SRS origin cluster 用 Redis 做流定位(无状态代理可 K8s Deployment)
- 录制落对象存储，时移走 HLS 滑窗
- 依据：Mux LL-HLS 指南、SRS v7 origin/edge 文档、AWS cell-based 架构

### 7.4 在线编程 IDE(ADR-I02)

```
┌────────────────────────────────────────────────────────────────┐
│ 前端    Monaco editor + Language Server + Yjs(CRDT协作编辑)     │
├────────────────────────────────────────────────────────────────┤
│ 教学IDE(信任/轻不信任代码)                                       │
│   WebContainer(StackBlitz,WASM,客户端算力,毫秒启动)             │
│   适用: 练习/教学/协作; 盲区: 无原生二进制/原始TCP/GPU           │
├────────────────────────────────────────────────────────────────┤
│ 评测Judge(不信任代码,强隔离)                                     │
│   Firecracker microVM(125-200ms启动,预热池snapshot 5-30ms)      │
│   judge0(REST+Redis worker队列) + Firecracker后端               │
│   资源限制: cpu/mem/time/net严格隔离; 千级microVM/宿主          │
│   GPU需求(ML作业): 仅Firecracker支持VFIO穿透(gVisor不支持)       │
├────────────────────────────────────────────────────────────────┤
│ SandboxWarmPool CRD: 预热snapshot池,避免冷启动UX杀手            │
└────────────────────────────────────────────────────────────────┘
```

**ADR-I02 沙箱后端按信任分级 + IDE/Judge分后端**
- **教学 IDE**：WebContainer(WASM，客户端算力，零后端成本，毫秒启动) —— 盲区是无原生二进制/原始TCP，仅适合教学
- **评测 Judge**：Firecracker microVM(125-200ms，预热池 5-30ms) + judge0 队列 —— Docker 单独**不够安全**(多租户不信任代码)，必须 gVisor 或 Firecracker
- **IDE vs Judge 必须分后端**：IDE=长生命工作区/协作/弱隔离可接受；Judge=短生命/单次执行/严格资源限制/最强隔离 —— SLA 不同
- **协作编辑**：Yjs CRDT(Monaco/CodeMirror 绑定)，离线优先 p2p 结对编程
- **冷启动是 UX 杀手**：Firecracker 用 SandboxWarmPool CRD 预热 snapshot 池，不每请求冷启
- 依据：Spheron 沙箱对比、WebContainer 官方、OSDI'25 Fork-in-the-Road

### 7.5 作业平台 Assignment(ADR-I03)

```
┌────────────────────────────────────────────────────────────────┐
│ 数据模型(Canvas assignment-centric)                              │
│  Assignment → Submission(multi-attempt) → Grade → Regrade       │
│  状态机: unsubmitted→draft→submitted→graded→reviewed→archived   │
├────────────────────────────────────────────────────────────────┤
│ 生命周期  教师创建→发布→学员提交(多次)→评分→复核→归档            │
│           截止/补交/宽限期/草稿vs正式/Git集成(项目作业)          │
├────────────────────────────────────────────────────────────────┤
│ 防作弊(弱防御纵深,仅flag不自动扣分)                              │
│   browser lockdown + AI proctoring(假阳率30-50%)                │
│   → AI flag + 人工复核,永不自动罚                                │
├────────────────────────────────────────────────────────────────┤
│ 代码查重(两层)                                                   │
│   Tier1: MinHash+LSH O(n)桶分近重复(可扩十亿级)                 │
│   Tier2: 桶内跑 Dolos(tree-sitter)/JPlag(GST); MOSS弃用(无API) │
│   embedding+向量库: 实验性,暂不主用                              │
└────────────────────────────────────────────────────────────────┘
```

**ADR-I03 assignment-centric + 两层查重 + 弱防御防作弊**
- **数据模型**：Canvas assignment-centric 模式(行业默认)，非 Moodle 旧的 per-activity-table；Assignment→Submission→Grade→Regrade 一等实体
- **代码查重两层**：Tier1 MinHash+LSH(O(n)，可扩十亿，LSHBloom 证明)预桶 → Tier2 Dolos/JPlag 桶内 pairwise。纯 pairwise O(n²) 百万提交不可行；MOSS 弃用(无 API/单批)
- **防作弊=弱防御纵深**：browser lockdown + AI proctoring 假阳率 30-50%，**仅 flag 不自动扣分**，人工复核
- **提交完整性**：时间戳+hash 完整性，否则 regrade 申诉可争议
- 依据：Canvas 数据模型、Dolos/JPlag、Milvus MinHash-LSH、proctoring 同行评议

### 7.6 评分平台 Grading(ADR-I04/I05)

```
┌────────────────────────────────────────────────────────────────┐
│ 分层沙箱pipeline(CodeGrade AutoTest模式,每提交隔离容器跑)        │
│ ① IO/单元测试   实时,确定,sub-second~秒   ← 唯一可信高风险        │
│ ② 静态分析      Semgrep(必含X/必不含AI生成模式)                  │
│ ③ 安全扫描      Semgrep SAST / CodeQL                            │
│ ④ Style         checkstyle/prettier                              │
│ ⑤ LLM rubric评分 异步批,CoT+self-consistency k采样              │
│    human-in-the-loop高风险(继承D-020)                            │
├────────────────────────────────────────────────────────────────┤
│ LLM评分优化                                                      │
│   Rubric分解(每步1-2分,无部分分)+CoT(Rubric Is All You Need)    │
│   self-consistency: k采样多数表决降方差(单shot噪声大)            │
│   Leniency metric: 监控模型严格度漂移                            │
├────────────────────────────────────────────────────────────────┤
│ 成绩聚合  per-assignment→per-course→per-term→成绩单              │
│   加权方案/申诉regrade工作流/查重flag接入(不自动零分)            │
└────────────────────────────────────────────────────────────────┘
```

**ADR-I04 分层沙箱评分pipeline + LLM异步批**
- **确定性阶段(①-④)同步实时**：IO/单元测试是唯一可信高风险的阶段；静态/安全/Style 秒级
- **LLM 阶段(⑤)异步批**：秒~十秒级/提交，**永不放同步请求路径**(延迟/成本爆炸)；走 AI 网关+模型路由(D-018)
- **CodeGrade AutoTest V2 块式声明 pipeline** 是参考架构：可视化块(IO/单元/结构/自定义脚本)per 提交隔离跑
- 依据：CodeGrade AutoTest、Semgrep 评分

**ADR-I05 LLM rubric评分 human-in-the-loop(细化D-020)**
- **Rubric 分解 + CoT**：每题拆 rubric 步骤(1-2 分/步，无部分分)，CoT 每准则推理后再出分(Rubric Is All You Need 2025，降 MAE)
- **self-consistency**：k 采样多数表决(单 shot 噪声大，"Towards Reliable LLM Grading" 2025)
- **高风险人工复核**：LLM=形成性反馈+初筛，summativ/高风险教师定(继承 D-020)
- **AI 代码检测器=信号非证据**：准确率争议，仅 flag 复核，不自动零分
- **查重 flag≠零分**：接 regrade/appeals 工作流，人工签核
- 依据：Rubric Is All You Need(arXiv 2503.23989)、self-consistency 论文

### 7.7 MVP 验收标准
- 三端核心流程跑通(注册→选课→学习→作业→评测→出分→直播互动)
- 4 子平台各自核心链路验证(直播推拉流/在线编程沙箱启动/作业提交查重/评分pipeline)
- 单元路由+读写分离+多级缓存+EDA 链路验证
- 分布式 Locust 1万 CCU 压测达标(含直播互动并发、Judge 沙箱并发)
- Pact 契约 + 单元/集成/安全测试通过(含沙箱逃逸安全测试)
- 可观测全链路 trace 可见

---

## 8. 项目群 WBS 与分阶段路线图

### 8.1 子项目分解(每子项目独立 spec→plan→实现)

| ID | 子项目 | 依赖 | 阶段 |
|----|--------|------|------|
| SP-01 | 技术中台 SDD+实现(网关/路由/读写分离/Disruptor/缓存/EDA/可观测) | 本spec | M0-M1 |
| SP-02 | 数据中台 SDD+实现(Flink/Iceberg/CDC/Feature Store) | SP-01 | M1-M2 |
| SP-03 | 业务中台 SDD+实现(UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK) | SP-01 | M1-M2 |
| SP-04 | AI 中台 SDD+实现(网关/RAG/Agents/数字人) | SP-02(Feature) | M2 |
| SP-05 | IT后端 MVP SDD+实现(三端+直播/在线编程/作业/评分4子平台+项目实战) | SP-01/03/04 | M1-M2 |
| SP-06 | 多端前端框架(三端共API骨架) | SP-01契约 | M0-M1 |
| SP-07 | 单元化+多区域部署实现(CellRouter/跨区Raft/geo-rep) | SP-01 | M2-M3 |
| SP-08 | 全链路测试体系(单/集/契约/压/混沌/安全) | 各SP | M1-M3 |
| SP-09 | 扩容到天花板(10-20单元/100万并发/30亿) | SP-05/07 | M3 |
| SP-10 | 横向复制新事业群(IT前端/考公/考研/养生) | SP-05验证 | M3+ |

### 8.2 里程碑路线图

```
M0(底座,1-2月)
├─ SP-01 技术中台核心(网关/路由/读写分离/缓存/EDA骨架)
├─ SP-06 三端前端骨架
└─ 基础设施复用对接(TiDB/Redis/Pulsar/Kafka/可观测)

M1(IT后端 MVP 端到端,2-3月)  ← 第一个可演示里程碑
├─ SP-05 IT后端 MVP(三端+评测+学习/作业/考试/直播)
├─ SP-02 数据中台 v1(实时指标+CDC入湖)
├─ SP-03 业务中台 v1(UCM/OPM/CMP/NTF)
├─ SP-04 AI 中台 v1(网关+RAG+批改Agent)
├─ SP-08 测试体系 v1(单/集/契约/1万CCU压测)
└─ 验收: 1万CCU达标,整套架构跑通

M2(多BU+多区域,3-4月)
├─ SP-07 单元化+多区域(2-3单元跨区Raft+geo-rep)
├─ SP-02/03/04 中台 v2(Feature Store/风控/数字人)
└─ 扩 1-2 个新事业群试点

M3(冲天花板,4月+)
├─ SP-09 扩到 10-20单元/100万并发/30亿容量
├─ SP-10 横向复制剩余事业群
└─ 全量 SLO 达标(G-01..G-10)
```

---

## 9. 工程化方法论(继承 ESP 验证)

- **TDD**：红绿重构，公共库≥80%覆盖，安全核心100% CI硬门
- **DDD**：限界上下文(§2.2)划清中台/BU/单元边界
- **EDA**：Pulsar 跨服务事件 + Disruptor 进程内削峰
- **读写分离**：4模式契约(ADR-T02)
- **契约先行**：OpenAPI 3 + Dubbo Triple + Pact 契约测试
- **可观测**：W3C traceparent 全链路 + 两级OTel Collector + 双日志轨
- **文档四件套**：PRD→SRS→SAD→SDD 双向追溯矩阵(ESP 模板)
- **ADR 驱动**：本文档 §0 + 各中台 ADR，含"未做及原因"

---

## 10. 风险与开放问题

| ID | 风险/问题 | 缓解 |
|----|----------|------|
| R-01 | 30亿/100万并发为天花板，真实压测需多节点集群(单节点RD不可行) | 设计预留，单节点仅验证拓扑非性能；M3 上多节点实测 |
| R-02 | 全局域主库单点风险 | Quorum读+TiDB自动failover；M2评估是否拆分 |
| R-03 | Redis跨区最终一致丢窗口 | 关键事务在TiDB；Redis仅缓存/会话 |
| R-04 | Pulsar geo-rep每区独立集群成本 | M2核算；必要时降级为单元内自治+异步对账 |
| R-05 | Iceberg+TiDB Cloud Lake 部分特性未GA | 不依赖未GA特性；用成熟 SinkV2+对象存储 |
| R-06 | LLM成本/延迟在100万并发下不可控 | 语义缓存+模型路由+mock压测+预算告警 |
| R-07 | 当前K8s集群未运行(Rancher Desktop未启动) | 本spec为设计阶段；实现前需启集群 |
| O-01 | 海外课程目录与国内同步策略(白名单粒度)待定 | M2 合规评审后定 |
| O-02 | Rust 热点服务(风控/计费)的具体边界待 SDD 细化 | SP-01/SP-03 SDD 阶段定 |

---

## 11. 参考与依据

- ESP 参考项目：`/Users/joelgong/claude-Projects/English-Study-Platform`(PRD/SRS/SAD/SDD/测试/部署全流程)
- 字节单元架构：https://www.infoq.cn/article/itqowhjhw23s5yhyyg5v
- 阿里 AppActive：https://www.alibabacloud.com/blog/appactive-an-open-source-multi-active-disaster-tolerance-architecture_599067
- TiDB 高并发最佳实践：https://docs.pingcap.com/zh/best-practices/high-concurrency-best-practices/
- Pulsar geo-replication：https://pulsar.apache.org/docs/3.3.x/administration-geo/
- Iceberg Flink writes：https://iceberg.apache.org/docs/nightly/flink-writes/
- Flink CDC YAML pipeline：https://nightlies.apache.org/flink/flink-cdc-docs-master/docs/core-concept/route/
- TiCDC FAQ(GC safepoint)：https://docs.pingcap.com/tidb/stable/ticdc-faq/
- Hopsworks+Feast Feature Store：https://www.hopsworks.ai/post/building-feature-pipelines-with-apache-flink
- Kong AI Gateway benchmark：https://konghq.com/blog/engineering/ai-gateway-benchmark-kong-ai-gateway-portkey-litellm
- StepGrade 代码批改研究：https://arxiv.org/html/2503.20851v1
- LLM 成本优化策略：https://www.premai.io/blog/llm-cost-optimization-8-strategies-that-cut-api-spend-by-80-2026-guide/
- 中国 PIPL 跨境：https://www.dlapiperdataprotection.com/countries/china/transfer.html

---

**下一步**：本 spec 经用户 review 通过后，进入 `superpowers:writing-plans` 为 SP-01(技术中台)编写首个实现计划。
