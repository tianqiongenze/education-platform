# SP-05 IT 后端 MVP 测试方案

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-05-TEST | v1.0 | 2026-08-03 | Draft(待评审) | 文档工程师 | SP-05-SRS v1.0 + SP-05-SAD v1.0 + SP-05-SDD v1.0 |

> 本测试方案覆盖 4 子平台（直播/在线编程/作业/评分）的功能/性能/安全/一致性测试，以及端到端（三端核心流程、1 万 CCU 压测、Pact 契约、混沌测试、全链路 trace）。

---

## 1. 引言

### 1.1 目的
定义 SP-05 IT 后端 MVP 的测试策略、用例、验收标准，作为测试执行与 CI/CD 的依据。对齐 MVP 7 项验收标准。

### 1.2 范围
- 直播：推拉流测试、延迟测试（<800ms）、房间切换、录制回放
- 在线编程：沙箱启动测试（冷启动/预热池）、评测隔离安全测试（逃逸尝试）、协作编辑冲突测试
- 作业：状态机测试、查重准确率测试（MinHash 召回/Dolos 精度）、防作弊假阳率测试
- 评分：pipeline 分层测试、LLM 评分一致性测试（self-consistency 方差）、成绩聚合测试
- 端到端：三端核心流程、1 万 CCU Locust 压测、Pact 契约、混沌测试、全链路 trace

### 1.3 测试金字塔
```
        ┌───────┐
        │ E2E  │  少量(三端核心流程, 1万CCU压测, 混沌)
        ├───────┤
        │ 集成 │  中等(子平台集成, 中台集成, 契约)
        ├───────┤
        │ 单元 │  大量(公共库≥80%, 安全核心100% CI硬门)
        └───────┘
```

### 1.4 依据
- SP-05-SRS §7 非功能需求、§9 验收标准
- ADR-I01..I05

---

## 2. 直播平台 Live 测试

### 2.1 推拉流测试

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-LIVE-01 | WHIP 推流 | OBS/ffmpeg WHIP 推流到 SRS origin | SRS 收流成功，Redis 登记 stream:{room} | ffmpeg+RTMP dump |
| TC-LIVE-02 | RTMP 推流 | ffmpeg RTMP 推流 | SRS 收流成功 | ffmpeg |
| TC-LIVE-03 | SRT 推流 | ffmpeg SRT 推流 | SRS 收流成功 | ffmpeg |
| TC-LIVE-04 | WebRTC 拉流（互动） | 学员 WebRTC 连 LiveKit SFU | 亚秒延迟，视频流畅 | LiveKit client SDK |
| TC-LIVE-05 | LL-HLS 拉流（大众） | 学员 LL-HLS 拉 CDN | 2-4s 延迟，视频流畅 | hls.js |
| TC-LIVE-06 | 多清晰度切换 | 学员切换 1080p/720p/480p | 切换 <2s，无卡顿 | 播放器 |
| TC-LIVE-07 | 转码验证 | 推流后检查转码输出 | 多清晰度流均生成 | SRS API |

### 2.2 延迟测试（G-05 <800ms）

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-LIVE-08 | WebRTC 端到端延迟 P95 | 推流端打时间戳，拉流端比对 | P95 <800ms | WS 压测+时间戳 |
| TC-LIVE-09 | LL-HLS 延迟 | 同上 | P95 2-4s | hls.js |
| TC-LIVE-10 | 弹幕延迟 | 发弹幕到收到 | P95 <500ms | WS 压测 |
| TC-LIVE-11 | 连麦延迟 | 教师+学员连麦 | P95 <800ms | LiveKit |

### 2.3 房间切换与故障

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-LIVE-12 | 房间创建→流→结束→归档 | 状态机全迁移 | 状态正确流转 |
| TC-LIVE-13 | 房间暂停→恢复 | pause→resume | 流恢复，无中断>2s |
| TC-LIVE-14 | cell 故障重连 | 模拟 cell 故障，客户端重连 | 路由器重连健康 cell，<60s 恢复 |
| TC-LIVE-15 | 观众超限降级 | 互动观众超几百 | 新观众降级 LL-HLS |
| TC-LIVE-16 | 推流中断重连 | 推流网络断开 | 客户端自动重连 3 次 |

### 2.4 录制回放

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-LIVE-17 | 录制生成 | 直播结束检查对象存储 | FLV/MP4 生成 |
| TC-LIVE-18 | 回放播放 | 学员观看回放 | 支持时移/倍速/章节 |
| TC-LIVE-19 | 录制完整性 | 对比推流时长与录制时长 | 误差 <1s |

---

## 3. 在线编程 IDE 测试

### 3.1 沙箱启动测试

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-IDE-01 | WebContainer 启动 | 开教学 IDE | <500ms 启动 | Performance API |
| TC-IDE-02 | Firecracker 冷启动 | 无预热池，首次启动 microVM | ≤200ms | judge0 trace |
| TC-IDE-03 | 预热池启动 | 从 SandboxWarmPool 取 snapshot | 5-30ms | CRD metrics |
| TC-IDE-04 | 池补充 | 消耗 snapshot 后 | 控制器异步补充到 target | CRD controller |
| TC-IDE-05 | 多语言启动 | Java/Python/Go/C++ | 各语言均启动 | - |

### 3.2 评测隔离安全测试（逃逸尝试）

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-IDE-06 | 网络逃逸 | 评测代码尝试访问外网 | 网络出口禁，连接拒绝 |
| TC-IDE-07 | 文件系统逃逸 | 评测代码尝试读 /etc/passwd | 仅沙箱文件系统可见 |
| TC-IDE-08 | 进程逃逸 | 评测代码尝试 fork bomb | 资源限制 kill，宿主不受影响 |
| TC-IDE-09 | CPU 超限 | 评测代码死循环 | 30s 超时 kill |
| TC-IDE-10 | 内存超限 | 评测代码分配大内存 | OOM kill，宿主不受影响 |
| TC-IDE-11 | 隔离验证 | 多 microVM 并发 | 互不可见 |
| TC-IDE-12 | GPU 穿透(ML) | ML 作业 VFIO | GPU 可用，隔离仍成立 |

### 3.3 协作编辑冲突测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-IDE-13 | 并发编辑同位置 | 两端同时改同一行 | CRDT 自动合并，无冲突 |
| TC-IDE-14 | 离线编辑重连 | 一端断网编辑，重连 | 自动 merge，无数据丢失 |
| TC-IDE-15 | 多端(3+)协作 | 教师+2 学员 | 全端同步 |
| TC-IDE-16 | 大文档性能 | 10k 行文档协作 | 延迟 <200ms |

---

## 4. 作业平台 Assignment 测试

### 4.1 状态机测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-ASG-01 | 全状态流转 | UNSUBMITTED→DRAFT→SUBMITTED→GRADED→REVIEWED→ARCHIVED | 流转正确 |
| TC-ASG-02 | 非法迁移 | GRADED→DRAFT | 拒绝 INVALID_TRANSITION |
| TC-ASG-03 | 超时锁定 | due_at+grace 后提交 | 锁定，拒绝 submit |
| TC-ASG-04 | multi-attempt | 第 2/3 次提交 | 独立 Submission+Grade |
| TC-ASG-05 | 超最大尝试 | 第 4 次（max=3） | 拒绝 |
| TC-ASG-06 | 草稿 vs 正式 | DRAFT 不触发评测 | 正式 SUBMITTED 才评测 |
| TC-ASG-07 | 完整性校验 | code_hash 不匹配 | 拒绝，记审计 |

### 4.2 查重准确率测试

| 用例 ID | 用例 | 步骤 | 预期 | 指标 |
|---------|------|------|------|------|
| TC-ASG-08 | MinHash 召回 | 构造已知抄袭集（变量重命名/注释改动） | Tier1 桶含所有正样本 | 召回率 ≥95% |
| TC-ASG-09 | MinHash 误桶 | 无关代码 | Tier1 桶不含 | 误桶率 <5% |
| TC-ASG-10 | Dolos 精度 | Tier2 跑桶内 pairwise | 相似度排序正确 | 精度 ≥90% |
| TC-ASG-11 | JPlag 交叉验证 | Dolos vs JPlag | 结果一致（相关系数>0.9） | - |
| TC-ASG-12 | 规模测试 | 10k 提交 | Tier1 O(n) 完成 <60s | - |
| TC-ASG-13 | 桶爆炸防护 | 单桶 >100 | 二次 LSH 细分 | - |

### 4.3 防作弊假阳率测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-ASG-14 | browser lockdown | 切屏/复制/新窗口 | 检测+flag |
| TC-ASG-15 | AI proctoring 假阳率 | 正常学员被 flag | 假阳率 30-50%（已知，仅 flag） |
| TC-ASG-16 | flag 不自动罚 | flag 后检查成绩 | 不扣分，待人工复核 |
| TC-ASG-17 | 人工复核 | 教师复核 flag | 可确认/撤销 |
| TC-ASG-18 | 审计日志 | flag 全程 | 审计日志可查 |

---

## 5. 评分平台 Grading 测试

### 5.1 pipeline 分层测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-GRD-01 | ①IO/单元测试 | 跑测试用例 | pass/fail 正确，覆盖率 |
| TC-GRD-02 | ②静态分析 | Semgrep 规则 | 违规列表正确 |
| TC-GRD-03 | ③安全扫描 | SAST | 漏洞列表正确 |
| TC-GRD-04 | ④Style | checkstyle | 风格违规正确 |
| TC-GRD-05 | ⑤LLM rubric | LLM 评分 | 分数+反馈 |
| TC-GRD-06 | 同步 vs 异步 | ①-④同步，⑤异步 | 确定性秒级返回，LLM 异步 |
| TC-GRD-07 | 每提交隔离 | 多提交并发 | 互不影响 |
| TC-GRD-08 | 超时处理 | 各层超时 | 超时 kill，记部分分 |

### 5.2 LLM 评分一致性测试

| 用例 ID | 用例 | 步骤 | 预期 | 指标 |
|---------|------|------|------|------|
| TC-GRD-09 | self-consistency 方差 | 同代码 k=5 采样 | 分数方差小 | 标准差 ≤1.0 |
| TC-GRD-10 | 多数表决 | 5 采样取众数 | 稳定最终分 | - |
| TC-GRD-11 | Leniency 漂移 | 批量评分监控 | 漂移告警 | 阈值 1.0 |
| TC-GRD-12 | CoT 质量 | 检查推理链 | 每准则有推理 | 人工抽检 |
| TC-GRD-13 | 对齐人工 | LLM vs 教师评分 | MAE ≤1.5（高风险仍人工） | - |
| TC-GRD-14 | AI 检测器=信号 | AI 生成代码 flag | 仅 flag 不零分 | - |
| TC-GRD-15 | 查重 flag≠零分 | 查重 flag 代码 | 接 regrade，不自动零分 | - |

### 5.3 成绩聚合测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-GRD-16 | per-assignment 聚合 | 单作业成绩 | 正确 |
| TC-GRD-17 | per-course 加权 | 多作业加权 | 加权平均正确 |
| TC-GRD-18 | per-term GPA | 多课程 | GPA 正确 |
| TC-GRD-19 | 申诉 regrade | APPROVED 重评 | 成绩更新 |
| TC-GRD-20 | 成绩单导出 | CSV/Excel | 格式正确 |

---

## 6. 端到端测试

### 6.1 三端核心流程

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-E2E-01 | web 全链路 | 注册→选课→学习→作业→评测→出分→直播互动 | 全链路跑通 | Playwright |
| TC-E2E-02 | h5 全链路 | 同上（移动适配） | 跑通 | Playwright |
| TC-E2E-03 | 小程序全链路 | 同上（受限能力） | 跑通 | 小程序自动化 |
| TC-E2E-04 | 三端共 API | 校验三端调用同一契约 | 契约一致 | OpenAPI 校验 |
| TC-E2E-05 | 教师全链路 | 开课→备课→批改→直播→学情 | 跑通 | Playwright |

### 6.2 1 万 CCU Locust 压测

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-E2E-06 | 1 万 CCU 核心 API | 分布式 Locust 独立节点 | P95<200ms, P99<500ms (G-03/G-04) | Locust |
| TC-E2E-07 | 直播互动并发 | 100 房间×100 互动观众 | 延迟<800ms (G-05) | Locust+LiveKit |
| TC-E2E-08 | Judge 沙箱并发 | 500 并发评测 | 预热池不耗尽，延迟<10s | Locust+judge0 |
| TC-E2E-09 | 弹幕削峰 | 1 万弹幕/s | 消费 lag<5s | Locust+Pulsar |
| TC-E2E-10 | AI 网关 mock 压测 | mock 模式 1k QPS | P95<3s (G-10) | Locust+mock |
| TC-E2E-11 | 读写分离验证 | 读写比例 8:2 | 读走 Follower，写走 Primary | DB trace |
| TC-E2E-12 | 多级缓存命中 | L1+L2 命中率 | L1>60%, L2>90% | Redis metrics |

### 6.3 Pact 契约测试

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-E2E-13 | 三端↔BFF 契约 | Pact 消费方驱动 | 契约兼容 |
| TC-E2E-14 | BFF↔子平台 契约 | Pact | 兼容 |
| TC-E2E-15 | 子平台↔中台 契约 | Pact | 兼容 |
| TC-E2E-16 | 契约 CI 硬门 | 不兼容 PR | 阻止合并 |

### 6.4 混沌测试

| 用例 ID | 用例 | 步骤 | 预期 | 工具 |
|---------|------|------|------|------|
| TC-E2E-17 | 服务 pod kill | 随机杀 pod | 自动重启，SLA 不降 | Chaos Mesh |
| TC-E2E-18 | 网络分区 | cell 间网络断 | 跨 cell 走 CDN，单 cell 自治 | Chaos Mesh |
| TC-E2E-19 | Redis 故障 | Redis 主节点挂 | 故障转移，缓存降级 | Chaos Mesh |
| TC-E2E-20 | TiDB 故障 | TiDB 节点挂 | 自动 failover | Chaos Mesh |
| TC-E2E-21 | AI 网关超时 | LLM 超时 | 降级 mock/缓存 | - |
| TC-E2E-22 | Judge 池耗尽 | 池空 | 排队，不崩 | - |

### 6.5 全链路 trace

| 用例 ID | 用例 | 步骤 | 预期 |
|---------|------|------|------|
| TC-E2E-23 | traceparent 贯通 | 三端→BFF→子平台→中台→基础设施 | Tempo 可查全链路 |
| TC-E2E-24 | 4 子平台指标看板 | Grafana | 直播延迟/SFU/Judge/查重/LLM 指标可见 |
| TC-E2E-25 | 双日志轨 | 业务+审计 | 审计独立存储可查 |

---

## 7. 测试环境与数据

### 7.1 测试环境
- 单 cell K8s 集群（MVP 拓扑，非多 cell）
- TiDB/Redis/Pulsar/Kafka/Milvus/对象存储/CDN/SRS/LiveKit/Firecracker CRD
- AI 网关 mock 模式（不依赖外部 LLM）
- 三端测试环境（web/h5/小程序模拟器）

### 7.2 测试数据
- 学员数据：1 万 mock 学员（单元化分表）
- 课程数据：100 mock 课程
- 作业数据：1k mock 提交（含已知抄袭集）
- 直播数据：100 mock 房间
- 压测数据：Locust 脚本生成

### 7.3 CI/CD 集成
- 单元测试：PR 触发，≥80% 覆盖（安全核心 100% CI 硬门）
- 契约测试：PR 触发，Pact CI 硬门
- 集成测试：合并到 main 触发
- E2E/压测： nightly 触发
- 混沌测试：weekly 触发

---

## 8. 验收标准对齐

| 验收项 | 测试覆盖 | 通过标准 |
|-------|---------|---------|
| 9.1 三端核心流程跑通 | TC-E2E-01..05 | 全通过 |
| 9.2 4 子平台核心链路 | TC-LIVE-01..19, TC-IDE-01..16, TC-ASG-01..18, TC-GRD-01..20 | 全通过+性能基线 |
| 9.3 单元路由+读写分离+缓存+EDA | TC-E2E-11/12, TC-E2E-23 | trace 可见+契约通过 |
| 9.4 1 万 CCU 压测 | TC-E2E-06..12 | G-01/G-03/G-04/G-05/G-10 达标 |
| 9.5 Pact+单元/集成/安全 | TC-E2E-13..16, TC-IDE-06..12 | CI 全绿+安全报告 |
| 9.6 全链路 trace | TC-E2E-23..25 | Tempo+Grafana 可查 |
| 9.7 AI 网关+LLM 评分 | TC-GRD-09..15, TC-E2E-10 | 一致性+漂移监控通过 |

---

## 9. 风险与开放问题

| ID | 风险 | 缓解 |
|----|------|------|
| R-TEST-01 | 单 cell 拓扑压测不代表多 cell | MVP 验证拓扑，多 cell 在 SP-07 |
| R-TEST-02 | AI 网关 mock 不等于真实 LLM | mock 验证链路，真实 LLM 抽样验证 |
| R-TEST-03 | 混沌测试可能影响共享环境 | 独立 chaos 命名空间，限定爆炸半径 |
| R-TEST-04 | 1 万 CCU 压测需独立节点 | Locust 分布式独立部署 |
| O-TEST-01 | 小程序自动化工具选型 | 评估 miniprogram-automator |

---

**下一步**：本测试方案经评审通过后，作为 SP-05 测试执行与 CI/CD 的依据。
