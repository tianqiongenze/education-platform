# SP-05 IT 后端 MVP 软件需求规格说明书（SRS）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-05-SRS | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | 顶层蓝图 EP-SPEC-001 §7 + 决策 D-021..D-026 |

> 本 SRS 兑现深度：IT 后端 MVP 全部学员/教师功能需求、4 子平台（直播/在线编程/作业/评分）功能需求清单、三端需求差异、非功能需求（G-01/G-05/G-10）、用户故事、约束与 MVP 7 项验收标准。承接顶层 SAD ADR-I01..I05。

---

## 1. 引言

### 1.1 目的
本文档定义 education-platform 项目群中 **SP-05 IT 后端 MVP 事业群** 的功能、非功能需求与验收标准，作为 SAD/SDD/测试/部署文档的上游输入，支撑 SLO G-01（1 万 CCU）、G-05（直播延迟 <800ms）、G-10（AI 推理 <3s）的 MVP 阶段目标达成。MVP 的核心目标不是冲天花板，而是**端到端验证整套架构能跑通**。

### 1.2 范围
SP-05 MVP In Scope：
- 学员：注册登录/选课购课/视频学习/代码作业提交+评测/考试/直播+互动/答疑
- 教师：开课/备课/批改（含 LLM 辅助）/直播/学情看板
- 三端：web(React)/h5/小程序，共 API
- 全链路：三端→BFF→单元路由→用户域服务+4 子平台→中台→基础设施
- 4 子平台：直播平台 Live、在线编程 IDE、作业平台 Assignment、评分平台 Grading
- 验证目标：整套架构能跑通（G-01 M1=1 万 CCU）

Out of Scope（不在 MVP 范围）：
- 多区域异地多活上线（M2/M3 落地，MVP 仅单 cell 验证拓扑）
- 100 万并发/30 亿用户容量压测（M3）
- 客服工单/运营营销全功能（仅最小闭环）
- 数字人/学习路径 Agent 全功能（AI 中台 v2，M2）
- 海外集群独立部署（M2 合规评审后）

### 1.3 术语
| 术语 | 含义 |
|------|------|
| BFF | Backend For Frontend，面向前端的聚合网关 |
| CCU | Concurrent User，并发用户数 |
| WHIP | WebRTC HTTP Ingestion Protocol，WebRTC 推流协议 |
| LL-HLS | Low-Latency HLS，2-4s 延迟的分片流协议 |
| SFU | Selective Forwarding Unit，WebRTC 选择性转发单元 |
| WebContainer | StackBlitz 基于 WASM 的浏览器端运行时 |
| Firecracker | AWS 开源 microVM，125-200ms 启动 |
| judge0 | 开源代码评测引擎，REST+Redis worker 队列 |
| Yjs | CRDT 协作编辑库 |
| MinHash+LSH | 最小哈希+局部敏感哈希，O(n) 近重复分桶 |
| Dolos/JPlag | 基于 tree-sitter/GST 的代码查重工具 |
| Rubric | 评分量规，每步 1-2 分 |
| CoT | Chain of Thought，思维链推理 |
| self-consistency | k 采样多数表决降方差 |
| 单元 (Cell) | 单元化架构中按 user_id 哈希划分的独立服务+数据单元 |

### 1.4 依据
- 顶层蓝图 EP-SPEC-001 §7 IT 后端 MVP 事业群
- ADR-I01 直播两层混合+房间单 cell 化
- ADR-I02 沙箱按信任分级+IDE/Judge 分后端
- ADR-I03 assignment-centric+两层查重+弱防御防作弊
- ADR-I04 分层沙箱评分 pipeline+LLM 异步批
- ADR-I05 LLM rubric 评分 human-in-the-loop
- 决策 D-021..D-026
- SLO G-01/G-03/G-04/G-05/G-10

---

## 2. 总体描述

### 2.1 系统定位
IT 后端 MVP 是项目群**第一个端到端打通的事业群**，验证三端→BFF→单元路由→用户域服务+4 子平台→中台→基础设施的完整链路。4 子平台是 IT 后端核心技术深度的集中体现：直播、在线编程、作业、评分。

### 2.2 用户角色与画像

| 角色 | 职责 | 关键诉求 |
|------|------|---------|
| 学员 | 注册/购课/学习/作业/考试/直播互动/答疑 | 三端一致体验、直播流畅、作业评测实时反馈、IDE 毫秒启动 |
| 教师 | 开课/备课/批改/直播/学情 | 开课低门槛、批改 LLM 辅助提效、学情看板实时、直播推流简单 |
| 教务管理员 | 课程审核/教师管理/数据看板 | 课程上架审核、教师资质管理、平台运营数据 |
| 平台运维 | 4 子平台+中台运维 | 沙箱/直播/录制资源可观测、单元故障可切换、灰度可回滚 |

### 2.3 约束

| ID | 约束 | 来源 |
|----|------|------|
| C-01 | 调中台不自建：用户/订单/课程/通知/搜索/风控全部调业务中台，不 fork | D-001 开放性、ADR-B01 |
| C-02 | 三端共 API：web/h5/小程序共享同一 OpenAPI 3 契约，差异在 BFF 聚合层 | EP-SPEC §2.1 |
| C-03 | 4 子平台不得绕过单元路由直连 DB，必须经 CellRouter 路由到 user 单元 | D-014、ADR-T01 |
| C-04 | 直播房间落单 cell，跨 cell 观众走 CDN 边缘，不跨 cell 拉媒体流 | ADR-I01 |
| C-05 | 评测 Judge 必须用 Firecracker microVM，不得用裸 Docker 跑不信任代码 | ADR-I02 |
| C-06 | 作业防作弊 AI flag 永不自动扣分，必须人工复核 | ADR-I03、ADR-I05 |
| C-07 | 评分 LLM 阶段（⑤）异步批，永不放同步请求路径 | ADR-I04 |
| C-08 | 代码查重 Tier1 MinHash+LSH 必须 O(n)，禁纯 pairwise | ADR-I03 |
| C-09 | LLM 评分必须 Rubric 分解+CoT+self-consistency k 采样 | ADR-I05 |
| C-10 | 三端前端共享组件库（React），小程序用 Taro 转译 | EP-SPEC §2.1 |
| C-11 | MVP 仅单 cell 验证拓扑，多 cell/多区域在 SP-07 | EP-SPEC §8 |
| C-12 | AI 网关 mock 模式支持压测/CI，不强依赖外部 LLM | D-018 |

### 2.4 假设与依赖
- 假设：SP-01 技术中台（网关/路由/读写分离/缓存/EDA/可观测）已交付 v1；SP-03 业务中台（UCM/OPM/CMP/NTF）已交付 v1；SP-04 AI 中台（网关+RAG+批改 Agent）已交付 v1；SP-06 三端前端骨架已交付；TiDB/Redis/Pulsar(CDC+事件统一)/Milvus/对象存储/K8s 基础设施可用。
- 依赖：SP-01 CellRouter、SP-02 CDC（学情入湖）、SP-03 课程目录/订单、SP-04 AI 网关、SP-06 前端骨架。

---

## 3. 学员功能需求

### 3.1 注册登录（FR-L01）
- FR-L01-01：学员可使用手机号+短信验证码注册账户，注册成功后自动登录并签发 JWT RS256。
- FR-L01-02：学员可使用微信/支付宝第三方登录（三端均支持，小程序原生）。
- FR-L01-03：登录态在三端共享，单账户最多 5 个并发会话，超限踢出最早会话。
- FR-L01-04：JWT 过期前 5 分钟前端静默刷新，刷新失败跳登录页。
- FR-L01-05：未实名学员可浏览免费课，购课/直播互动前强制实名认证。

### 3.2 选课购课（FR-L02）
- FR-L02-01：学员可按分类/关键词/讲师检索课程，搜索走业务中台 SRH。
- FR-L02-02：学员可查看课程详情页（大纲/讲师/试看/评价/价格）。
- FR-L02-03：学员可将课程加入购物车并下单，支付走业务中台 OPM，订单单元化。
- FR-L02-04：支付成功后自动开通学习权限，发 Pulsar 事件 `order.paid` → 学习记录域创建 Enrollment。
- FR-L02-05：学员可申请退款，退款经客服审批后撤销 Enrollment。

### 3.3 视频学习（FR-L03）
- FR-L03-01：学员可在课程详情页进入视频学习页，播放器支持清晰度切换/倍速(0.5-2.0)/章节跳转/书签。
- FR-L03-02：播放进度每 10 秒上报一次（Disruptor 批量 ingest），断点续播精确到秒。
- FR-L03-03：完成一个章节后自动标记进度，进度=已学章节时长/总时长。
- FR-L03-04：视频走对象存储+CDN 分发，首帧 <1s P95。
- FR-L03-05：学习行为事件（播放/暂停/跳转/答题）入数据中台，供学情分析。

### 3.4 代码作业提交+评测（FR-L04）
- FR-L04-01：学员可在作业列表查看待提交作业（截止时间/状态/分数）。
- FR-L04-02：学员可进入在线编程 IDE（Monaco+WebContainer）编写代码，支持多语言（Java/Python/Go/JS/C++）。
- FR-L04-03：学员在 IDE 内可"运行"代码（WebContainer 客户端算力，毫秒级）查看输出。
- FR-L04-04：学员可"提交"作业，提交后代码进入评测 Judge（Firecracker microVM 强隔离）。
- FR-L04-05：评测完成后学员实时看到 ①IO/单元测试结果（同步），LLM rubric 评分异步批返回（秒~十秒）。
- FR-L04-06：学员可查看历史提交（multi-attempt），每次提交独立评测+独立 Grade。
- FR-L04-07：学员可对评分结果发起申诉（Regrade），申诉触发教师人工复核。

### 3.5 考试（FR-L05）
- FR-L05-01：学员可在考试列表查看待考/已考考试（时间/时长/状态/成绩）。
- FR-L05-02：学员进入考试需通过身份核验（人脸/证件，AI proctoring 弱防御）。
- FR-L05-03：考试期间启用 browser lockdown（禁切屏/禁复制/禁新窗口），AI proctoring 仅 flag 不自动罚。
- FR-L05-04：考试题型支持单选/多选/判断/填空/编程题（编程题复用评测 Judge）。
- FR-L05-05：交卷后客观题即时出分，编程题走评测 pipeline，主观题走 LLM rubric+教师复核。
- FR-L05-06：考试异常（断网/崩溃）支持 5 分钟内重连续考，状态从 Redis 恢复。

### 3.6 直播+互动（FR-L06）
- FR-L06-01：学员可在直播列表查看进行中/预告/回放直播。
- FR-L06-02：学员进入直播间后，互动模式（≤几百观众）走 WebRTC LiveKit SFU（亚秒延迟），大众观看模式走 LL-HLS+CDN（2-4s 延迟）。
- FR-L06-03：学员可发送弹幕/聊天/参与投票/申请连麦，弹幕经 WS 网关+Redis 路由+Pulsar 削峰。
- FR-L06-04：学员连麦申请经教师审批后加入 WebRTC SFU 房间。
- FR-L06-05：直播结束后可观看回放（录制 FLV/MP4 落对象存储），回放支持时移/倍速。
- FR-L06-06：直播中断（网络/服务）客户端自动重连，3 次失败提示退出。

### 3.7 答疑（FR-L07）
- FR-L07-01：学员可在课程答疑区提问（文本/图片/代码片段）。
- FR-L07-02：AI 答疑 Agent（RAG 课程知识库）即时给出初筛回答，标注"AI 参考"。
- FR-L07-03：教师可回答学员问题，回答后学员收到通知（NTF）。
- FR-L07-04：学员可对回答点赞/采纳，被采纳回答置顶。
- FR-L07-05：高频问题自动沉淀到课程 FAQ 库（AI 中台抽取）。

---

## 4. 教师功能需求

### 4.1 开课（FR-T01）
- FR-T01-01：教师可创建课程（标题/分类/封面/简介/讲师/价格/难度），提交教务审核。
- FR-T01-02：审核通过后课程上架到课程目录（全局域）。
- FR-T01-03：教师可管理课程章节（视频上传/资料附件/作业关联/直播关联）。

### 4.2 备课（FR-T02）
- FR-T02-01：教师可上传视频/资料到对象存储，自动转码多清晰度。
- FR-T02-02：教师可创建作业（Assignment），配置题目/截止时间/补交宽限期/查重开关/评测配置。
- FR-T02-03：教师可创建考试，配置题型/时长/防作弊等级/评分规则。
- FR-T02-04：教师可创建直播（时间/时长/互动模式/连麦上限/录制开关）。

### 4.3 批改（含 LLM 辅助）（FR-T03）
- FR-T03-01：教师可在批改台查看待批改提交列表（按作业/学员/状态筛选）。
- FR-T03-02：客观题/IO 测试/静态/安全/Style 由评分 pipeline 自动评分，教师可见结果。
- FR-T03-03：LLM rubric 评分给出形成性反馈+初筛分数，教师可在其基础上调整。
- FR-T03-04：高风险评分（summativ/考试/申诉）教师必须人工签核。
- FR-T03-05：教师可批量操作（批量通过/批量退回/批量加标签）。
- FR-T03-06：查重 flag 在批改台高亮显示，教师复核后决定是否触发 regrade 工作流。

### 4.4 直播（FR-T04）
- FR-T04-01：教师可通过 OBS/ffmpeg 推流（WHIP/RTMP/SRT），平台提供推流地址+密钥。
- FR-T04-02：教师可在直播间审批连麦申请、禁言学员、发起投票。
- FR-T04-03：教师可在直播中共享屏幕/白板（WebRTC 数据通道）。
- FR-T04-04：直播结束自动停止推流+完成录制，录制回放自动上架到课程章节。

### 4.5 学情看板（FR-T05）
- FR-T05-01：教师可查看课程学情看板（ enrollment/活跃/完成率/作业提交率/平均分）。
- FR-T05-02：教师可查看单学员学情（学习时长/作业历史/考试成绩/答疑记录）。
- FR-T05-03：学情数据从数据中台 Iceberg 实时聚合（Flink 实时指标+Trino ad-hoc）。
- FR-T05-04：教师可导出学情报表（CSV/Excel）。

---

## 5. 4 子平台功能需求

### 5.1 直播平台 Live（FR-LIVE）

| ID | 功能需求 | 关联 ADR |
|----|---------|---------|
| FR-LIVE-01 | 支持教师通过 WHIP/RTMP/SRT 推流到 SRS origin cluster | ADR-I01 |
| FR-LIVE-02 | SRS origin 无状态代理+Redis 路由流定位，水平扩展 | ADR-I01 |
| FR-LIVE-03 | 转码（ffmpeg/NVENC）多清晰度，录制 FLV/MP4 落对象存储 | ADR-I01 |
| FR-LIVE-04 | 互动模式（连麦/小班）走 LiveKit SFU（WebRTC，亚秒，≤几百观众） | ADR-I01 |
| FR-LIVE-05 | 大众观看走 LL-HLS+CDN（2-4s 延迟，无上限观众） | ADR-I01 |
| FR-LIVE-06 | 直播房间（主播+SFU 状态）落单 cell，room→cell 一致性哈希 | ADR-I01 |
| FR-LIVE-07 | 跨 cell 观众走 CDN 边缘（cell-agnostic），不跨 cell 拉媒体流 | ADR-I01 |
| FR-LIVE-08 | 弹幕/聊天/连麦/投票走 WS 网关+Redis 会话路由+Pulsar 削峰 | ADR-I01 |
| FR-LIVE-09 | 直播间状态机：created→streaming→paused→ended→archived | ADR-I01 |
| FR-LIVE-10 | 录制回放支持时移/倍速/章节跳转 | ADR-I01 |
| FR-LIVE-11 | 直播中断客户端自动重连 3 次，cell 故障经路由器重连健康 cell | ADR-I01 |
| FR-LIVE-12 | 直播延迟 P95 <800ms（互动模式 WebRTC 端到端，G-05） | ADR-I01 |

### 5.2 在线编程 IDE（FR-IDE）

| ID | 功能需求 | 关联 ADR |
|----|---------|---------|
| FR-IDE-01 | 前端 Monaco editor + Language Server + Yjs CRDT 协作编辑 | ADR-I02 |
| FR-IDE-02 | 教学 IDE（信任/轻不信任代码）走 WebContainer（WASM，毫秒启动） | ADR-I02 |
| FR-IDE-03 | 评测 Judge（不信任代码）走 Firecracker microVM（125-200ms，预热池 5-30ms） | ADR-I02 |
| FR-IDE-04 | judge0 REST+Redis worker 队列调度评测任务 | ADR-I02 |
| FR-IDE-05 | SandboxWarmPool CRD 预热 snapshot 池，避免冷启动 | ADR-I02 |
| FR-IDE-06 | 评测资源限制：cpu/mem/time/net 严格隔离，千级 microVM/宿主 | ADR-I02 |
| FR-IDE-07 | GPU 作业（ML）走 Firecracker VFIO 穿透（gVisor 不支持） | ADR-I02 |
| FR-IDE-08 | IDE vs Judge 分后端：IDE 长生命工作区/协作；Judge 短生命/单次执行 | ADR-I02 |
| FR-IDE-09 | 协作编辑（结对编程）走 Yjs CRDT，离线优先 p2p | ADR-I02 |
| FR-IDE-10 | 支持语言：Java/Python/Go/JS/C++（评测），WebContainer 内多语言 | ADR-I02 |

### 5.3 作业平台 Assignment（FR-ASG）

| ID | 功能需求 | 关联 ADR |
|----|---------|---------|
| FR-ASG-01 | 数据模型 Canvas assignment-centric：Assignment→Submission→Grade→Regrade | ADR-I03 |
| FR-ASG-02 | Submission multi-attempt，每次提交独立 Grade | ADR-I03 |
| FR-ASG-03 | 状态机：unsubmitted→draft→submitted→graded→reviewed→archived | ADR-I03 |
| FR-ASG-04 | 截止时间/补交宽限期/草稿 vs 正式提交/Git 集成（项目作业） | ADR-I03 |
| FR-ASG-05 | 防作弊弱防御：browser lockdown+AI proctoring（假阳率 30-50%） | ADR-I03 |
| FR-ASG-06 | AI proctoring flag 仅标记，永不自动扣分，人工复核 | ADR-I03 |
| FR-ASG-07 | 代码查重 Tier1 MinHash+LSH O(n) 桶分近重复（可扩十亿级） | ADR-I03 |
| FR-ASG-08 | 代码查重 Tier2 桶内 Dolos(tree-sitter)/JPlag(GST) pairwise | ADR-I03 |
| FR-ASG-09 | MOSS 弃用（无 API/单批），embedding+向量库实验性暂不主用 | ADR-I03 |
| FR-ASG-10 | 提交完整性：时间戳+hash，否则 regrade 申诉可争议 | ADR-I03 |
| FR-ASG-11 | 教师可创建/发布/归档作业，配置查重开关/评测配置 | ADR-I03 |
| FR-ASG-12 | 学员可查看作业历史提交+每次成绩+申诉状态 | ADR-I03 |

### 5.4 评分平台 Grading（FR-GRD）

| ID | 功能需求 | 关联 ADR |
|----|---------|---------|
| FR-GRD-01 | 分层沙箱 pipeline（CodeGrade AutoTest 模式），每提交隔离容器跑 | ADR-I04 |
| FR-GRD-02 | ①IO/单元测试：实时、确定、sub-second~秒，唯一可信高风险 | ADR-I04 |
| FR-GRD-03 | ②静态分析 Semgrep（必含 X/必不含 AI 生成模式） | ADR-I04 |
| FR-GRD-04 | ③安全扫描 SAST/CodeQL | ADR-I04 |
| FR-GRD-05 | ④Style checkstyle/prettier | ADR-I04 |
| FR-GRD-06 | ⑤LLM rubric 评分异步批，CoT+self-consistency k 采样 | ADR-I04/I05 |
| FR-GRD-07 | 确定性阶段①-④同步实时；LLM 阶段⑤异步批永不放同步路径 | ADR-I04 |
| FR-GRD-08 | LLM Rubric 分解（每步 1-2 分，无部分分）+CoT 每准则推理后出分 | ADR-I05 |
| FR-GRD-09 | self-consistency k 采样多数表决降方差 | ADR-I05 |
| FR-GRD-10 | Leniency metric 监控模型严格度漂移 | ADR-I05 |
| FR-GRD-11 | 高风险人工复核（summativ/考试/申诉），LLM=形成性反馈+初筛 | ADR-I05 |
| FR-GRD-12 | AI 代码检测器=信号非证据，查重 flag≠零分，接 regrade/appeals | ADR-I05 |
| FR-GRD-13 | 成绩聚合：per-assignment→per-course→per-term→成绩单 | ADR-I04 |
| FR-GRD-14 | 加权方案/申诉 regrade 工作流 | ADR-I04 |

---

## 6. 三端需求差异

| 能力 | Web(React) | H5(React 移动优先) | 小程序(Taro) |
|------|-----------|------------------|-------------|
| 注册登录 | 手机+短信+第三方 | 手机+短信+第三方 | 微信原生登录+手机绑定 |
| 视频学习 | 完整播放器（清晰度/倍速/章节/书签） | 竖屏适配，基础播放（清晰度/倍速） | 原生 video 组件，基础播放 |
| 在线编程 IDE | Monaco 全功能+协作编辑+WebContainer | 只读代码查看+轻量编辑（无 WebContainer） | 只读代码查看（无 IDE 编辑） |
| 作业提交 | 完整 IDE 提交+文件上传 | 文件上传+轻量编辑提交 | 文件上传（小程序 chooseFile） |
| 直播观看 | WebRTC 互动+LL-HLS 大众 | LL-HLS 大众（互动走 H5 WebRTC 兼容） | LL-HLS+小程序 RTC 组件 |
| 弹幕互动 | 全功能（弹幕/聊天/连麦/投票） | 弹幕+聊天+投票（连麦受限） | 弹幕+聊天+投票（连麦受限） |
| 考试 | browser lockdown 全功能 | browser lockdown 受限（移动端） | 不支持考试（仅查看成绩） |
| 学情看板 | 教师完整看板 | 学员个人学情 | 学员个人学情 |
| 共享 API | 同一 OpenAPI 3 契约 | 同一 OpenAPI 3 契约 | 同一 OpenAPI 3 契约 |
| 差异层 | BFF 聚合层按端裁剪字段 | BFF 聚合层按端裁剪字段 | BFF 聚合层按端裁剪字段 |

**三端共 API 原则**（C-02）：所有端共享同一份 OpenAPI 3 契约，差异在 BFF 聚合层按端裁剪返回字段，不在服务层分支。前端共享 React 组件库（web/h5），小程序用 Taro 转译。

---

## 7. 非功能需求

### 7.1 性能（SLO 对齐）

| ID | 指标 | MVP 目标 | 测量方法 | 关联 SLO |
|----|------|---------|---------|---------|
| NFR-P01 | 峰值并发用户 | 10,000 CCU | 分布式 Locust（独立节点） | G-01 |
| NFR-P02 | 核心 API P95 | <200ms | Prometheus histogram | G-03 |
| NFR-P03 | 核心 API P99 | <500ms | Prometheus histogram | G-04 |
| NFR-P04 | 直播互动延迟 P95 | <800ms | WS 端到端压测 | G-05 |
| NFR-P05 | AI 推理 P95（批改/答疑） | <3s | AI 网关 trace+mock 模式压测 | G-10 |
| NFR-P06 | 视频/直播首帧 | <1s P95 | CDN 日志+前端埋点 | - |
| NFR-P07 | IDE 启动（WebContainer） | <500ms | 前端 Performance API | - |
| NFR-P08 | 评测 Judge 冷启动 | ≤200ms（预热池 5-30ms） | judge0 trace | - |
| NFR-P09 | 评测 pipeline 确定性阶段 | <10s P95 | pipeline trace | - |
| NFR-P10 | LLM rubric 评分 | <30s P95（异步批） | AI 网关 trace | - |

### 7.2 可用性
- NFR-A01：MVP 可用性 99.9%（G-07 M1 目标），核心链路 99.95%。
- NFR-A02：单 cell 故障切换 <60s（G-08 M1 目标），CellRouter 流量纠正恢复。
- NFR-A03：直播/SFU/Judge 单实例故障不影响其他房间/作业，隔离粒度=房间/提交。

### 7.3 安全
- NFR-S01：评测 Judge 必须强隔离（Firecracker microVM），通过沙箱逃逸安全测试。
- NFR-S02：JWT RS256+JWKS 签发验签，跨语言统一，禁明文 token。
- NFR-S03：AI 网关 mock 模式不泄漏真实 LLM 凭证。
- NFR-S04：作业防作弊数据（人脸/证件）加密存储，仅复核教师可见。
- NFR-S05：直播录制访问需签名 URL，防盗链。

### 7.4 可观测
- NFR-O01：W3C traceparent 全链路注入（三端→BFF→子平台→中台→基础设施）。
- NFR-O02：4 子平台关键指标埋点（直播延迟/SFU 房间数/Judge 队列/查重桶/LLM 评分延迟）。
- NFR-O03：双日志轨（业务日志+审计日志），审计日志（防作弊/评分/regrade）独立存储。

### 7.5 可维护性
- NFR-M01：4 子平台各自独立部署（K8s Deployment/StatefulSet），互不影响。
- NFR-M02：配置外置 Nacos，特性开关（直播/IDE/查重/LLM）可独立灰度。
- NFR-M03：CI/CD pipeline 覆盖三端+4 子平台+中台，Pact 契约测试 CI 硬门。

### 7.6 合规
- NFR-C01：MVP 单 cell 国内部署，海外集群在 M2 合规评审后。
- NFR-C02：学员数据按 user_id 单元化存储，不跨单元读写。
- NFR-C03：AI proctoring 数据采集需学员明示同意，可关闭。

---

## 8. 用户故事

### 8.1 学员故事
- **US-L01**：作为一名学员，我希望能在手机上随时随地学习课程视频，断网后能从断点续播，这样我能在通勤时高效学习。
- **US-L02**：作为一名学员，我希望在 IDE 中编写代码后能立即看到运行结果（毫秒级），提交后能实时看到测试用例通过情况，这样我能快速迭代。
- **US-L03**：作为一名学员，我希望直播互动延迟低（<800ms），弹幕能被老师看到，这样我有参与感。
- **US-L04**：作为一名学员，我希望作业评分有详细的 rubric 反馈（不只是对错），这样我知道哪里丢分、怎么改进。
- **US-L05**：作为一名学员，我希望对评分有异议时能发起申诉并得到人工复核，这样评分公平。

### 8.2 教师故事
- **US-T01**：作为一名教师，我希望 LLM 能帮我初筛代码作业并给出 rubric 反馈，我只需复核高风险部分，这样批改效率提升。
- **US-T02**：作为一名教师，我希望直播推流简单（OBS 一键推流），弹幕能高亮关键问题，这样直播体验好。
- **US-T03**：作为一名教师，我希望学情看板能实时看到哪些学员掉队、哪些题目错误率高，这样我能针对性干预。
- **US-T04**：作为一名教师，我希望查重 flag 能高亮显示但不自动零分，由我决定，这样避免误伤。

### 8.3 教务管理员故事
- **US-A01**：作为教务管理员，我希望课程上架有审核流程，教师资质可管理，这样平台内容质量可控。
- **US-A02**：作为教务管理员，我希望平台运营数据（DAU/MAU/课程销量/直播时长）实时可看，这样我能决策。

### 8.4 平台运维故事
- **US-O01**：作为运维，我希望 4 子平台资源（SFU/Judge/录制）可观测，单元故障可切换，这样我能保障 SLA。
- **US-O02**：作为运维，我希望特性开关能独立灰度（直播/IDE/查重/LLM），出问题能快速回滚，这样上线风险可控。

---

## 9. MVP 验收标准

### 9.1 验收项 1：三端核心流程跑通
- 三端（web/h5/小程序）完成 注册→选课→学习→作业→评测→出分→直播互动 全链路。
- 三端共享同一 API 契约，差异仅在 BFF 聚合层。
- 验收方式：端到端自动化测试（Playwright 三端）+ 人工冒烟。

### 9.2 验收项 2：4 子平台各自核心链路验证
- 直播：推流→转码→分发（WebRTC 互动+LL-HLS 大众）→录制回放 全链路，延迟 P95 <800ms（G-05）。
- 在线编程：IDE 启动（WebContainer <500ms）→运行→提交→评测 Judge（Firecracker）→结果返回 全链路。
- 作业：创建→发布→提交→状态机流转→查重（Tier1+Tier2）→评分→归档 全链路。
- 评分：分层 pipeline ①-⑤ 全链路，确定性阶段同步实时，LLM 阶段异步批。
- 验收方式：4 子平台各自端到端测试+性能基线。

### 9.3 验收项 3：单元路由+读写分离+多级缓存+EDA 链路验证
- 请求经 CellRouter 路由到正确 user 单元（含 misroute 纠正）。
- 读写分离 4 模式（READ/WRITE/READ+consistent/READ+tiflash）契约验证。
- 多级缓存 L1(Caffeine)+L2(Redis) 失效一致性验证（Pulsar 广播+延迟双删）。
- EDA：Pulsar 跨服务事件（order.paid→Enrollment）+ Disruptor 进程内削峰（学习事件 ingest）链路验证。
- 验收方式：链路 trace 可见+契约测试。

### 9.4 验收项 4：分布式 Locust 1 万 CCU 压测达标
- 分布式 Locust（独立节点，非单节点）压测 1 万 CCU（G-01 M1）。
- 含直播互动并发（WebRTC SFU 房间数）、Judge 沙箱并发（microVM 池）。
- 核心 API P95 <200ms（G-03）、P99 <500ms（G-04）。
- 验收方式：Locust 报告+Prometheus 指标。

### 9.5 验收项 5：Pact 契约+单元/集成/安全测试通过
- Pact 契约测试：三端↔BFF、BFF↔子平台、子平台↔中台 全链路契约 CI 硬门。
- 单元测试：公共库 ≥80% 覆盖，安全核心 100% CI 硬门。
- 集成测试：4 子平台+中台集成链路。
- 安全测试：含沙箱逃逸安全测试（Firecracker 隔离验证）。
- 验收方式：CI pipeline 全绿+安全测试报告。

### 9.6 验收项 6：可观测全链路 trace 可见
- W3C traceparent 从三端注入→BFF→子平台→中台→基础设施 全链路贯通。
- 4 子平台关键指标看板（直播延迟/SFU 房间数/Judge 队列/查重桶/LLM 评分延迟）。
- 双日志轨（业务+审计）独立存储可查。
- 验收方式：Tempo trace 查询+Grafana 看板。

### 9.7 验收项 7：AI 网关+LLM 评分链路验证
- AI 网关 mock 模式压测通过（不依赖外部 LLM，G-10 <3s）。
- LLM rubric 评分 CoT+self-consistency k 采样实现验证。
- Leniency metric 监控漂移验证。
- human-in-the-loop 高风险人工复核工作流验证。
- 验收方式：AI 网关 trace+LLM 评分一致性测试（self-consistency 方差）。

---

## 10. 需求追溯矩阵（摘要）

| 需求 ID | 来源 ADR/决策 | SLO | 验收项 |
|---------|-------------|-----|-------|
| FR-LIVE-12 | ADR-I01 | G-05 | 9.2 |
| FR-IDE-03/05 | ADR-I02 | NFR-P08 | 9.2 |
| FR-ASG-07/08 | ADR-I03 | - | 9.2 |
| FR-GRD-07 | ADR-I04 | NFR-P09/P10 | 9.2 |
| FR-GRD-09 | ADR-I05 | - | 9.7 |
| NFR-P01 | G-01 | G-01 | 9.4 |
| NFR-P04 | G-05 | G-05 | 9.2 |
| NFR-P05 | G-10 | G-10 | 9.7 |

---

## 11. 风险与开放问题

| ID | 风险/问题 | 缓解 |
|----|----------|------|
| R-SP05-01 | WebContainer 在低端移动端 H5 性能不足 | H5 仅只读代码查看，编辑提交走文件上传 |
| R-SP05-02 | 小程序不支持 browser lockdown 考试 | 小程序仅查看成绩，考试走 web/h5 |
| R-SP05-03 | Firecracker 预热池资源占用 | 按 Judge 并发预测池大小，闲时缩容 |
| R-SP05-04 | LLM 评分成本在压测下不可控 | AI 网关 mock 模式压测+语义缓存+模型路由 |
| R-SP05-05 | 直播 WebRTC SFU 在大规模互动成本爆炸 | 互动模式硬上限几百观众，超限降级 LL-HLS |
| R-SP05-06 | 单 cell 验证拓扑不等于多 cell 真异地多活 | MVP 仅验证拓扑，多 cell 在 SP-07 |
| O-SP05-01 | AI proctoring 供应商选型（自研 vs 采购）待定 | M2 评审 |
| O-SP05-02 | Dolos/JPlag 集成方式（CLI vs API）待 SDD 定 | SDD 阶段定 |

---

**下一步**：本 SRS 经评审通过后，作为 SP-05 SAD/SDD/测试/部署文档的上游输入。
