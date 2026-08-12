# TOGAF 过渡架构 (Transition Architecture)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-TA | v1.0 | 2026-08-12 | Approved | ZCode(架构治理) | D-157 / 00-顶层§9 |

> **文档定位**：TOGAF ADM Phase F 产物。定义从当前态(Baseline)经过渡态(TA-1/TA-2)到目标态(M3)的迁移路径,每阶段给出架构变更清单与风险矩阵。与 00-顶层§9 路线图对齐。

---

## §1 当前态 (Baseline — M1 完成)

| 维度 | 状态 | 证据 |
|------|------|------|
| 代码完成度 | ~95% | 14 Java 模块 + Go + Python(134p/26skip) + 前端 11 tasks typecheck 全 PASS(D-153) |
| 部署验证 | 0%(k3s 关闭) | TD-04 K8s 集群离线 |
| SLO 达标 | 1万 CCU 目标(M1),未压测验证 | NFR-01 M1 阶段 |
| 单元化 | 代码层实现,单 cell | ABB-03 CellRouter 代码就绪 |
| 多区域 | 未部署 | D-041 4-cell 拓扑设计完成,未落地 |
| OTel | CI 环境验证,生产未验证 | TD-07 |
| ABB 覆盖 | 28/28 ABB 代码层就绪 | 02-ABB Registry |
| SBB 锁定 | 全部版本锁定 | 03-SBB §2 |
| 架构合规 | P-01..P-10 9/10 COMPLIANT(P-04 PARTIALLY) | 06-Governance §2 |
| 技术债 | TD-01..TD-10 | 06-Governance §5 |

## §2 过渡态 TA-1 (M1 → M2)

### 2.1 目标

- 多区域多活部署验证(2-3 cell)
- 跨单元 RPC 拒绝 + Pulsar 异步事件验证
- 全链路 OTel 生产验证
- 1 万 CCU → 10 万 CCU 压测达标

### 2.2 架构变更清单

| 变更类型 | ABB/SBB | 说明 | 关联 ADR |
|---------|---------|------|---------|
| 新增 | ABB-03 CellRouter 多 cell 路由表 | hash(user_id)%4 路由 | D-014 |
| 新增 | SBB-28 Karmada 多集群调度 | 4-cell(hz/sz/bj/sg) | D-041 |
| 修改 | ABB-25 TiDB 跨区 Raft | Placement Rules 跨 2 区 | D-041 |
| 修改 | ABB-24 Pulsar geo-rep | 跨 cell 事件复制 | D-041 |
| 修改 | ABB-26 Redis CRDT | Active-Active 多活 | D-041 |
| 修改 | ABB-23 OTel 生产部署 | tail-sampling 生产配置 | D-118 |
| 废弃 | 本地兜底会话 | 状态全外化 Redis | TD-05 |
| 修复 | TD-04 K8s 集群恢复 | k3s/自建集群上线 | TD-04 |
| 修复 | TD-07 ws-gateway OTel 生产验证 | — | TD-07 |

### 2.3 风险矩阵

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 跨区 Raft 延迟超标 | 中 | 高 | Placement Rules 控制副本分布,Follower Read 就近读 |
| Pulsar geo-rep 消息积压 | 中 | 中 | 监控 backlog,设置阈值告警 + 限流 |
| Redis CRDT 冲突 | 低 | 中 | CRDT 数据类型限制(计数器/集合),冲突策略测试 |
| Karmada 调度器稳定性 | 中 | 高 | 先单集群 + Karmada 调度 1 cell 灰度,再扩 |
| k3s 恢复失败 | 中 | 高 | 备选方案:自建 k8s 或云托管 |

## §3 过渡态 TA-2 (M2 → M3)

### 3.1 目标

- 100 万并发达标(G-01)
- TiKV/TiDB 扩容 ≥3 节点
- Argo Rollouts 金丝雀发布
- 10-20 单元扩容
- 全 BU 上线(考公/考研/养生/棋牌)

### 3.2 架构变更清单

| 变更类型 | ABB/SBB | 说明 | 关联 ADR |
|---------|---------|------|---------|
| 新增 | Argo Rollouts | 金丝雀/蓝绿发布 | D-114 扩展 |
| 修改 | ABB-25 TiDB 扩容 | ≥3 节点 + 分片扩展 | D-008/D-015 |
| 修改 | ABB-03 CellRouter | 路由表 N 扩到 10-20 | D-012 |
| 新增 | ABB-09/10 senior-care/boardgame 全量上线 | BU 横向复制 | SP-10 |
| 修改 | ABB-16 Data Platform | Feature Store + 实时指标全量 | D-042 |
| 修改 | ABB-15 AI Gateway | Kong AI 替代 LiteLLM | D-018 |
| 修复 | TD-06 冷热分层自动化 | Iceberg 归档自动化 | P-04 |
| 修复 | TD-09 CockroachDB 海外 PoC | — | D-027 |

### 3.3 风险矩阵

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 100 万 CCU 压测不达标 | 中 | 极高 | 分阶段 10万→50万→100万,每阶段瓶颈分析 |
| TiDB 扩容数据重分布 | 低 | 高 | 低峰期执行,PRE_SPLIT_REGIONS 预分片 |
| AI 网关迁移(LiteLLM→Kong)中断 | 中 | 中 | 双跑期 + 流量切换,回滚预案 |
| LLM 成本失控 | 中 | 高 | 语义缓存 + 模型路由 + 成本看板 + 预算告警 |
| 多单元故障切换 >30s | 低 | 极高 | AppActive 模式演练,故障注入验证 G-08 |

## §4 目标态 (M3)

| 维度 | 目标 | SLO |
|------|------|-----|
| 用户容量 | 30 亿注册用户 | G-02 |
| 并发 | 100 万 CCU | G-01 |
| 单元数 | 10-20 单元(国内 + 海外) | — |
| 可用性 | 99.99% | G-07 |
| 故障切换 | <30s | G-08 |
| API P95 | <200ms | G-03 |
| 直播延迟 | <800ms P95 | G-05 |
| 事件 ingestion | ≥500,000 ev/s | G-06 |
| 入湖延迟 | <5min | G-09 |
| AI 推理 P95 | <3s | G-10 |
| BU 覆盖 | 全事业群(IT 后端/前端/考公/考研/养生/棋牌) | — |

## §5 每阶段架构变更汇总

| 阶段 | 新增 ABB | 修改 ABB | 废弃 | 新增 SBB | 修改 SBB |
|------|---------|---------|------|---------|---------|
| Baseline(M1) | 28 ABB 全部代码就绪 | — | — | 31 SBB 锁定 | — |
| TA-1(M1→M2) | — | ABB-03/23/24/25/26 | 本地会话 | Karmada(SBB-28) | OTel 生产配置 |
| TA-2(M2→M3) | — | ABB-03/15/16/25 | — | Argo Rollouts, Kong AI | TiDB 扩容 |
| Target(M3) | — | — | — | CockroachDB(海外 PoC) | — |

## §6 迁移风险总览

| 风险 ID | 阶段 | 风险 | 严重度 | 缓解策略 | 负责人 |
|---------|------|------|--------|---------|--------|
| MR-01 | TA-1 | k3s 集群恢复失败 | 高 | 备选自建/云托管 | SRE |
| MR-02 | TA-1 | 跨区 Raft 延迟 | 高 | Placement Rules + Follower Read | SP-07 |
| MR-03 | TA-1 | Karmada 稳定性 | 高 | 灰度引入 | SP-07 |
| MR-04 | TA-2 | 100 万 CCU 不达标 | 极高 | 分阶段压测 | SP-09 |
| MR-05 | TA-2 | AI 网关迁移中断 | 中 | 双跑 + 回滚 | SP-04 |
| MR-06 | TA-2 | LLM 成本失控 | 高 | 缓存+路由+预算 | SP-04 |
| MR-07 | M3 | 海外 CockroachDB PoC 失败 | 中 | 回退 TiDB 海外单集群 | SP-07 |
| MR-08 | 全程 | PIPL 合规违规 | 极高 | 数据流审计 + 海外独立 | 安全合规 |

## §7 与 Phase F 迁移规划对齐

本过渡架构与 00-顶层§9 路线图严格对齐:

| 本文档阶段 | 00-顶层§9 里程碑 | 关键路径 SP | 验收标准 |
|-----------|----------------|-----------|---------|
| Baseline | M1(IT 后端 MVP) | SP-05 | 1 万 CCU + 三端核心流程可演示 |
| TA-1 | M2(多 BU+多区域) | SP-07 | 多区域多活 + 跨单元故障<30s |
| TA-2 | M3(冲天花板) | SP-09/10 | 100 万 CCU + 全 BU 上线 |
| Target | M3+ | — | 全量 SLO G-01..G-10 达标 |

**关键路径**(00-顶层§9.2): SP-01 → SP-02 → SP-04 → SP-05 → SP-09(M3 天花板)。TA-1 关键依赖 SP-07(单元化+多区域),TA-2 关键依赖 SP-09(扩容)。

---

**关联文档**：00-顶层§9 路线图 / 02-ABB / 03-SBB / 05-ARS / 06-Governance Log(技术债 TD-04/05/06/07/09)
