# SP-05 IT 后端 MVP 软件架构设计文档（SAD）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-05-SAD | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | 顶层蓝图 EP-SPEC-001 §7 + SP-05-SRS + 决策 D-021..D-026 |

> 本 SAD 兑现深度：IT 后端总体架构（三端→BFF→4 子平台→中台）、4 子平台各自架构图、ADR-I01..I05、三端共 API 设计、部署拓扑。对齐 TOGAF 四域（业务/数据/应用/技术）。

---

## 1. 引言

### 1.1 目的
本文档定义 SP-05 IT 后端 MVP 的架构设计，作为 SDD/测试/部署文档的上游输入。承接顶层 SAD §7 的 4 子平台 SAD 级描述，细化到可进入 SDD 的程度。

### 1.2 范围
- IT 后端总体架构（三端→BFF→单元路由→用户域服务+4 子平台→中台→基础设施）
- 4 子平台架构（直播/在线编程/作业/评分）
- ADR-I01..I05（已在顶层 SAD §7 给出，本文档补充上下文与影响）
- 三端共 API 设计（OpenAPI 3 契约）
- 部署拓扑（单 cell MVP，预留多 cell）

### 1.3 依据
- 顶层蓝图 EP-SPEC-001 §7 IT 后端 MVP 事业群
- SP-05-SRS v1.0
- ADR-I01..I05（详见顶层 SAD §7.3-§7.6）
- TOGAF 四域对齐（业务/数据/应用/技术）

---

## 2. TOGAF 四域对齐

| TOGAF 域 | IT 后端 MVP 对应 | 关键决策 |
|---------|----------------|---------|
| 业务架构 | 学员/教师/教务/运维 4 角色，学习/教学/直播/作业/考试/答疑 6 业务流 | SP-05-SRS §3-§5 |
| 数据架构 | 用户域单元化（TiDB 分片）+ 全局域共享 + 实时/湖仓（Iceberg）+ 向量库（Milvus 查重） | D-012/D-013/D-015、ADR-I03 |
| 应用架构 | 三端→BFF→CellRouter→用户域服务+4 子平台→中台，4 子平台独立部署 | ADR-I01..I05 |
| 技术架构 | K8s+TiDB+Redis+Pulsar(CDC+事件统一)+Milvus+对象存储+CDN+Firecracker CRD+SRS+LiveKit | EP-SPEC §3 |

---

## 3. IT 后端总体架构

### 3.1 总体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│  三端接入层                                                          │
│  web(React) │ h5(React 移动优先) │ 小程序(Taro)                      │
│  共享 OpenAPI 3 契约 + W3C traceparent 注入 + 共享 React 组件库       │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────┐
│  BFF / API 网关层（技术中台 SP-01）                                   │
│  Java(Netty edge+Disruptor+WebMVC) + Go WS 网关  ← D-032/ADR-T07(rev2)│
│  JWT RS256+JWKS │ CellRouter │ Sentinel 限流 │ 灰度 │ BFF 按端聚合    │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
┌───────▼────────┐         ┌───────▼───────┐         ┌────────▼────────┐
│  全局域服务     │         │  用户域服务    │         │  实时域服务      │
│  (课程/教师/    │         │  (账户/学习/   │         │  (直播/互动/     │
│   运营/工单)    │         │   订单/画像)   │         │   IM/弹幕)      │
│  共享+多副本读  │         │  强单元化      │         │  房间单 cell     │
└───────┬────────┘         └───────┬───────┘         └────────┬────────┘
        │                          │                          │
        │           ┌──────────────┴──────────────┐           │
        │           │                              │           │
        │   ┌───────▼────────┐            ┌───────▼───────┐   │
        │   │  在线编程 IDE   │            │  作业平台      │   │
        │   │  (用户域)       │            │  Assignment   │   │
        │   │  Rust+TS        │            │  (用户域)      │   │
        │   │  ADR-I02        │            │  Java          │   │
        │   └───────┬────────┘            └───────┬───────┘   │
        │           │                              │           │
        │   ┌───────▼──────────────────────────────▼───────┐   │
        │   │       评分平台 Grading（用户域+AI 中台）        │   │
        │   │       Java+Python(LLM) ADR-I04/I05            │   │
        │   └───────┬───────────────────────────────────────┘   │
        │           │                                           │
        │   ┌───────▼───────────────────────────────────────┐   │
        │   │       直播平台 Live（实时域）                    │   │
        │   │       Go(SFU/origin)+Java(房间管理) ADR-I01     │◄──┘
        │   └───────┬───────────────────────────────────────┘
        └───────────┼───────────────────────────────────────────┘
                    │
┌───────────────────▼───────────────────────────────────────────────┐
│              四大中台（共享底座，SP-01/02/03/04）                    │
│  技术中台 │ 数据中台(Flink+Iceberg) │ 业务中台(UCM/OPM/CMP/...) │   │
│  AI 中台(网关/RAG/批改 Agent)                                     │
└───────────────────┬───────────────────────────────────────────────┘
                    │
┌───────────────────▼───────────────────────────────────────────────┐
│  基础设施层（复用，不重建）                                          │
│  TiDB/TiFlash │ Redis Cluster │ Pulsar(CDC+事件) │ Milvus(查重) │
│  对象存储(S3/MinIO) │ K8s(Firecracker CRD) │ CDN │ SRS v7 │ LiveKit │
│  可观测(OTel+Loki+Prom+Tempo)                                     │
└───────────────────────────────────────────────────────────────────┘
```

### 3.2 请求路由流程

```
学员请求(JWT 含 user_id)
   │
   ▼
[三端] → [BFF: Netty edge+Disruptor+WebMVC]  ← D-032/ADR-T07(rev2)
   │   ① 验签 JWT RS256(JWKS)
   │   ② CellRouter: cell = hash(user_id) % N（路由表外置 Nacos）
   │   ③ Sentinel 限流(网关层)
   │   ④ BFF 聚合(按端裁剪字段)
   ▼
[用户域服务 / 实时域服务 / 4 子平台]
   │   ⑤ 服务层 Sentinel 限流(服务层)
   │   ⑥ 读写分离 4 模式(READ/WRITE/READ+consistent/READ+tiflash)
   │   ⑦ 多级缓存 L1(Caffeine)+L2(Redis)
   ▼
[中台 / 基础设施]
   │   ⑧ 业务中台(UCM/OPM/CMP/...)
   │   ⑨ 数据中台(Flink/Iceberg/CDC)
   │   ⑩ AI 中台(网关/RAG/Agent)
   │   ⑪ 基础设施(TiDB/Redis/Pulsar/...)
```

### 3.3 4 子平台总览

| 子平台 | 归属域 | 语言 | 复用基础设施 | 关键 ADR | MVP 核心链路 |
|--------|--------|------|-------------|---------|-------------|
| 直播平台 Live | 实时域 | Go(SFU/origin)+Java(房间管理) | Redis(路由)+对象存储(录制)+CDN | ADR-I01 | 推流→转码→分发→录制 |
| 在线编程 IDE | 用户域 | Rust(沙箱)+TS(前端) | K8s(Firecracker CRD)+Milvus(查重) | ADR-I02 | IDE 启动→运行→提交→评测 |
| 作业平台 Assignment | 用户域 | Java | TiDB+Redis+Pulsar | ADR-I03 | 创建→提交→查重→评分→归档 |
| 评分平台 Grading | 用户域+AI 中台 | Java+Python(LLM) | Firecracker+AI 网关+Iceberg | ADR-I04/I05 | 分层 pipeline ①-⑤→聚合 |

---

## 4. 直播平台 Live 架构（ADR-I01）

### 4.1 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│ 推流层                                                               │
│  OBS/ffmpeg → WHIP(WebRTC ingest) / RTMP / SRT                       │
│  教师端推流，平台提供推流地址+密钥                                     │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────┐
│ Origin 层                                                            │
│  SRS v7 origin cluster（无状态代理 + Redis 路由流定位）               │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                      │
│  │ SRS origin │  │ SRS origin │  │ SRS origin │  K8s Deployment      │
│  │  node-0    │  │  node-1    │  │  node-2    │  (无状态,水平扩展)    │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                      │
│        └────────────────┼────────────────┘                            │
│                         │ Redis: stream:{room_id} → origin_node       │
│  转码(ffmpeg/NVENC): source → 1080p/720p/480p/360p                    │
│  录制: FLV/MP4 → 对象存储(回放/时移)                                   │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
        ┌──────────────────────────┴──────────────────────────┐
        │                                                  │
┌───────▼────────────────────┐              ┌────────────────▼──────────────┐
│ 边缘分发：互动模式           │              │ 边缘分发：大众观看模式          │
│ LiveKit SFU(Go, WebRTC)     │              │ LL-HLS 经 CDN 边缘             │
│ 互动主播+连麦, ≤几百观众     │              │ 2-4s 延迟, 无上限观众           │
│ 亚秒延迟                     │              │ SRS edge cluster + Squid/ATS   │
│ 房间落单 cell(room→cell     │              │ (cell-agnostic, 跨 cell 观众)  │
│  一致性哈希)                 │              │                                │
└─────────────────────────────┘              └────────────────────────────────┘
        │                                                  │
        └──────────────────────────┬───────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────┐
│ 互动层                                                               │
│  弹幕/聊天/连麦/投票: WS 网关(技术中台 Go)                            │
│  + Redis(会话路由 fnv1a(session)%N) + Pulsar(削峰缓冲)               │
│  单节点 50 万连接, 集群百万级                                         │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 直播房间单 cell 化

```
直播房间路由:
  room_id → cell = consistent_hash(room_id) % N
  房间状态(主播+SFU+互动)落单 cell
  cell 故障仅影响本 cell 房间, 客户端经路由器重连健康 cell

跨 cell 观众:
  走 CDN 边缘(cell-agnostic), 仅 CDN 成本
  不跨 cell 拉媒体流(避免跨 cell 带宽/延迟)

房间状态机:
  created → streaming → paused → ended → archived
```

### 4.3 ADR-I01 上下文与影响

**ADR-I01 直播两层混合 + 房间单 cell 化**
- 背景：WebRTC 大规模成本爆炸（百万观众不可行），纯 HLS 延迟高（>5s）无法满足互动。
- 决策：互动（主播+连麦）走 WebRTC SFU（LiveKit）≤几百观众；被动大众走 LL-HLS+CDN 无上限；房间落单 cell；跨 cell 观众走 CDN 边缘。
- 影响：LiveKit SFU 部署需 GPU/高带宽节点；SRS origin 无状态可 K8s Deployment；CDN 配置 LL-HLS 切片（CMAF/部分分片）。
- 依据：Mux LL-HLS 指南、SRS v7 origin/edge 文档、AWS cell-based 架构。

---

## 5. 在线编程 IDE 架构（ADR-I02）

### 5.1 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│ 前端                                                                 │
│  Monaco editor + Language Server(LSP) + Yjs(CRDT 协作编辑)          │
│  ┌─────────────────────────────────────────────────────┐            │
│  │  Monaco Editor  │  Yjs Doc(CRDT)  │  LSP Client     │            │
│  └─────────────────────────────────────────────────────┘            │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
        ┌──────────────────────────┴──────────────────────────┐
        │                                                  │
┌───────▼────────────────────────┐              ┌────────────▼──────────────┐
│ 教学 IDE（信任/轻不信任代码）    │              │ 评测 Judge（不信任代码）    │
│ WebContainer(StackBlitz, WASM) │              │ Firecracker microVM        │
│ 客户端算力, 毫秒启动             │              │ 125-200ms 启动             │
│ 适用: 练习/教学/协作            │              │ 预热池 snapshot 5-30ms     │
│ 盲区: 无原生二进制/原始TCP/GPU  │              │ judge0(REST+Redis 队列)    │
│                                │              │ 资源限制: cpu/mem/time/net │
│ 长生命工作区/协作/弱隔离可接受  │              │ 千级 microVM/宿主          │
│                                │              │ GPU(ML): VFIO 穿透          │
│                                │              │ 短生命/单次执行/最强隔离    │
└────────────────────────────────┘              └────────────┬──────────────┘
                                                             │
                                              ┌──────────────▼──────────────┐
                                              │ SandboxWarmPool CRD         │
                                              │ 预热 snapshot 池             │
                                              │ 避免冷启动 UX 杀手           │
                                              └─────────────────────────────┘
```

### 5.2 IDE vs Judge 分后端

```
教学 IDE 后端:
  - 长生命工作区(学员可跨会话恢复)
  - 协作编辑(Yjs CRDT, p2p)
  - 弱隔离可接受(信任/轻不信任代码)
  - SLA: 启动 <500ms, 运行 <100ms

评测 Judge 后端:
  - 短生命(单次执行后销毁)
  - 单次执行(无状态)
  - 最强隔离(Firecracker microVM)
  - SLA: 冷启动 ≤200ms(预热池 5-30ms), 评测 <10s P95
```

### 5.3 ADR-I02 上下文与影响

**ADR-I02 沙箱后端按信任分级 + IDE/Judge 分后端**
- 背景：Docker 单独不够安全（多租户不信任代码）；gVisor 不支持 GPU 穿透；WebContainer 仅适合教学（无原生二进制）。
- 决策：教学 IDE=WebContainer（WASM，客户端算力，毫秒启动）；评测 Judge=Firecracker microVM+judge0 队列；IDE vs Judge 分后端（SLA 不同）；协作=Yjs CRDT；冷启动用 SandboxWarmPool CRD 预热池。
- 影响：Firecracker 需 K8s CRD 管理；judge0 worker 队列需 Redis；Yjs 需 WebSocket 网关（技术中台复用）。
- 依据：Spheron 沙箱对比、WebContainer 官方、OSDI'25 Fork-in-the-Road。

---

## 6. 作业平台 Assignment 架构（ADR-I03）

### 6.1 架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│ 数据模型（Canvas assignment-centric）                                │
│  Assignment → Submission(multi-attempt) → Grade → Regrade           │
│  状态机: unsubmitted→draft→submitted→graded→reviewed→archived       │
└─────────────────────────────────────────────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
┌───────▼────────┐    ┌────────────▼───────────┐    ┌─────────▼──────────┐
│ 生命周期管理    │    │ 防作弊（弱防御纵深）    │    │ 代码查重（两层）    │
│ 教师创建→发布→ │    │ browser lockdown       │    │ Tier1: MinHash+LSH  │
│ 学员提交(多次)→│    │ + AI proctoring        │    │  O(n) 桶分近重复    │
│ 评分→复核→归档 │    │ 假阳率 30-50%          │    │  (可扩十亿级)       │
│ 截止/补交/宽限 │    │ → AI flag + 人工复核   │    │ Tier2: 桶内 Dolos/  │
│ 草稿vs正式     │    │ 永不自动罚             │    │  JPlag pairwise     │
│ Git 集成       │    │                        │    │ MOSS 弃用(无 API)   │
└────────────────┘    └────────────────────────┘    └────────────────────┘
        │                          │                          │
        └──────────────────────────┼──────────────────────────┘
                                   │
                                   ▼
                          [评分平台 Grading]
```

### 6.2 代码查重两层架构

```
提交代码提交
   │
   ▼
[Tier1: MinHash+LSH 分桶]  O(n)
   │  对每份代码: tokenize → shingle → MinHash(k 个哈希) → LSH 桶
   │  落同桶 = 近重复候选
   ▼
[Tier2: 桶内 pairwise 精查]  O(桶内 n²)
   │  Dolos(tree-sitter AST) / JPlag(GST)
   │  输出相似度矩阵 + 高亮 diff
   ▼
[查重 flag → 作业平台]
   flag ≠ 零分, 接 regrade/appeals 工作流, 人工签核
```

### 6.3 ADR-I03 上下文与影响

**ADR-I03 assignment-centric + 两层查重 + 弱防御防作弊**
- 背景：Moodle 旧的 per-activity-table 模型扩展性差；纯 pairwise O(n²) 百万提交不可行；MOSS 无 API/单批不可集成；纯 AI 监考假阳率 30-50% 太高。
- 决策：Canvas assignment-centric 数据模型（Assignment→Submission→Grade→Regrade 一等实体）；代码查重两层（Tier1 MinHash+LSH O(n)→Tier2 Dolos/JPlag 桶内 pairwise）；防作弊弱防御（AI flag 仅标记不自动扣分，人工复核）；提交完整性（时间戳+hash）。
- 影响：MinHash+LSH 需 Milvus（集群已有）；Dolos/JPlag 以 CLI/API 集成；AI proctoring 数据加密存储。
- 依据：Canvas 数据模型、Dolos/JPlag、Milvus MinHash-LSH、proctoring 同行评议。

---

## 7. 评分平台 Grading 架构（ADR-I04/I05）

### 7.1 分层沙箱 pipeline 架构

```
提交代码（来自作业平台/考试）
   │
   ▼
[Firecracker microVM 隔离容器]（每提交隔离,CodeGrade AutoTest 模式）
   │
   ├─① IO/单元测试      实时, 确定, sub-second~秒   ← 唯一可信高风险
   │   输入: 测试用例    输出: pass/fail + 覆盖率    超时: 30s
   │
   ├─② 静态分析          Semgrep
   │   输入: 源码         输出: 规则违规列表          超时: 10s
   │   (必含 X / 必不含 AI 生成模式)
   │
   ├─③ 安全扫描          SAST / CodeQL
   │   输入: 源码         输出: 漏洞列表              超时: 30s
   │
   ├─④ Style             checkstyle / prettier
   │   输入: 源码         输出: 风格违规列表          超时: 10s
   │
   └─⑤ LLM rubric 评分   异步批, CoT+self-consistency
       输入: 源码+rubric  输出: 分数+形成性反馈       超时: 60s/批
       (human-in-the-loop 高风险, 继承 D-020)
       │
       ▼
[确定性阶段①-④: 同步实时返回]
[LLM 阶段⑤: 异步批, 永不放同步路径]
   │
   ▼
[成绩聚合]
   per-assignment → per-course → per-term → 成绩单
   加权方案 / 申诉 regrade 工作流 / 查重 flag 接入(不自动零分)
```

### 7.2 LLM rubric 评分优化架构

```
[源码 + Rubric(每步 1-2 分, 无部分分)]
   │
   ▼
[CoT 推理]  每准则推理后再出分(Rubric Is All You Need 2025)
   │
   ▼
[self-consistency k 采样]  k 次采样多数表决降方差
   │  (单 shot 噪声大, "Towards Reliable LLM Grading" 2025)
   ▼
[Leniency metric 监控]  模型严格度漂移告警
   │
   ▼
[human-in-the-loop]
   LLM = 形成性反馈 + 初筛
   summativ/高风险 → 教师定(继承 D-020)
   AI 代码检测器 = 信号非证据, 查重 flag ≠ 零分
```

### 7.3 ADR-I04/I05 上下文与影响

**ADR-I04 分层沙箱评分 pipeline + LLM 异步批**
- 背景：LLM 未达人类一致性（StepGrade 2025 MAE 0.44-0.62），放同步路径延迟/成本爆炸。
- 决策：确定性阶段①-④同步实时（IO/单元是唯一可信高风险）；LLM 阶段⑤异步批永不放同步路径；CodeGrade AutoTest V2 块式声明 pipeline 是参考架构。
- 影响：Firecracker 共享作业平台 SandboxWarmPool；AI 网关 mock 模式支持压测；LLM 评分走 AI 网关+模型路由。

**ADR-I05 LLM rubric 评分 human-in-the-loop（细化 D-020）**
- 背景：LLM 单 shot 评分噪声大；AI 代码检测器准确率争议；纯自动罚易误伤。
- 决策：Rubric 分解（每步 1-2 分，无部分分）+CoT（每准则推理后出分）；self-consistency k 采样多数表决；高风险人工复核；AI 检测器=信号非证据；查重 flag≠零分接 regrade/appeals。
- 影响：LLM 评分需 k 倍推理成本（AI 网关成本栈缓解：cache→route→cascade→fallback）；regrade 工作流需教师签核。
- 依据：Rubric Is All You Need(arXiv 2503.23989)、self-consistency 论文。

---

## 8. 三端共 API 设计

### 8.1 OpenAPI 3 契约原则

```
三端共享同一份 OpenAPI 3 契约(sp05-api.yaml)
差异在 BFF 聚合层按端裁剪返回字段, 不在服务层分支

契约结构:
  /api/v1/...
    paths: 三端共用
    components/schemas: 共用
    x-end-variants: 按端裁剪(web/h5/miniprogram)

BFF 聚合层(按端):
  - web: 全字段返回
  - h5: 裁剪大字段(如 IDE 代码), 移动端友好
  - miniprogram: 裁剪不支持能力(如 IDE 编辑), 仅查看

契约测试:
  - Pact 契约测试(三端↔BFF, BFF↔子平台, 子平台↔中台)
  - CI 硬门: 契约不兼容禁止合并
```

### 8.2 核心 API 摘要

| 域 | API | 方法 | 说明 |
|----|-----|------|------|
| 账户 | /api/v1/auth/register | POST | 注册（手机+短信） |
| 账户 | /api/v1/auth/login | POST | 登录（JWT RS256） |
| 课程 | /api/v1/courses | GET | 课程列表（走 SRH） |
| 课程 | /api/v1/courses/{id} | GET | 课程详情 |
| 订单 | /api/v1/orders | POST | 下单（走 OPM） |
| 学习 | /api/v1/progress/report | POST | 上报进度（Disruptor） |
| 作业 | /api/v1/assignments | GET/POST | 作业列表/创建 |
| 作业 | /api/v1/submissions | POST | 提交作业 |
| 评测 | /api/v1/judge/run | POST | 运行代码（WebContainer/Judge） |
| 评测 | /api/v1/judge/submit | POST | 提交评测（Firecracker） |
| 评分 | /api/v1/grades/{submissionId} | GET | 查询评分 |
| 评分 | /api/v1/regrades | POST | 发起申诉 |
| 直播 | /api/v1/live/rooms | POST | 创建直播间 |
| 直播 | /api/v1/live/rooms/{id}/ingest | GET | 获取推流地址 |
| 直播 | /api/v1/live/rooms/{id}/play | GET | 获取播放地址（WebRTC/LL-HLS） |
| 直播 | /ws/live/{roomId} | WS | 弹幕/聊天/连麦（WS 网关） |
| 答疑 | /api/v1/qanda/questions | POST | 提问（AI 答疑 Agent） |

---

## 9. 部署拓扑（单 cell MVP）

### 9.1 单 cell 部署拓扑

```
┌──────────────────────── 单 cell（MVP，预留多 cell）────────────────────┐
│                                                                       │
│  ┌────────────── K8s 集群 ──────────────────────────────────────┐     │
│  │                                                              │     │
│  │  ┌─ 三端前端 ─┐  ┌─ BFF ─────────┐  ┌─ CellRouter ─┐         │     │
│  │  │ web/nginx  │  │ Netty edge   │  │ (Spring)     │         │     │
│  │  │ h5/nginx   │  │ +WebMVC(Java)│  │ CellRouter   │         │     │
│  │  │ 小程序发布 │  │ WS网关(Go)   │  │              │         │     │
│  │  └────────────┘  └───────────────┘  └──────────────┘         │     │
│  │                                                              │     │
│  │  ┌─ 用户域服务 ─┐ ┌─ 4 子平台 ──────────────────────────┐    │     │
│  │  │ account     │ │ live-room(Java) │ live-srs(Go)       │    │     │
│  │  │ learning    │ │ live-sfu(Go,LiveKit) │ ide(TS+Rust)  │    │     │
│  │  │ order       │ │ assignment(Java) │ grading(Java+Py)  │    │     │
│  │  │ profile     │ │ judge0 + Firecracker CRD             │    │     │
│  │  └─────────────┘ └──────────────────────────────────────┘    │     │
│  │                                                              │     │
│  │  ┌─ 中台副本 ───────────────────────────────────────────┐    │     │
│  │  │ tech(网关/路由/缓存/EDA/可观测) │ data(Flink/Iceberg)│    │     │
│  │  │ business(UCM/OPM/CMP/NTF/SRH/RSK) │ ai(网关/RAG/Agent)│   │     │
│  │  └──────────────────────────────────────────────────────┘    │     │
│  └──────────────────────────────────────────────────────────────┘     │
│                                                                       │
│  ┌─ 基础设施（复用）──────────────────────────────────────────┐       │
│  │ TiDB(分片0) │ Redis Cluster(分片0) │ Pulsar(ns0)          │       │
│  │ Pulsar(CDC+事件) │ Milvus(查重) │ 对象存储(MinIO/S3)           │       │
│  │ CDN(LL-HLS) │ SRS v7 origin/edge │ LiveKit SFU            │       │
│  │ OTel+Loki+Prom+Tempo(可观测)                              │       │
│  └────────────────────────────────────────────────────────────┘       │
└───────────────────────────────────────────────────────────────────────┘

多 cell 预留(SP-07):
  - CellRouter 路由表外置 Nacos, N 可调
  - 用户域服务/中台副本/基础设施分片按 cell 部署
  - MVP 仅验证拓扑, 多 cell 在 SP-07 落地
```

### 9.2 部署策略
- 三端前端：web/h5 走 nginx 静态托管，小程序走微信发布。
- BFF/CellRouter/用户域服务/中台：K8s Deployment + HPA（autoscaling/v2，scaleDown 120s/25% 防抖）。
- 直播：SRS origin/edge K8s Deployment（无状态）；LiveKit SFU StatefulSet（有状态，需 GPU/高带宽节点）。
- 在线编程：Firecracker K8s CRD（SandboxWarmPool）；judge0 Deployment+Redis worker 队列。
- 作业/评分：Java 服务 Deployment；评分 Python(LLM) Deployment；Firecracker 共享 SandboxWarmPool。
- 灰度：特性开关（直播/IDE/查重/LLM）独立灰度，出问题快速回滚。

---

## 10. 架构决策追溯

| ADR | 决策 | 来源 | 影响子平台 |
|-----|------|------|-----------|
| ADR-I01 | 直播两层混合+房间单 cell 化 | D-021 | 直播 Live |
| ADR-I02 | 沙箱按信任分级+IDE/Judge 分后端 | D-022 | 在线编程 IDE |
| ADR-I03 | assignment-centric+两层查重+弱防御防作弊 | D-023/D-024 | 作业 Assignment |
| ADR-I04 | 分层沙箱评分 pipeline+LLM 异步批 | D-025 | 评分 Grading |
| ADR-I05 | LLM rubric 评分 human-in-the-loop | D-020/D-025 | 评分 Grading |

---

## 11. 风险与开放问题

| ID | 风险 | 缓解 |
|----|------|------|
| R-SAD-01 | WebRTC SFU 大规模成本爆炸 | 互动硬上限几百观众，超限降级 LL-HLS |
| R-SAD-02 | Firecracker CRD 在 K8s 成熟度 | MVP 单 cell 验证，多 cell 评估 |
| R-SAD-03 | LLM 评分成本 | AI 网关 mock+语义缓存+模型路由+k 采样成本控制 |
| R-SAD-04 | 三端共 API 在小程序受限 | BFF 按端裁剪，小程序仅查看不支持能力 |
| R-SAD-05 | 单 cell 不等于多 cell 真异地多活 | MVP 验证拓扑，多 cell 在 SP-07 |

---

**下一步**：本 SAD 经评审通过后，作为 SP-05 SDD 的上游输入。
