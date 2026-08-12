# education-platform 项目群 —— 顶层 TOGAF 企业架构文档

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应Spec |
|------|------|------|------|------|----------|
| EP-TOGAF-001 | v1.0 | 2026-08-03 | Approved | ZCode(教授级高工模式) | EP-SPEC-001 |

> **文档定位**：本文件是 education-platform 项目群的**顶层企业架构伞文档**，按 TOGAF ADM 四架构域(Business/Data/Application/Technology)组织，统领所有子项目(SP-01..SP-10)与事业群(IT后端/IT前端/考公/考研/养生)的架构设计。
> **下游文档**：各子项目的 SRS/SAD/SDD/原型/测试/部署文档均以本文件为架构基线，通过双向追溯矩阵锚定。

---

## 目录

1. [架构愿景(Architecture Vision)](#1-架构愿景)
2. [利益相关者与关注点](#2-利益相关者与关注点)
3. [业务架构(Business Architecture)](#3-业务架构)
4. [数据架构(Data Architecture)](#4-数据架构)
5. [应用架构(Application Architecture)](#5-应用架构)
6. [技术架构(Technology Architecture)](#6-技术架构)
7. [架构治理与合规](#7-架构治理与合规)
8. [机会与解决方案(Phase E)](#8-机会与解决方案)
9. [迁移规划(Phase F)](#9-迁移规划)
10. [实施治理(Phase G/H)](#10-实施治理)
11. [变更管理(Phase H)](#11-变更管理)
12. [ADR 全集索引](#12-adr-全集索引)
13. [双向追溯矩阵](#13-双向追溯矩阵)
14. [技术架构增强 (D-039/D-040)](#14-技术架构增强)
15. [多区域多活部署架构 (D-041)](#15-多区域多活部署架构)
16. [AI智能驱动架构增强 (D-042)](#16-ai智能驱动架构增强)

---

## 1. 架构愿景

### 1.1 业务场景

构建支撑**大型在线教育公司多事业群**(IT后端/IT前端/考公/考研/养生…)的统一教育平台项目群。以四大中台(技术/数据/业务/AI)为共享底座，做到：

- **对需求开放**：新事业群接入 = 注册领域服务 + 调中台，不重造轮子
- **工业级规模**：天花板 30亿用户 / 100万并发 / 上万教师 + 客服
- **多区域部署**：国内异地多活 + 海外独立数据隔离(PIPL 合规)
- **多端一致**：web/h5/小程序三端共 API，前后端联调联测
- **工程化全流程**：TDD + DDD + EDA + 读写分离，全测试阶段可人工测试

### 1.2 量化 SLO 目标(天花板 vs MVP)

| ID | 指标 | 天花板(M3) | MVP(M1) | 测量方法 | TOGAF域 |
|----|------|-----------|---------|----------|---------|
| G-01 | 峰值并发用户 | 1,000,000 CCU | 10,000 CCU | 分布式 Locust | Technology |
| G-02 | 注册用户容量 | 3,000,000,000 | 1,000,000 | TiDB 容量规划 | Data |
| G-03 | 核心 API P95 | <200ms | <200ms | Prometheus histogram | Technology |
| G-04 | 核心 API P99 | <500ms | <500ms | Prometheus histogram | Technology |
| G-05 | 直播/互动延迟 | <800ms P95 | <800ms P95 | WS 端到端压测 | Application |
| G-06 | 学习事件 ingestion | ≥500,000 ev/s | ≥10,000 ev/s | Disruptor + Pulsar | Technology |
| G-07 | 可用性 | 99.99% | 99.9% | PromQL up + 故障注入 | Technology |
| G-08 | 单元故障切换 | <30s | <60s | AppActive 模式演练 | Technology |
| G-09 | 数据中台实时延迟 | <5min(Iceberg入湖) | <5min | Flink checkpoint→commit | Data |
| G-10 | AI 推理 P95 | <3s | <3s | AI 网关 trace + mock压测 | Application |

### 1.3 TOGAF ADM 裁剪说明

本项目采用 **TOGAF ADM 裁剪模式**：完整走 A→B→C→D→E→F→G→H 八阶段，但产出务实化：
- Phase A(愿景)：本文档 §1-§2
- Phase B(业务架构)：§3
- Phase C(信息系统架构)：§4(数据) + §5(应用)
- Phase D(技术架构)：§6
- Phase E(机会与解决方案)：§8
- Phase F(迁移规划)：§9(路线图)
- Phase G(实施治理)：§10(CI/CD + 架构合规门)
- Phase H(变更管理)：§11(ADR 驱动)

**裁剪理由**：完整 TOGAF 文档量过大(单项目可达千页)，本项目群采用"四域骨架 + ADR 驱动 + 追溯矩阵"模式，保留治理刚性、压缩冗余叙述。

---

## 2. 利益相关者与关注点

### 2.1 利益相关者矩阵

| 利益相关者 | 角色 | 关注点 | TOGAF域映射 | 验收方式 |
|-----------|------|--------|------------|----------|
| 学员(30亿天花板) | 服务消费者 | 学习体验流畅、多端一致、直播低延迟 | Business/Application | UAT + 可用性监控 |
| 教师(上万滚动授课) | 内容生产者 | 开课便捷、批改高效、LLM辅助准确 | Business/Application | 教师满意度调研 |
| 客服 | 运营支撑 | 工单流转高效、学员画像可见 | Business | 工单SLA |
| 运营 | 业务运营 | 课程上架/活动/数据看板 | Business/Data | 运营报表 |
| 管理员 | 平台运维 | 权限/审计/风控/单元运维 | Technology | 审计日志 |
| 架构师(本文档读者) | 架构决策 | 可扩展性/可维护性/合规 | All | ADR review |
| 安全合规团队 | 风险控制 | PIPL 数据出境/沙箱逃逸/多租户隔离 | Technology/Business | 安全审计 |
| 财务 | 成本控制 | LLM成本/CDN成本/基础设施成本 | Technology | 成本看板 |

### 2.2 关键关注点 → 架构决策映射

| 关注点 | 驱动的架构决策 | ADR ID |
|--------|--------------|--------|
| 30亿用户不可能单库 | 单元化(Shard-by-UserID) + 逻辑分表 | D-008, D-012, D-015 |
| 100万并发不能单点 | 混合单元化 + 跨区 Raft + 多级缓存 | D-012, D-014 |
| 海外数据不能回流国内 | 海外独立集群 + PIPL 合规 | D-004 |
| LLM 成本/延迟不可控 | 语义缓存 + 模型路由 + mock 压测 | D-018, R-06 |
| 新事业群接入要快 | 四大中台共享 + 契约先行 + DDD 限界上下文 | D-001, ADR-B01 |
| 跨单元不能直接 RPC | Pulsar 事件 + Flink 异步聚合 | D-012, ADR-T03 |

---

## 3. 业务架构(Business Architecture)

> Phase B：定义业务战略、治理、组织、关键业务流程。本项目群以"事业群 × 中台"矩阵组织业务能力。

### 3.1 业务能力地图(Capability Map)

```
education-platform 业务能力地图
│
├─ 学员服务 (Learner Services)
│  ├─ 注册登录与多端会话
│  ├─ 选课购课
│  ├─ 视频学习与进度跟踪
│  ├─ 代码作业提交与评测
│  ├─ 在线编程练习(IDE)
│  ├─ 考试与认证
│  ├─ 直播学习与互动
│  ├─ AI 答疑与辅导
│  └─ 个人画像与学习路径
│
├─ 教师服务 (Teacher Services)
│  ├─ 开课与备课
│  ├─ 作业设计与发布
│  ├─ 批改(LLM辅助 + 人工复核)
│  ├─ 直播授课
│  ├─ 学情看板
│  └─ 教学资源管理
│
├─ 客服服务 (Support Services)
│  ├─ 工单管理
│  ├─ IM 会话
│  ├─ 退款与投诉
│  └─ 学员画像查询
│
├─ 运营服务 (Operations Services)
│  ├─ 课程上架与目录管理
│  ├─ 营销活动与优惠券
│  ├─ 数据看板
│  └─ 内容审核
│
├─ 平台管理 (Platform Administration)
│  ├─ 权限与角色管理
│  ├─ 审计日志
│  ├─ 风控规则管理
│  ├─ 单元运维与流量调度
│  └─ 系统配置
│
└─ 中台能力 (Shared Platform Capabilities)
   ├─ 技术中台(网关/鉴权/路由/缓存/EDA/可观测)
   ├─ 数据中台(实时指标/CDC入湖/特征工程/数仓)
   ├─ 业务中台(UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK)
   └─ AI中台(模型网关/RAG/Agent/数字人)
```

### 3.2 事业群 × 业务能力矩阵

| 业务能力 | IT后端(MVP) | IT前端 | 考公 | 考研 | 养生 |
|---------|:-----------:|:------:|:----:|:----:|:----:|
| 选课购课 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 视频学习 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 代码作业+评测 | ✅核心 | ✅ | — | — | — |
| 在线编程IDE | ✅核心 | ✅ | — | — | — |
| 直播+互动 | ✅核心 | ✅ | ✅ | ✅ | ✅ |
| AI答疑 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 考试模拟 | ✅ | — | ✅核心 | ✅核心 | — |
| 学习路径 | ✅ | ✅ | ✅ | ✅ | ✅ |

> **中台复用原则**：✅ 的能力由中台统一提供，事业群调而非自建。标注"核心"的是该事业群的差异化深度能力，需事业群自建但调用中台底座。

### 3.3 核心业务流程(价值流)

#### 3.3.1 学员学习价值流

```
[注册/登录] → [选课购课] → [视频学习] → [作业/练习] → [评测出分] → [直播互动] → [AI答疑] → [学习路径推荐]
     │              │              │             │             │             │            │              │
   UCM中台       OPM中台       学习记录      作业平台      评分平台      直播平台     AI中台       AI中台
   (单元化)     (单元化)      (单元化)      (单元化)      (单元化)      (实时域)     (全栈)       (全栈)
```

#### 3.3.2 教师教学价值流

```
[开课申请] → [备课/资源] → [发布作业] → [直播授课] → [批改(LLM+人工)] → [学情分析] → [教学优化]
     │              │             │             │              │                │             │
   CMP中台       CMP中台      作业平台      直播平台       评分平台          数据中台      AI中台
   (全局域)     (全局域)     (单元化)      (实时域)      (单元化+AI)       (Flink)      (全栈)
```

#### 3.3.3 新事业群接入价值流

```
[业务需求分析] → [DDD限界上下文设计] → [注册领域服务] → [调中台API] → [自建BU专属能力] → [契约测试] → [上线]
                      │                       │              │                │                │          │
                   本文档§3               Nacos注册       OpenAPI/Dubbo    如IT后端的代码评测   Pact       CI/CD
```

### 3.4 业务组织映射

```
                    ┌─────────────── CTO / 架构委员会 ───────────────┐
                    │                  (架构治理)                       │
    ┌───────────────┼───────────────────┼───────────────────┐          │
    │               │                   │                   │          │
┌───▼────┐  ┌───────▼──────┐  ┌────────▼────────┐  ┌───────▼──────┐  │
│技术中台队│  │  数据中台队   │  │   业务中台队     │  │   AI中台队    │  │
│(Java/Go)│  │ (Python/Flink)│  │  (Java/Rust)    │  │ (Python/Go)  │  │
└────┬────┘  └───────┬──────┘  └────────┬────────┘  └───────┬──────┘  │
     │               │                   │                   │          │
     └───────────────┼───────────────────┼───────────────────┘          │
                     │                   │                              │
              ┌──────▼───────────────────▼──────┐                       │
              │      事业群业务团队(BU)           │                       │
              │  IT后端 │ IT前端 │ 考公 │ 考研 │ 养生                    │
              └─────────────────────────────────┘                       │
                    │                                               │
              ┌─────▼─────┐                                   ┌──────▼─────┐
              │ SRE/运维队 │                                   │ 安全合规队  │
              └───────────┘                                   └────────────┘
```

---

## 4. 数据架构(Data Architecture)

> Phase C-1：定义数据资产、数据模型、数据流转、数据治理。

### 4.1 数据资产清单

| 数据域 | 归属 | 核心实体 | 存储选型 | 单元化策略 | 生命周期 |
|--------|------|---------|----------|-----------|---------|
| 用户账户 Account | 用户域 | User/Auth/Credential | TiDB(热) + Iceberg(冷) | 强单元化 user_id | 永久 |
| 学习记录 Learning | 用户域 | Enrollment/Progress/Assignment | TiDB(热) + Iceberg(冷) | 强单元化 user_id | 热6月→冷归档 |
| 订单支付 Order | 用户域 | Order/Payment/Refund | TiDB | 强单元化 user_id | 永久(合规) |
| 个人画像 Profile | 用户域 | LearnerProfile/Mastery | TiDB + Redis(特征) | 强单元化 user_id | 永久 |
| 课程目录 Course | 全局域 | Course/CourseSection/Chapter | TiDB + Redis(缓存) | 共享+多副本读 | 永久 |
| 题库 Question | 全局域+用户域 | Question/QuestionBank | TiDB + Milvus(语义) | 共享库+个人作答落单元 | 永久 |
| 教师档案 Teacher | 全局域 | TeacherProfile/CourseDraft | TiDB | 共享+多副本读 | 永久 |
| 运营活动 Ops | 全局域 | Campaign/CouponTemplate | TiDB | 共享主库单写 | 活动期→归档 |
| 客服工单 Ticket | 全局域 | Ticket/Session | TiDB + ES(检索) | 共享(跨客服调度) | 2年→归档 |
| 风控规则 RiskRule | 全局域 | RiskRule/BlackList | TiDB + Redis(热规则) | 共享+广播缓存 | 永久 |
| 直播 Live | 实时域 | LiveRoom/Stream | Redis(路由) + 对象存储(录制) | 单元内(房间主单元) | 录制永久 |
| 互动 Interact | 实时域 | Danmaku/Connect/IM | TiDB + Pulsar(削峰) | 单元内 | 3月→归档 |
| AI 向量 | AI域 | Embedding/KnowledgeChunk | Milvus(主) + pgvector(小集合) | namespace+FGAC | 永久 |
| 数据湖 | 数据域 | Iceberg 表(数仓/特征/训练) | S3/MinIO + Iceberg | 统一落点 | 分区过期策略 |

### 4.2 数据模型分层

```
┌─────────────────────────────────────────────────────────────┐
│  L1: 概念模型(Conceptual) —— DDD 限界上下文(见§3.2 spec)     │
│  12 个限界上下文,定义领域边界                                 │
├─────────────────────────────────────────────────────────────┤
│  L2: 逻辑模型(Logical) —— 聚合根 + 实体 + 值对象              │
│  每个限界上下文的聚合设计(详见各子项目 SDD)                   │
├─────────────────────────────────────────────────────────────┤
│  L3: 物理模型(Physical) —— TiDB 表结构 + 分片策略             │
│  AUTO_RANDOM(5) + PRE_SPLIT_REGIONS + 手动 user_id 分表      │
│  Iceberg 表分区策略(day/bucket)                              │
├─────────────────────────────────────────────────────────────┤
│  L4: 缓存模型(Cache) —— Redis Cluster key 规范                │
│  key = {bu}:{svc}:{entity}:{id}, 值=纯JSON snake_case         │
│  EMPTY_SENTINEL = __EP_CACHE_EMPTY__ (防穿透)                 │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 数据流转架构

```
                    ┌────────────── 在线写入路径 ──────────────┐
                    │                                          │
  用户请求 → BFF → CellRouter → 用户域服务(单元内)
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
              TiDB Primary    Disruptor         Pulsar
              (强一致写)     (高频写削峰)      (跨服务事件)
                    │               │               │
              TiDB Follower    批量flush         异步消费
              (读写分离读)     到DB             (订单→学习等)
                    │
              TiCDC changefeed
                    │
              Pulsar(Avro+内置SchemaRegistry, ns=cdc://)
                    │
         ┌──────────┼──────────┐
         │          │          │
     Flink CDC  Flink SQL   Flink CEP
     (入湖)     (实时指标)  (风控/告警)
         │          │          │
     Iceberg    Redis/      触发动作
     (湖仓)     Prometheus   (限流/阻断)
         │
     Spark(离线数仓)
     Trino(ad-hoc查询)
     AI训练数据
```

### 4.4 冷热分层策略(ADR-D04)

| 层级 | 存储 | 数据 | 成本量级 | 访问延迟 |
|------|------|------|---------|---------|
| 热(在线) | TiDB + TiFlash | 近期+活跃用户 | $$$ | <10ms |
| 温(近线) | TiFlash 列存 | OLAP 分析 | $$ | <100ms |
| 冷(湖仓) | Iceberg + S3/MinIO | 历史数据+训练数据 | $ | 秒级 |
| 归档 | S3 Glacier | 合规归档 | cents | 分钟级 |

**强制规则**：在线 TiDB 只留热数据(近期 + 活跃用户)；温冷归档 Iceberg；AI 训练/历史报表/归档审计走 Iceberg，不回压在线库。

### 4.5 数据治理

| 治理维度 | 策略 | 责任方 |
|---------|------|--------|
| 数据质量 | Avro Schema Registry 强制校验 | 数据中台队 |
| 数据安全 | JWT 驱动 FGAC(禁 app 拼 tenant_id) | AI中台队 |
| 数据合规 | PIPL 海外隔离 / 国内数据不出境 | 安全合规队 |
| 数据 lineage | TiCDC → Pulsar(ns=cdc://) → Flink → Iceberg 全链路 | 数据中台队 |
| 数据保留 | 热6月→冷归档；订单永久(合规) | 运营 + SRE |
| 数据加密 | 传输 TLS / 静态 AES-256 | SRE |

---

## 5. 应用架构(Application Architecture)

> Phase C-2：定义应用组件、服务边界、交互模式。

### 5.1 应用组件总览

```
┌─────────────────────────────────────────────────────────────────┐
│  多端接入层                                                      │
│  web(React) │ h5(React移动优先) │ 小程序(微信/Taro)              │
│  共享 API 契约(OpenAPI 3) + W3C traceparent 注入                 │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│  BFF / API 网关层(Java/Netty edge+Disruptor+WebMVC + Go WS网关)     │  ← D-032/ADR-T07(rev2)
│  JWT RS256 签发+JWKS │ CellRouter │ Sentinel限流 │ 灰度           │
└────────────────────────────────┬────────────────────────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
┌───────▼────────┐    ┌──────────▼──────────┐    ┌────────▼──────────┐
│  全局域服务     │    │   用户域服务(单元内) │    │  实时域服务        │
│  课程/题库/教师 │    │   学习/订单/账户     │    │  直播/互动/IM      │
│  运营/工单/风控 │    │   画像/作业/评分     │    │  WS网关            │
└───────┬────────┘    └──────────┬──────────┘    └────────┬──────────┘
        │                        │                        │
        └────────────────────────┼────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│              四大中台(共享底座，每单元部署副本)                    │
│  技术中台 │ 数据中台 │ 业务中台 │ AI中台                          │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│              事业群业务服务(BU)                                   │
│  IT后端(MVP) │ IT前端 │ 考公 │ 考研 │ 养生                      │
│  └ 4子平台: 直播/在线编程/作业/评分                               │
└────────────────────────────────┬────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│  基础设施层(复用容器集群)                                         │
│  TiDB/TiFlash │ Redis Cluster │ Pulsar(CDC+事件统一) │ Milvus │ 图库 │ K8s │
│  S3/MinIO │ 可观测(Loki/Prom/Tempo)                                          │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 服务清单与语言选型

| 服务 | 中台/BU | 语言 | 域归属 | 单元化 | 关键依赖 |
|------|--------|------|--------|--------|---------|
| bff-java | 技术中台 | Java | 接入层 | 无状态 | Redis(JWKS)/Nacos |
| gateway-go | 技术中台 | Go | 接入层 | 无状态 | Redis/Pulsar(WS跨pod广播) |
| CellRouter | 技术中台 | Java | 接入层 | 无状态 | Nacos(路由表) |
| ep-rw-java | 技术中台 | Java | SDK | — | TiDB |
| ep-disruptor | 技术中台 | Java | SDK | — | — |
| ep-cache | 技术中台 | Java | SDK | — | Redis/Pulsar |
| ep-api | 技术中台 | Java | RPC | — | Dubbo Triple |
| ucm-service | 业务中台 | Java | 用户域 | 强单元化 | TiDB/Redis |
| opm-service | 业务中台 | Java+Rust | 用户域 | 强单元化 | TiDB/Pulsar |
| mmp-service | 业务中台 | Java | 全域+用户域 | 混合 | TiDB/Redis |
| ntf-service | 业务中台 | Java | 跨域 | — | Pulsar |
| cmp-service | 业务中台 | Java | 全局域 | 共享 | TiDB/Redis/ES |
| srh-service | 业务中台 | Java | 全局域 | 共享 | ES/Milvus |
| tkt-service | 业务中台 | Java | 全局域 | 共享 | TiDB/ES |
| rsk-service | 业务中台 | Rust+Java | 全域+用户域 | 混合 | Redis/Flink |
| flink-jobs | 数据中台 | Python/SQL | 数据域 | — | Pulsar(ns=cdc://)/Iceberg/TiDB |
| ai-gateway | AI中台 | Python | AI域 | 无状态 | Redis(LangCache)/Kong |
| rag-service | AI中台 | Python | AI域 | namespace+FGAC | Milvus/pgvector |
| agent-service | AI中台 | Python | AI域 | — | LangGraph/AI网关 |
| live-origin | IT后端 | Go | 实时域 | 房间单cell | SRS/Redis/CDN |
| live-room | IT后端 | Java | 实时域 | 房间单cell | TiDB/Redis |
| ide-service | IT后端 | Rust+TS | 用户域 | 强单元化 | K8s(Firecracker) |
| assignment-service | IT后端 | Java | 用户域 | 强单元化 | TiDB/Pulsar |
| grading-service | IT后端 | Java+Python | 用户域+AI | 强单元化 | Firecracker/AI网关 |

### 5.3 交互模式

| 模式 | 场景 | 技术 | 示例 |
|------|------|------|------|
| 同步 RPC | 请求-响应 | Dubbo Triple | BFF→UCM 查用户 |
| 异步事件 | 跨服务解耦 | Pulsar | 订单支付成功→开通课程 |
| 进程内削峰 | 高频写 | Disruptor | 学习事件 ingest |
| WS 长连接 | 实时推送 | Go WS网关 + Pulsar(跨pod广播) | 直播弹幕/连麦信令 |
| CDC 流 | 数据同步 | TiCDC→Pulsar→Flink | TiDB 变更入湖 |
| 批处理 | 离线计算 | Spark on Iceberg | 留存/画像/报表 |

### 5.4 契约治理

- **契约先行**：OpenAPI 3(REST) + Dubbo IDL(Triple RPC) 在实现前定义
- **Pact 契约测试**：消费者驱动，CI 硬门
- **版本策略**：语义化版本 + 向后兼容窗口(2个minor)
- **演化策略**：Avro Schema Registry(EVOLVE 模式) for CDC

---

## 6. 技术架构(Technology Architecture)

> Phase D：定义技术基础设施、部署拓扑、可观测、安全。

### 6.1 基础设施栈(复用容器集群)

| 组件 | 选型 | 版本 | 用途 | 复用 |
|------|------|------|------|------|
| 分布式数据库 | TiDB | 8.5 LTS | HTAP 在线库(Primary/Follower) + TiFlash 列存；对齐 ESP v8.5.7 3-region Raft 验证(ADR-T-DB) | ✅ 集群已有 |
| Java 框架 | Spring Boot | 4.0.7 | 全栈基座(D-032 pin, SCA 2025.1.0.0 强映射 SB4.0) | ✅ |
| 微服务治理 | Spring Cloud Alibaba | 2025.1.0.0 | Nacos 注册/配置 + Sentinel 限流 + Dubbo Triple RPC(D-032/铁律6: 禁 Spring Cloud Netflix/SCG 原生组件) | ✅ |
| Spring Cloud | Spring Cloud | 2025.1.2 | 仅作 SCA release train 对齐基线(SCA 2025.1.0.0↔SC 2025.1.x↔SB4.0), 不直接使用其原生组件 | ✅ |
| 边缘网关 | Netty + Disruptor + WebMVC | 4.1.136.Final / 4.0.0 / SB4.0.7 | 自建 BFF 边缘接入层(ADR-T07 rev2, 禁 SCG — D-032) | ✅ |
| 缓存 | Redis Cluster | 8.0 | L2 缓存/会话/路由表/特征(对齐 ESP 实际部署 redis:8.0-alpine, 非 7.x) | ✅ |
| 消息队列 | Pulsar | 3.3.x | 跨服务事件/CDC 统一总线/geo-rep(ADR-02 承接 ESP, CDC 亦走 Pulsar — ADR-D01, 彻底不引入 Kafka; 4.x 升级延迟至 v2.0 — ADR-T11) | ✅ |
| CDC sink | TiCDC→Pulsar | 8.5 LTS / 3.3.x | TiCDC changefeed 直达 Pulsar(ns=cdc://, Avro+内置 Schema Registry), Flink CDC 消费入湖 — 替代原 Kafka 缓冲方案(ADR-D01) | ✅ |
| 向量数据库 | Milvus | 2.5+ | RAG/语义检索/查重(对齐 ESP 验证 2.5.0) | ✅ |
| 图数据库 | Neo4j | 5.25+ | 知识图谱/学习路径(对齐 ESP 验证 5.25.1-community) | ✅ |
| 对象存储 | S3/MinIO | — | 录制/资料/Iceberg 底层 | ✅ |
| 湖仓 | Apache Iceberg | 1.x | 流批一体 Lakehouse | 新增(基于S3) |
| 容器编排 | K8s | 1.28+ | 服务编排/自愈/HPA | ✅ |
| 可观测-日志 | Loki | — | 结构化日志(承接 ESP Fluent Bit→Loki) | ✅ |
| 可观测-指标 | Prometheus | — | 指标采集/告警 | ✅ |
| 可观测-链路 | Tempo + OTel | — | 分布式链路追踪(承接 ESP 双层 Collector tail-sampling 防 OOM) | ✅ |
| 配置中心 | Nacos | 2.4.3 | 路由表/特性开关/限流规则下发(pin ESP 验证版, 不升 v3 直到镜像可用) | ✅ |
| 限流降级 | Sentinel | 1.8+ | 网关+服务双层(规则走 Nacos 分布式下发, 禁本地内存) | ✅ |
| 服务发现/RPC | Dubbo | 3.3.6 | Triple(HTTP/2+Protobuf)契约先行, 禁 STRICT 序列化(ADR-T09, pin ESP 验证版) | ✅ |
| AI 网关 | Kong AI / LiteLLM | — | LLM 路由/缓存/计费(D-018 LiteLLM 起步→Kong AI) | 新增 |
| Python Web | Litestar + Granian | 2.x / ≥1.6.0 | Rust ASGI 高性能(承接 ESP ADR-09, 替代 FastAPI/Uvicorn; ESP 实际解析至 2.7.9, EP 锁定 ≥1.6.0 下限) | 新增 |
| 直播 SFU | LiveKit | — | WebRTC 互动 | 新增 |
| 直播 Origin | SRS v7 | — | 推流/转码/录制 | 新增 |
| 沙箱 | Firecracker | — | 评测 Judge microVM | 新增 |

### 6.2 部署拓扑(混合单元化)

```
┌────────────────────── 国内多活(华东/华北/华南三角)──────────────────┐
│                                                                     │
│   ┌─────── Cell-0 (华东+华北) ───────┐  ┌──── Cell-1 (华北+华南) ──┐│
│   │ TiDB分片0(跨2区Raft)              │  │ TiDB分片1                ││
│   │ Redis分片0                        │  │ Redis分片1                ││
│   │ Pulsar namespace-0                │  │ Pulsar namespace-1        ││
│   │ 中台副本(技术/数据/业务/AI)        │  │ 中台副本                  ││
│   │ BU副本(IT后端4子平台)             │  │ BU副本                    ││
│   └───────────────────────────────────┘  └──────────────────────────┘│
│                                                                     │
│   ┌──── 全局域主库(华北,单写) ──────┐  ┌──── Redis多副本(异步) ────┐│
│   │ 课程/题库/教师/运营/工单/风控     │  │ 缓存/会话(eventual)       ││
│   └──────────────────────────────────┘  └───────────────────────────┘│
│                                                                     │
│   ┌──── Pulsar跨单元(geo-rep聚合) ──┐  ┌──── Flink集群 ────────────┐│
│   │ 单元间异步事件                    │  │ CDC入湖/实时指标/特征     ││
│   └──────────────────────────────────┘  └───────────────────────────┘│
└────────────────────────────────┬────────────────────────────────────┘
                                 │ 海外用户完全不进国内(PIPL)
┌────────────────────────────────▼──── 海外独立集群 ──────────────────┐
│ 新加坡单元 + 法兰克福单元 │ 海外课程目录隔离/白名单同步 │ 数据不回流 │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.3 单元化机制(ADR D-012/D-014)

| 机制 | 说明 | 实现 |
|------|------|------|
| 路由表外置 | hash(user_id)%N 的 N 与映射可调 | Nacos/etcd |
| misroute 自动纠正 | 请求 context 内嵌纠正计数器(上限3) | CellRouter |
| 跨单元写挂起 | failover 期间该 user_id 写挂起 | AppActive 模式 |
| 跨区 Raft | 每单元 TiDB 分片跨 2 区 | TiDB Placement Rules |
| 就近写 | 请求路由到就近区副本 | CellRouter + 地理位置 |
| 全局域单写多读 | 主库1份 + 只读副本 | TiDB Follower |
| 跨单元走异步 | 单元间不直接 RPC | Pulsar + Flink 聚合 |

**容量规划**：100万并发 / 单单元 5-10万 ≈ 10-20 单元(国内)；30亿用户 / 单单元 1.5-3亿 ≈ 10-20 单元。M1 阶段 2-3 单元验证，M3 扩到 10-20。

### 6.4 可观测架构

```
服务(pod) ──OTel SDK──► Edge Collector(tail-sampling 10%)
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

**双日志轨**：access log(请求级) + business log(业务事件级)，分别采集、独立检索。

### 6.5 安全架构

| 层 | 措施 | ADR |
|----|------|-----|
| 接入 | TLS 1.3 / WAF / DDoS 防护 | — |
| 鉴权 | JWT RS256 + JWKS(BFF签发,下游验签) | ADR-T01 |
| 授权 | RBAC + ABAC(资源级) | — |
| 多租户隔离 | RAG namespace + JWT FGAC(禁 app 拼 tenant_id) | ADR-A02 |
| 沙箱隔离 | Firecracker microVM(评测Judge) | ADR-I02 |
| 数据加密 | 传输 TLS / 静态 AES-256 | — |
| 数据合规 | PIPL 海外隔离 / 国内不出境 | D-004 |
| 审计 | 全操作审计日志(不可篡改) | — |
| 风控 | Rust 热点规则 + Flink 实时行为 | ADR-B02 |

### 6.6 K8s 部署规范(ADR-T05)

```yaml
# 探针三分离(ESP血泪)
startupProbe:    { timeoutSeconds: 3, failureThreshold: 30, periodSeconds: 10 }  # 慢启动
livenessProbe:   { timeoutSeconds: 3, failureThreshold: 5,  periodSeconds: 10 }  # 不轻易杀
readinessProbe:  { timeoutSeconds: 3, failureThreshold: 5,  periodSeconds: 5  }  # 流量门控

# HPA autoscaling/v2
hpa:
  scaleUp:   { stabilizationWindowSeconds: 0,     policies: [{type: Percent, value: 100, periodSeconds: 30}] }  # 激进扩
  scaleDown: { stabilizationWindowSeconds: 120,   policies: [{type: Percent, value: 25,  periodSeconds: 120}]} # 防抖缩

# PDB(滚动部署不中断)
podDisruptionBudget: { minAvailable: 1 }

# NetworkPolicy(默认拒绝,白名单放通)
networkPolicy: { deny-all + allow-namespace }
```

---

## 7. 架构治理与合规

### 7.1 架构原则(不可违反)

| ID | 原则 | 违反后果 |
|----|------|---------|
| P-01 | 新 BU 接入 = 调中台,不自建共享能力 | 架构委员会否决 |
| P-02 | 跨单元不直接 RPC,走 Pulsar 异步 | 代码审查不过 |
| P-03 | 禁 app 层拼 tenant_id,用 JWT FGAC | 安全审计红线 |
| P-04 | 在线 TiDB 只留热数据,冷数据归 Iceberg | 容量告警 + 强制迁移 |
| P-05 | 公共库 ≥80% 覆盖,安全核心 100% | CI 硬门阻断 |
| P-06 | 契约先行(OpenAPI/IDL),Pact 测试 | CI 硬门阻断 |
| P-07 | 探针三分离,timeout≥3s | 部署审查不过 |
| P-08 | LLM 评分 human-in-the-loop,高风险教师定 | 安全合规红线 |
| P-09 | 海外数据不回流国内 | PIPL 合规红线 |
| P-10 | 上下文 80% 主动压缩,状态外化 Redis | 铁律 |

### 7.2 架构委员会

- **组成**：CTO + 4中台负责人 + SRE负责人 + 安全合规负责人
- **职责**：ADR 审批 / 架构原则守护 / 跨子项目冲突裁决 / 技术债看板
- **节奏**：双周架构评审会 / 季度架构复盘 / ADR 随时提交

### 7.3 合规框架

| 法规 | 要求 | 架构对策 |
|------|------|---------|
| PIPL(中国) | 个人信息不出境 | 海外独立集群 + 数据不回流 |
| GDPR(欧盟) | 数据主体权利 | 数据可删除/导出接口 |
| 等保2.0 | 安全等级保护 | 审计日志 + 加密 + 访问控制 |
| 著作权法 | 课程内容版权 | DRM + 水印 + 访问控制 |

### 7.4 TOGAF 构件索引

> 本项目群 TOGAF ADM 完整构件集(D-157 补全)。所有构件以本文件(00)为伞文档,通过双向追溯保持一致。

| 文件 | 构件类型 | TOGAF 阶段 | 内容摘要 |
|------|---------|-----------|---------|
| `00-top-level-enterprise-architecture.md` | 顶层企业架构伞文档 | A-H | 本文件,四架构域+治理+ADR+追溯 |
| `01-esp-experience-absorption-report.md` | 经验吸收报告 | A(愿景输入) | ESP 项目经验教训沉淀 |
| `02-architecture-building-blocks.md` | ABB 登记表 | C/D | 28 个架构构建块(ABB-01..28) |
| `03-solution-building-blocks.md` | SBB 登记表 | E | 31 个解决方案构建块(产品/技术+版本锁定+禁用清单) |
| `04-standards-information-base.md` | 标准信息库(SIB) | G | 编码/命名/API/安全/可观测/部署/测试/数据 8 类标准 + CI 门禁 |
| `05-architecture-requirements-specification.md` | 架构需求规格(ARS) | B/C/D | 功能/非功能/数据/应用/技术需求 + Iron Law + 追溯矩阵 |
| `06-governance-log.md` | 治理日志 | G/H | 合规审查记录 + 原则/铁律合规状态 + ADR 流水 + 技术债看板 |
| `07-architecture-contract.md` | 架构契约 | G | 架构委员会 vs 各 SP 合规条款 + 违约处理 + 变更管理 + 签署 |
| `08-transition-architecture.md` | 过渡架构 | F | Baseline(M1)→TA-1(M2)→TA-2(M3)→Target + 风险矩阵 |

---

## 8. 机会与解决方案

> Phase E：将业务需求映射到具体技术解决方案(子项目)。

### 8.1 子项目分解(SP-01..SP-10)

| ID | 子项目 | 依赖 | 阶段 | TOGAF域 |
|----|--------|------|------|---------|
| SP-01 | 技术中台(网关/路由/读写分离/Disruptor/缓存/EDA/可观测) | 本文档 | M0-M1 | Technology |
| SP-02 | 数据中台(Flink/Iceberg/CDC/Feature Store) | SP-01 | M1-M2 | Data |
| SP-03 | 业务中台(UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK) | SP-01 | M1-M2 | Application |
| SP-04 | AI中台(网关/RAG/Agents/数字人) | SP-02 | M2 | Application |
| SP-05 | IT后端MVP(三端+直播/在线编程/作业/评分) | SP-01/03/04 | M1-M2 | Business |
| SP-06 | 多端前端框架(三端共API骨架) | SP-01契约 | M0-M1 | Application |
| SP-07 | 单元化+多区域部署(CellRouter/跨区Raft/geo-rep) | SP-01 | M2-M3 | Technology |
| SP-08 | 全链路测试体系(单/集/契约/压/混沌/安全) | 各SP | M1-M3 | All |
| SP-09 | 扩容到天花板(10-20单元/100万并发/30亿) | SP-05/07 | M3 | Technology |
| SP-10 | 横向复制新事业群(IT前端/考公/考研/养生) | SP-05验证 | M3+ | Business |

### 8.2 解决方案选型矩阵

| 问题 | 候选方案 | 决策 | 理由 | ADR |
|------|---------|------|------|-----|
| 30亿用户分片 | A全单元化 / B纯分表 / C混合单元化 | C | 纯A过度设计全局数据,纯B在100万并发崩,C是务实落地 | D-012 |
| 直播大规模 | 纯WebRTC / 纯HLS / 混合 | 混合 | WebRTC大规模成本爆炸,被动观众必须CDN | D-021 |
| 在线编程沙箱 | Docker / gVisor / Firecracker | Firecracker(Judge)+WebContainer(IDE) | Docker不够安全,Firecracker唯一GPU穿透 | D-022 |
| 代码查重 | 纯pairwise / MinHash+LSH / 向量 | MinHash+LSH+桶内pairwise | pairwise O(n²)百万不可行 | D-024 |
| LLM评分 | 纯自动 / 纯人工 / human-in-the-loop | human-in-the-loop | LLM未达人类一致性 | D-020 |
| 数据湖 | Hudi / Iceberg / Delta | Iceberg | Flink+Iceberg checkpoint即提交,成熟SinkV2 | D-013 |
| AI网关 | 自建 / LiteLLM / Kong AI | LiteLLM起步→Kong AI | Kong基准>800% LiteLLM RPS | D-018 |
| 多租户RAG | DB-per-tenant / namespace+FGAC | namespace+FGAC | DB-per-tenant >10k不可行 | D-019 |
| 全球部署数据库 | A仅TiDB / B仅CockroachDB / C TiDB国内+CockroachDB海外PoC | C | TiDB已验证(ESP)+HTAP+CDC链路完整; CockroachDB原生多区域SQL+PG兼容+数据驻留适合海外PIPL; PoC不阻塞MVP | ADR-T12 |

---

## 9. 迁移规划

> Phase F：定义从当前态到目标态的迁移路线图。

### 9.1 里程碑路线图

```
M0(底座,1-2月)
├─ SP-01 技术中台核心(网关/路由/读写分离/缓存/EDA骨架) [3/7 SDK已实现: ep-rw-java, ep-disruptor, ep-cache]
├─ SP-06 三端前端骨架
└─ 基础设施复用对接(TiDB/Redis/Pulsar(CDC+事件统一)/可观测)
    验收: 中台SDK可用,前端骨架可调API

M1(IT后端 MVP 端到端,2-3月)  ← 第一个可演示里程碑
├─ SP-05 IT后端 MVP(三端+直播/在线编程/作业/评分)
├─ SP-02 数据中台 v1(实时指标+CDC入湖)
├─ SP-03 业务中台 v1(UCM/OPM/CMP/NTF)
├─ SP-04 AI 中台 v1(网关+RAG+批改Agent)
├─ SP-08 测试体系 v1(单/集/契约/1万CCU压测)
└─ 验收: 1万CCU达标,整套架构跑通,三端核心流程可演示

M2(多BU+多区域,3-4月)
├─ SP-07 单元化+多区域(2-3单元跨区Raft+geo-rep)
├─ SP-02/03/04 中台 v2(Feature Store/风控/数字人)
└─ 扩 1-2 个新事业群试点
    验收: 多区域多活,跨单元故障切换<30s

M3(冲天花板,4月+)
├─ SP-09 扩到 10-20单元/100万并发/30亿容量
├─ SP-10 横向复制剩余事业群(考公/考研/养生)
└─ 全量 SLO 达标(G-01..G-10)
    验收: 100万CCU压测达标,全事业群上线
```

### 9.2 依赖与关键路径

```
SP-01 ──────► SP-03 ──────► SP-05
  │     └────► SP-02 ─────► SP-04 ──► SP-05
  │                                   │
  └────► SP-06                        │
  └────► SP-07 ──────────────────────► SP-09
                                       │
                       SP-05 验证 ────► SP-10
                       
  SP-08 贯穿所有阶段(测试体系)
```

**关键路径**：SP-01 → SP-02 → SP-04 → SP-05 → SP-09(M3 天花板)

---

## 10. 实施治理

> Phase G：确保实施符合架构规范。

### 10.1 CI/CD 架构合规门

```
代码提交 → Pre-commit(lint/format)
    │
    ▼
CI Pipeline:
    ├─ 编译构建
    ├─ 单元测试(覆盖率≥80%,安全核心100%)  ← P-05 硬门
    ├─ 契约测试(Pact)                      ← P-06 硬门
    ├─ SAST 安全扫描(Semgrep/CodeQL)
    ├─ 容器镜像扫描(Trivy)
    ├─ 架构合规检查(依赖方向/包规范/探针配置) ← P-07 硬门
    └─ 部署到 Staging
         │
         ▼
    集成测试 + 契约验证
         │
         ▼
    手动审批(架构委员会)
         │
         ▼
    蓝绿/金丝雀部署到 Prod
```

### 10.2 ADR 驱动开发

- 任何架构决策必须提交 ADR(到 `decisions.jsonl` + 本文档 §12)
- ADR 格式：背景 → 决策 → 理由 → 依据 → 状态
- 状态机：Proposed → Accepted → Deprecated/Superseded

### 10.3 技术债看板

| ID | 技术债 | 影响 | 优先级 | 计划 |
|----|--------|------|--------|------|
| TD-01 | SP-01 Task 3 (ep-cache) 独立 review 未完成 | 代码质量未独立验证 | P1 | M0 补 review |
| TD-02 | SP-01 Task 4-7 (ep-api/bff/gateway/integration) 暂停 | 中台不完整 | P0 | M0 恢复 |
| TD-03 | SP-02/03/04 spec 未产出 | 数据/业务/AI中台无设计基线 | P0 | M0 并行产出 |
| TD-04 | K8s 集群离线(Rancher Desktop) | 无法部署验证 | P1 | 实现前恢复 |
| TD-05 | 会话状态未同步 Redis(本地兜底) | 跨会话恢复受限 | P2 | 集群恢复后同步 |

---

## 11. 变更管理

> Phase H：管理架构变更。

### 11.1 变更流程

```
变更提议 → ADR 提交(Proposed)
    │
    ▼
架构委员会评审(双周会 or 紧急临时会)
    │
    ├─ 接受 → Accepted → 更新本文档 + 通知下游子项目
    ├─ 拒绝 → Rejected → 归档
    └─ 延期 → 需更多信息 → 补充后重审
```

### 11.2 紧急变更

- 生产故障驱动的紧急变更：SRE 可临时决策，48h 内补 ADR
- 安全漏洞驱动的紧急变更：安全合规队可临时决策，24h 内补 ADR

---

## 12. ADR 全集索引

> 所有 ADR 同步存于 `~/.zcode/sessions/education-platform/decisions.jsonl`

### 12.1 架构级决策(D-xxx)

| ID | 决策 | 状态 | TOGAF域 | 详见 |
|----|------|------|---------|------|
| D-001 | 项目群而非单项目 | Accepted | All | EP-SPEC-001 §0 |
| D-002 | 本文档=顶层+4中台SAD级 | Accepted | All | EP-SPEC-001 §0 |
| D-003 | 首个MVP=IT后端 | Accepted | Business | EP-SPEC-001 §0 |
| D-004 | 国内多活+海外独立隔离 | Accepted | Technology | EP-SPEC-001 §0 |
| D-005 | 30亿/100万=天花板分阶段 | Accepted | All | EP-SPEC-001 §0 |
| D-006 | 会话状态外化Redis+80%压缩 | Accepted | Technology | 全局铁律 |
| D-008 | 单元化(Shard-by-UserID)+逻辑分表 | Accepted | Data/Technology | EP-SPEC-001 §0 |
| D-009 | Java主+Go/Python/Rust补充 | Accepted | Application | EP-SPEC-001 §0 |
| D-010 | AI中台=全栈 | Accepted | Application | EP-SPEC-001 §0 |
| D-011 | 数据中台=Flink核心实时 | Accepted | Data | EP-SPEC-001 §0 |
| D-012 | 根架构=混合单元化 | Accepted | All | EP-SPEC-001 §0 |
| D-013 | Flink+Iceberg湖仓一体 | Accepted | Data | EP-SPEC-001 §0 |
| D-014 | 单元化流量纠正模式 | Accepted | Technology | EP-SPEC-001 §0 |
| D-015 | TiDB手动user_id分表 | Accepted | Data | EP-SPEC-001 §0 |
| D-016 | CDC=TiCDC→Pulsar→Flink→Iceberg(统一消息总线,不引入Kafka) | Accepted | Data | EP-SPEC-001 §4 |
| D-017 | Flink→Iceberg checkpoint即提交 | Accepted | Data | EP-SPEC-001 §0 |
| D-018 | AI网关分层+成本栈 | Accepted | Application | EP-SPEC-001 §0 |
| D-019 | RAG多租户namespace+FGAC | Accepted | Application/Data | EP-SPEC-001 §0 |
| D-020 | IT代码批改human-in-the-loop | Accepted | Application | EP-SPEC-001 §0 |
| D-021 | 直播两层混合 | Accepted | Application | EP-SPEC-001 §0 |
| D-022 | 沙箱按信任分级 | Accepted | Application | EP-SPEC-001 §0 |
| D-023 | 作业Canvas assignment-centric | Accepted | Application | EP-SPEC-001 §0 |
| D-024 | 代码查重两层MinHash+LSH | Accepted | Application | EP-SPEC-001 §0 |
| D-025 | 评分分层沙箱pipeline | Accepted | Application | EP-SPEC-001 §0 |
| D-026 | IT后端4子平台SAD级 | Accepted | Application | EP-SPEC-001 §0 |
| D-027 | 全球部署数据库: TiDB国内主选+CockroachDB海外PoC | Accepted/Proposed | Technology | sp01-sad §3.12 (ADR-T12) |
| D-039 | 虚拟线程铁律: SB4服务 spring.threads.virtual.enabled=true, synchronized→ReentrantLock | Accepted | Technology/Application | 本文档 §14 |
| D-040 | GraalVM Native Image铁律: AOT+native-maven-plugin 0.10.4, JIT/Native双模式 | Accepted | Technology | 本文档 §14 |
| D-041 | 多区域多活数据同步: TiDB Follower Read+Pulsar Geo-Rep+Redis CRDT+Karmada, 4 cell(hz/sz/bj/sg) | Accepted | Technology/Data | 本文档 §15 |
| D-042 | AI智能驱动数据中台(SP-02升级, ADR-D05~D08) | Accepted | Data/Application | 本文档 §16 |

### 12.2 中台级 ADR

| ID | 决策 | 中台 | 详见 |
|----|------|------|------|
| ADR-T01 | CellRouter流量纠正 | 技术中台 | EP-SPEC-001 §3 |
| ADR-T02 | 读写分离4模式 | 技术中台 | EP-SPEC-001 §3 |
| ADR-T03 | Disruptor ingest + Pulsar 事件 | 技术中台 | EP-SPEC-001 §3 |
| ADR-T04 | 多级缓存跨语言对齐 | 技术中台 | EP-SPEC-001 §3 |
| ADR-T05 | 探针三分离+HPA稳定窗 | 技术中台 | EP-SPEC-001 §3 |
| ADR-T06 | 不用Seata,幂等键+乐观锁+Pulsar at-least-once | 技术中台 | sp01-sad §3.6 (ESP Seata SCA冲突) |
| ADR-T07 | BFF用Netty edge+Disruptor+WebMVC,禁SCG(D-032) | 技术中台 | sp01-sad §3.7 |
| ADR-T08 | Sentinel规则走Nacos分布式,禁本地内存 | 技术中台 | sp01-sad §3.8 (ESP踩坑) |
| ADR-T09 | Dubbo Triple禁STRICT序列化,统一Protobuf契约 | 技术中台 | sp01-sad §3.9 (ESP Dubbo STRICT跨语言踩坑) |
| ADR-T10 | 大表强制RANGE分区(防session_turns 82M OOM) | 技术中台 | sp01-sad §3.10 (ESP session_turns 16次OOM) |
| ADR-T11 | Pulsar 3.3.x锁定,4.x升级延迟至v2.0 | 技术中台 | sp01-sad §3.11 (ESP TODO升级评估) |
| ADR-T12 | 全球部署数据库选型: TiDB国内主选, CockroachDB海外PoC(SP-07) | 技术中台 | sp01-sad §3.12 (用户指令新增) |
| ADR-D01 | CDC=TiCDC→Pulsar→Flink→Iceberg(非直连,统一消息总线) | 数据中台 | sp02-sad §5 (替代原Kafka方案) |
| ADR-D02 | Flink→Iceberg checkpoint即提交 | 数据中台 | EP-SPEC-001 §4 |
| ADR-D03 | Feature Store在线/离线一致 | 数据中台 | EP-SPEC-001 §4 |
| ADR-D04 | 冷热分层强制 | 数据中台 | EP-SPEC-001 §4 |
| ADR-B01 | 中台能力=共享领域服务 | 业务中台 | EP-SPEC-001 §5 |
| ADR-B02 | 订单强单元化+Rust热点 | 业务中台 | EP-SPEC-001 §5 |
| ADR-A01 | 模型网关分层+成本栈 | AI中台 | EP-SPEC-001 §6 |
| ADR-A02 | RAG多租户隔离 | AI中台 | EP-SPEC-001 §6 |
| ADR-A03 | IT代码批改human-in-the-loop | AI中台 | EP-SPEC-001 §6 |
| ADR-A04 | LangGraph多步Agent | AI中台 | EP-SPEC-001 §6 |
| ADR-I01 | 直播两层混合+房间单cell | IT后端 | EP-SPEC-001 §7 |
| ADR-I02 | 沙箱按信任分级 | IT后端 | EP-SPEC-001 §7 |
| ADR-I03 | assignment-centric+两层查重 | IT后端 | EP-SPEC-001 §7 |
| ADR-I04 | 分层沙箱评分pipeline | IT后端 | EP-SPEC-001 §7 |
| ADR-I05 | LLM rubric评分human-in-the-loop | IT后端 | EP-SPEC-001 §7 |

---

## 13. 双向追溯矩阵

### 13.1 SLO → 架构决策追溯

| SLO | 驱动的架构决策 | 实现子项目 | 验证方式 |
|-----|--------------|-----------|---------|
| G-01(100万CCU) | D-012混合单元化/D-014流量纠正 | SP-01/07/09 | Locust 100万压测 |
| G-02(30亿用户) | D-008单元化+D-015分表/D-013 Iceberg | SP-07/09 | TiDB容量规划+压测反推 |
| G-03/04(API P95/P99) | ADR-T02读写分离/ADR-T04缓存 | SP-01 | Prom histogram |
| G-05(直播延迟) | D-021两层混合 | SP-05 | WS端到端压测 |
| G-06(50万ev/s) | ADR-T03 Disruptor+Pulsar | SP-01 | Disruptor基准 |
| G-07(99.99%) | D-014跨区Raft/ADR-T05探针 | SP-07 | 故障注入验证 |
| G-08(故障<30s) | D-014 AppActive模式 | SP-07 | 单元切换演练 |
| G-09(入湖<5min) | D-016/17 TiCDC→Pulsar→Flink→Iceberg | SP-02 | checkpoint监控 |
| G-10(AI P95<3s) | D-018网关+缓存+路由 | SP-04 | AI网关trace |

### 13.2 业务能力 → 子项目追溯

| 业务能力 | 中台/BU提供 | 子项目 | TOGAF域 |
|---------|-----------|--------|---------|
| 注册登录 | UCM中台 | SP-03 | Business/Application |
| 选课购课 | CMP+OPM中台 | SP-03 | Business/Application |
| 视频学习 | 学习记录(单元化) | SP-05 | Business/Application |
| 代码作业+评测 | 作业+评分平台 | SP-05 | Business/Application |
| 在线编程IDE | IDE子平台 | SP-05 | Business/Application |
| 直播+互动 | 直播子平台 | SP-05 | Business/Application |
| AI答疑 | AI中台Agent | SP-04 | Application |
| 学习路径 | AI中台Agent | SP-04 | Application |
| 数据看板 | 数据中台Flink | SP-02 | Data |
| 风控 | 业务中台RSK | SP-03 | Application |

### 13.3 文档体系索引

| 文档类型 | 顶层 | SP-01技术中台 | SP-02数据中台 | SP-03业务中台 | SP-04 AI中台 | SP-05 IT后端 |
|---------|------|-------------|-------------|-------------|------------|------------|
| 需求分析(SRS) | 本文档§1-3 | docs/requirements/sp01-srs.md | docs/requirements/sp02-srs.md | docs/requirements/sp03-srs.md | docs/requirements/sp04-srs.md | docs/requirements/sp05-srs.md |
| 架构设计(SAD) | 本文档§4-6 | docs/architecture/sp01-sad.md | docs/architecture/sp02-sad.md | docs/architecture/sp03-sad.md | docs/architecture/sp04-sad.md | docs/architecture/sp05-sad.md |
| 详细设计(SDD) | — | docs/design/sp01-sdd.md | docs/design/sp02-sdd.md | docs/design/sp03-sdd.md | docs/design/sp04-sdd.md | docs/design/sp05-sdd.md |
| 原型设计 | — | docs/prototype/tech-platform/ | docs/prototype/data-platform/ | docs/prototype/business-platform/ | docs/prototype/ai-platform/ | docs/prototype/it-backend/ |
| 测试 | §10 | docs/testing/sp01-test.md | docs/testing/sp02-test.md | docs/testing/sp03-test.md | docs/testing/sp04-test.md | docs/testing/sp05-test.md |
| 部署 | §6 | docs/deployment/sp01-deploy.md | docs/deployment/sp02-deploy.md | docs/deployment/sp03-deploy.md | docs/deployment/sp04-deploy.md | docs/deployment/sp05-deploy.md |

---

**文档结束**

> 本文档是 education-platform 项目群的架构基线。所有下游子项目文档必须以本文档为架构锚点，通过 §13 追溯矩阵保持双向可追溯。任何架构变更通过 §11 流程管理，ADR 登记到 §12。

---

## 14. 技术架构增强 (D-039/D-040)

> 本章节是对 §6 技术架构的增强补充，由 ADR D-039(虚拟线程铁律)与 D-040(GraalVM Native Image铁律)驱动。仅追加，不修改 §6 已有内容。

### 14.1 虚拟线程并发模型 (ADR-D039)

**铁律**：所有 Spring Boot 4.0 服务强制 `spring.threads.virtual.enabled=true`；涉及对象锁的共享可变状态由 `synchronized` 块迁移至 `java.util.concurrent.locks.ReentrantLock`，避免虚拟线程 pinning 导致载体线程阻塞。

**Platform Thread vs Virtual Thread 并发模型对比**：

```
传统 Platform Thread 模型 (1 请求 1 OS 线程)
┌──────────────────────────────────────────────────────────┐
│  请求池 (200 threads, Tomcat worker)                      │
│  ┌─────┬─────┬─────┬─────┬─────────────────┬─────┐       │
│  │ T1  │ T2  │ T3  │ ... │ T200            │     │       │
│  │ ▲   │ ▲   │ ▲   │     │ ▲               │     │       │
│  └─┼───┴─┼───┴─┼───┴─────┴─┼───────────────┴─────┘       │
│    │     │     │           │                              │
│    └─────┴─────┴───────────┴── 阻塞在 IO/DB/JWT 验签       │
│  每线程 ~1MB 栈, 200 并发即 200MB 栈空间                    │
│  阻塞=OS 线程空闲, 上限 ~数千                               │
└──────────────────────────────────────────────────────────┘

虚拟线程模型 (JDK 21+ / Spring Boot 4, D-039)
┌──────────────────────────────────────────────────────────┐
│  载体线程池 (Carrier Pool, ~CPU 核数)                      │
│  ┌────┬────┬────┬─────────────────┐                       │
│  │ C1 │ C2 │ C3 │ ... (ForkJoinPool)│                      │
│  └─┬──┴─┬──┴─┬──┴─────────────────┘                       │
│    │    │    │                                            │
│    ▼    ▼    ▼   unmount → mount (IO 阻塞时自动切换)        │
│  ┌──────────────────────────────────────────────┐         │
│  │  虚拟线程 (百万级, 栈在堆上, ~KB 级)            │         │
│  │  VT1  VT2  VT3 ... VT1_000_000                │         │
│  │  │    │    │         │                        │         │
│  │  └────┴────┴─────────┴── IO ready 后 resume    │         │
│  └──────────────────────────────────────────────┘         │
│  1M 并发只需 ~MB 级堆内存, 阻塞=unmount 不占载体线程         │
└──────────────────────────────────────────────────────────┘

Pinning 风险与对策 (D-039 铁律):
  synchronized 块内阻塞  →  VT pin 到载体线程, 阻塞其他 VT
  对策: ReentrantLock.tryLock()/lock() 替代 synchronized
        - 显式 lock/unlock (try-finally)
        - 支持可中断、超时、公平模式
        - 配合 VT 不 pin, 载体线程可继续调度其他 VT
  检测: -Djdk.tracePinnedThreads=full 启动参数 + CI 门禁扫描
```

**配置基线 (SB4 服务)**：

```yaml
# application.yml (所有 SB4 服务强制)
spring:
  threads:
    virtual:
      enabled: true   # D-039 铁律
```

**代码规范**：

| 场景 | 旧写法 (禁用) | 新写法 (D-039) |
|------|--------------|---------------|
| 共享缓存更新 | `synchronized(map){ map.put(k,v); }` | `lock.lock(); try { map.put(k,v); } finally { lock.unlock(); }` |
| 资源互斥 | `synchronized(this)` | `private final ReentrantLock lock = new ReentrantLock();` |
| 阻塞队列消费 | `synchronized` wait/notify | `ReentrantLock` + `Condition` |

### 14.2 GraalVM Native Image 双模式部署 (ADR-D040)

**铁律**：所有 SB4 服务采用 JIT/Native 双模式构建——JIT 模式用于快速迭代与压测调优，Native Image 模式用于生产部署以追求启动速度与内存占用。统一通过 `native-maven-plugin 0.10.4` 构建原生镜像。

**双模式构建与部署流程**：

```
                       源码 (SB4 + Spring AOT)
                              │
                ┌─────────────┴─────────────┐
                │                            │
        JIT 模式 (开发/压测)         Native 模式 (生产)
                │                            │
        mvn spring-boot:run          mvn -Pnative package
                │                            │
        JVM (HotSpot, JIT)           GraalVM native-image
        启动 ~3-8s                    启动 ~50-100ms
        峰值吞吐高 (C2 JIT)            内存 ~1/5
        反射/动态代理无限制            需 AOT hint (reachability)
                │                            │
                └─────────────┬─────────────┘
                              │
                    同一容器镜像仓库
                    (双 tag: jit / native)
                              │
                    K8s 按场景调度:
                    - 突发扩容 pod → native (秒级启动)
                    - 长稳态 pod → jit (峰值吞吐)
```

**Native Image 构建约束**：

| 维度 | 约束 | 对策 |
|------|------|------|
| 反射 | 默认不可达 | `@RegisterReflectionForBinding` + `reflect-config.json` |
| 动态代理 | 需 AOT hint | Spring AOT 自动生成 + `proxy-config.json` |
| 资源文件 | 需显式声明 | `resource-config.json` (application.yml/静态资源) |
| 类初始化 | 构建期 vs 运行期 | `--initialize-at-build-time` 调优 |
| 第三方库兼容 | 需 Native 兼容性 | SB4 Starter 自带 GraalVM metadata; Dubbo/Nacos 需验证 |

**Maven 插件配置基线**：

```xml
<plugin>
  <groupId>org.graalvm.buildtools</groupId>
  <artifactId>native-maven-plugin</artifactId>
  <version>0.10.4</version>   <!-- D-040 铁律 pin -->
  <configuration>
    <buildArgs>
      <buildArg>-H:IncludeResources=.*</buildArg>
      <buildArg>-march=compatibility</buildArg>
      <buildArg>--initialize-at-build-time=org.springframework</buildArg>
    </buildArgs>
  </configuration>
</plugin>
```

### 14.3 与 §6 的映射关系

| §6 条目 | 增强内容 | ADR |
|---------|---------|-----|
| §6.1 基础设施栈 (Java 框架) | SB4 启用虚拟线程 + GraalVM 双模式 | D-039/D-040 |
| §6.6 K8s 部署规范 | 突发扩容优先调度 native 镜像 pod | D-040 |
| §6.4 可观测 | `-Djdk.tracePinnedThreads=full` 加入 startupProbe 前 init 探测 | D-039 |

---

## 15. 多区域多活部署架构 (D-041)

> 本章节是对 §6.2 部署拓扑的多区域扩展，由 ADR D-041 驱动。从原"国内三角 + 海外独立"升级为 **4-cell 多活拓扑**，统一通过 Karmada 多集群调度。仅追加，不修改 §6.2 已有内容。

### 15.1 4-Cell 多活拓扑 (杭州/深圳/北京/新加坡)

```
                        ┌─────────────────────────────────┐
                        │     Karmada 控制平面 (中心调度)    │
                        │  PropagationPolicy / OverridePolicy│
                        │  按权重/地域/健康度调度工作负载       │
                        └────────────────┬────────────────┘
                                         │
        ┌──────────────────┬─────────────┼─────────────────┬──────────────────┐
        │                  │             │                 │                  │
   ┌────▼─────┐      ┌─────▼────┐   ┌────▼─────┐     ┌─────▼────┐      ┌─────▼─────┐
   │ Cell-hz  │      │ Cell-sz  │   │ Cell-bj  │     │ Cell-sg  │      │ (海外只读 │
   │ 杭州     │      │ 深圳     │   │ 北京     │     │ 新加坡   │      │  法兰克福)│
   │ 国内华东 │      │ 国内华南 │   │ 国内华北 │     │ 海外亚太 │      │  备份单元 │
   └────┬─────┘      └─────┬────┘   └────┬─────┘     └─────┬────┘      └───────────┘
        │                  │             │                 │
   ┌────▼──────────────────▼─────────────▼─────────────────▼────┐
   │                  数据同步层 (跨 cell)                         │
   │                                                              │
   │  TiDB Follower Read ── 跨区只读副本, 就近读                   │
   │  Pulsar Geo-Replication ── 事件总线跨区双向复制               │
   │  Redis CRDT (Active-Active) ── 缓存/会话最终一致              │
   └──────────────────────────────────────────────────────────────┘

   权重调度 (Karmada, 默认):
     hz : sz : bj : sg = 40 : 30 : 20 : 10   (国内为主, 海外增量)
   故障切换:
     cell-hz 宕机 → 权重重算: sz:bj = 50:50 (国内)
     cell-sg 宕机 → 海外切法兰克福备份单元

   PIPL 合规边界:
     cell-sg/法兰克福 数据不回流国内 (D-004 不变)
     国内 3 cell 间可双向同步; 海外仅单向出 (白名单课程目录)
```

### 15.2 Karmada 多集群调度策略

| 策略对象 | 类型 | 说明 |
|---------|------|------|
| PropagationPolicy | 全局 | 工作负载按权重分发到 4 cell, 副本数由 Karmada 按 `ReplicaSchedulingType=Divided` 自动切分 |
| OverridePolicy | 区域级 | 按 cell 注入 region-aware 配置 (DB 连接串/Redis endpoint/Pulsar tenant) |
| 健康检查重调度 | 故障 | pod 失联阈值触发 `ApplicationFailover` 自动迁移副本到健康 cell |
| 跨 cell 亲和 | 调度 | 用户域服务 sticky 到 hash(user_id)%4 对应 cell (与 §6.3 单元化对齐) |

### 15.3 跨区域数据同步策略

```
                ┌─────────── 跨 Cell 数据同步流向 ───────────┐
                │                                            │
   TiDB 层:     │   Cell-hz Primary  ──Follower Read──► Cell-sz/bj 只读
                │                    ──Follower Read──► Cell-sg (海外, 仅白名单表)
                │   写: 就近写 Primary; 跨区 Raft (Placement Rules) 保证强一致
                │
   Pulsar 层:   │   ns-events-hz  ──Geo-Rep──►  ns-events-sz
                │                  ──Geo-Rep──►  ns-events-bj
                │                  ──Geo-Rep──►  ns-events-sg (单向, 受控 topic)
                │   跨 cell 事件最终一致, 用于跨服务解耦 (§5.3 异步事件模式)
                │
   Redis 层:    │   Redis CRDT (Active-Active)
                │   key = {bu}:{svc}:{entity}:{id}
                │   各 cell 本地写 → CRDT 合并 → 最终一致
                │   会话/路由表/热缓存 多活可读可写
                │
   Iceberg 层:  │   各 cell CDC → 本地 Pulsar → 本地 Flink → 本地 Iceberg
                │   跨区 Iceberg 通过 S3 跨区复制 (异步, T+1 近线)
                │
                └────────────────────────────────────────────┘

   冲突与一致性矩阵:
   ┌─────────────┬───────────────┬──────────────┬──────────────┐
   │ 数据类型     │ 一致性级别      │ 冲突策略      │ 同步延迟      │
   ├─────────────┼───────────────┼──────────────┼──────────────┤
   │ TiDB 强一致  │ Strong (Raft)  │ 主从无冲突    │ <1s (同区)    │
   │ TiDB 跨区读  │ Read-Your-Writes│ Follower Read│ <1s          │
   │ Pulsar 事件  │ Eventual       │ topic 全序   │ 秒级          │
   │ Redis CRDT   │ Eventual       │ CRDT 合并    │ <1s          │
   │ Iceberg      │ Eventual (T+1) │ 分区不冲突    │ 小时级        │
   └─────────────┴───────────────┴──────────────┴──────────────┘
```

### 15.4 与 §6.2/§6.3 的关系

- §6.2 原"国内三角 + 海外独立"拓扑升级为 §15.1 的 4-cell 显式拓扑，海外由单点新加坡扩展为新加坡 + 法兰克福备份。
- §6.3 单元化机制 (CellRouter/路由表/misroute 纠正) 不变，但 cell 数量由 2-3 扩展为 4，路由表 `hash(user_id)%4`。
- Karmada 接管原 §6.3 中 "跨区 Raft / 就近写" 的调度职责，与 CellRouter 协同：Karmada 管工作负载分发，CellRouter 管请求路由。

---

## 16. AI智能驱动架构增强 (D-042)

> 本章节是对 §4 数据架构 + §5 应用架构的 AI 能力增强，由 ADR D-042 驱动，对应 SP-02 数据中台升级与 ADR-D05~D08。仅追加概述，不修改 §4/§5 已有内容。

### 16.1 SP-02 数据中台 AI 能力扩展概述

SP-02 数据中台在原"Flink + Iceberg + CDC + Feature Store"基线上，升级为 **AI 智能驱动数据中台**，引入数据资产智能化治理、特征自动发现、数据质量 AI 预测等能力。

### 16.2 新增 ADR (D05~D08)

| ADR | 决策 | 域归属 | 概述 |
|-----|------|--------|------|
| ADR-D05 | 数据资产自动标签与血缘推断 (LLM 驱动) | Data | 用 LLM 对 Iceberg 表/字段自动打标签 + 血缘关系推断，替代人工编目 |
| ADR-D06 | 特征自动发现与在线/离线一致性校验增强 | Data | 基于业务日志 + Flink 指标，自动候选特征；与 ADR-D03 Feature Store 对齐做一致性校验 |
| ADR-D07 | 数据质量 AI 预测与异常根因定位 | Data | 时序模型预测数据量/延迟异常，结合 lineage 自动定位根因表 |
| ADR-D08 | AI 驱动的数据生命周期管理 | Data | 基于访问热度预测自动执行 §4.4 冷热分层迁移 (热→温→冷→归档) |

### 16.3 AI 能力与现有架构的协同

```
   SP-02 数据中台 (AI 智能驱动, D-042)
   ┌─────────────────────────────────────────────────────┐
   │                                                      │
   │  原 SP-02 核心 (不变)                                 │
   │  TiCDC → Pulsar(ns=cdc://) → Flink → Iceberg          │
   │  Flink 实时指标 / Feature Store / Spark 离线           │
   │                                                      │
   │  + AI 增强层 (D05~D08, 新增)                          │
   │    ├─ LLM 数据资产标签 + 血缘 (ADR-D05)               │
   │    ├─ 特征自动发现 (ADR-D06)                          │
   │    ├─ 数据质量预测 + 根因定位 (ADR-D07)                │
   │    └─ 生命周期 AI 调度 (ADR-D08) → 联动 §4.4 冷热分层  │
   │                                                      │
   │  AI 中台 (SP-04) 提供 LLM/Agent 能力支撑              │
   └─────────────────────────────────────────────────────┘

   交互: AI 增强层只读消费 SP-02 数据 + 调 SP-04 AI 网关;
        不改变原 CDC/Flink/Iceberg 主链路 (§4.3 不变)
```

### 16.4 与 §4/§5 的关系

- §4.3 数据流转架构主链路 (TiCDC→Pulsar→Flink→Iceberg) 不变，AI 增强层为旁路只读消费。
- §4.4 冷热分层策略不变，但触发逻辑由人工/规则升级为 ADR-D08 的 AI 热度预测自动触发。
- §5 应用架构中 SP-02 与 SP-04 的调用关系新增：SP-02 AI 增强层调用 SP-04 AI 网关 (LLM/Agent)，反向不依赖。

---

**文档结束 (增强版 v1.1, ADR D-039~D-042)**

---

## §18 智能体架构 (D-046)

> 2026-08-05 追加。从 TOGAF Application Architecture 视角描述 SP-04 AI 中台智能体层重构 (D-046)。

### 18.1 概述

D-046 将 SP-04 AI 中台智能体层重构为 7 个解耦子系统，统一由 LangGraph 1.0 编排，对应 7 条 ADR（A07~A13）。本节聚焦应用组件视角与业务能力映射。

### 18.2 应用组件视图

| 组件 | 职责 | 对应 ADR |
|------|------|---------|
| ai_mcp | 外部工具/资源/Prompt 统一接入（MCP, JSON-RPC 2.0, stdio+HTTP） | ADR-A07 |
| ai_a2a | 跨 Agent 互操作协议（AgentCard, Task 生命周期, SSE） | ADR-A08 |
| ai_agent/graphs | 分层多智能体图（Supervisor + 6 子图, RedisSaver） | ADR-A09 |
| ai_agent/reflexion | 自反思循环（evaluate→reflect→retry, episodic memory） | ADR-A10 |
| ai_harness | 执行护栏（timeout/depth/budget/sandbox/audit） | ADR-A11 |
| ai_hermes | 事件驱动编排（EventBus, Pulsar+InMemory 降级, EventRouter） | ADR-A12 |
| ai_plugin | 插件系统（Manifest, Permission, Sandbox, Discovery） | ADR-A13 |

### 18.3 业务能力映射

| 业务能力 | 主导组件 | 协同组件 |
|---------|---------|---------|
| 答疑问答 | ai_agent/graphs (qa) | ai_mcp, ai_harness, ai_hermes |
| 作业批改 | ai_agent/graphs (grading) | ai_harness, ai_hermes, ai_a2a (HITL) |
| 自动出题 | ai_agent/graphs (quiz) | ai_mcp, ai_harness |
| 学习路径 | ai_agent/graphs (learnpath) | ai_hermes, ai_plugin |
| 数字人 | ai_agent/graphs (digital_human) | ai_mcp (TTS/render 工具) |
| 辩论 | ai_agent/graphs (debate) | ai_harness |
| 跨 Agent 协作 | ai_a2a | ai_hermes, ai_agent/graphs |
| 外部能力扩展 | ai_plugin, ai_mcp | ai_harness (沙箱) |

### 18.4 关键架构约束

- **多租户隔离**：所有 Redis 键含 `{tenant}` 段；Plugin tenant_whitelist；TokenBudget 按 tenant×agent×model 隔离。
- **降级容错**：Redis→YAML（注册表）/ MemorySaver（checkpointer）；Pulsar→InMemoryBus（Hermes）；Iceberg→本地 JSONL（审计）。
- **HITL**：Grading/Quiz/LearnPath 通过 LangGraph `interrupt_before` 暂停，A2A Task 进入 input-required，超时自动 reject。
- **收敛保证**：Supervisor `_round_done` 防路由死循环；Reflexion max_trials=3 防反思不收敛。
- **可观测**：AuditLogger 全量审计工具调用，Redis Stream → Iceberg，端到端延迟 P95 < 5s。

### 18.5 与 §5/§16 的关系

- §5 应用架构中 SP-04 智能体层由原 LangGraph 单图升级为分层多图 + 7 子系统；网关层新增 agent/a2a/tool/plugin/memory 5 组路由。
- §16 AI 智能驱动数据中台的 SP-02 AI 增强层调用 SP-04 网关不变，但底层由重构后的 Supervisor 路由到对应子图处理。

---

## §19 MCP 协议合规架构 (D-047)

> 2026-08-05 追加。从 TOGAF Application/Technology Architecture 视角记录 SP-07 MCP Server 实现技术选型（D-047），作为 §18 智能体架构（D-046）的 Java 侧落地补充。

### 19.1 决策摘要

SP-07 MCP Server 采用 **官方 MCP Java SDK（modelcontextprotocol/java-sdk v2.0.0）+ Spring AI MCP 注解（@McpTool / @McpToolParam）** 替换 D-046 中的手写 JSON-RPC dispatcher，对应 ADR-A07（MCP 协议接入）的 Java 侧实现落地。

| 项 | 决策 |
|----|------|
| 协议实现 | 官方 MCP Java SDK v2.0.0 |
| 装配方式 | Spring AI MCP（`spring-ai-starter-mcp-server-webmvc` + `spring-ai-mcp-annotations`） |
| 工具注册 | `@McpTool` / `@McpToolParam` 声明式注解，Spring 容器扫描 |
| 传输 | Streamable HTTP（MCP 2.0 spec），端点 `/mcp/v1` |
| BOM | Spring AI 2.0.0 BOM（兼容 Spring Boot 4.0.x） |

### 19.2 应用架构影响

- 新增模块 `sp07-mcp-server`，作为 SP-07 对外暴露 MCP 工具的唯一入口。
- 删除 D-046 中手写的 `McpController` / `McpRequest` / `McpResponse` / `ToolSchema` / `ToolRegistry` 五件套。
- 工具实现收敛到 `KnowledgeTools.java`（`knowledge.search` / `submission.grade` / `quiz.generate`）。
- 调用链：外部 MCP Client → BFF（鉴权 + tenant_id 注入）→ sp07-mcp-server `/mcp/v1` → Spring AI MCP 自动分发到 `@McpTool` 方法 → Dubbo 调用 sp07-retrieval-service / sp07-agent-service。

### 19.3 技术架构影响

| 维度 | 约束 | 来源 |
|------|------|------|
| Spring Boot 版本 | 4.0.x（铁律，不可降级） | 全局基线 |
| Spring AI BOM | 2.0.0 | D-047 |
| GraalVM AOT | sp07-mcp-server 仅做兼容（不强制 native）；BFF/API 仍强制 native | D-040 |
| 传输协议 | Streamable HTTP（MCP 2.0），禁用 stdio（远程服务场景） | ADR-A07 |
| 协议版本协商 | `MCP-Protocol-Version` header，默认 2.0，向下兼容 1.0 initialize | MCP 2.0 spec |
| 取消语义 | `notifications/cancelled` 取消虚拟线程执行 | D-039 |
| 多租户 | `tenant_id` 经 `ToolContext` 透传，FGAC 单点注入 | ADR-A02 |

### 19.4 备选方案与拒绝理由

| 备选 | 结论 | 关键拒绝理由 |
|------|------|-------------|
| Spring AI Alibaba (SAA) | ❌ | 稳定版 v1.1.2.2 依赖 SB 3.5（与 SB 4.0 铁律冲突）；2.0.0-M1 预发布；无 GraalVM AOT 证据 |
| AgentScope | ❌ | Java 侧依赖 SAA StateGraph（等价直接用 SAA）；Python 侧与 LangGraph 重复；无 Pulsar 集成；v2.0 过新 |
| 手写 JSON-RPC | ❌ | 缺协议版本协商 / Streamable HTTP / SSE / cancellation；无 conformance tests |
| 官方 MCP Java SDK + Spring AI MCP | ✅ | 40/40 conformance；与 Spring AI 共维护；SB 4.0 兼容；GraalVM AOT 友好 |

### 19.5 与 §18 / §16 的关系

- §18 智能体架构（D-046）中 `ai_mcp`（Python 侧）通过 MCP/A2A 协议调用外部工具；本节补充 Java 侧（SP-07）的 MCP Server 实现选型，两侧协议契约不变（MCP 2.0 over Streamable HTTP）。
- §16 AI 智能驱动数据中台的 SP-02 AI 增强层调用 SP-04 网关不变；SP-04 网关若需调用 SP-07 知识工具，统一经 sp07-mcp-server `/mcp/v1` 端点。
- 本决策不变更 D-033 / D-039 / D-040 / D-041 / D-042 的任何既有约束。

### 19.6 治理与合规

- CI 强制执行官方 MCP conformance test 套件（40/40 通过为发布门禁）。
- CI 静态扫描禁止 `McpController` / `McpRequest` / `McpResponse` / `ToolSchema` / `ToolRegistry` 类存在。
- 升级 `spring-ai-bom` 或 `modelcontextprotocol/java-sdk` 主版本须走 D-047 变更评审。

---

## §17 多级智能记忆架构 (D-045)

> 关联：D-045 多级智能记忆系统；ADR-A05（L0~L3 四级架构）；ADR-A06（Mem0 框架集成）；D-046 智能体架构（§18）；D-048 审计 P0-5；铁律 7（D-033 缓存）、铁律 8（决策对比）。

### 17.1 TOGAF 对齐定位

ep-memory 是 SP-04 AI 中台的独立平台级共享记忆库，在 TOGAF 企业架构中跨越数据架构与应用架构两层：

| TOGAF 域 | ep-memory 对应 |
|---------|---------------|
| **业务架构** | 支撑"个性化学习"与"跨会话续聊"业务能力：学生画像延续、教师批改上下文、组织知识沉淀 |
| **数据架构** | L0/L1 Redis（热）+ L2 Milvus 3.0/TiDB/NebulaGraph（温/冷）+ L3 Milvus 3.0/NebulaGraph（持久），多级存储分层 |
| **应用架构** | MemoryManager 编排层作为 SP-04 应用组件，经 gRPC 供 Java 侧（SP-07/SP-03）调用 |
| **技术架构** | 复用 Redis Cluster（D-033）、Milvus 3.0（ADR-INF-01 v2.0）、NebulaGraph 3.x + TiDB CTE（ADR-INF-02 v2.0, 终止Neo4j D-053）、TiDB，无新增基础设施 |

### 17.2 L0~L3 在企业架构中的定位

```
┌──────────────────────────────────────────────────────────────┐
│  应用层: D-046 智能体 (Supervisor + 5 Agent)                  │
│     │ recall/remember 每轮调用                                │
│     ▼                                                        │
│  编排层: MemoryManager (自研, Python SDK + Java gRPC SDK)     │
│     │ 分级召回 L0→L1→L2→L3 / 分级写入                         │
│     ▼                                                        │
│  ┌────────┬────────┬─────────────────────┬────────────┐      │
│  │ L0 即时│ L1 工作│ L2 长期(Mem0核心)    │ L3 组织    │      │
│  │ Redis  │ Redis  │ Milvus+TiDB+Neo4j   │ Milvus+Neo4j│     │
│  │ 10轮   │ 摘要   │ 情景/语义/程序       │ 租户级共享  │      │
│  │ 24h    │ 7d     │ 30d降权/90d归档      │ 永不过期    │      │
│  └────────┴────────┴─────────────────────┴────────────┘      │
│  数据层: Redis Cluster / Milvus / TiDB / Neo4j               │
└──────────────────────────────────────────────────────────────┘
```

| 级别 | 企业架构角色 | 数据治理 |
|------|-------------|---------|
| **L0 即时记忆** | 应用层会话状态（瞬态），对应 TOGAF 应用架构的会话管理 | TTL 24h 自动淘汰，不持久化 |
| **L1 工作记忆** | 应用层任务上下文（短期），跨会话续聊的关键 | 7d 降级到 L2，摘要持久化 |
| **L2 长期记忆** | 数据层个体记忆（持久），学生画像/教师偏好/实体关系 | 30d 降权/90d 归档冷存；Mem0 pipeline 提取 |
| **L3 组织记忆** | 数据层组织知识（持久共享），租户级沉淀 | 永不自动过期；双人审核晋升/删除；显式授权访问 |

### 17.3 与其他章节的关系

- **§16 AI 智能驱动架构（D-042）**：SP-02 数据中台的智能特征平台可为 L2 语义记忆提供特征输入；ep-memory 的学生画像反哺数据中台。
- **§18 智能体架构（D-046）**：MemoryManager 作为 LangGraph 节点工具注入，是智能体具备"记忆"能力的前提；Reflexion 评估同时用于 L1 摘要与 L2 提取质量保障。
- **§19 MCP 协议合规（D-047）**：Java 侧 ep-memory-java SDK 经 gRPC 调用 Python 侧，与 MCP Server 走 Streamable HTTP 的契约驱动模式一致，符合 D-040 GraalVM AOT。
- **§5 应用架构 / §4 数据架构**：ep-memory 复用既定的 Redis/Milvus/TiDB/Neo4j 组件，不引入新基础设施，符合铁律 8（不重复造轮子）。

### 17.4 治理与合规

- L3 组织记忆晋升/删除需双人审核 + 审计落 Iceberg（D-046 AuditLogger 链路）。
- 所有记忆操作（remember/recall/forget/promote）经 AuditLogger 留痕。
- 多租户隔离：所有记忆键含 tenant_id 段；L3 跨用户共享需显式 `mem:org:read` 授权（ADR-A02 FGAC）。
- 铁律 8 合规：L2 框架选型经 Mem0/LangMem/全自研三方对比（ADR-A06）；L0~L3 架构经分级/单级/全自研三方对比（ADR-A05）。

---

**文档结束 (增强版 v1.4, ADR D-039~D-042 + D-045 + D-046 + D-047)**
