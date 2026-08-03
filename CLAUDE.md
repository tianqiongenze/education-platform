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
- 当前集群状态：**K8s 未运行**(Rancher Desktop 未启)，走本地文件兜底，`synced_to_redis=false`；集群恢复后同步
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
