# SP-04 AI中台 软件需求规格说明书(SRS)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp04` for reference continuity. Per D-137, sp04 → ai-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-SRS | v1.0 | 2026-08-03 | 评审通过 | AI中台文档工程组 | 架构委员会 |

> 关联：项目群顶层 spec `2026-08-03-education-platform-blueprint-and-platforms-sad.md` §6
> 决策：D-010 / D-018 / D-019 / D-020；ADR-A01 / ADR-A02 / ADR-A03 / ADR-A04

## 1. 引言
### 1.1 目的
本文档对 SP-04 AI中台 的功能性需求与非功能性需求进行完整、可验证的描述，覆盖 4 层架构（AI应用层 / 模型网关 / RAG层 / 数据底座）及 5 个智能 Agent（批改 / 出题 / 答疑 / 学习路径 / 数字人）。文档面向架构评审、研发实现、测试验收三方。

### 1.2 范围
- **包含**：模型网关（路由 / 缓存 / 限流 / 计费 / mock）、RAG（混合检索 / 多租户隔离）、5 个 Agent、Agent 编排（LangGraph）、与数据中台 Feature Store 对接。
- **不包含**：模型训练全流程（属数据中台 + 算法组）、数字人音视频渲染底层（属业务中台直播能力）、海外独立集群合规审计（属安全合规组）。

### 1.3 术语
| 术语 | 含义 |
|------|------|
| FGAC | Fine-Grained Access Control，JWT 驱动的细粒度访问控制 |
| LangCache | Redis 语义缓存模块 |
| RouteLLM | 意图/难度分类器路由，将请求分流至高/低成本模型 |
| Cost Stack | 模型网关成本优化栈：cache→intent route→confidence cascade→fallback |
| HITL | Human-In-The-Loop，教师复核环节 |
| LLM_MODE=mock | 离线/CI 模式，网关返回固定桩数据，不调用真实 provider |

### 1.4 角色与涉众
| 角色 | 关注点 |
|------|--------|
| 学员 | 答疑响应快、批改反馈准、学习路径合理 |
| 教师 | 批改结果可复核、出题可控、不被 LLM 错误评分误导 |
| AI工程师 | Agent 可编排、模型可切换、评测可量化 |
| 平台运营 | 成本可控、缓存命中率高、多租户隔离无泄漏 |
| 安全合规 | 跨租户数据零泄漏、PIPL 出境合规 |

## 2. 总体需求

### 2.1 业务目标
- 为 IT后端 / IT前端 / 考公 / 考研 / 养生 五大事业群提供统一 AI 能力。
- 单次 LLM 调用成本较"直连旗舰模型"基线降低 ≥45%（ADR-A01）。
- RAG 跨租户泄漏事故 = 0（ADR-A02，#1 事故类防控）。
- IT 代码批改保持教师最终裁量权（ADR-A03，HITL）。

### 2.2 约束
| ID | 约束 | 来源 |
|----|------|------|
| C-01 | 复用集群已有 Milvus（知识库层）与图库，不得另起向量栈 | D-010 |
| C-02 | 多租户隔离 = namespace + pool + JWT 驱动 FGAC，**禁止 app 层拼接 tenant_id** | D-019 / ADR-A02 |
| C-03 | Python 服务占主体，主语言规范见 D-009 | D-009 |
| C-04 | LLM_MODE=mock 必须在 CI 中默认启用，真实 provider 调用需显式开关 | D-018 |
| C-05 | 复用 Redis Cluster（`zcode:session:*` 命名空间）做 LangCache 与 LangGraph checkpointer | D-006 |
| C-06 | 高风险评分必须教师复核，不得直接落库为最终分 | D-020 / ADR-A03 |
| C-07 | 国内异地多活 + 海外独立，AI 中台需按区域部署独立实例 | D-004 |

### 2.3 假设与依赖
- 数据中台 Feature Store 已就绪并按契约推送特征。
- 技术中台 K8s 集群提供 GPU 节点池（如需本地 rerank / 小模型）。
- Kong AI Gateway 企业版或 LiteLLM 0.x+ 可用。
- 各事业群业务中台已提供课程目录、作业、学情领域服务 API。

## 3. 功能需求

> 编号规则：FR-APPL-x（AI应用层）、FR-GW-x（模型网关）、FR-RAG-x（RAG层）、FR-DATA-x（数据底座）。

### 3.1 AI应用层 —— 批改 Agent（FR-APPL-1）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-APPL-1.1 | 接收作业提交（代码 / 文本 / 公式），按 rubric 分解子维度 | rubric 模板可配置，覆盖 IT 代码题、考公申论、考研论述 |
| FR-APPL-1.2 | LLM 形成性反馈：CoT 逐步推理 + self-consistency 采样 k=5 | 输出包含推理链 JSON，非纯分数 |
| FR-APPL-1.3 | 风险分级：低风险直接出分；中风险抽样复核；高风险（权重≥40% 或 MAE 预估>0.6）强制 HITL | 风险标签随结果落库 |
| FR-APPL-1.4 | 教师复核界面：可改分、可驳回重批、可标注 LLM 误判样本回流评测集 | 复核动作写入审计日志 |
| FR-APPL-1.5 | LangGraph 编排：interrupt_before(teacher_review)，Redis checkpointer 持久化，Command(resume) 恢复 | 重启 / 超时后状态可恢复 |

### 3.2 AI应用层 —— 出题 Agent（FR-APPL-2）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-APPL-2.1 | 按知识点 + 难度 + 题型（单选 / 多选 / 判断 / 简答 / 编程）生成题目 | 输出符合 QTI 3.0 结构 |
| FR-APPL-2.2 | 引用知识库来源（RAG），每题标注出处 chunk_id | 无来源题目标"低可信"并过滤 |
| FR-APPL-2.3 | 难度校准：IRT 3PL 模型预估 a/b/c 参数，难度偏差 |δ|<0.3 | 校准失败题进题库待审 |
| FR-APPL-2.4 | 去重：与题库相似度 >0.85 题目不外发 | 向量相似 + BM25 双重去重 |
| FR-APPL-2.5 | 教师审核工作流：批量通过 / 修改 / 拒绝 | 审核后题目录入正式题库 |

### 3.3 AI应用层 —— 答疑 Agent（FR-APPL-3）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-APPL-3.1 | 多轮对话，支持图片 / 公式 / 代码片段 | 上下文窗口 8k token，超出自动摘要压缩 |
| FR-APPL-3.2 | RAG 检索课程知识库，回答带引用 | 引用率 ≥95% 知识类问题 |
| FR-APPL-3.3 | 幻觉兜底：未知问题显式"我不确定"并转人工 | 拒答率纳入质量看板 |
| FR-APPL-3.4 | 安全过滤：政治 / 色情 / 暴力 / 作弊请求拦截 | 命中策略后返回标准话术 |
| FR-APPL-3.5 | 会话状态走 LangGraph checkpointer，跨设备续聊 | 切换设备上下文不丢失 |

### 3.4 AI应用层 —— 学习路径 Agent（FR-APPL-4）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-APPL-4.1 | 基于学情画像 + 知识图谱 + Feature Store 特征生成路径 | 路径节点 ≤10，含预估掌握度 |
| FR-APPL-4.2 | 动态调整：练习结果回流，路径每 N 次练习重算 | 重算触发阈值可配 |
| FR-APPL-4.3 | 多目标：应试 / 兴趣 / 补弱，目标可切换 | 目标变更 30s 内出新路径 |
| FR-APPL-4.4 | LangGraph 多步：画像聚合→图谱推理→路径生成→人工校验(可选) | 每步状态可观测 |

### 3.5 AI应用层 —— 数字人（FR-APPL-5）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-APPL-5.1 | 教师数字人直播辅讲：文本→口型→语音合成 | 端到端延迟 <1.5s |
| FR-APPL-5.2 | 表情 / 动作脚本可配置 | 脚本模板 ≥20 套 |
| FR-APPL-5.3 | 多语言：中 / 英 / 粤 | 切换 ≤2s |
| FR-APPL-5.4 | 与直播平台（SP 子项目）解耦，事件驱动 | 走业务中台事件总线 |

### 3.6 模型网关（FR-GW）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-GW-1 | 多 LLM 路由：按 provider / model / region 路由 | 路由表热更新，<5s 生效 |
| FR-GW-2 | 语义缓存（Redis LangCache）：相似 query 命中返回 | 命中率 ≥60%（ADR-A01 基线 60-73%） |
| FR-GW-3 | 意图路由（RouteLLM 类分类器）：简单→小模型，复杂→旗舰 | 路由准确率 ≥90%，成本降 ≥45% |
| FR-GW-4 | 置信度级联（confidence cascade）：低置信回退大模型 | 级联阈值可配 |
| FR-GW-5 | 多 provider fallback：provider A 失败自动切 B/C | RTO ≤3s，失败可观测 |
| FR-GW-6 | 限流：按 tenant / agent / model 三维限流 | 超限返回 429 + Retry-After |
| FR-GW-7 | 计费：每次调用记录 token / 成本 / 模型 / tenant | 计费日志写入 Iceberg，T+1 对账 |
| FR-GW-8 | LLM_MODE=mock：返回固定桩数据，支持脚本化响应 | CI 默认开启，无外部依赖 |
| FR-GW-9 | 成本栈执行顺序固定：cache→intent route→confidence cascade→fallback | 顺序不可配置（ADR-A01） |
| FR-GW-10 | 可观测：路由统计 / 延迟分位 / 缓存命中 / 成本看板 | Prometheus + Grafana |

### 3.7 RAG层（FR-RAG）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-RAG-1 | 混合检索：BM25 + 向量（Milvus）+ metadata 过滤 | 三路融合 RRF，召回@10 ≥0.85 |
| FR-RAG-2 | Reranker：cross-encoder 重排 top-50→top-10 | nDCG@10 提升 ≥0.08 |
| FR-RAG-3 | 多租户隔离：namespace + pool + JWT 驱动 FGAC | 跨租户查询 0 返回（渗透测试） |
| FR-RAG-4 | 租户小集合走 pgvector，知识库层走 Milvus 集群 | 路由透明，调用方无感 |
| FR-RAG-5 | 文档入库：上传→分块（语义分块 512±128）→向量化→索引 | 入库吞吐 ≥100 文档/s |
| FR-RAG-6 | 索引状态可观测：pending / indexing / ready / failed | 状态机持久化 |
| FR-RAG-7 | 检索测试控制台：输入 query 看召回 chunks 与分数 | 管理控制台集成 |
| FR-RAG-8 | 禁止 app 层拼接 tenant_id，统一由网关注入 | 代码静态扫描强制 |

### 3.8 数据底座（FR-DATA）
| 子需求 | 描述 | 验收要点 |
|--------|------|----------|
| FR-DATA-1 | Feature Store 对接：订阅数据中台特征 topic | 特征延迟 P95 <2s |
| FR-DATA-2 | Iceberg 训练数据沉淀：批改 / 出题 / 答疑样本落湖 | schema 含 tenant_id（FGAC）|
| FR-DATA-3 | 向量库与图库复用集群实例，不另建 | 资源池化 |
| FR-DATA-4 | 评测集管理：误判样本回流、版本化、A/B | 评测集 ≥1000 条/Agent |

## 4. 非功能需求

| ID | 指标 | 目标 | 测量方法 | 关联 |
|----|------|------|----------|------|
| NFR-01 | AI 调用 P95 延迟 | <3s（不含 HITL 等待） | 分布式 trace | G-10 |
| NFR-02 | 语义缓存命中率 | ≥60% | Prometheus counter | ADR-A01 |
| NFR-03 | 单调用成本降幅 | ≥45% vs 直连旗舰 | 计费日志对账 | ADR-A01 |
| NFR-04 | RAG 跨租户泄漏 | 0 次/年 | 渗透测试 + 审计 | ADR-A02 |
| NFR-05 | 批改 MAE（vs 教师） | <0.4（非高风险维度） | 评测集回归 | ADR-A03 |
| NFR-06 | 网关可用性 | 99.95% | 黑盒拨测 | — |
| NFR-07 | fallback RTO | ≤3s | 故障注入 | ADR-A01 |
| NFR-08 | mock 模式 CI 覆盖 | 100% 流水线默认 | CI 配置审计 | D-018 |
| NFR-09 | 限流准确率 | 100%（超限必拦截） | 压测 | — |
| NFR-10 | 索引入库吞吐 | ≥100 文档/s | 压测 | FR-RAG-5 |

## 5. 用户故事

### 5.1 学员
- US-S01：作为学员，我提交编程作业后 3s 内看到形成性反馈（非最终分），让我立即知道哪里错了。
- US-S02：作为学员，我在答疑中问"二分查找边界怎么写"，得到带课程章节引用的代码示例。
- US-S03：作为学员，系统根据我近 5 次练习错题，自动推送"补弱路径"，而非重复已会内容。

### 5.2 教师
- US-T01：作为教师，我打开批改复核台，看到 LLM 已对 200 份作业预评分，我只需复核被标"高风险"的 30 份。
- US-T02：作为教师，我指定"数据结构-链表-中等"生成 10 题，系统返回带难度预估与去重的题库待审。
- US-T03：作为教师，我能查看某题被 LLM 误判的样本，一键加入评测集。

### 5.3 AI工程师
- US-E01：作为 AI 工程师，我在管理控制台切换 provider A→B，5s 内全量生效。
- US-E02：作为 AI 工程师，我上传课程文档，看到分块 / 索引状态，并在检索测试台验证召回。
- US-E03：作为 AI 工程师，我在评测看板看到批改 Agent 本周 MAE 上升，触发回归排查。
- US-E04：作为 AI 工程师，CI 中 LLM_MODE=mock 默认开启，流水线无外部 API 依赖。

## 6. 验收标准

### 6.1 功能验收
- 5 个 Agent 各自端到端用例通过率 100%。
- 模型网关成本栈 4 阶段顺序执行，单元测试覆盖。
- RAG 多租户渗透测试：租户 A 持 A 的 JWT 查询，对 B 的 namespace 0 返回。
- LLM_MODE=mock 下全量回归用例通过。

### 6.2 非功能验收
- 压测：1000 QPS 网关，P95 <3s，缓存命中率 ≥60%。
- 故障注入：provider A 故障，3s 内 fallback 至 B，用户无感。
- 安全：跨租户渗透测试 0 泄漏；FGAC 绕过尝试 0 成功。
- 成本：连续 7 天对账，单调用成本降幅 ≥45%。

### 6.3 文档验收
- SRS / SAD / SDD / 测试方案 / 部署方案 / 管理控制台原型齐全且评审通过。
- 运维 runbook（LLM 降级 / 缓存失效 / 模型切换）可执行。

## 7. 风险与开放问题

| ID | 风险 | 缓解 |
|----|------|------|
| R-01 | LLM 评分 MAE 未达人类一致性（StepGrade 2025 MAE 0.44-0.62） | ADR-A03 HITL + 风险分级 |
| R-02 | 跨租户泄漏（#1 事故类） | ADR-A02 FGAC + 渗透测试 + 静态扫描禁 app 拼 tenant_id |
| R-03 | provider 单点故障 | 多 provider fallback + 健康探针 |
| R-04 | 缓存脏数据（知识库更新后旧缓存命中） | 版本 key + 主动失效 + TTL 兜底 |
| R-05 | GPU 资源争抢（本地 rerank） | 节点池隔离 + 限流 + 降级到 API rerank |
| O-01 | 数字人底层渲染归属是否归 AI 中台（待业务中台裁决） | 暂以事件解耦，后续治理 |
| O-02 | 海外集群 LLM provider 合规清单（PIPL/GDPR） | 安全合规组出清单后接入 |

## 8. 追溯矩阵（节选）

| 需求 | 决策/ADR | 测试项 | 部署项 |
|------|---------|--------|--------|
| FR-GW-2 缓存 | D-018 / ADR-A01 | 缓存命中测试 | Redis LangCache 配置 |
| FR-RAG-3 多租户 | D-019 / ADR-A02 | 渗透测试 | Milvus namespace 配置 |
| FR-APPL-1.5 LangGraph | ADR-A04 | 状态恢复测试 | Redis checkpointer |
| FR-APPL-1.3 HITL | D-020 / ADR-A03 | 复核流程测试 | 教师复核台 |
| FR-GW-8 mock | D-018 | mock 模式回归 | CI 环境变量 |

---

（全文完，共约 230 行有效内容）

---

## D-046: 智能体架构需求

### 功能需求

| 编号 | 需求 | 描述 | 验收标准 |
|------|------|------|---------|
| FR-AGT-01 | MCP 协议支持 | 平台支持 Model Context Protocol 接入外部工具/资源/Prompts，支持 stdio 与 Streamable HTTP 双传输，注册表 Redis 优先 YAML 兜底 | 可注册/注销/列举 MCP Server；可经 MCPClient 调用 Tools/Resources/Prompts |
| FR-AGT-02 | A2A 协议支持 | 支持 Agent-to-Agent Protocol，AgentCard 发布于 `/.well-known/agent.json`，Task 生命周期 6 态，JSON-RPC over HTTPS + SSE | Task 可在 submitted→working→input-required→completed 间正确流转；SSE 可订阅进度 |
| FR-AGT-03 | 分层多智能体图 | Supervisor + 5 业务 Agent（QA/Grading/Quiz/LearnPath/DigitalHuman）+ Debate 子图，子图作为节点嵌入父图，RedisSaver checkpointer | Supervisor 路由准确率 ≥90%；`_round_done` 保证图必然终止 |
| FR-AGT-04 | Reflexion 自反思 | 每个业务子图内置 evaluate→reflect→retry 循环，max_trials=3，阈值 0.7，episodic memory Redis TTL 30d | 反思后输出质量提升；达 max_trials 必然终止并返回 grounded answer |
| FR-AGT-05 | Harness 执行护栏 | AgentExecutor 超时 120s / 深度 50 / 试验 3；SandboxedToolNode 权限+注入清洗+预算+截断+审计；AuditLogger Redis Stream→Iceberg | 超限请求被拒绝；所有工具调用留审计记录；预算超额拒绝执行 |
| FR-AGT-06 | Hermes 事件编排 | EventBus 统一 pub/sub，Pulsar 3 重试 + DLQ，`PULSAR_ENABLED=false` 降级 InMemoryBus；EventRouter 规则 + Redis 热更 | 事件链 submission.created→grading.completed→quiz.generated→learnpath.updated 可端到端触发 |
| FR-AGT-07 | 插件系统 | PluginManifest 声明式注册，6 类 PluginPermission，tenant_whitelist 隔离，PluginSandbox 强制校验，向量检索发现 | 非白名单租户调用被拒；权限不足调用被拒；manifest 校验失败拒绝注册 |
| FR-AGT-08 | HITL 人工审核 | Grading/Quiz/LearnPath 子图设置 `interrupt_before` 暂停于 teacher_review/teacher_audit/review 节点，A2A Task 进入 input-required | 暂停后状态持久化；教师审核后可 resume；超时自动 reject |
| FR-AGT-09 | 多租户隔离 | 所有子系统（MCP/A2A/Harness/Hermes/Plugin/Memory）按 tenant_id 隔离，Redis 键含 `{tenant}` 段 | 跨租户访问被拒；租户间数据零泄漏 |
| FR-AGT-10 | 降级容错 | Redis 不可用→YAML 兜底（MCP/Plugin 注册表）；Pulsar 不可用→InMemoryBus 降级（Hermes）；RedisSaver 不可用→MemorySaver 兜底 | 故障注入下核心读路径不中断；降级触发告警 |

### 非功能需求

| 编号 | 需求 | 指标 |
|------|------|------|
| NFR-AGT-1 | 性能 | Agent 执行 P95 < 3s（不含 HITL 等待） |
| NFR-AGT-2 | 可用性 | 智能体层 99.9%，降级路径不丢事件 |
| NFR-AGT-3 | 安全 | 所有工具调用审计留痕；插件沙箱零逃逸 |
| NFR-AGT-4 | 可观测 | AuditLogger 端到端延迟 < 5s 落 Iceberg |

### 追溯矩阵

| 需求 | ADR | 测试项 | 部署项 |
|------|-----|--------|--------|
| FR-AGT-01 | ADR-A07 | MCP 注册/传输测试 | MCP_ENABLED 环境变量 |
| FR-AGT-02 | ADR-A08 | A2A Task 生命周期测试 | A2A_ENABLED |
| FR-AGT-03 | ADR-A09 | Supervisor 路由 + _round_done 测试 | REDIS_CHECKPOINTER_URL |
| FR-AGT-04 | ADR-A10 | Reflexion 收敛测试 | REFLEXION_MAX_TRIALS / THRESHOLD |
| FR-AGT-05 | ADR-A11 | Harness 限额 + 沙箱拒绝测试 | AUDIT_REDIS_STREAM |
| FR-AGT-06 | ADR-A12 | Hermes pub/sub + 降级测试 | PULSAR_ENABLED / HERMES_ENABLED |
| FR-AGT-07 | ADR-A13 | 插件 manifest + 白名单测试 | — |
| FR-AGT-08 | ADR-A09 | interrupt_before HITL 测试 | — |
| FR-AGT-09 | — | 多租户隔离渗透测试 | — |
| FR-AGT-10 | — | 降级容错故障注入测试 | — |

---

（D-046 章节完）

---

## §ep-memory 记忆需求 (D-045, FR-MEM-01..08)

> 关联：ADR-A05（L0~L3 四级架构）；ADR-A06（Mem0 集成）。

### 功能需求

| 编号 | 需求 | 描述 | 验收标准 |
|------|------|------|---------|
| FR-MEM-01 | L0 即时记忆 | Redis Cluster 存储当前会话最近 10 轮对话，key=`mem:imm:{tenant}:{user}:{session}`，TTL 24h，LPUSH+LTRIM 维持窗口 | 第 11 轮写入后 L0 仅保留最近 10 轮；24h 无更新自动淘汰；recall 优先返回 L0 |
| FR-MEM-02 | L1 工作记忆 | Redis Cluster 存储会话摘要 + 任务上下文，key=`mem:work:{tenant}:{user}`，TTL 7d；摘要经 Reflexion 评估阈值 0.7 | 会话切换时生成摘要写入 L1；7d 到期降级到 L2 后删除；L1 命中时 recall 含摘要 |
| FR-MEM-03 | L2 情景记忆 | Milvus collection `mem_episodic`（dim=1536, partition_key=tenant_id）存储用户对话片段向量化，Mem0 pipeline 提取，向量相似度 top-k 召回 | Mem0 client.add 写入成功；search 返回 top-k 相似片段；partition_key 隔离租户 |
| FR-MEM-04 | L2 语义记忆 | TiDB 表 `mem_semantic` 存储结构化事实（偏好/属性/已确认信息），含 confidence/status 字段，SQL 精确查询 | 事实提取写入成功；新旧冲突时旧值标记 superseded；status 30d→decayed/90d→archived |
| FR-MEM-05 | L2 程序记忆 | Neo4j 图 `mem_procedural` 存储实体-关系图谱（User/Course/Skill/Goal），Cypher 图遍历召回 | 实体关系正确抽取写入；图遍历返回关联实体；边权重 30d×0.5/90d 归档 |
| FR-MEM-06 | L3 组织记忆 | Milvus `mem_org` + Neo4j `mem_org_graph` 存储租户级共享知识，跨用户共享需显式授权，永不自动过期 | promote_to_org 需双人审核 + 审计；授权用户 recall 含 L3；未授权用户 L3 不可见 |
| FR-MEM-07 | 遗忘机制 | L0 TTL 24h；L1 7d 降级 L2；L2 30d 降权（score×0.5）/ 90d 归档（冷存）；L3 仅手动 | CronJob 每日扫描执行降级/降权/归档；归档项召回被 filter 排除 |
| FR-MEM-08 | 记忆 API | Python SDK（MemoryManager）+ Java SDK（gRPC stub→Dubbo 服务），暴露 remember/recall/forget/promote_to_org；REST 网关 `POST /v1/memory/*` | Python SDK 可直接调用；Java SDK 经 gRPC 调通；REST 端点鉴权 + 租户隔离 |

### 非功能需求

| 编号 | 需求 | 指标 |
|------|------|------|
| NFR-MEM-1 | 性能 | recall P95 < 200ms（L0/L1 命中）/ < 500ms（L2 三路 RRF） |
| NFR-MEM-2 | 可用性 | ep-memory 服务 99.9%；Redis 故障 L0/L1 降级 MemorySaver |
| NFR-MEM-3 | 安全 | L3 跨用户访问需显式授权；所有记忆操作审计落 Iceberg |
| NFR-MEM-4 | 扩展性 | L2 无状态可水平扩展；支撑 3 亿用户 / 千级租户 |

### 追溯矩阵

| 需求 | ADR | 测试项 | 部署项 |
|------|-----|--------|--------|
| FR-MEM-01 | ADR-A05 | L0 窗口管理测试 | REDIS_CLUSTER_URL |
| FR-MEM-02 | ADR-A05 | L1 摘要 + 降级测试 | — |
| FR-MEM-03 | ADR-A05/A06 | L2 Mem0 提取 + 向量检索测试 | MILVUS_COLLECTION_MEM |
| FR-MEM-04 | ADR-A05 | L2 语义事实冲突消解测试 | TIDB_DSN |
| FR-MEM-05 | ADR-A05/A06 | L2 程序图谱测试 | NEO4J_GRAPH_MEM |
| FR-MEM-06 | ADR-A05 | L3 共享 + 权限测试 | — |
| FR-MEM-07 | ADR-A05 | 遗忘机制 CronJob 测试 | MEM0_CONFIG_PATH |
| FR-MEM-08 | ADR-A06 | API + Java SDK gRPC 测试 | EP_MEMORY_GRPC_PORT |

---

（D-045 章节完）
