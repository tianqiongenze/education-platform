# 棋牌游戏事业群(BU-BOARDGAME)架构设计 —— 教育平台 + 对弈增强

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| BG-SAD-001 | v1.0 | 2026-08-09 | Draft(待评审) | ZCode(首席架构师) | EP-TOGAF-001 / SP10-SAD-001 / D-103 中台复用评估 / 现网源码核查 |

> **核心定位声明**: 棋牌游戏事业群首先是一个**完整的在线教育平台**(课程 + 直播 + 作业 + 学习进度), 然后在此基础上增加**在线对弈**作为棋类教育的"实战练习"环节。本文不是"从零建游戏平台", 而是"**教育平台 + 互动对弈增强**"。对弈域在 DDD 上隶属于"课程教学的实战环节", 不是独立游戏系统。

---

## 目录

1. [需求理解: 棋类教育 = 教育平台 + 对弈增强](#1-需求理解-棋类教育--教育平台--对弈增强)
2. [现有能力盘点](#2-现有能力盘点)
3. [教育与对弈的结合点分析](#3-教育与对弈的结合点分析五维度)
4. [限界上下文设计(三层)](#4-限界上下文设计三层-shared--extended--owned)
5. [复用 vs 自建矩阵](#5-复用-vs-自建矩阵)
6. [领域模型设计(聚合根 + 状态机 + 事件)](#6-领域模型设计聚合根--状态机--事件)
7. [WebSocket 消息协议](#7-websocket-消息协议)
8. [技术方案选型(Rust / Go / Java 三方对比)](#8-技术方案选型rust--go--java-三方对比)
9. [实施路线图(MVP / 增强 / 完善)](#9-实施路线图)
10. [附录 A: 与顶层架构追溯矩阵](#附录-a-与顶层架构追溯矩阵)
11. [附录 B: ADR 索引](#附录-b-adr-索引)

---

## 1. 需求理解: 棋类教育 = 教育平台 + 对弈增强

### 1.1 一句话定位

> **BU-BOARDGAME = 复用现有教育中台搭建"棋类课程平台" + 自建"对弈核心域"作为教学闭环的实战环节。**

棋类教学和普通教育的根本差异, 在于"**知识内化必须通过实战**":
- 普通课程: 学视频 → 做作业 → 评分 (被动接受, 单向闭环)
- 棋类课程: 学视频 → 做死活题 → **人机对弈练习** → **人人对弈实战** → **AI 复盘分析** → 棋力等级(ELO)跟踪 (主动对抗, 双向闭环)

因此对弈不是"附加的游戏功能", 而是**棋类教育不可分割的教学环节**, 地位等同于普通教育里的"作业+考试"。

### 1.2 三个不可妥协的设计原则

| 原则 | 含义 | 违反后果 |
|------|------|----------|
| **教育优先(Education-First)** | 课程/直播/作业/进度四个教育基座必须先打通, 对弈才能上线 | 平台沦为纯游戏站, 失去教育属性 |
| **对弈教学化(Match-as-Lesson)** | 每场对弈必须可绑定到课程/作业/学习进度, 不是孤立对局 | 对弈与教育割裂, 无法形成教学闭环 |
| **中台复用(Platform-Inherits)** | 用户/订单/支付/通知/缓存/AI 一律调中台, 只自建"棋类特有域" | 重复造轮子, 违反 ADR-BU01 |

### 1.3 与 SP-10 复制方法论的衔接

本文严格遵循 `sp10-bu-replication.md` 的 BU 复制方法论:
- §1.3 复用矩阵 → 本文 §5
- §4 DDD 边界上下文 → 本文 §4
- §5 事件驱动集成 → 本文 §6.4
- §11 BU 复制检查清单 → 本文 §9 Go-Live gate

---

## 2. 现有能力盘点

基于源码核查(非文档声称), 把现有教育平台能力按**完成度**分成三档。这是判断"哪些直接复用、哪些要补齐、哪些要新建"的事实依据。

### 2.1 能力完成度三档

```
┌──────────────────────────────────────────────────────────────────────┐
│                  BU-BOARDGAME 能力依赖 × 完成度                       │
└──────────────────────────────────────────────────────────────────────┘

  已完成(可直接复用)        前端在但后端缺失(需补齐)      完全缺失(需新建)
  ─────────────────        ─────────────────────         ─────────────────
  用户体系 (sp03-user)      直播后端 (live-go)            对弈核心域
  课程 CRUD/发布 (sp03)     章节课时实体 (Chapter/Lesson) GameRoom/GameMatch
  作业→提交→评分 (sp05)     报名/学习进度 (Enrollment/    Move/RuleEngine
  JWT/多租户/Cell路由        LearningRecord)              Matchmaking
  BFF/网关/路由表           WebSocket 业务接线            GameReplay/复盘
  缓存/记忆/AI 中台         (gateway-go 仅 /healthz)      AI 复盘 Agent
```

### 2.2 详细盘点表

| # | 能力域 | 后端实现 | 前端契约 | 完成度 | 关键证据(源码路径) |
|---|--------|---------|----------|--------|-------------------|
| 1 | 用户注册/登录/会话 | sp03 `user/User.java` + bff `JwtService` | `types/user.ts` | 已完成 | `UserStatus` 状态机 + RS256 JWT |
| 2 | 课程 CRUD/发布/归档 | sp03 `course/Course.java` | `types/course.ts` | 已完成 | `CourseStatus{DRAFT,PUBLISHED,ARCHIVED}` + `publish()/archive()` 充血 + 事件 |
| 3 | 作业→提交→评分流水线 | sp05 `homework/` + `grading/` | `types/assignment.ts` | 已完成 | `Assignment`→`Submission`→`GradePipeline`→`GradeStage` 四级链路, 含幂等键 + 版本号 |
| 4 | 多租户上下文 | sp05 `TenantContext` + `TenantContextFilter` | - | 已完成 | ThreadLocal 租户隔离 |
| 5 | 幂等性 | sp05 `IdempotencyService` + 实体 `idempotency_key` | - | 已完成 | Submission/GradePipeline 均有唯一幂等键 |
| 6 | BFF 边缘网关 | bff-java `RouteTable` + `CellRouter` | - | 已完成 | D-068 双路由归一化 + 单元路由 |
| 7 | 缓存中台 | ep-cache (L1 Caffeine + L2 Redis) | - | 已完成(即插即用) | D-103 评估 🟢 100% 可复用 |
| 8 | AI 中台 | sp04-ai (7 大子系统) | - | 已完成(YAML 换行) | IndustryConfig 切换行业零代码 |
| 9 | 知识中台 | sp07-knowledge (D-098 已拆分) | - | 已完成(轻量适配) | EducationTools 替换为 BoardGameTools |
| 10 | 数据中台 | sp02-data (CDC + BI + AIOps) | - | 已完成(CDC 泛化) | LearningEventCDC → BoardGameEventCDC |
| 11 | 支付域 | sp03-payment `PaymentChannelSpi` | - | 已完成(行业无关) | D-095 五渠道策略 |
| 12 | 订单域 | sp03-order `Order.productType` | - | 已完成(泛化) | D-100 判别器, productType=BOARDGAME_COURSE |
| 13 | **直播后端** | **RouteTable 引用 `live-go:80` 但目录不存在** | `types/live.ts` 完整 | **前端在但后端缺失** | `services/` 下无 `live-go` 目录; RouteTable 第 45 行引用 |
| 14 | **课程章节/课时/视频** | **无 Chapter/Lesson 实体** | `types/course.ts` 定义了 `CourseChapter`/`Lesson` | **前端在但后端缺失** | sp03 `course/` 包仅 `Course` 聚合, 无 Chapter/Lesson 文件 |
| 15 | **报名/学习进度** | **无 Enrollment/LearningRecord 实体** | 前端调用 `/api/learning` | **前端在但后端缺失** | RouteTable 第 51 行路由到 sp03, 但 sp03 无 learning 包(404) |
| 16 | **WebSocket 业务接线** | gateway-go 仅 `/healthz`, Hub 未接线 | - | **底座在但未接线** | `main.go` 只注册 healthz; `hub.go` 有 Broadcast/Register 但无业务调用 |
| 17 | **对弈核心域** | 无 | 无 | **完全缺失** | 全平台无 GameRoom/Match/Move 任何代码 |
| 18 | **规则引擎** | 无 | 无 | **完全缺失** | 无 chess/go/xiangqi 规则实现 |
| 19 | **匹配/ELO** | 无 | 无 | **完全缺失** | 无 Matchmaking/Rating 代码 |
| 20 | **AI 复盘** | sp04-ai 有 Agent 编排能力但无棋类复盘 Graph | 无 | **可扩展(用 sp04)** | 替换 `ai_agent/graphs/` 为 `review_agent.py` |

### 2.3 盘点结论 → BU-BOARDGAME 的工作量分布

```
┌─────────────────────────────────────────────────────────────────┐
│        BU-BOARDGAME 总工作量构成(估算)                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  复用中台(0 代码)        ████████████████  45%                   │
│  ├ 用户/订单/支付/缓存/AI/知识/数据/BFF/网关                      │
│                                                                  │
│  补齐教育基座(中等)      ████████          25%   ← 先做           │
│  ├ live-go 直播后端        (复用 gateway-go WS)                   │
│  ├ Chapter/Lesson 实体     (扩展 sp03 Course 域)                  │
│  └ Enrollment/Learning     (扩展 sp03 + 投影)                     │
│                                                                  │
│  自建对弈核心域(重)      ██████████        30%   ← 核心差异化      │
│  ├ GameRoom/Match/Move/Replay 聚合                               │
│  ├ RuleEngine(棋/Go/象戏)                                        │
│  ├ Matchmaking + ELO                                             │
│  └ AI 复盘 Agent(挂 sp04-ai)                                     │
└─────────────────────────────────────────────────────────────────┘
```

> **关键洞察**: BU-BOARDGAME 不是"全新游戏平台", 而是**先补齐 3 个教育基座缺口(直播/章节/进度), 再叠加对弈核心域**。中台复用率高达 45%, 真正自建的只有对弈域(30%)。

---

## 3. 教育与对弈的结合点分析(五维度)

### 3.1 课程域差异: 被动学习 → 交互式实战学习

| 维度 | 普通教育课程 | 棋类教育课程 | 架构影响 |
|------|-------------|-------------|----------|
| 课时类型 | video / live / coding / quiz / reading | + **tsumego**(死活题) / **review**(棋谱复盘) / **match**(实战对弈) | LessonType 枚举扩展(§6.2) |
| 学习方式 | 单向看视频/读文档 | 看视频 + **棋盘交互**(摆变化图) + **对弈练习** | 需要棋盘渲染组件 + 走子协议 |
| 内容载体 | 视频/文档 URL | 视频/文档 + **SGF/KIF/PGN 棋谱文件** | 新增棋谱存储格式 + 解析器 |
| 评估方式 | 作业分数 | 作业分数 + **对弈胜率 + 棋力等级(ELO)** | 学习进度模型扩展 Rating 字段 |

### 3.2 教学闭环差异: 单向闭环 → 双向对抗闭环

```
普通教学闭环(单向):
   讲授 ──→ 作业 ──→ 评分 ──→ 反馈
              ↑                   │
              └───── 修正 ────────┘

棋类教学闭环(双向对抗):
   讲授 ──→ 死活题 ──→ 人机对弈(练) ──→ 人人对弈(战) ──→ AI复盘(析) ──→ ELO跟踪
              ↑              │             │                │              │
              │              └── 走子记录 ─┴── 棋谱归档 ────┘              │
              └──────────────────── 反馈调整教学路径 ─────────────────────┘
```

新增的三个教学环节(人机/人人/复盘)都需要**对弈域**支撑, 且产出的"对弈记录"回流到学习进度。

### 3.3 对弈如何融入教育体系(四条绑定路径)

| 绑定路径 | 说明 | 数据模型体现 |
|---------|------|-------------|
| **课时绑定** | 一节 `match` 类型课时就是一场对弈任务 | `Lesson.type=MATCH` + `Lesson.config={rule,aiLevel,minutes}` |
| **作业绑定** | 作业要求"与 AI 对弈 3 盘并复盘" | `Assignment.type=GAME_PRACTICE` + 关联 `GameMatch[]` |
| **进度绑定** | 对弈记录计入学习进度 | `LearningRecord` 扩展 `matchesPlayed/wins/eloDelta` |
| **证书绑定** | 棋力等级达标颁发段位证书 | `Certificate` 触发条件 = ELO ≥ 阈值 |

> 核心设计决策: **GameMatch 必须可追溯到 courseId/lessonId/assignmentId**, 这是"对弈教学化"原则的数据落地。

### 3.4 房间/对弈与直播的关系(共享 WS, 不同消息类型)

```
                  共享 WebSocket 基础设施(gateway-go)
                  ┌─────────────────────────────────┐
                  │  Hub(Redis 分片广播)            │
                  │  JWT 鉴权 + Frame{type,data}    │
                  └──────────────┬──────────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        ▼                        ▼                        ▼
   直播间(Live)             对弈房间(GameRoom)        复盘房间(Replay)
   ─────────                ────────────              ────────────
   老师单向推流              师生/生生双向              基于历史棋谱
   消息: live.danmaku        状态实时同步              互动讲解
        live.raise-hand      消息: game.move           消息: game.replay
                             game.undo-request              game.annotate
                             game.resign
   角色: teacher(讲)         角色: player×2 + spectator  角色: reviewer + audience
```

**三者共享 gateway-go 底座, 但消息 type 前缀隔离**(`live.*` / `game.*` / `replay.*`), 互不污染, 可在同一条 WS 连接上复用(一个用户可边看直播边对弈)。

### 3.5 用户角色与棋力模型差异

| 维度 | 普通教育 | 棋类教育 |
|------|---------|---------|
| 角色 | student / teacher / admin | + **player**(对弈者) / **spectator**(观战者) / **referee**(裁判/AI) |
| 等级模型 | 无 | **ELO Rating**(国际象棋) / **段位**(围棋 Kyu/Dan) / **等级**(象棋) |
| 关系 | 师生 | 师生 + **对手**(动态匹配) + **棋友**(社交) |

ELO/段位是 Matchmaking 的输入, 也是学习进度的输出指标。

---

## 4. 限界上下文设计(三层: Shared / Extended / Owned)

### 4.1 三层上下文划分

严格遵循 SP-10 §4 的 BU-Owned / Platform-Shared / ACL 划分, 增加**第三层"Education-Extended"**用于表达"扩展现有教育域"。

```
┌──────────────────────── BU-BOARDGAME 事业群 ────────────────────────┐
│                                                                      │
│  ┌────────── BU-Owned(自建对弈核心域)──────────┐                     │
│  │  gameroom │ match │ move │ ruleengine │      │                     │
│  │  matchmaking │ rating │ replay │ review      │                     │
│  └──────────────────┬───────────────────────────┘                     │
│                     │ ACL(对弈域 ↔ 教育域翻译)                         │
│  ┌──────────────────┴───────────────────────────┐                     │
│  │  Education-Extended(扩展现有教育域)           │                     │
│  │  course-ext:  + boardGameType, + Lesson(tsumego/review/match)     │
│  │  homework-ext: + Assignment.type=GAME_PRACTICE                    │
│  │  learning-ext: + matchesPlayed/wins/elo                           │
│  │  live-ext:    + board-cast(棋盘推演直播模式)                       │
│  └──────────────────┬───────────────────────────┘                     │
│                     │ U/D(上游/客户)                                   │
└─────────────────────┼────────────────────────────────────────────────┘
                      │
   ┌──────────────────┴───────────────────────────────────────────┐
   │   Platform-Shared(直接复用中台, BU 只消费不持有)              │
   │  ucm(用户) │ opm(订单) │ payment(支付) │ notification(通知)   │
   │  cache(缓存) │ memory(记忆) │ ai-gateway │ knowledge │ data    │
   │  bff(网关) │ gateway-go(WS底座) │ cell-router │ eda(Pulsar)   │
   └──────────────────────────────────────────────────────────────┘
```

### 4.2 三层上下文清单

#### 4.2.1 Platform-Shared(直接复用, 零代码)

| 上下文 | 提供方 | 消费方式 | 复用度 | 依据 |
|--------|--------|----------|--------|------|
| 用户中心 UCM | sp03-user | REST + 事件投影 | 100% | D-103 🟢 |
| 订单中心 OPM | sp03-order | REST + 事件 | 100% | D-100 productType=BOARDGAME_COURSE |
| 支付中心 | sp03-payment | REST + SPI | 100% | D-095 PaymentChannelSpi 行业无关 |
| 通知中心 | (中台 B-NOT-05) | REST + 事件 | 100% | 站内信/推送 |
| 缓存中台 | ep-cache | JAR 引入 | 100% | D-103 🟢 |
| 记忆中台 | ep-memory-java | JAR + Dubbo | 100% | D-103 🟢 |
| AI 中台 | sp04-ai | REST + WS | 95% | YAML 换棋类行业 |
| 知识中台 | sp07-knowledge | REST + Dubbo | 85% | 替换 EducationTools→BoardGameTools |
| 数据中台 | sp02-data | CDC + BI | 80% | LearningEventCDC→BoardGameEventCDC |
| BFF 网关 | bff-java | 路由表扩展 | 95% | 加 `/api/boardgame/*` 路由 |
| WS 底座 | gateway-go | Hub 复用 | 70% | 业务消息接线(§7) |
| 事件总线 | Pulsar (T-EDA-09) | topic 订阅 | 100% | `boardgame.*` namespace |
| Cell 路由 | CellRouter | user_id 分片 | 100% | D-083 |

#### 4.2.2 Education-Extended(扩展现有教育域, 走 ACL/扩展点)

| 扩展上下文 | 基座 | 扩展方式 | 变更 |
|-----------|------|----------|------|
| course-ext | sp03 Course | 加字段 + 加 LessonType | `Course.boardGameType`(chess/go/xiangqi); 新增 `Lesson` 聚合(type 含 tsumego/review/match) |
| homework-ext | sp05 Assignment | 加判别字段 | `Assignment.submissionType ∈ {TEXT, CODE, GAME_RECORD}`; GAME_RECORD 类型提交即对弈棋谱 |
| learning-ext | (新建, 属教育基座补齐) | 新建 Enrollment/LearningRecord | 扩展 `matchesPlayed / wins / losses / draws / currentElo` 字段 |
| live-ext | (新建 live-go, 复用 gateway-go) | 新建 LiveRoom + 棋盘推演模式 | `LiveRoom.mode ∈ {CAMERA, SCREEN, BOARD_CAST}`; BOARD_CAST 模式推送棋盘走子 |

> **扩展原则**: 优先用"**判别器/扩展字段**"而非"继承", 避免污染基座模型。例如 `Assignment.submissionType=GAME_RECORD` 而不是新建 `GameAssignment` 子类——与 D-100 Order 泛化思路一致。

#### 4.2.3 BU-Owned(自建对弈核心域)

| 上下文 | 聚合根 | 主要实体/值对象 | 持久化 | 对外事件 |
|--------|--------|----------------|--------|----------|
| gameroom | GameRoom | RoomSeat, Spectator | TiDB + Redis | boardgame.room.created/closed |
| match | GameMatch | Move(VO), Clock | TiDB + Redis(热状态) | boardgame.match.started/moved/finished |
| ruleengine | (无聚合根, 纯域服务) | RuleEngine 接口 + Chess/Go/Xiangqi 实现 | 无状态 | - |
| matchmaking | MatchmakingTicket | (短生命周期) | Redis(TTL) | boardgame.match.made |
| rating | PlayerRating | RatingHistory | TiDB | boardgame.rating.changed |
| replay | GameReplay | Move[]/Annotation | TiDB + OSS(SGF/PGN) | boardgame.replay.generated |
| review | ReviewSession | ReviewNote | TiDB + Redis | boardgame.review.started |

### 4.3 上下文关系映射(对齐 SP-10 §4.3)

| 关系 | 模式 | 示例 |
|------|------|------|
| BU-Owned ↔ Education-Extended | ACL | GameMatch → Lesson(MATCH) 翻译, 隔离对弈模型不渗入课程域 |
| Education-Extended ↔ Platform-Shared | C/S(客户/供应商) | course-ext 反向驱动 CMP 中台增加 boardGameType 扩展点 |
| BU-Owned ↔ Platform-Shared | U/D + ACL | match 调 ucm 取对手画像; 经 ACL 翻译 |
| BU-Owned ↔ AI 中台 | U/D | review 调 sp04-ai 生成复盘分析 |

---

## 5. 复用 vs 自建矩阵

### 5.1 完整决策矩阵(每能力域一行)

| 能力域 | 决策 | 依据 | 工作量 |
|--------|------|------|--------|
| 用户注册/登录/会话 | **复用** | sp03-user 完整, D-103 🟢 | 0 |
| 多租户/JWT/角色 | **复用** | bff JwtService + TenantContext | 0 |
| 课程 CRUD/发布 | **复用** | sp03 Course 完整 | 0 |
| **棋类课程类型扩展** | **扩展** | Course 加 boardGameType 字段 | 1 人天 |
| **章节/课时管理** | **新建(教育基座补齐)** | sp03 无 Chapter/Lesson | 5 人天 |
| **课时类型扩展(tsumego/review/match)** | **扩展** | LessonType 枚举新增 3 项 | 1 人天 |
| 作业→提交→评分流水线 | **复用** | sp05 完整 | 0 |
| **对弈作业类型** | **扩展** | Assignment.submissionType=GAME_RECORD | 2 人天 |
| **报名/学习进度** | **新建(教育基座补齐)** | sp03 无 Enrollment/LearningRecord | 5 人天 |
| **进度含对弈指标** | **扩展** | LearningRecord 加 matchesPlayed/elo | 2 人天 |
| 订单/支付 | **复用** | D-100/D-095 已泛化 | 0 |
| **直播授课后端** | **新建(教育基座补齐)** | live-go 不存在, 复用 gateway-go WS | 8 人天 |
| **棋盘推演直播模式** | **扩展** | LiveRoom.mode=BOARD_CAST | 3 人天 |
| 通知(站内信/推送) | **复用** | 中台 B-NOT-05 | 0 |
| 缓存/记忆/AI/知识/数据中台 | **复用/轻适配** | D-103 评估 | 3 人天(YAML+Tools 替换) |
| **GameRoom 房间管理** | **新建** | BU-Owned 核心 | 5 人天 |
| **GameMatch 对局** | **新建** | BU-Owned 核心 | 8 人天 |
| **Move 走子 VO** | **新建** | BU-Owned 核心 | 2 人天 |
| **规则引擎(棋/Go/象戏)** | **新建** | BU-Owned 核心, 性能敏感 | 15 人天 |
| **匹配(Matchmaking)** | **新建** | BU-Owned, 基于 ELO | 5 人天 |
| **ELO/段位 Rating** | **新建** | BU-Owned | 3 人天 |
| **棋谱回放 Replay** | **新建** | BU-Owned + OSS | 5 人天 |
| **AI 复盘 Agent** | **扩展 sp04-ai** | 新增 review_agent.py Graph | 5 人天 |
| WS 网关业务接线 | **扩展** | gateway-go Hub 已有, 加业务消息路由 | 5 人天 |
| BFF 路由 | **扩展** | RouteTable 加 `/api/boardgame/*` | 0.5 人天 |
| 事件总线 topic | **复用** | Pulsar namespace `boardgame` | 0 |

### 5.2 决策汇总

```
复用(零代码)    : 11 项 ──┐
扩展(轻适配)    : 11 项 ──┼─→ 中台复用率 = (复用+扩展) / 总 = 22/27 ≈ 81%
新建(BU 自建)   :  6 项 ──┘    (其中 3 项是教育基座补齐, 3 项是对弈核心)
```

> 对比 SP-10 §1.3 复用矩阵的历史 BU(IT/K12/考公/企业), BU-BOARDGAME 复用率 81% 与 K12(80%) 相当, 高于 IT后端 MVP(因 IT 后端沙箱是各行其道 X)。**自建工作量集中在"对弈核心域"和"3 个教育基座补齐"**, 这正是 BU-BOARDGAME 的差异化所在。

---

## 6. 领域模型设计(聚合根 + 状态机 + 事件)

### 6.1 总览: 聚合根清单

| # | 聚合根 | 上下文 | 类型 | 职责 |
|---|--------|--------|------|------|
| 1 | GameRoom | gameroom | BU-Owned | 房间生命周期 + 座位 + 观战 |
| 2 | GameMatch | match | BU-Owned | 一局对局 + 走子序列 + 时钟 |
| 3 | PlayerRating | rating | BU-Owned | 玩家棋力(ELO/段位) + 历史 |
| 4 | GameReplay | replay | BU-Owned | 棋谱归档 + 标注 |
| 5 | ReviewSession | review | BU-Owned | 复盘会话(基于 Replay) |
| 6 | Lesson | course-ext | Extended | 课时(扩展类型) |
| 7 | Enrollment | learning-ext | Extended | 报名 + 学习进度 |
| 8 | LiveRoom | live-ext | Extended | 直播间(含棋盘推演) |

值对象(VO): `Move`(走子)、`BoardState`(棋盘状态)、`Clock`(计时)、`Seat`(座位)、`Annotation`(标注)。
域服务(无状态): `RuleEngine`(规则引擎)、`Matchmaker`(匹配)、`EloCalculator`(等级计算)。

### 6.2 Course/Lesson 扩展(Education-Extended)

#### 6.2.1 Course 加 boardGameType 判别字段

```java
// sp03-business/course/Course.java (扩展, 不破坏现有 DRAFT→PUBLISHED→ARCHIVED 状态机)
@Entity
@Table(name = "ep_course")
public class Course implements Serializable {
    // ... 现有字段保持不变(courseId/title/description/teacherId/category/difficulty/price/status/...) ...

    /** 棋类课程标识。null 表示普通课程; 非 null 表示棋类课程, 值为棋种。 */
    @Column(name = "board_game_type", length = 16)
    private BoardGameType boardGameType;   // CHESS / GO / XIANGQI / null

    /** 棋盘规格(围棋 9/13/19 路, 象棋固定 9x10)。null 表示非棋类或默认。 */
    @Column(name = "board_size")
    private Integer boardSize;             // Go: 9|13|19, Chess/Xiangqi: null
}

public enum BoardGameType {
    CHESS("国际象棋"), GO("围棋"), XIANGQI("中国象棋");
    // ...
}
```

> **设计要点**: 用可空字段而非继承, 保持与现有 `Course.publish()/archive()` 状态机和事件链完全兼容。普通课程 `boardGameType=null`, 零迁移成本。

#### 6.2.2 Lesson 聚合(新建, 教育基座补齐 + 棋类扩展)

```java
// sp03-business/course/Lesson.java (新建, 同时服务普通与棋类课程)
@Entity
@Table(name = "ep_lesson")
public class Lesson implements Serializable {

    @Id
    private Long id;                       // Snowflake ID (D-132)
    
    @Column(name = "chapter_id", nullable = false)
    private Long chapterId;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LessonType type;               // 见下枚举

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "is_free", nullable = false)
    private Boolean isFree = false;

    @Column(name = "resource_url", length = 1024)
    private String resourceUrl;            // 视频 URL / 文档 URL

    /** 棋类课时专用配置(JSON)。仅 type=tsumego/review/match 时非空。 */
    @Column(name = "game_config", columnDefinition = "json")
    private String gameConfig;             // 见 LessonGameConfig
}

public enum LessonType {
    // 普通教育(现有前端契约 types/course.ts)
    VIDEO, LIVE, CODING, QUIZ, READING,
    // 棋类教育扩展
    TSUMEGO,   // 死活题: gameConfig = { problems: [...], timeLimit }
    REVIEW,    // 棋谱复盘: gameConfig = { replayId, annotations }
    MATCH      // 实战对弈: gameConfig = { rule, opponentType: AI|HUMAN, aiLevel, minutes }
}
```

```java
// LessonGameConfig (JSON 结构, 不持久化为表)
public record LessonGameConfig(
    BoardGameType rule,        // 棋种
    OpponentType opponentType, // AI / HUMAN / MATCHMAKING
    Integer aiLevel,           // AI 难度 1-20(opponentType=AI 时)
    Integer minutes,           // 限时
    Long replayId,             // type=REVIEW 时关联的回放
    List<Long> problemIds      // type=TSUMEGO 时的死活题集
) {}
```

#### 6.2.3 Chapter 聚合(新建, 教育基座补齐, 通用)

```java
// sp03-business/course/Chapter.java (新建, 章节聚合)
@Entity
@Table(name = "ep_chapter")
public class Chapter implements Serializable {
    @Id private Long id;
    @Column(name = "course_id", nullable = false) private Long courseId;
    @Column(nullable = false, length = 255) private String title;
    @Column(name = "sort_order", nullable = false) private Integer sortOrder;
    // Lessons 由 Lesson.chapterId 反向关联, 不在 Chapter 内聚合列表(避免大对象)
}
```

### 6.3 对弈核心域聚合根(BU-Owned)

#### 6.3.1 GameRoom 聚合根 + 状态机

```java
// bu-boardgame/gameroom/GameRoom.java
public class GameRoom {
    private Long roomId;
    private BoardGameType gameType;        // 棋种
    private RoomMode mode;                 // CASUAL(休闲) / RANKED(排位) / TEACHING(教学) / ASSIGNMENT(作业)
    private Long hostId;                   // 房主(创建者)
    private List<Seat> seats;              // 座位(2 个, 黑/白或红/黑)
    private List<Long> spectators;         // 观战者 ID
    private RoomStatus status;             // 见状态机
    private Long matchId;                  // 关联的对局(PLAYING 时非空)
    private Instant createdAt;
    // 状态机: 见 §6.3.2
}

public enum RoomStatus {
    CREATED, WAITING, READY, PLAYING, FINISHING, FINISHED, ABORTED
}
```

**GameRoom 状态机**:

```
                  create()
        ─────────────────────►
   (初始)                      [CREATED]
                                  │
                                  │ invite/join(第1人入座)
                                  ▼
                              [WAITING] ◄──── leave(全空) ────
                                  │                             │
                                  │ join(第2人入座, 双方就绪)     │
                                  ▼                             │
                              [READY]                           │
                                  │                             │
                                  │ startMatch()                │
                                  ▼                             │
            ┌──────────────► [PLAYING] ◄── resume() ────┐       │
            │                  │                        │       │
            │                  │ pause()                │       │
            │                  ▼                        │       │
            │              [PAUSED] ─── resume() ───────┘       │
            │                  │                                │
            │                  │ finish/abort/resign            │
            │                  ▼                                │
            │             [FINISHING]                           │
            │                  │ (结算 ELO, 归档 Replay)         │
            │                  ▼                                │
            │             [FINISHED] ─── close() ───────────────┼──► (终态)
            │                  │                                │
            └── rematch() ─────┘                                │
                  (新建 match, 房间回到 READY)                    │
                                                               │
                  abort() (任意时刻强制终止) ────────────────────┘
                       ▼
                  [ABORTED] (终态)
```

#### 6.3.2 GameMatch 聚合根 + 走子

```java
// bu-boardgame/match/GameMatch.java
public class GameMatch {
    private Long matchId;
    private Long roomId;                   // 反向关联 GameRoom
    private BoardGameType gameType;
    private Long blackPlayerId;            // 黑方(先手)
    private Long whitePlayerId;            // 白方(后手) —— 象棋为红黑
    private SeatColor turn;                // 当前轮到谁 BLACK/WHITE
    private List<Move> moves;              // 走子序列(事件溯源风格)
    private BoardState currentState;       // 当前棋盘(由 moves 折叠得出, 缓存)
    private Clock clock;                   // 双方剩余时间
    private MatchStatus status;            // ONGOING / CHECKMATE / RESIGNED / DRAW / TIMEOUT / ABORTED
    private Long winnerId;                 // 胜者(和棋为 null)
    private String resultRule;             // 结束原因 "checkmate" / "resign" / "timeout" / "draw_agree"
    private Instant startedAt;
    private Instant finishedAt;

    // 教育绑定(对弈教学化原则)
    private MatchContext context;          // 见下

    /** 应用一步走子(充血行为)。规则引擎校验 + 更新状态 + 记录事件。 */
    public MoveApplied applyMove(Move move, RuleEngine engine) {
        if (status != MatchStatus.ONGOING) throw new IllegalStateException("match not ongoing");
        if (move.color() != turn) throw new IllegalMoveException("not your turn");
        engine.validate(currentState, move);   // 规则校验, 失败抛 IllegalMoveException
        BoardState next = engine.apply(currentState, move);
        this.moves.add(move);
        this.currentState = next;
        this.clock = clock.tick(turn);
        this.turn = turn.opposite();
        if (engine.isTerminal(next)) {
            this.status = engine.resultStatus(next);
            this.winnerId = engine.winner(next, blackPlayerId, whitePlayerId);
            this.finishedAt = Instant.now();
            recordEvent(new MatchFinishedEvent(matchId, status, winnerId));
        } else {
            recordEvent(new MoveAppliedEvent(matchId, move, turn));
        }
        return new MoveApplied(move, next, status);
    }

    public void resign(SeatColor resigner) { /* ... */ }
    public void offerDraw(SeatColor offerer) { /* ... */ }
    public void acceptDraw() { /* ... */ }
}

/** 对弈的教学上下文(对弈教学化原则的数据落地)。 */
public record MatchContext(
    Long courseId,          // 关联课程(可空, 排位赛为 null)
    Long lessonId,          // 关联课时(type=MATCH)
    Long assignmentId,      // 关联作业(GAME_PRACTICE 类型)
    MatchRole role          // CASUAL / RANKED / LESSON_PRACTICE / ASSIGNMENT
) {}
```

#### 6.3.3 Move 值对象(走子)

```java
/** 走子值对象。不同棋种用不同的 coordinate 表示, 但接口统一。 */
public record Move(
    int sequence,                 // 第几手(从 1 开始)
    SeatColor color,              // BLACK / WHITE
    String notation,              // 通用表示: 棋谱坐标或代数记谱
    MovePayload payload,          // 棋种特定的负载(JSON)
    long clockAfterMoveMs,        // 走完后剩余时间
    Instant playedAt
) {}

// 棋种特定负载(用 sealed interface 或 marker interface)
public interface MovePayload {}
public record ChessMovePayload(String from, String to, String promotion) implements MovePayload {}      // 国际象棋 e2e4
public record GoMovePayload(int x, int y, boolean pass) implements MovePayload {}                        // 围棋 Q16
public record XiangqiMovePayload(int fromX, int fromY, int toX, int toY) implements MovePayload {}       // 象棋 炮二平五
```

#### 6.3.4 PlayerRating 聚合根(ELO/段位)

```java
// bu-boardgame/rating/PlayerRating.java
public class PlayerRating {
    private Long userId;
    private BoardGameType gameType;        // 每棋种独立 Rating
    private int elo;                       // ELO 分(初始 1200)
    private String danKyu;                 // 段位表示(Go: "3D"/"5K", 仅显示用, 由 elo 换算)
    private int wins;
    private int losses;
    private int draws;
    private int streak;                    // 连胜/连负
    private Instant updatedAt;

    /** 比赛后更新 ELO(标准 Elo 公式 K=32)。 */
    public void applyResult(double score, int opponentElo) {
        double expected = 1.0 / (1 + Math.pow(10, (opponentElo - elo) / 400.0));
        int delta = (int) Math.round(32 * (score - expected));
        this.elo = Math.max(0, elo + delta);
        if (score == 1) { wins++; streak = streak >= 0 ? streak + 1 : 1; }
        else if (score == 0) { losses++; streak = streak <= 0 ? streak - 1 : -1; }
        else { draws++; streak = 0; }
        this.danKyu = DanKyuConverter.toDanKyu(elo, gameType);
        recordEvent(new RatingChangedEvent(userId, gameType, elo, delta));
    }
}
```

#### 6.3.5 GameReplay 聚合根(棋谱归档)

```java
// bu-boardgame/replay/GameReplay.java
public class GameReplay {
    private Long replayId;
    private Long matchId;                  // 源对局
    private BoardGameType gameType;
    private List<Move> moves;              // 完整走子序列
    private String notationFileUrl;        // OSS: SGF(Go) / PGN(Chess) / KIF(Xiangqi)
    private MatchResult result;
    private List<Annotation> annotations;  // 标注(复盘时添加)
    private String aiAnalysisUrl;          // AI 复盘报告(sp04-ai 生成)
    private Instant archivedAt;
}
```

### 6.4 事件契约(对齐 SP-10 §5 Pulsar topic 规范)

所有事件 topic 遵循 `persistent://edu/boardgame/boardgame.<domain>.<action>.v<n>`:

| 事件 | topic | 产生方 | 消费方 | 用途 |
|------|-------|--------|--------|------|
| RoomCreated | `boardgame.room.created.v1` | gameroom | gateway-go(WS 推送) | 通知房间可加入 |
| MatchStarted | `boardgame.match.started.v1` | match | gateway-go, learning-ext | 开始走子同步 + 计入学习进度 |
| MoveApplied | `boardgame.match.moved.v1` | match | gateway-go | 走子广播(跨 pod) |
| MatchFinished | `boardgame.match.finished.v1` | match | rating, replay, learning-ext | 更新 ELO + 归档棋谱 + 进度 |
| RatingChanged | `boardgame.rating.changed.v1` | rating | learning-ext, ucm | 同步段位到用户画像 |
| ReplayGenerated | `boardgame.replay.generated.v1` | replay | review, sp04-ai | 触发 AI 复盘 |
| ReviewStarted | `boardgame.review.started.v1` | review | gateway-go | 复盘会话广播 |

```json
// schema-registry/boardgame/match.finished.v1.avsc
{
  "type": "record",
  "name": "MatchFinished",
  "namespace": "edu.platform.boardgame",
  "fields": [
    { "name": "eventId", "type": "string" },
    { "name": "occurredAt", "type": "long" },
    { "name": "buId", "type": "string", "default": "BU-BOARDGAME" },
    { "name": "matchId", "type": "long" },
    { "name": "roomId", "type": "long" },
    { "name": "gameType", "type": "string" },
    { "name": "blackPlayerId", "type": "long" },
    { "name": "whitePlayerId", "type": "long" },
    { "name": "winnerId", "type": ["null","long"], "default": null },
    { "name": "resultRule", "type": "string" },
    { "name": "context", "type": {
        "type": "record", "name": "MatchContext",
        "fields": [
          { "name": "courseId", "type": ["null","long"], "default": null },
          { "name": "lessonId", "type": ["null","long"], "default": null },
          { "name": "assignmentId", "type": ["null","long"], "default": null },
          { "name": "role", "type": "string" }
        ]
    }},
    { "name": "cellId", "type": "int" }
  ]
}
```

---

## 7. WebSocket 消息协议

复用 gateway-go 的 `Frame{type, data}` 结构(`services/gateway-go/src/ws/frame.go`), 在其上分层定义业务消息类型。三类房间(直播/对弈/复盘)共享一条 WS 连接, 用 `type` 前缀隔离。

### 7.1 消息类型命名规范

```
<domain>.<action>

domain:
  room.*   — 通用房间(直播/对弈/复盘共用)
  game.*   — 对弈专用
  replay.* — 复盘专用
  live.*   — 直播专用
  system.* — 系统层(连接/心跳/错误)
```

### 7.2 消息类型清单

#### 7.2.1 系统层(连接管理, 复用现有 gateway-go)

| type | 方向 | 用途 | data 示例 |
|------|------|------|----------|
| `system.hello` | C→S | 建连握手, 上报身份 | `{jwt, bu:"boardgame"}` |
| `system.welcome` | S→C | 服务端确认 | `{userId, cellId}` |
| `system.ping` | C→S | 心跳 | `{t: epochMs}` |
| `system.pong` | S→C | 心跳响应 | `{t}` |
| `system.error` | S→C | 错误 | `{code, message}` |

> gateway-go 现有 JWT 校验(`src/jwt/validator.go`)直接复用, 仅需在 `system.hello` 后把连接注册到对应房间的 Hub session。

#### 7.2.2 通用房间消息(room.*)

| type | 方向 | 用途 | data |
|------|------|------|------|
| `room.join` | C→S | 加入房间 | `{roomId, role:"player\|spectator\|teacher"}` |
| `room.joined` | S→C | 加入广播 | `{roomId, userId, role, seatColor?}` |
| `room.leave` | C→S | 离开房间 | `{roomId}` |
| `room.left` | S→C | 离开广播 | `{roomId, userId}` |
| `room.chat` | C↔S | 房间聊天 | `{roomId, userId, text}` |
| `room.state` | S→C | 全量状态快照(新成员入房时推送) | `{room:GameRoomDTO, match?:GameMatchDTO}` |

#### 7.2.3 对弈消息(game.*)

| type | 方向 | 用途 | data |
|------|------|------|------|
| `game.move` | C→S | 走子 | `{matchId, move:{seq, color, notation, payload}}` |
| `game.moved` | S→C | 走子广播(房间全员) | `{matchId, move, boardState, nextTurn}` |
| `game.illegal` | S→C | 非法走子拒绝 | `{matchId, reason:"not_your_turn\|invalid_move\|..."}` |
| `game.undo-request` | C→S | 请求悔棋 | `{matchId, backTo}` |
| `game.undo-response` | C→S | 对方回应悔棋 | `{matchId, accepted:bool}` |
| `game.resign` | C→S | 认输 | `{matchId}` |
| `game.offer-draw` | C→S | 提和 | `{matchId}` |
| `game.draw-response` | C→S | 回应提和 | `{matchId, accepted:bool}` |
| `game.finished` | S→C | 对局结束 | `{matchId, status, winnerId, resultRule, eloDelta}` |
| `game.clock` | S→C | 时钟同步(每秒推) | `{matchId, blackMs, whiteMs}` |

#### 7.2.4 观战/回放消息(replay.*)

| type | 方向 | 用途 | data |
|------|------|------|------|
| `game.spectate` | C→S | 观战(只读加入对弈房间) | `{matchId}` |
| `replay.open` | C→S | 打开历史棋谱 | `{replayId}` |
| `replay.step` | C→S | 跳到第 N 手 | `{replayId, step}` |
| `replay.synced` | S→C | 同步棋盘状态 | `{replayId, step, boardState}` |
| `replay.annotate` | C→S | 添加标注(复盘老师) | `{replayId, step, text, mark}` |
| `replay.annotated` | S→C | 标注广播 | `{replayId, step, userId, text, mark}` |

#### 7.2.5 直播消息(live.*)

| type | 方向 | 用途 | data |
|------|------|------|------|
| `live.danmaku` | C↔S | 弹幕 | `{roomId, userId, text, color}` |
| `live.raise-hand` | C→S | 举手提问 | `{roomId, userId}` |
| `live.call-down` | S→C | 老师叫号 | `{roomId, userId}` |
| `live.board-cast` | S→C | 棋盘推演(老师讲解时推送走子) | `{roomId, boardState, annotation}` |
| `live.stream-status` | S→C | 推流状态 | `{roomId, status:"live\|ended"}` |

### 7.3 帧示例(对齐现有 Frame 结构)

现有 gateway-go `frame.go`:
```go
type Frame struct {
    Type string `json:"type"`
    Data any    `json:"data,omitempty"`
}
```

对弈走子完整往返示例:

```json
// 客户端 → 服务端: 黑方走 e2-e4
{ "type": "game.move",
  "data": { "matchId": 8812345,
            "move": { "seq": 1, "color": "BLACK", "notation": "e2e4",
                      "payload": { "from": "e2", "to": "e4", "promotion": null }}}}

// 服务端 → 房间全员广播
{ "type": "game.moved",
  "data": { "matchId": 8812345,
            "move": { "seq": 1, "color": "BLACK", "notation": "e2e4" },
            "boardState": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b",
            "nextTurn": "WHITE",
            "clock": { "blackMs": 599980, "whiteMs": 600000 }}}
```

### 7.4 WS 路由扩展(gateway-go)

```go
// gateway-go/src/handler/boardgame.go (新增)
// 复用现有 hub.Hub 的 Broadcast/Register/Unregister
func HandleBoardGameWS(w http.ResponseWriter, r *http.Request) {
    // 1. JWT 校验(复用 src/jwt/validator.go)
    claims := jwt.Validate(r)
    // 2. 升级 WS
    conn := upgrader.Upgrade(w, r, nil)
    // 3. system.hello → 注册到 Hub(按 roomId 分 session)
    sessionID := roomID
    ch := hub.Register(sessionID)
    // 4. 读循环: 解码 Frame, 按 type 分发到对应处理器
    for {
        _, msg, _ := conn.ReadMessage()
        frame, _ := ws.DecodeFrame(msg)
        switch frame.Type {
        case "game.move":
            // → 转发到 boardgame-match 服务(规则校验) → 成功后 hub.Broadcast
            handleGameMove(frame.Data, sessionID)
        case "room.chat":
            hub.Broadcast(ctx, sessionID, frame.Encode())
        // ... live.danmaku / replay.annotate ...
        }
    }
}
```

`main.go` 新增一行注册(替换现有仅 `/healthz` 的现状):
```go
mux.HandleFunc("/ws/boardgame", handler.HandleBoardGameWS)
```

---

## 8. 技术方案选型(Rust / Go / Java 三方对比)

本节遵循 **Iron Law 8(≥3 方案对比)**, 对两个关键技术决策给出 Rust / Go / Java 三方对比并给出决策。

### 8.1 决策一: 棋类规则引擎(计算密集, 性能敏感)

规则引擎负责: 走子合法性校验、棋盘状态推进、终局判定(将死/和棋/提子)、合法走子生成。这是对弈域的"热路径", 每步走子都要调用, 且 AI 思考时需要做百万级局面评估(Minimax/Alpha-Beta/MCTS)。

| 维度 | Rust | Go | Java |
|------|------|------|------|
| **原始性能(CPU 密集)** | ⭐⭐⭐⭐⭐ 零成本抽象, 接近 C | ⭐⭐⭐⭐ GC 偶发停顿, 但够用 | ⭐⭐⭐ JIT 热身后快, 但有 GC + 启动慢 |
| **MCTS/Alpha-Beta 百万节点/s** | ⭐⭐⭐⭐⭐ 最优(LLVM 优化 + 无 GC) | ⭐⭐⭐⭐ 良好(goroutine 并发友好) | ⭐⭐⭐ 中等(GC 压力大) |
| **与现有 DDD 一致性** | ⭐ 需重学, 团队栈不匹配 | ⭐⭐ 不一致(异构) | ⭐⭐⭐⭐⭐ 与 sp03/sp05 同栈, D-066 充血模型直接复用 |
| **生态(棋类库)** | ⭐⭐⭐ 有 `chess`/`shakmaty` crate | ⭐⭐⭐ 有 `notnil/chess` 等 | ⭐⭐⭐⭐ 有 `chesslib`, 围棋 `gnugo` 绑定 |
| **FaaS/嵌入式(规则引擎可作为 sidecar)** | ⭐⭐⭐⭐⭐ WASM 友好, 可嵌入任意宿主 | ⭐⭐⭐ 独立进程/gRPC | ⭐⭐ 需 JVM, 嵌入笨重 |
| **开发速度** | ⭐⭐⭐ 学习曲线陡, 编译慢 | ⭐⭐⭐⭐⭐ 快速, 编译秒级 | ⭐⭐⭐⭐ 成熟 IDE, 但样板多 |
| **团队栈匹配(D-032)** | ❌ 不在 D-032 栈 | ✅ 已有 gateway-go | ✅ 主力栈(sp03/05/07) |
| **D-032 技术栈铁律** | ❌ 需 ADR 特批引入 | ✅ 合规 | ✅ 合规 |

#### 决策 1: **混合方案 —— Go 做规则引擎服务 + 现成棋类库, Java 做对弈域聚合根**

**理由**:
1. **D-032 合规**: Go 已在栈内(gateway-go), Java 是主力栈, 无需引入新语言(Rust 需特批, 风险高)。
2. **性能足够**: 规则引擎本身是纯逻辑(校验/推进), 单步走子耗时 <1ms, Go 完全够用。真正算力密集的是 **AI 棋力引擎**(如 Stockfish/Leela/KataGo), 这些用**现成 C/C++ 开源引擎**作为 sidecar(gRPC 调用), 而非自研——这与"AI 中台复用"思路一致。
3. **架构一致**: 对弈域的**聚合根(GameMatch/GameRoom)用 Java**(与 sp03/sp05 同栈, DDD 充血模型一致), **规则引擎作为无状态域服务用 Go 实现**(性能 + 与 gateway-go 同栈可共享 ep-go-common)。
4. **Rust 被否决**: 虽性能最优, 但 (a) 不在 D-032 栈, 需架构委员会特批; (b) 团队学习成本高; (c) 性能优势在"非 AI 自研"场景下不显著(瓶颈在 AI 引擎 sidecar 而非规则校验)。如未来需自研 AI 棋力引擎且性能不达标, 再以 ADR 形式重新评估 Rust。

**落地**:
```go
// bu-boardgame-ruleengine (Go 服务, 无状态)
// 通过 ep-go-common 接入 Cell 路由 + JWT
type RuleEngine interface {
    Validate(state BoardState, move Move) error
    Apply(state BoardState, move Move) BoardState
    IsTerminal(state BoardState) bool
    ResultStatus(state BoardState) MatchStatus
    LegalMoves(state BoardState, color Color) []Move
}
// 每棋种一个实现
type ChessEngine struct{}   // 复用 notnil/chess
type GoEngine struct{}      // 围棋(提子/劫争/数目)
type XiangqiEngine struct{} // 象象
```

```java
// Java 侧(GameMatch.applyMove 调用 Go 规则引擎)
public class RuleEngineClient {  // ACL
    private final RuleEngineRpc engineRpc;  // Dubbo/gRPC 到 Go 服务
    public void validate(BoardState state, Move move) {
        engineRpc.validate(state.toProto(), move.toProto());
    }
}
```

### 8.2 决策二: WebSocket 网关扩展

| 维度 | Go(扩展现有 gateway-go) | Rust(新建) | Java(新建, 用 Spring WebSocket) |
|------|------|------|------|
| **现有底座复用** | ⭐⭐⭐⭐⭐ Hub/Frame/JWT 已就绪 | ⭐ 全重建 | ⭐ 全重建 |
| **连接并发(10w CCU)** | ⭐⭐⭐⭐ goroutine 轻量 | ⭐⭐⭐⭐⭐ tokio 最优 | ⭐⭐⭐ 线程模型 + 内存重 |
| **跨 pod 广播** | ⭐⭐⭐⭐ Redis 已接, Pulsar 待(D-029) | ⭐⭐⭐ 自实现 | ⭐⭐⭐ 自实现 |
| **与 bff-java 一致** | ⭐⭐ 异构但已共存 | ⭐ 异构 | ⭐⭐⭐⭐⭐ 同栈 |
| **D-032 合规** | ✅ | ❌ | ✅ |
| **改造工作量** | 接线业务消息(~5 人天) | 全新建(~30 人天) | 全新建 + 与现有 gateway-go 二选一(~40 人天) |
| **生态(WS 库)** | ⭐⭐⭐⭐ gorilla/nhooyr | ⭐⭐⭐⭐ tokio-tungstenite | ⭐⭐⭐ Spring WebSocket/Netty |

#### 决策 2: **Go(扩展现有 gateway-go)**

**理由**:
1. **底座已就绪**: `hub.go`(Redis 分片广播)、`frame.go`(Frame 编解码)、`jwt/validator.go`(RS256 校验)三件套完整, 仅 `main.go` 缺业务 `/ws` handler(当前只注册 `/healthz`)。扩展工作量最小。
2. **性能 + 并发模型匹配**: 对弈场景高并发长连接, goroutine 模型天然适合; Rust 虽更优但"杀鸡用牛刀", 10w CCU 量级 Go 完全 hold 住。
3. **D-029 路径清晰**: 跨 pod 广播当前用 Redis Pub/Sub(M0), 后续切 Pulsar(`persistent://public/ws/broadcast`), 这条路在 hub.go 注释里已规划好, 扩展即可。
4. **Java 被否决**: (a) 现有 gateway-go 已运行, 弃之重建是浪费; (b) JVM 内存开销大, 长连接网关用 Java 不划算; (c) 与"复用优先"原则冲突。
5. **Rust 被否决**: 性能优势在当前量级无实际收益, 全重建成本极高, 且引入新语言违反 D-032。

### 8.3 决策汇总表

| 子系统 | 选型 | 关键理由 |
|--------|------|----------|
| 对弈域聚合根(GameMatch/GameRoom/Replay) | **Java** | 与 sp03/sp05 DDD 同栈, D-066 充血一致 |
| 规则引擎(校验/推进/终局) | **Go 服务** | 性能够 + 与 gateway-go 同栈 + D-032 合规 |
| AI 棋力引擎(对手 AI) | **现成 C/C++ 引擎 sidecar**(Stockfish/KataGo) | 不自研, 通过 gRPC 调用 |
| WS 网关 | **Go(扩展现有 gateway-go)** | 底座已就绪, 仅接线业务 handler |
| AI 复盘 | **Python(sp04-ai 扩展)** | 复用 sp04-ai Agent 编排, 新增 review_agent.py |
| 匹配/ELO/房间管理 | **Java** | 业务逻辑, 与对弈域同栈 |
| 前端棋盘渲染 | **TypeScript + 现成棋盘库** | chessboard.js / wgo / 象棋棋盘库 |

---

## 9. 实施路线图

分三阶段递进, 每阶段都是可上线的增量。**MVP 必须先打通教育基座, 再叠加对弈**。

### 9.1 三阶段总览

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      BU-BOARDGAME 实施路线图                              │
└──────────────────────────────────────────────────────────────────────────┘

 Phase 1: MVP(教育基座 + 单机对弈)        4 周
 ├ 补齐教育基座缺口(直播/章节/进度)        │
 ├ 最小对弈闭环(人机对弈, 单棋种象棋)      │
 └ 复用 gateway-go WS 接线                 │
        ▼
 Phase 2: 增强(多人对弈 + 匹配 + 复盘)     4 周
 ├ 人人对弈 + 房间系统
 ├ Matchmaking + ELO Rating
 ├ 多棋种(围棋/象棋)
 └ AI 复盘(sp04-ai review_agent)
        ▼
 Phase 3: 完善(社交 + 赛事 + 直播棋盘)     4 周
 ├ 锦标赛/联赛系统
 ├ 直播棋盘推演(BOARD_CAST 模式)
 ├ 社交(棋友/对战历史/分享)
 └ 数据看板(棋力增长曲线)
```

### 9.2 Phase 1: MVP(4 周)—— 教育基座 + 单机对弈

**目标**: 一个能跑通"看棋类课程 → 学死活题 → 与 AI 对弈 → 记录进度"的最小闭环。

| 周次 | 交付物 | 依赖 |
|------|--------|------|
| W1 | 教育基座补齐: `Chapter`/`Lesson` 实体(sp03); `LessonType` 扩展 TSUMEGO/REVIEW/MATCH; BFF 路由 `/api/boardgame/*` | 复用 sp03 Course |
| W2 | 教育基座补齐: `Enrollment`/`LearningRecord`(sp03 learning-ext); `live-go` 最小直播后端(复用 gateway-go, 仅 live.danmaku) | 复用 gateway-go |
| W3 | 对弈核心 v1: `GameRoom`/`GameMatch`/`Move`(Java, 单棋种=象棋); Go 规则引擎(象棋, 复用 notnil/chess); WS 接线 game.move/moved | 复用 sp03/05 |
| W4 | AI 对手 sidecar(Stockfish 接入); 棋盘前端(象棋); 学习进度记录对弈; 端到端联调 + Go-Live 检查 | 全链路 |

**MVP Go-Live gate**(对齐 SP-10 §11):
- [ ] A1 DDD 边界图评审通过
- [ ] A2 复用矩阵签字(本文 §5)
- [ ] A3 D-032 栈合规(Go + Java, 无 Rust)
- [ ] S1 JWT RS256 接入
- [ ] C1 单棋种(象棋)端到端对弈可跑
- [ ] D1 至少 SAD + SDD 文档齐备

### 9.3 Phase 2: 增强(4 周)—— 多人对弈 + 匹配 + 复盘

**目标**: 人人对弈 + 棋力等级体系 + AI 复盘教学。

| 周次 | 交付项 |
|------|--------|
| W5 | 房间系统完整(GameRoom 状态机全状态); 观战(game.spectate); 悔棋/提和/认输 |
| W6 | Matchmaking(基于 ELO 匹配); `PlayerRating` 聚合; ELO 更新事件链 |
| W7 | 多棋种: 围棋(Go 规则引擎, 提子/劫争); `GameReplay` 棋谱归档(SGF/PGN) |
| W8 | AI 复盘(sp04-ai 新增 `review_agent.py` Graph); `ReviewSession` 复盘房间; replay.* 消息 |

### 9.4 Phase 3: 完善(4 周)—— 社交 + 赛事 + 直播棋盘

**目标**: 完整的棋类教育生态。

| 周次 | 交付项 |
|------|--------|
| W9 | 象棋(中国象棋)棋种; 锦标赛/联赛系统(Tournament 聚合, 瑞士制/淘汰制) |
| W10 | 直播棋盘推演(`LiveRoom.mode=BOARD_CAST`, live.board-cast 消息); 老师边讲边摆 |
| W11 | 社交: 棋友关系(复用 ucm 画像扩展); 对战历史; 棋谱分享 |
| W12 | 数据看板(棋力增长曲线, 复用 sp02 BI); 季度能力反哺评审(对齐 SP-10 §12.2) |

### 9.5 跨阶段非功能保障(每阶段都必须满足)

| 维度 | 要求 | 对齐 |
|------|------|------|
| SLO | 对弈走子 P95 < 100ms; WS 消息 P99 < 500ms; 1w CCU(MVP) → 5w(Phase3) | G-01/G-03 |
| Cell 部署 | BU-BOARDGAME 独立 cell 集, user_id 分片 | D-083 |
| 数据隔离 | `boardgame_db` 逻辑库, 禁跨 BU JOIN | SP-10 §6.1 |
| 事件 | 全走 Pulsar `boardgame.*` namespace, Avro schema 注册 | SP-10 §5 |
| 监控 | 4 大盘(业务/SLO/资源/事件) + 告警规则 | SP-10 §10 |
| 安全 | 棋谱防作弊(走子频率/异常模式检测); 未成年人保护(若涉及) | S5/S6 |

---

## 附录 A: 与顶层架构追溯矩阵

| 本文章节 | 上游依据 | 关键铁律 |
|---------|---------|---------|
| §1 需求理解 | EP-TOGAF-001 §8/§9, SP-10 §1 | D-001 四大中台 |
| §2 能力盘点 | 现网源码核查(sp03/sp05/gateway-go/bff-java) | D-103 中台评估 |
| §3 结合点分析 | (本文原创, 棋类教育领域知识) | - |
| §4 限界上下文 | SP-10 §4 DDD 边界 | DDD 限界上下文 |
| §5 复用矩阵 | SP-10 §1.3 复用矩阵 | ADR-BU01 中台优先 |
| §6 领域模型 | sp03 Course/sp05 Assignment 现有模型, D-066 充血 | DDD 充血 + 事件 |
| §7 WS 协议 | gateway-go frame.go/hub.go 现有结构 | D-029 跨 pod 广播 |
| §8 技术选型 | Iron Law 8(≥3 方案) | D-032 技术栈 |
| §9 路线图 | SP-10 §3 七步启动, §11 检查清单 | Cell-based / 灰度 |

## 附录 B: ADR 索引

| ADR ID | 标题 | 决策 | 依据 |
|--------|------|------|------|
| ADR-BG01 | 教育优先原则 | 教育基座(课程/直播/作业/进度)必须先打通, 对弈后置 | §1.2 |
| ADR-BG02 | 对弈教学化 | 每场 GameMatch 可绑定 courseId/lessonId/assignmentId | §1.2 / §3.3 |
| ADR-BG03 | Course 扩展用判别字段 | `Course.boardGameType` 可空字段而非继承, 与 D-100 一致 | §6.2.1 |
| ADR-BG04 | LessonType 枚举扩展 | 在 video/live/coding/quiz/reading 上加 tsumego/review/match | §6.2.2 |
| ADR-BG05 | 规则引擎用 Go | 性能 + D-032 合规 + 与 gateway-go 同栈; Rust 否决(不在栈) | §8.1 |
| ADR-BG06 | WS 网关扩展现有 gateway-go | 复用 Hub/Frame/JWT 底座, 不重建 | §8.2 |
| ADR-BG07 | AI 棋力引擎用现成 C/C++ sidecar | 不自研 AI, 调 Stockfish/KataGo | §8.1 |
| ADR-BG08 | 三类房间共享 WS | 直播/对弈/复盘用 type 前缀隔离, 共享 gateway-go | §3.4 / §7 |
| ADR-BG09 | ELO 用标准公式(K=32) | 国际象棋标准, 围棋段位由 ELO 换算 | §6.3.4 |
| ADR-BG10 | GameRoom 状态机 7 态 | CREATED→WAITING→READY→PLAYING→FINISHING→FINISHED + ABORTED | §6.3.1 |

---

> 文档结束。本卷为 BU-BOARDGAME 的架构设计蓝图, 遵循 SP-10 BU 复制方法论, 以"教育平台 + 对弈增强"为核心定位。后续实施以本文 §9 路线图为准, 每阶段交付后回灌经验更新本文档(活文档), 重大变更走 ADR 流程(附录 B)。
