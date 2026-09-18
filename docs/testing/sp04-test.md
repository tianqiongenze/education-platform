# SP-04 AI中台 测试方案

> **D-200 Naming Note:** This document's filename retains the legacy code `sp04` for reference continuity. Per D-137, sp04 → ai-platform. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| SP04-TEST | v1.0 | 2026-08-03 | 评审通过 | AI中台QA组 | 架构委员会 |

> 关联：SRS `sp04-srs.md`、SAD `sp04-sad.md`、SDD `sp04-sdd.md`；ADR-A01..A04

## 1. 测试目标与策略
- **质量门**：功能 100% 通过；NFR 全达标；跨租户泄漏 0；成本降幅 ≥45% 连续 7 天对账。
- **策略**：分层测试（单元→集成→端到端→压测→安全→评测）+ LLM_MODE=mock 全程覆盖 CI。
- **评测分离**：确定性逻辑走断言；LLM 输出走评测集 + 指标阈值。

## 2. 测试分层矩阵

| 层 | 范围 | 工具 | 责任 |
|----|------|------|------|
| 单元 | 成本栈/RRF/FGAC 注入/risk 分类/IRT | pytest + mock | 研发 |
| 集成 | Agent↔网关↔网关↔RAG↔Milvus | pytest + testcontainers | 研发+QA |
| E2E | 5 Agent 端到端 | Playwright + API | QA |
| 压测 | 网关/RAG/Agent | Locust 分布式 | QA+SRE |
| 安全 | FGAC/内容安全 | 渗透脚本 + 静态扫描 | 安全+QA |
| 评测 | LLM 输出质量 | 评测集 + 离线脚本 | 算法+QA |

## 3. Agent 端到端测试

### 3.1 批改 Agent
| 用例 | 步骤 | 期望 |
|------|------|------|
| TC-G-01 | 提交低风险文本作业 | 3s 内形成性反馈，无 interrupt |
| TC-G-02 | 提交高权重代码作业 | 触发 interrupt_before(teacher_review)，状态落 Redis |
| TC-G-03 | 教师改分后 resume | finalize 落库，审计含 teacher_action |
| TC-G-04 | 进程重启后 resume | get_state 恢复，可继续 |
| TC-G-05 | 评测集回归 MAE | 非高风险 MAE <0.4（NFR-05） |

### 3.2 出题 Agent
| 用例 | 期望 |
|------|------|
| TC-Q-01 | 生成 10 题，全部带 chunk_id 来源 |
| TC-Q-02 | 与题库相似度 >0.85 题目被过滤 |
| TC-Q-03 | IRT 校准 |δ|<0.3，否则进待审 |
| TC-Q-04 | 教师批量审核通过后入正式库 |

### 3.3 答疑 Agent
| 用例 | 期望 |
|------|------|
| TC-A-01 | 知识类问题引用率 ≥95% |
| TC-A-02 | 未知问题返回"我不确定"并转人工 |
| TC-A-03 | 政治敏感输入被拦截 |
| TC-A-04 | 多轮上下文切换设备不丢失 |

### 3.4 学习路径 Agent
| 用例 | 期望 |
|------|------|
| TC-P-01 | 路径节点 ≤10，含掌握度预估 |
| TC-P-02 | 练习回流后重算触发 |
| TC-P-03 | 目标切换 30s 内出新路径 |

### 3.5 数字人
| 用例 | 期望 |
|------|------|
| TC-D-01 | 文本→口型+语音端到端 <1.5s |
| TC-D-02 | 多语言切换 ≤2s |

## 4. 模型网关测试

### 4.1 成本栈顺序
| 用例 | 期望 |
|------|------|
| TC-GW-01 | cache 命中直接返回，不进 route（探针验证 route 未调用） |
| TC-GW-02 | cache 未命中→route 决策模型→cascade 低置信升级 |
| TC-GW-03 | provider A 故障→3s 内 fallback B（RTO≤3s, NFR-07） |
| TC-GW-04 | 全 provider 故障→503 + 降级话术 |

### 4.2 缓存
| 用例 | 期望 |
|------|------|
| TC-GW-05 | 相似 query（语义近）命中，命中率 ≥60%（NFR-02） |
| TC-GW-06 | 知识库版本升级后旧缓存失效 |
| TC-GW-07 | 跨租户缓存不串（A 的 query 不命中 B 的缓存） |

### 4.3 路由
| 用例 | 期望 |
|------|------|
| TC-GW-08 | 简单意图路由到小模型，准确率 ≥90% |
| TC-GW-09 | 路由表热更新 <5s 生效 |
| TC-GW-10 | 成本降幅 ≥45%（7 天对账, NFR-03） |

### 4.4 mock 模式
| 用例 | 期望 |
|------|------|
| TC-GW-11 | LLM_MODE=mock 全量回归通过，无外部调用 |
| TC-GW-12 | trace_id 路由到指定 fixture 用例 |

### 4.5 限流计费
| 用例 | 期望 |
|------|------|
| TC-GW-13 | tenant×agent×model 超限返回 429 + Retry-After |
| TC-GW-14 | 计费事件含 tokens/cost/model/tenant，写 Iceberg 可对账 |

## 5. RAG 测试

### 5.1 检索质量
| 用例 | 期望 |
|------|------|
| TC-R-01 | 召回@10 ≥0.85（标准评测集） |
| TC-R-02 | Reranker 后 nDCG@10 提升 ≥0.08 |
| TC-R-03 | RRF 融合优于单路（BM25/向量） |

### 5.2 多租户隔离（核心安全测试）
| 用例 | 期望 |
|------|------|
| TC-R-04 | 持 tenant A JWT 查询，对 B namespace 0 返回 |
| TC-R-05 | 伪造 X-Tenant-Id 头覆盖 → 网关拒绝（仅信 JWT） |
| TC-R-06 | app 层尝试拼接 tenant_id → 静态扫描阻断 CI |
| TC-R-07 | 跨租户缓存渗透：A query 不命中 B 缓存 |
| TC-R-08 | 渗透：构造无 tenant_id 查询 → SDK 拒绝 |
| TC-R-09 | pgvector schema 越权访问 → 拒绝 |

### 5.3 入库
| 用例 | 期望 |
|------|------|
| TC-R-10 | 100 文档/s 入库吞吐（NFR-10） |
| TC-R-11 | 状态机 pending→indexing→ready 正确流转 |
| TC-R-12 | 失败可重试，状态落 failed |

## 6. 性能压测

| 场景 | 工具 | 目标 |
|------|------|------|
| 网关 1000 QPS | Locust 分布式 | P95 <3s（NFR-01/G-10） |
| RAG 检索 500 QPS | Locust | P95 <500ms |
| Agent 并发 200 | Locust | P95 <3s（不含 HITL） |
| 缓存命中场景 | Locust | 命中率 ≥60% |
| fallback 故障注入 | Chaos | RTO ≤3s |

**压测环境**：独立 K8s namespace，LLM_MODE=mock + 真实 provider 灰度各一轮。

## 7. 安全测试

| 用例 | 期望 |
|------|------|
| TC-SEC-01 | FGAC 绕过：篡改 JWT tid → 拒绝 |
| TC-SEC-02 | app 层 tenant_id 拼接 → 静态扫描阻断 |
| TC-SEC-03 | 跨租户查询 0 泄漏（NFR-04） |
| TC-SEC-04 | 内容安全：政治/色情/暴力/作弊拦截 |
| TC-SEC-05 | PII 脱敏：评测集样本不含原始 PII |
| TC-SEC-06 | 审计日志 hash 链不可篡改 |

## 8. LLM 评测集设计

### 8.1 评测集结构
```
eval/
  grading/  # ≥1000 条, 含教师金标分
    {id, submission, rubric, gold_score, gold_cot, risk}
  quiz/     # ≥1000 条, 含难度金标
  qa/       # ≥1000 条, 含引用与拒答金标
  path/     # ≥500 条, 含合理路径金标
```

### 8.2 评测指标
| Agent | 指标 | 阈值 |
|-------|------|------|
| 批改 | MAE（非高风险） | <0.4 |
| 批改 | 风险分类 F1 | ≥0.85 |
| 出题 | 难度 |δ|<0.3 占比 | ≥85% |
| 出题 | 去重拒绝准确率 | ≥95% |
| 答疑 | 引用率（知识类） | ≥95% |
| 答疑 | 拒答准确率 | ≥90% |
| 学习路径 | 节点合理性（人工抽审） | ≥90% |

### 8.3 评测流程
- 每周离线跑评测集，指标写入评测看板（管理控制台）。
- 误判样本由教师一键回流，版本化（dvc），A/B 比对模型版本。
- 指标劣化 >5% 触发回归告警。

## 9. CI 流水线

```
代码提交
  ├─ lint + 静态扫描(no-tenant-concat / milvus-expr-required)
  ├─ 单元测试(LLM_MODE=mock)
  ├─ 集成测试(testcontainers: Milvus/pgvector/Redis)
  ├─ E2E(mock 模式)
  └─ 发布候选镜像
夜班:
  ├─ 评测集回归(真实 provider, 灰度)
  ├─ 压测(mock)
  └─ 安全渗透脚本
```

## 10. 验收清单
- [ ] 5 Agent E2E 全通过
- [ ] 成本栈 4 阶段顺序单测通过
- [ ] 缓存命中率 ≥60%（压测）
- [ ] 路由准确率 ≥90%
- [ ] fallback RTO ≤3s（故障注入）
- [ ] 跨租户渗透 0 泄漏
- [ ] 静态扫描 0 违规
- [ ] P95 <3s（1000 QPS）
- [ ] 评测集指标达阈值
- [ ] 计费对账误差 <0.1%

---

（全文完，约 230 行有效内容）

---

## D-046: 智能体架构测试矩阵

### 1. 测试用例

| 用例 ID | 模块 | 场景 | 预期结果 | 类型 |
|---------|------|------|---------|------|
| TC-AGT-01 | MCP | 注册新 MCP Server（stdio 传输） | Registry 写入 `mcp:servers:{tenant}`，YAML 双写 | 单元 |
| TC-AGT-02 | MCP | 注销已注册 Server | Redis 与 YAML 均移除/标记 disabled | 单元 |
| TC-AGT-03 | MCP | list 查询租户全部 Server | 返回完整列表；Redis 故障时回退 YAML | 单元 |
| TC-AGT-04 | MCP | 传输选择：stdio vs Streamable HTTP | MCPClient 按 ServerInfo.transport 正确建立连接 | 集成 |
| TC-AGT-05 | MCP | 工具 inputSchema 校验失败 | SandboxedToolNode 拒绝调用并审计 | 单元 |
| TC-AGT-06 | A2A | Task submitted→working→completed 正常流转 | 状态机正确转换，Redis `a2a:task:{id}` 持久化 | 单元 |
| TC-AGT-07 | A2A | Task 进入 input-required（HITL） | 暂停并发布事件；resume 后继续 | 集成 |
| TC-AGT-08 | A2A | Task canceled/failed/rejected 终态 | 终态不可回退，TTL 7d | 单元 |
| TC-AGT-09 | A2A | AgentCard 序列化 `/.well-known/agent.json` | JSON 字段完整，可被 AgentRegistry 拉取 | 集成 |
| TC-AGT-10 | A2A | SSE 订阅 Task 进度 | 客户端收到 working/completed 事件 | 集成 |
| TC-AGT-11 | Graph | Supervisor 路由到正确子图 | 意图→子图映射准确率 ≥90% | 集成 |
| TC-AGT-12 | Graph | `_round_done` 终止保护 | 选择子图后不再回 Supervisor，直接 FINISH | 单元 |
| TC-AGT-13 | Graph | RedisSaver checkpointer 持久化 | 重启后可从 thread_id 恢复状态 | 集成 |
| TC-AGT-14 | Graph | MemorySaver 降级 | Redis 故障时自动降级并告警 | 故障注入 |
| TC-AGT-15 | Reflexion | evaluate 通过（score ≥0.7） | 直接进入 respond，不触发 reflect | 单元 |
| TC-AGT-16 | Reflexion | evaluate 失败→reflect→retry | trial_count 递增，反思写入 reflections | 单元 |
| TC-AGT-17 | Reflexion | 达 max_trials=3 强制终止 | 返回 grounded answer，不再重试 | 单元 |
| TC-AGT-18 | Reflexion | episodic memory 注入 | 历史 reflection 从 Redis 读取并注入 prompt | 集成 |
| TC-AGT-19 | Harness | 超时 120s 触发 | AgentExecutionError，网关返回 408 | 集成 |
| TC-AGT-20 | Harness | 深度超 50 触发 | AgentExecutionError | 单元 |
| TC-AGT-21 | Harness | 沙箱权限不足拒绝 | 调用被拒，审计记录 PERMISSION_DENIED | 单元 |
| TC-AGT-22 | Harness | prompt 注入输入被清洗 | 含 `ignore previous` 的输入被拒/清洗 | 单元 |
| TC-AGT-23 | Harness | 路径遍历输入被拒 | `../` 与绝对路径被拒 | 单元 |
| TC-AGT-24 | Harness | Token 预算超额拒绝 | budget ZSET 求和超限，拒绝执行 | 单元 |
| TC-AGT-25 | Harness | 输出截断 + 审计落账 | 超长输出截断并标记，AuditLogger 写 Stream | 集成 |
| TC-AGT-26 | Harness | AuditLogger Redis 故障→JSONL 兜底 | 事件写入 `/tmp/ep_audit_fallback.jsonl` | 故障注入 |
| TC-AGT-27 | Hermes | publish/subscribe 端到端 | InMemoryBus 模式下事件正确投递 | 单元 |
| TC-AGT-28 | Hermes | Pulsar 模式 3 重试 + DLQ | 消费失败 3 次后入 DLQ | 集成 |
| TC-AGT-29 | Hermes | PULSAR_ENABLED=false 降级 | 自动切换 InMemoryBus，不丢事件 | 故障注入 |
| TC-AGT-30 | Hermes | EventRouter 规则路由 | 事件按 `hermes:routes` 规则投递下游 | 单元 |
| TC-AGT-31 | Hermes | 路由规则 Redis 热更新 | 不重启即可更新路由 | 集成 |
| TC-AGT-32 | Plugin | manifest 校验通过 | 注册成功，写入 `plugin:registry:{tenant}` | 单元 |
| TC-AGT-33 | Plugin | manifest 校验失败（缺字段） | 拒绝注册并返回错误 | 单元 |
| TC-AGT-34 | Plugin | tenant_whitelist 过滤 | 非白名单租户调用被拒 | 单元 |
| TC-AGT-35 | Plugin | 权限不足拒绝 | PluginSandbox 拒绝并审计 | 单元 |
| TC-AGT-36 | Plugin | 向量检索 Discovery | top-k 返回相关插件 | 集成 |
| TC-AGT-37 | HITL | interrupt_before 暂停于 teacher_review | 图暂停，A2A Task 进入 input-required | 集成 |
| TC-AGT-38 | HITL | 教师审核 resume | 提交审核结果后图继续执行 | 集成 |
| TC-AGT-39 | HITL | 审核超时自动 reject | 24h 未审核，Task 转 rejected | 集成 |
| TC-AGT-40 | 多租户 | 跨租户隔离渗透 | 租户 A 无法访问租户 B 的 Agent/Plugin/Task/Memory | 安全 |

### 2. 门禁指标

| 指标 | 阈值 |
|------|------|
| D-046 用例通过率 | 100% |
| Supervisor 路由准确率 | ≥90% |
| Reflexion 收敛率 | 100%（max_trials 内必终止） |
| 审计落账延迟 P95 | < 5s |
| 降级路径事件零丢失 | 100% |
| 插件沙箱逃逸 | 0 |

---

（D-046 章节完）

---

## §ep-memory 记忆系统测试矩阵 (D-045)

> 关联：ADR-A05；ADR-A06；FR-MEM-01..08。

### 1. 测试用例

| 编号 | 模块 | 场景 | 预期 | 类型 |
|------|------|------|------|------|
| TC-MEM-01 | L0 | 10 轮窗口管理 | 第 11 轮写入后 L0 仅保留最近 10 轮 | 单元 |
| TC-MEM-02 | L0 | TTL 24h 淘汰 | 24h 无更新后 key 自动过期 | 集成 |
| TC-MEM-03 | L0 | 摘要压缩触发 | L0 长度达 8 轮触发 L1 摘要生成 | 集成 |
| TC-MEM-04 | L1 | 会话恢复 | 新会话 recall 含 L1 摘要，上下文连续 | 集成 |
| TC-MEM-05 | L1 | 跨设备续聊 | 设备 A 写入 L1 后，设备 B recall 命中 | 集成 |
| TC-MEM-06 | L1 | TTL 7d 降级 L2 | 7d 到期后摘要被提取写入 L2，L1 key 删除 | 集成 |
| TC-MEM-07 | L2 | Mem0 提取 | 对话写入后 Mem0 client.add 成功抽取事实写入 Milvus/TiDB/Neo4j | 集成 |
| TC-MEM-08 | L2 | 向量检索 | recall 返回 top-k 相似情景记忆，RRF 融合三路 | 集成 |
| TC-MEM-09 | L2 | 语义冲突消解 | 新旧事实矛盾时旧值标记 superseded，新值 active | 单元 |
| TC-MEM-10 | L2 | 程序图谱召回 | Cypher 遍历返回关联实体（User-Course-Skill） | 集成 |
| TC-MEM-11 | L2 | 去重 | 向量相似度 > 0.95 时合并而非新增 | 单元 |
| TC-MEM-12 | L3 | 组织记忆共享 | 授权用户 recall 含 L3；未授权用户 L3 不可见 | 安全 |
| TC-MEM-13 | L3 | 权限隔离 | 无 `mem:org:read` 权限调用 L3 被拒并审计 | 安全 |
| TC-MEM-14 | L3 | 双人审核晋升 | promote_to_org 需 2 名 reviewer 通过才生效 | 集成 |
| TC-MEM-15 | 遗忘 | L0 TTL 淘汰 | 24h 后 L0 key 不存在 | 集成 |
| TC-MEM-16 | 遗忘 | L1 7d 降级 | CronJob 扫描到期 L1，提取摘要写 L2 后 DEL | 集成 |
| TC-MEM-17 | 遗忘 | L2 30d 降权 | 30d 后召回分数 ×0.5 | 集成 |
| TC-MEM-18 | 遗忘 | L2 90d 归档 | 90d 后 status=archived，召回 filter 排除 | 集成 |
| TC-MEM-19 | 遗忘 | L3 不自动过期 | L3 项 90d+ 仍可召回，仅手动 forget 删除 | 集成 |
| TC-MEM-20 | MemoryManager | 分级召回编排 | recall 顺序 L0→L1→L2→L3，各级命中后仍合并返回 | 集成 |
| TC-MEM-21 | MemoryManager | remember 写入编排 | 同步 L0 + 异步 L1 摘要 + 异步 L2 Mem0 提取 | 集成 |
| TC-MEM-22 | MemoryManager | 显式 forget | forget 删除对应级记忆，L3 需治理员权限 | 集成 |
| TC-MEM-23 | API | Python SDK 调用 | MemoryManager 直接调用成功 | 单元 |
| TC-MEM-24 | API | Java SDK gRPC | ep-memory-java 经 gRPC 调通 Python 侧 RPC | 集成 |
| TC-MEM-25 | API | REST 端点 | POST /v1/memory/recall 鉴权 + 租户隔离 | 集成 |
| TC-MEM-26 | 多租户 | 跨租户隔离 | 租户 A 无法 recall 租户 B 的任何级记忆 | 安全 |
| TC-MEM-27 | 降级 | Redis 故障 | L0/L1 降级 MemorySaver，L2 直接兜底召回 | 故障注入 |

### 2. 门禁指标

| 指标 | 阈值 |
|------|------|
| D-045 用例通过率 | 100% |
| recall P95（L0/L1 命中） | < 200ms |
| recall P95（L2 三路 RRF） | < 500ms |
| Mem0 提取成功率 | ≥ 98% |
| 遗忘 CronJob 执行成功率 | 100% |
| L3 跨租户泄漏 | 0 |
| 归档项误召回 | 0 |

---

（D-045 章节完）
