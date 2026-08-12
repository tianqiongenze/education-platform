# education-platform — 项目级工作约定

> 本文件是项目级长期记忆，叠加在 `~/.zcode/CLAUDE.md`(ZCode 全局铁律) 和 `~/.claude/CLAUDE.md`(用户角色档案) 之上。
> 优先级：项目级 > ZCode 全局 > 用户角色档案。冲突以本文件为准。

## 0. 项目一句话
education-platform: 大型在线教育公司多事业群平台项目群，4 大中台(技术/数据/业务/AI)共享底座 + 多事业群(IT后端 MVP 首发/IT前端/考公/考研/养生)，天花板 30 亿用户/100 万并发，国内异地多活+海外独立，TDD+DDD+EDA+读写分离，复用容器集群 TiDB/Redis Cluster/Pulsar/图库/向量库。

## 1. 当前阶段
- **第一批设计资料已完成**(2026-08-03)：顶层 TOGAF 企业架构 + 4 大中台(SP-01~SP-04) + IT 后端 MVP(SP-05) 全套 6 类文档(需求/架构/详细/原型/测试/部署)已产出并提交(commit ec67959)
- 3 个技术中台 SDK 已实现并通过测试：ep-rw-java(11 测试/97.7%)、ep-disruptor(9 测试/93%)、ep-cache(31 测试/95.8%)
- **下一批待产出**：考公/考研/养生 事业群(业务模型待用户输入)、SP-06~SP-10(前端/单元化/测试/扩容/事业群复制)
- SP-01 Task 4-7 代码(ep-api/bff-java/gateway-go/集成冒烟)暂停，设计文档已齐可随时恢复

## 2. 上下文工程铁律(继承全局，本项目强化)
- 会话状态外化：Redis Cluster 在线→`zcode:session:education-platform:*`；离线→`~/.zcode/sessions/education-platform/`
- 当前集群状态：**K8s 集群运行中**（Rancher Desktop k3s，节点 lima-rancher-desktop），但 education-platform 命名空间的 Pod 全是 nginx 占位镜像（local-verify 语法验证），**核心服务未真实部署运行**（Iron Law 9 违规）。基础设施（TiDB/Redis/Pulsar/Milvus）在共享命名空间 ekb-m2-aws-us-east-1 真实运行。D-140 正在修复部署阻断问题
- 决策/坑/进度持续写 `~/.zcode/sessions/education-platform/{decisions,pitfalls}.jsonl` + `active.json`
- 80% 上下文阈值主动 checkpoint + 压缩(继承全局铁律 0.2)

## 3. 关键决策快照(详见 spec §0)
- 根架构=混合单元化(用户域强单元化+全局域共享)，D-012
- 语言=Java主+Go/Python/Rust，D-009
- 数据中台=Flink+Iceberg 湖仓一体，D-013/D-016/D-017
- AI中台=全栈+human-in-the-loop代码批改，D-010/D-020
- 30亿/100万并发=天花板设计分阶段上线，D-005

## 4. 参考资料
- ESP 参考项目：`/Users/joelgong/claude-Projects/English-Study-Platform`(验证过的模式来源)
- spec 主文档：`docs/superpowers/specs/2026-08-03-education-platform-blueprint-and-platforms-sad.md`

## 5. 工作模式
- 推进优先：联网对齐最佳实践后连续推进，非关键分叉不每节停顿确认
- 诚实报告：测试失败/跳步/环境受限如实记录，不虚报
- 架构基线：按 10 亿/100 万并发工业级对标，方案经得起资深架构师拷问

## 6. 项目铁律 (Iron Laws)

> 铁律是项目群所有子项目、所有贡献者必须遵守的硬约束。违反即技术债，必须在 PR 中标注并限期偿还。

### 铁律 1 — 会话状态外化 (D-006)
所有会话状态写 Redis Cluster，禁止进程内 `ConcurrentHashMap` / `dict` 充当持久化存储。

### 铁律 2 — 读写分离 (ep-rw-java)
所有读流量走 TiFlash/只读副本，写流量走 TiDB 主库，强制走 `@ReadOnly` / `@WriteOnly` 注解。

### 铁律 3 — 缓存铁律 (D-033)
审计结果 / 热点数据先查 Redis，本地 YAML 兜底；禁止裸查 DB。

### 铁律 4 — 消息铁律 (Pulsar Avro)
所有跨服务消息走 Pulsar + Avro schema，禁止裸 JSON。

### 铁律 5 — 先调研后落地
所有新功能实现前，必须联网检索当前最佳实践与官方文档，禁止凭记忆编码。

### 铁律 6 — Dubbo + Spring Cloud Alibaba
RPC = Dubbo 3.3.x，注册中心 = Nacos 3.x，禁止 Spring Cloud Netflix 组件。

### 铁律 7 — 虚拟线程 + GraalVM AOT (D-039 / D-040)
Java 侧全量虚拟线程；面向 GraalVM Native Image，禁止反射、资源文件运行时加载。

### 铁律 8 — 决策对比分析 (D-048)
**所有技术决策在实施前必须完成 ≥3 方案对比分析**，产出内容至少包含：
1. 问题陈述与约束条件
2. ≥3 个候选方案（含"自研"时必须额外列官方/成熟开源方案）
3. 对比维度：成熟度 / 社区活跃度 / 与现有栈兼容性 / 性能 / 安全 / AOT 兼容 / 多租户隔离
4. 结论与理由，记入 `decisions.jsonl` 的 `alternatives` 字段
5. 若选定"自研"，必须论证为何无现成方案可用

**禁止行为**：
- 在 `pom.xml` / `pyproject.toml` 声明依赖却手写 stub 绕过（"声明即不使用"反模式）
- 用 `ConcurrentHashMap` / 内存 `dict` / 内存 `list` 充当数据库
- 用 `String.hashCode()` / 明文存储密码（必须 `BCryptPasswordEncoder`）
- 手写已存在官方 SDK / 标准协议实现（MCP / A2A / OAuth2 / JSON Schema 校验等）
- 手写已存在中间件内置能力（Pulsar DLQ / Sentinel 限流 / Redis Pub-Sub / MyBatis-Plus 租户拦截器）

**触发条件**：每次新增依赖、每次新建 Service/Controller、每次选型（库 / 框架 / 中间件 / 协议）。

**审计钩子**：CI 流水线静态扫描以下反模式，命中即失败：
- `ConcurrentHashMap` / `HashMap` 字段声明出现在 `*ServiceImpl` 中
- `hashCode()` 出现在密码相关字段
- `import com.fasterxml.jackson` + 手写 `JsonRpc` / `tools/call` 字样同文件
- `pom.xml` 声明 `sentinel` / `redisson` / `seata` 但 `*.java` 无对应 `import`

### 铁律 9 — 运行验证真相律 (D-140，最高优先级)
> **一个功能"完成"的唯一标准是：它在真实（非 mock）环境中运行并通过验证。制品存在（代码/YAML/文档/CI 配置）不等于功能完成。自评分数必须基于运行证据，不得基于文件存在。**

**根本动机**：在 AI 辅助开发时代，"制品存在"的欺骗性远超人类开发时代。AI 擅长生成"看起来完整"的制品（代码/YAML/文档/CI 配置），但制品存在 ≠ 系统能跑。Iron Law 1-8 保证了"方向正确、过程规范、文档齐全"，但没有任何一条保证"结果是真实的"。Iron Law 9 堵住这个裂缝。

#### 9.1 完成定义（Definition of Done）
```
功能完成 = 代码编写 + 真实环境部署运行 + 健康/功能验证通过 + 运行证据归档

        代码写了 ──✕──→ 不算完成
    代码 + YAML 写了 ──✕──→ 不算完成
代码 + YAML + 文档 ──✕──→ 不算完成
代码 + 部署运行 + 验证通过 + 证据 ──✓──→ 完成
```

#### 9.2 评分真实性规则
| 规则 | 要求 |
|------|------|
| R1 制品≠运行 | 评分证据必须区分"制品存在"(`📦 制品就绪`)和"运行验证"(`✅ 运行验证通过`)。两者不得混用 |
| R2 禁止 mock 满分 | 基于 mock/stub/inspect 的测试子项最高得 60% 分；真实环境验证才可得满分 |
| R3 运行证据强制 | 部署运维维度满分项必须附带 `kubectl get pods`(全部 Running) + `curl /health`(UP) 输出作为评分证据 |
| R4 分数降级条款 | 若核心服务未实际运行，部署运维维度封顶 6/12（C 级），不得给 A+ |
| R5 收官诚实律 | 任何决策声明"完成/收官/可上线"时，必须附带运行证据或明确标注"📦 制品就绪，未运行验证" |

#### 9.3 上线决策唯一性
```
上线就绪 = go-live-checklist 全部 [x] + production-readiness-report 前置条件全部 ✅ + 真实环境稳定运行

三个条件任一不满足 → 禁止声明"可上线"
不允许同一项目存在两个矛盾的上线结论（progress.md / production-readiness-report / go-live-checklist 必须一致）
```

#### 9.4 审计钩子（check-iron-law-9.py）
CI 流水线扫描以下反模式，命中即 **降级标注 + 警告**（首次过渡期为警告，后续升级为失败）：
- decisions.jsonl 中含"完成/收官/可上线/就绪"但 impact 字段无运行证据 → 标注 `[制品就绪-未运行验证]`
- architecture-scorecard.md 部署运维维度满分子项无 kubectl 证据 → 该子项封顶 60%
- production-readiness-report.md 上线结论与 go-live-checklist 勾选数不一致 → CRITICAL
- 同一项目存在两个不同上线结论 → CRITICAL

### 铁律 10 — 设计-实现可追溯性强制律 (D-196，最高优先级与铁律9并列)
> **每一份 SDD/SAD/ADR 设计章节，必须在代码中有可追溯的实现文件路径与之对应。设计文档中的每一处"简化实现/Phase 2/PROPOSED"必须登记为带截止日期的技术债 ADR。EP_DEV_ONLY 守卫必须关联一个"生产就绪"升级工单。已存在共享 SDK 时，禁止手写替代实现。功能"完成"的定义必须包含"设计章节对应代码已实现并通过测试"，而不仅仅是"有测试通过"。**

**根本动机**：Iron Law 1-9 保证了方向正确、过程规范、文档齐全、运行真实，但没有任何一条保证"设计文档中的每个决策都被代码兑现"。实践中，设计文档写了 GNN 字段血缘、ONNX Runtime 推理、Trino JDBC 执行，但代码里是正则解析器、EWMA 回归、5 条规则决策树——"简化版"成为永久事实标准，无人跟踪何时升级。共享 SDK（ep-cache）存在 7 个月，9 个服务中只有 1 个使用，其余手写 LRU+Redis 且有正确性 bug。5/7 SDD 没有"实现状态"章节，设计文档写完即冻结，失去对实现的约束力。铁律 10 堵住这个裂缝。

#### 10.1 设计-代码双向追溯
```
SDD/SAD/ADR 每个设计章节
  ↓ 必须标注
实现文件路径 (com.ep.xxx.YyyImpl.java / services/zzz/handler.go)
  ↓ 必须标注
对应测试文件 (YyyImplTest.java)
  ↓ 必须标注
设计章节编号 (SDD §3.2 / ADR-D07)
```
- 新建 SDD/SAD 时，每个设计章节末尾必须有 `> 实现文件: <路径>` 标注
- 已有 SDD/SAD 必须补充"实现状态"章节（5/7 SDD 当前缺失，限期补齐）
- 代码文件类注释必须引用设计章节编号：`// SDD §3.2 LiveRoom 状态机`

#### 10.2 简化实现技术债登记
- 设计文档中标注"简化实现/PARTIAL/Phase 2"的每一项，**必须**在 `docs/adr/` 中创建一个技术债 ADR：
  - ADR 标题：`D-xxx-<feature>-tech-debt.md`
  - 必须包含：当前简化实现描述、完整设计目标、差距分析、升级触发条件、目标截止日期
- 禁止"简化"变为永久——技术债 ADR 必须有具体的升级截止日期（最长 6 个月）
- `EP_DEV_ONLY` 守卫的每个使用点，必须关联一个 GitHub Issue 或 ADR，描述"何时移除此守卫"
- CI 静态扫描：`EP_DEV_ONLY` 出现在 `src/main/` 而非 `src/test/` 中 → 警告 + 要求关联 ADR 编号

#### 10.3 共享 SDK 强制使用
- 当项目中已存在共享 SDK（ep-cache, ep-rw-java, ep-disruptor, ep-go-common, ep-python-common）时：
  - **禁止**手写替代实现（手写 LRU + Redis、手写限流器、手写读写分离逻辑）
  - 新建服务必须在首个 PR 中接入相关共享 SDK
  - 已有服务有 1 个迭代周期的时间迁移到共享 SDK
  - 迁移完成前，必须在 PR 中标注技术债 ADR 编号
- CI 静态扫描：
  - `container/list` + `redis.Client` 同文件出现（Go 手写 LRU） → 警告：应使用 ep-common-go/cache
  - `ConcurrentHashMap` 字段声明出现在 `*ServiceImpl` 中且项目有 ep-cache 依赖 → 警告：应使用 CacheService
  - `functools.lru_cache` 出现在 `services/` Python 文件中 → 警告：应使用 ep-python-common cache

#### 10.4 设计合规性审计（每个版本必须执行）
- 每个版本发布前，必须执行设计合规性审计：
  1. 遍历所有 SDD/SAD/ADR 设计章节
  2. 对每个章节，验证"实现文件路径"指向的代码是否存在
  3. 对每个 PARTIAL/PROPOSED 标注，验证技术债 ADR 是否存在且未过期
  4. 对每个 EP_DEV_ONLY，验证关联的升级工单/ADR 是否存在
  5. 输出 `docs/audit/design-compliance-report.md`，列出所有不合规项
- 审计结果与 architecture-scorecard.md 挂钩：不合规项 > 5 → 架构评分降一级

#### 10.5 完成定义扩展（补充铁律 9.1）
```
功能完成 = 铁律 9.1 全部条件
         + 设计章节对应代码已实现（非简化版）
         + 简化实现已登记技术债 ADR 且有截止日期
         + 共享 SDK 已接入（如适用）
         + SDD/SAD "实现状态"章节已更新

  代码写了 + 测试通过 ──✕──→ 不算完成（可能只实现了简化版）
  代码 + 测试 + 设计章节标注 ──✕──→ 不算完成（可能缺技术债 ADR）
  代码 + 测试 + 设计标注 + 技术债 ADR + SDK接入 + 实现状态更新 ──✓──→ 完成
```
