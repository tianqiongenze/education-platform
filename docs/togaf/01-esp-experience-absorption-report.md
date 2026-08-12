# ESP 经验吸收与差距分析报告

> **目的**：对比 English-Study-Platform(ESP, 已验证参考项目) 与 education-platform(EP, 本项目群) 的架构/中间件/框架/测试/部署实践，逐条标注**已吸收 / 部分吸收 / 未吸收 / 有意排除**，并给出修复行动项。
>
> **ESP 路径**：`/Users/joelgong/claude-Projects/English-Study-Platform`
> **EP 路径**：`/Users/joelgong/claude-Projects/education-platform`
> **生成日期**：2026-08-03

---

## 0. 一句话结论

EP 设计文档**已系统性吸收 ESP 的核心架构决策**(Pulsar 统一事件总线+CDC、TiDB HTAP、读写分离 SDK、多级缓存 SDK、Disruptor、JWT RS256+JWKS、探针三分离、DefaultTyping 禁令、LangGraph Agent、OTel 双层 Collector)，并在**11 个维度做了升级**(单元化、Flink+Iceberg 湖仓、AI 网关、RAG FGAC、Spark 离线层、ES 全文检索、Redis 真集群、数字人、RouteLLM、语义缓存、Chaos Mesh)。

**本轮修复完成情况**（2026-08-03）：
- ✅ P0 行动项 5/5 全部完成（A-01 TiDB 8.5、A-04 LangGraph ≥1.0、A-07 user_id 必传、A-10 ADR-T06 不用 Seata、A-14 Litestar+Granian）
- ✅ P1 行动项 9/9 全部完成（A-02 Milvus 2.5+、A-03 Neo4j 5.25+、A-05 数字人渲染栈、A-06 pulsar arm64、A-08 ADR-T07 SCG、A-09 ADR-T08 Sentinel 分布式、A-11 Dubbo/Nacos pin、A-12 Spring Cloud pin、A-17 单节点压测禁令）
- ✅ ESP 18 条踩坑：13 条已吸收/已修复，5 条降级为 P2（Tempo OOM、TraceId 传播、Go vendor、混沌 env 清理、NetworkPolicy CNI）
- ⏳ P2 行动项 8/8 待修复（文档完备性提升，不阻塞 MVP 开发）
- ✅ ESP 16 条踩坑已写入 `~/.zcode/sessions/education-platform/pitfalls.jsonl`（ESP-P-01~ESP-P-16）

**第二轮穷尽审计修复完成情况**（2026-08-03 第二轮）：
- ✅ **新增 3 条 ADR**：ADR-T09（Dubbo Triple 禁 STRICT 序列化）、ADR-T10（大表强制 RANGE 分区防 OOM）、ADR-T11（Pulsar 3.3.x 锁定 + 4.x 升级延迟至 v2.0）
- ✅ **版本对齐 2 项**：Redis 7.x → 8.0（对齐 ESP 实际 `redis:8.0-alpine`）；Granian 1.x → ≥1.6.0（ESP 实际解析至 2.7.9）
- ✅ **新增 4 条 ESP 踩坑对策**：JWT_SECRET<256bit（EP 从 Day 1 用 RS256 规避）、Dubbo timeout 3s→10s（冷缓存 DEADLINE_EXCEEDED）、learning-worker 分离模式（ESP_ROLE=worker + Disruptor↔Pulsar 双路径降级）、session_turns 82M OOM（大表 RANGE 分区）
- ✅ ESP 踩坑清单更新：18 条全部有对应 EP 对策（13 条 ✅ 已吸收 + 5 条 ⚠️ P2）

---

## 1. 中间件与基础设施对比

| # | 组件 | ESP 选择(版本) | EP 选择(版本) | 吸收状态 | 差距/行动 |
|---|------|--------------|--------------|---------|----------|
| 1 | 事件总线 | Pulsar 3.3.x(ADR-02) | Pulsar 3.3.x | ✅ 完全吸收 | — |
| 2 | CDC 总线 | 不用(无 Flink/Spark) | Pulsar 3.3.x(统一 CDC+事件, ns=cdc://) | ✅ 合理引入 | EP 将 CDC 总线统一到 Pulsar(TiCDC→Pulsar→Flink)，不再引入 Kafka，技术栈单一化便于多活+geo-rep，ADR-D01 已论证 |
| 3 | 数据库 | TiDB v8.5.7(3 region Raft) | TiDB 7.x | ⚠️ **版本需对齐** | **行动 A-01**：EP 应将 TiDB 版本从 7.x 升到 8.5+，对齐 ESP 验证版本(v8.5.7 Raft 强一致已验证) |
| 4 | 分析引擎 | TiFlash HTAP(ADR-03, 无 Spark/Flink) | Flink 1.18 + Iceberg + Spark 3.5 | ✅ 合理升级 | EP 天花板 30 亿用户需独立湖仓，ESP 仅 5000 CCU 用 TiFlash 足够。Flink+Iceberg 是升级而非遗漏 |
| 5 | Redis | 单实例(非真集群) | Redis Cluster 7.x(3主3从→30节点) | ✅ 升级 | EP 从 ESP 的"伪集群"升级到真 Redis Cluster，支持单元化每单元独立集群 |
| 6 | 向量库 | Milvus 2.5.0(运行但未用) | Milvus 2.4+ | ✅ 合理引入 | EP 有 RAG/查重/相似代码需求，引入 Milvus 合理。**行动 A-02**：版本应对齐 ESP 的 2.5.0+ |
| 7 | 图库 | Neo4j 5.25.1(运行但未用) | Neo4j/JanusGraph(待定) | ⚠️ 需明确 | **行动 A-03**：EP 应明确选 Neo4j 5.25+(ESP 已验证可用)，学习路径/知识图谱 DAG 需要图查询 |
| 8 | 搜索 | 不用 ES(用 Loki+TiDB app_logs) | Elasticsearch 8.14.x | ✅ 合理引入 | EP 有课程/题库全文检索+BM25 混合检索需求。ES 8.14 ECK Operator 部署方案已完整 |
| 9 | 对象存储 | 仅 Milvus 后端的 MinIO | S3/MinIO(应用级) | ✅ 升级 | EP 需要存储直播录像/作业附件/代码提交快照 |
| 10 | 湖仓 | 无(纯 TiFlash) | Flink+Iceberg+Spark | ✅ 合理升级 | EP 30 亿用户需独立湖仓，ESP 5000 CCU 不需要 |
| 11 | Schema Registry | 不用 | Pulsar 内置 Schema Registry + Avro | ✅ 新增 | EP CDC 链路需要 schema 治理；采用 Pulsar 内置 Schema Registry，不单独引入 Apicurio(ADR-D01) |

---

## 2. AI/Agent 框架对比

| # | 维度 | ESP(版本/实现) | EP(版本/实现) | 吸收状态 | 差距/行动 |
|---|------|--------------|--------------|---------|----------|
| 1 | Agent 编排 | LangGraph 1.2.10(pinned) | LangGraph 0.2+ | ⚠️ **版本需对齐** | **行动 A-04**：EP 应 pin LangGraph ≥1.0(对齐 ESP 验证版本)。ESP 已验证 `interrupt_before`+`Command(resume)` HITL 模式 |
| 2 | LangGraph Checkpointer | MemorySaver(测试用), RedisSaver(注释未实现) | RedisClusterSaver(设计已实现) | ✅ 修复 ESP 遗留 | EP 修复了 ESP 的"RedisSaver 未实现"问题，checkpointer 直接用 Redis Cluster |
| 3 | LLM 网关 | 无(直连 Anthropic API) | Kong AI / LiteLLM sidecar | ✅ 合理升级 | EP 多模型多 Agent 需要网关层。ESP 直连 API 在小规模可行 |
| 4 | RAG | 无 | BM25(ES)+Vector(Milvus)+Rerank+RRF | ✅ 新增 | EP 教育 Platform 需要 RAG(知识库/题库/代码) |
| 5 | RAG 多租户隔离 | 无 | JWT→namespace FGAC(ADR-A02/D-019) | ✅ 新增(铁律) | **跨租户泄漏 = #1 事故类型**。EP 用 Milvus expression + JWT tenant_id，禁止应用层拼接 |
| 6 | 数字人 | TTS+口型+emotion frame | 事件驱动(TTS+lip-sync+模板) | ✅ 新增 | EP 数字人列为 5 Agent 之一。**行动 A-05**：需明确渲染底层技术栈(ESP 用 TTS audio 驱动口型, EP 应补充具体方案) |
| 7 | 语义缓存 | 无 | Redis LangCache(similarity 0.92) | ✅ 新增 | EP LLM 调用需语义缓存降本 |
| 8 | 模型路由 | 无(单 Anthropic) | RouteLLM 类分类器 | ✅ 新增 | EP 多模型需按意图/难度路由降本 |
| 9 | LLM 评估 | 无(仅 Pact+人工) | 评估集+指标阈值+CI | ✅ 新增 | EP 需要系统化 LLM 评估 |
| 10 | LLM_MODE | mock(K8s 全用) | mock(CI 中) | ✅ 吸收 | EP 继承 ESP 的 `LLM_MODE=mock` 用于 CI |
| 11 | HITL 代码批改 | LangGraph interrupt(仅写作) | 5 Agent 全部支持 + LLM rubric CoT + self-consistency | ✅ 升级 | EP 将 ESP 的 HITL 模式扩展到批改/出题/答疑全部 Agent |
| 12 | Python pulsar-client | optional extra(无 arm64 wheel) | 未提及 | ⚠️ **需补写对策** | **行动 A-06**：EP AI 服务(Python)用 Pulsar，必须显式写明 `PULSAR_ENABLED` 降级开关 + arm64 wheel 检查，避免 ESP 踩过的坑 |

---

## 3. 架构模式对比

| # | 模式 | ESP(版本/实现) | EP(版本/实现) | 吸收状态 | 差距/行动 |
|---|------|--------------|--------------|---------|----------|
| 1 | 单元化/单元化 | 无(单集群) | 混合单元化(D-012, ByteDance 模式) | ✅ 新增 | EP 30 亿用户必须单元化 |
| 2 | 读写分离 | AbstractRoutingDataSource+AOP, esp-rw-java | ep-rw-java(同模式, 11 测试 97.7%) | ✅ 完全吸收 | SDK 直接复用 ESP 验证模式，Feature flag 安全降级 |
| 3 | 多级缓存 | esp-cache L1+L2, 设计完整但未完全接线 | ep-cache(同模式, 31 测试 95.8%) | ✅ 完全吸收 | 修复了 ESP 的"只有 gateway-go 实际用 Redis"问题 |
| 4 | Disruptor | esp-disruptor 4.0.0, 3 服务使用 | ep-disruptor 4.0.0(9 测试 93%) | ✅ 完全吸收 | — |
| 5 | 缓存 Key 格式 | **坑**: Java 明文 vs Python/Go sha256[:24] → 跨语言 MISS | 明文 `{bu}:{svc}:{entity}:{id}`(ADR-T04 禁 sha256) | ✅ 吸收 ESP 血泪 | ADR-T04 直接引用 ESP 事故 |
| 6 | Jackson DefaultTyping | **坑**: @class 跨语言不可反序列化 | 纯 JSON snake_case, 禁 DefaultTyping(ADR-T04) | ✅ 吸收 ESP 血泪 | ADR-T04 直接引用 ESP "定时跨语言炸弹" |
| 7 | 事件驱动 | Pulsar topics(但 writing.graded 无 user_id) | Pulsar 事件总线 + 事件 schema + user_id 强制 | ✅ 吸收+修复 | **行动 A-07**：EP 需在事件 schema 规范中显式写明"user_id 必传, 禁止默认 0"作为 ESP 教训 |
| 8 | 网关 | 自建 Netty edge + Spring MVC(ADR-08 移除 SCG) | ~~Spring Cloud Gateway(BFF)~~ → Netty edge+Disruptor+WebMVC(D-032/ADR-T07 rev2) | ✅ **已对齐** | **行动 A-08(已闭环 rev2)**：EP 原用 SCG，2026-08-04 D-032/铁律6 要求全项目群禁用 Spring Cloud 原生组件，EP 反转为 Netty edge+Disruptor+WebMVC，与 ESP Netty edge 路线完全对齐 |
| 9 | 限流 | Sentinel 1.8.8 本地内存(非分布式) | Sentinel 1.8+ 网关+服务双层 | ⚠️ 需补坑 | **行动 A-09**：EP 需显式说明 Sentinel 规则用 Nacos 下发(分布式), 避免 ESP 的"本地内存规则"问题 |
| 10 | 分布式事务 | Seata 2.2.0 AT 模式(**坑**: SCA→webclient 冲突) | 不用 Seata(幂等键+乐观锁+Pulsar at-least-once) | ✅ 有意排除 | EP 选择不用 Seata，用幂等键+乐观锁替代。**行动 A-10**：需补充 ADR 说明"为何不用 Seata"(引用 ESP 的 SCA bean 冲突 + 升级受阻坑) |
| 11 | DDD | 弱 DDD(anemic repository+service) | 强 DDD(限界上下文=单元边界, 聚合根) | ✅ 升级 | EP 将 DDD 限界上下文作为单元化边界判断准则 |
| 12 | 服务发现/RPC | Dubbo Triple + Nacos v2.4.3 | Dubbo Triple + Nacos 2.x | ✅ 吸收 | **行动 A-11**：EP 需 pin Dubbo 3.3.6 + Nacos 2.4.3(ESP 验证版本, v3.x 升级受阻) |
| 13 | BFF | Netty edge + Spring MVC(Jetty) | ~~Spring Cloud Gateway~~ → Netty edge+Disruptor+WebMVC(D-032) | ✅ **已对齐** | EP 反转决策, 与 ESP 完全对齐 |

---

## 4. 语言与框架版本对比

| # | 语言/框架 | ESP 版本 | EP 版本 | 吸收状态 | 差距/行动 |
|---|----------|---------|---------|---------|----------|
| 1 | Java | 21 + Spring Boot 4.0.7 | Spring Boot 4.0.7(Jetty) | ✅ 完全对齐 | — |
| 2 | Spring Cloud | 2025.1.2 + SCA 2025.1.0.0 | 未明确 pin | ⚠️ **需 pin** | **行动 A-12**：EP 需 pin Spring Cloud 2025.1.2 + SCA 2025.1.0.0 |
| 3 | Go | 1.25.0 + gorilla/websocket v1.5.3 + go-redis v9.21.0 | Go 1.25(同栈) | ✅ 完全对齐 | **行动 A-13**：需补写"go mod tidy 在网络受限环境需 vendor 或本地 module cache"(ESP 教训) |
| 4 | Python Web 框架 | Litestar 2.x + Granian 1.x(ADR-09, 替代 FastAPI/Uvicorn) | **未指定**(gunicorn/uvicorn) | ⚠️ **需决策** | **行动 A-14**：EP AI 服务用 Python，需选择：(a)跟随 ESP 用 Litestar+Granian(Rust ASGI, 高性能)，或(b)用 FastAPI+Uvicorn(更主流)。建议跟随 ESP |
| 5 | Python 版本 | 3.11(writing-py), 3.10(其他) | 3.11(AI), 3.12(sandbox) | ✅ 对齐 | — |
| 6 | Rust | 不用(仅 Granian 内部) | 风控/计费热点 | ✅ 合理新增 | EP 100 万并发需 Rust 热路径 |
| 7 | Dubbo | 3.3.6 Triple | Dubbo Triple(版本未 pin) | ⚠️ 需 pin | 见 A-11 |
| 8 | Disruptor | 4.0.0 | 4.0.0 | ✅ 对齐 | — |
| 9 | Caffeine | 3.2.4 | 3.2.4 | ✅ 对齐 | — |
| 10 | Jackson | 2.19.4 | 2.19.4 | ✅ 对齐 | — |
| 11 | JJWT | 0.13.0 | 0.13.0 | ✅ 对齐 | — |
| 12 | HikariCP | 6.3.3 | 6.3.3 | ✅ 对齐 | — |
| 13 | 前端 React | 19.2.0 + Vite 7 + TS 5.9 | 未在文档中 pin | ⚠️ 需补 | **行动 A-15**：EP 前端版本需在 SP-06 中 pin(对齐 ESP 或更新) |
| 14 | H5 | React 19 + antd-mobile 5.42 + PWA | 未 pin | ⚠️ 需补 | 见 A-15 |
| 15 | 小程序 | Taro 4.2.1 + React 18.3.1(Taro 4 pinned) | 未 pin | ⚠️ 需补 | 见 A-15 |

---

## 5. 测试实践对比

| # | 维度 | ESP | EP | 吸收状态 | 差距/行动 |
|---|------|-----|-----|---------|----------|
| 1 | TDD | Red-Green-Refactor, ≥80% | 同, 公共库≥80%, 安全核心 100% | ✅ 吸收+加强 | — |
| 2 | 测试金字塔 | L1-L4 | L1-L8 | ✅ 升级 | — |
| 3 | Testcontainers | 1.20+ | 1.20+ | ✅ 对齐 | — |
| 4 | Pact 契约测试 | 4.6.16(file-based for Dubbo) | Pact JVM 4.6+ | ✅ 吸收 | **行动 A-16**：EP 需补写"Dubbo Triple 无原生 Pact HTTP transport → file-based"的 ESP 教训 |
| 5 | 性能测试 | Locust(单节点 5000 CCU 不可行, 3 次验证) | Locust 分布式(独立节点) | ✅ 吸收血泪 | **行动 A-17**：EP 需显式写明"禁止单节点压测, 必须 Locust 分布式"(ESP 3 次验证单节点 5000 CCU WS peak=0) |
| 6 | 混沌测试 | Chaos Mesh(pod kill/Redis down/TiDB down) | Chaos Mesh(8 场景) | ✅ 吸收+扩展 | **行动 A-18**：EP 需补写 ESP 的混沌脚本 env 残留坑(${Dur} typo → REDIS_HOST 卡死) |
| 7 | 覆盖率 | Jacoco 0.8.12 | Jacoco 0.8.12 | ✅ 对齐 | — |
| 8 | 安全测试 | SEC-1~SEC-4(基础) | OWASP Top10 + 沙箱逃逸 7 项 | ✅ 升级 | — |
| 9 | E2E | Newman(Postman) | 端到端 + Pact + Locust + Chaos | ✅ 升级 | — |

---

## 6. 部署与 K8s 对比

| # | 维度 | ESP | EP | 吸收状态 | 差距/行动 |
|---|------|-----|-----|---------|----------|
| 1 | 探针三分离 | **血泪**: liveness timeout=1s → pod 雪崩 | ADR-T05 "ESP 血泪", startup/liveness/readiness 分离, timeout≥3s, failureThreshold≥5 | ✅ 吸收血泪 | EP 唯一标注"ESP 血泪"的 ADR |
| 2 | HPA | autoscaling/v2, stabilizationWindow | autoscaling/v2, scaleUp 0s/100%/30s, scaleDown 120s/25% | ✅ 吸收+优化 | EP 补充了 scaleUp stabilization=0(快速扩容) |
| 3 | PDB | minAvailable:1(单节点→drain 阻塞) | minAvailable(每服务) | ✅ 吸收 | **行动 A-19**：EP 需注明"单节点环境 PDB 会阻塞 drain, 生产多节点无此问题"(ESP 教训) |
| 4 | NetworkPolicy | default-deny(但 RD CNI 不执行) | default-deny + allow-list | ✅ 吸收 | **行动 A-20**：EP 需注明"NetworkPolicy 需 Calico/Cilium CNI 才生效"(ESP 教训) |
| 5 | GitOps | 未用 | ArgoCD + Argo Rollouts | ✅ 升级 | — |
| 6 | 可观测 | Fluent Bit→Loki + OTel→Tempo + Prometheus | 同栈 + OTel 双层 Collector | ✅ 吸收 | — |
| 7 | OTel 双层 Collector | DaemonSet(tail-sampling)+Gateway(2 replica) | 同(Edge 10% + Gateway) | ✅ 完全吸收 | **行动 A-21**：EP 需补写 ESP Tempo OOM 教训(tail-sampling 防后端 OOM) |
| 8 | ConfigMap | **坑**: Seata application.yml kubectl 创建→重装丢失→CrashLoop | ConfigMap 管理 | ✅ 吸取教训 | EP 所有配置走 ConfigMap/Nacos, 不用 kubectl 临时创建 |
| 9 | 基础设施复用 | ExternalName → ekb-m2-*.svc.cluster.local | 每单元独立 + 共享基础设施 | ✅ 升级 | — |
| 10 | 单节点 vs 多节点 | **血泪**: 单节点 5000 CCU 不可行(3 次验证) | 多节点 + 单元化 | ✅ 吸收血泪 | EP 从 Day 1 设计多节点 |

---

## 7. 安全对比

| # | 维度 | ESP | EP | 吸收状态 | 差距/行动 |
|---|------|-----|-----|---------|----------|
| 1 | JWT | RS256 + JWKS(ADR-12, 从 HS256 演进) | RS256 + JWKS(ADR-T, 从 Day 1) | ✅ 吸收 | **行动 A-22**：EP 需写明"压测脚本必须用 RS256 mint token"(ESP locustfile 用 HS256→100% 401 血泪) |
| 2 | 伪造头防护 | AuthFilter 剥离 X-User-Id/X-Role | AuthFilter 覆写下游头 | ✅ 吸收 | — |
| 3 | FGAC | 无 | JWT→tenant_id Milvus expression(铁律) | ✅ 新增(铁律) | — |
| 4 | PIPL/GDPR | 无海外 | 海外隔离 + 数据不回流(D-004) | ✅ 新增 | — |
| 5 | 代码沙箱 | 无 | WebContainer + Firecracker 分级信任 | ✅ 新增 | — |
| 6 | Secrets | K8s Secret secretKeyRef | 同 | ✅ 对齐 | — |

---

## 8. ESP 18 条踩坑清单 → EP 吸收状态

| # | ESP 踩坑 | EP 吸收状态 | EP 对策 |
|---|---------|-----------|---------|
| 1 | 单节点 5000 CCU 不可行(3 次验证, WS peak=0) | ✅ 已吸收 | EP 从 Day 1 多节点 + 单元化；sp01-test/sp05-test 禁单节点压测 |
| 2 | liveness timeout=1s → pod 雪崩 | ✅ ADR-T05 "ESP 血泪" | timeout≥3s, failureThreshold≥5, 三探针分离 |
| 3 | Tempo OOM(全量 trace 打爆) | ⚠️ 需补写(P2) | OTel 双层 Collector tail-sampling 10%(已设计), 但需显式注明"防 Tempo OOM" |
| 4 | TraceId 6 个断点(BFF Netty 盲区+gateway-go 空+Java 无 logback) | ⚠️ 需补写(P2) | EP 需写明 traceId 全链路传播契约 |
| 5 | 缓存 Key 跨语言不匹配(Java 明文 vs Python/Go sha256) | ✅ ADR-T04 | 明文 key, 禁 sha256 |
| 6 | Jackson DefaultTyping 跨语言炸弹 | ✅ ADR-T04 | 纯 JSON snake_case, 禁 DefaultTyping |
| 7 | Seata SCA bean 冲突(webclient→CodecsAutoConfiguration) | ✅ ADR-T06 | EP 不用 Seata, 幂等键+乐观锁+Pulsar at-least-once 替代 |
| 8 | Nacos/Seata 升级受阻(v3 需 derby re-import, 镜像不可达) | ✅ 已修复 | Nacos pin 2.4.3 + 注明"不升级 v3 直到镜像可用"；Seata 不引入(ADR-T06) |
| 9 | ConfigMap 必须 in manifest(kubectl 创建→重装丢失) | ✅ 已吸收 | EP 所有配置走 ConfigMap/Nacos |
| 10 | Dubbo timeout 3s→10s(cold cache DEADLINE_EXCEEDED) | ✅ 已修复(sp01-sdd §5.4) | Dubbo 3.3.6 pin, 默认 timeout≥10s, 读 10s/写 15s, 长任务走异步 Pulsar |
| 11 | user_id 传播断点(writing.graded 无 user_id→默认 0) | ✅ 已修复(sp01-sdd §7.2) | EP 事件 schema 强制 user_id 必传, CI Avro/JSON Schema 校验 |
| 12 | ClassCastException user_id(String vs number) | ✅ 已修复(sp01-sdd §7.2) | user_id 类型统一为 string, 跨语言契约注明 |
| 13 | pulsar-client 无 arm64 wheel(Python) | ✅ 已修复(sp04-sdd/sp04-deploy) | pulsar-client optional extra, PULSAR_ENABLED=false 降级开关 |
| 14 | go mod tidy 网络受限(go.sum 不一致) | ⚠️ 需补写(P2-A-13) | EP 需注明 Go 依赖 vendor 策略 |
| 15 | Redis "集群"实为单实例(文档与部署不一致) | ✅ 已修复 | EP 用真 Redis Cluster 8.0(对齐 ESP redis:8.0-alpine) |
| 16 | 混沌脚本 env 残留(${Dur} typo → REDIS_HOST 卡死) | ⚠️ 需补写(P2-A-18) | EP 混沌 Runbook 需注明"脚本执行后验证 env 恢复" |
| 17 | NetworkPolicy RD CNI 不执行 | ⚠️ 需补写(P2-A-20) | EP 需注明需 Calico/Cilium |
| 18 | locustfile HS256 → 100% 401 | ✅ 已修复(sp05-test R-TEST-06) | CI 校验 locustfile 用 RS256+JWKS, 禁 HS256 |
| **19** | **Dubbo Triple STRICT 序列化**(跨语言 ClassNotFoundException) | ✅ ADR-T09(sp01-sad §3.9) | 统一 Protobuf 契约, 禁 STRICT/Java 原生序列化, .proto IDL → 多语言 stub |
| **20** | **session_turns 82M 行 TiDB OOM**(16 次重启) | ✅ ADR-T10(sp01-sad §3.10) | 高频写表 Day 1 强制 RANGE 分区, CI DDL 检查无分区→编译失败 |
| **21** | **Pulsar 4.x 升级评估未完成**(ESP TODO) | ✅ ADR-T11(sp01-sad §3.11) | 3.3.x 锁定, 4.x 升级延迟至 v2.0(2027Q1), 升级前提明确 |
| **22** | **JWT_SECRET <256bit**(HS256 短密钥可暴力破解) | ✅ 已修复(sp01-sdd §5.3) | EP Day 1 用 RS256 非对称签名, 私钥≥2048bit, CI 校验密钥长度 |
| **23** | **learning-worker 分离模式**(API 进程被批量写入拖慢) | ✅ 已修复(sp01-sdd §5.5) | EP_ROLE=api/worker 双角色部署, Disruptor↔Pulsar 双路径降级 |
| **24** | **Redis 版本不一致**(EP 文档 7.x, ESP 实际 8.0-alpine) | ✅ 已修复(TOGAF §6.1) | EP 统一为 Redis 8.0, 对齐 ESP 实际部署 |
| **25** | **Granian 版本不一致**(EP 文档 1.x, ESP 实际 2.7.9) | ✅ 已修复(TOGAF §6.1) | EP 统一为 Granian ≥1.6.0, ESP 实际解析 2.7.9 |

---

## 9. 行动项汇总(按优先级)

### P0 — 必须修复(影响架构正确性)

| ID | 行动 | 涉及文档 | 状态 |
|----|------|---------|------|
| A-01 | TiDB 版本 7.x → 8.5+(对齐 ESP 验证) | TOGAF, sp01-sad, sp02-srs | ✅ 已修复(2026-08-03) |
| A-04 | LangGraph 版本 0.2+ → ≥1.0(对齐 ESP 验证) | sp04-sdd, sp04-srs | ✅ 已修复(2026-08-03) |
| A-07 | 事件 schema 强制 user_id 必传(ESP 踩坑) | sp01-sdd, sp03-sdd | ✅ 已修复(2026-08-03, sp01-sdd §7.2) |
| A-10 | 补 ADR "为何不用 Seata"(引用 SCA bean 冲突坑) | TOGAF ADR 索引 | ✅ 已修复(2026-08-03, ADR-T06) |
| A-14 | Python Web 框架决策(Litestar+Granian vs FastAPI) | sp04-sad, sp04-deploy | ✅ 已修复(2026-08-03) |

### P1 — 应该修复(影响可运维性)

| ID | 行动 | 涉及文档 | 状态 |
|----|------|---------|------|
| A-02 | Milvus 版本 2.4+ → 2.5.0+(对齐 ESP) | sp04-sdd, sp04-deploy | ✅ 已修复(TOGAF §6.1) |
| A-03 | 图库选型明确为 Neo4j 5.25+ | TOGAF, sp04-sad | ✅ 已修复(TOGAF §6.1) |
| A-05 | 数字人渲染底层技术栈明确 | sp04-sdd, sp04-srs | ✅ 已修复(sp04-sdd §2.5) |
| A-06 | Python pulsar-client arm64 降级开关 | sp04-sdd, sp04-deploy | ✅ 已修复(2026-08-03) |
| A-08 | 补 ADR "为何用 SCG 而非 Netty 自建" | sp01-sad | ✅ 已闭环 rev2(2026-08-04, D-032 反转: EP 改用 Netty edge+Disruptor+WebMVC, 与 ESP 对齐, ADR-T07 rev2) |
| A-09 | Sentinel 规则 Nacos 下发(非本地内存) | sp01-sad, sp01-sdd | ✅ 已修复(ADR-T08 + sp01-sdd §5.1) |
| A-11 | pin Dubbo 3.3.6 + Nacos 2.4.3(注明不升 v3) | TOGAF, sp01-sad | ✅ 已修复(TOGAF §6.1 + sp01-sad) |
| A-12 | pin Spring Cloud 2025.1.2 + SCA 2025.1.0.0 | sp01-sad | ✅ 已修复(sp01-sad infra 表) |
| A-17 | 禁止单节点压测(ESP 3 次验证) | sp01-test, sp05-test | ✅ 已修复(sp01-test §6 + sp05-test R-TEST-05/06) |

### 第二轮穷尽审计 — 新增修复项

| ID | 行动 | 涉及文档 | 状态 |
|----|------|---------|------|
| A-23 | Dubbo Triple STRICT 序列化踩坑 + Protobuf 契约强制 | sp01-sad §3.9 (ADR-T09), TOGAF ADR 索引 | ✅ 已修复(2026-08-03) |
| A-24 | session_turns 82M 行 OOM → 大表强制 RANGE 分区 | sp01-sad §3.10 (ADR-T10), TOGAF ADR 索引 | ✅ 已修复(2026-08-03) |
| A-25 | Pulsar 4.x 升级路线图(3.3.x 锁定 + v2.0 评估) | sp01-sad §3.11 (ADR-T11), TOGAF ADR 索引 | ✅ 已修复(2026-08-03) |
| A-26 | JWT_SECRET<256bit 踩坑 + RS256 Day 1 对策 | sp01-sdd §5.3 | ✅ 已修复(2026-08-03) |
| A-27 | Dubbo timeout≥10s 冷缓存 DEADLINE_EXCEEDED 对策 | sp01-sdd §5.4 | ✅ 已修复(2026-08-03) |
| A-28 | learning-worker 分离模式(EP_ROLE=api/worker + 降级) | sp01-sdd §5.5 | ✅ 已修复(2026-08-03) |
| A-29 | Redis 版本 7.x → 8.0(对齐 ESP redis:8.0-alpine) | TOGAF §6.1, sp01-sad infra 表 | ✅ 已修复(2026-08-03) |
| A-30 | Granian 版本 1.x → ≥1.6.0(ESP 实际 2.7.9) | TOGAF §6.1, sp04-sad, sp04-deploy | ✅ 已修复(2026-08-03) |

### P2 — 建议补写(提升文档完备性)

| ID | 行动 | 涉及文档 | 状态 |
|----|------|---------|------|
| A-13 | Go 依赖 vendor 策略(网络受限) | sp01-deploy | 待修复 |
| A-15 | 前端版本 pin(React/Vite/Taro) | SP-06(待产出) | 待 SP-06 |
| A-16 | Dubbo Triple Pact file-based 说明 | sp01-test, sp03-test | 待修复 |
| A-18 | 混沌脚本 env 恢复验证 | sp01-test | 待修复 |
| A-19 | PDB 单节点阻塞 drain 说明 | sp01-deploy | 待修复 |
| A-20 | NetworkPolicy 需 Calico/Cilium 说明 | sp01-deploy | 待修复 |
| A-21 | OTel tail-sampling 防 Tempo OOM 说明 | sp01-sad | 待修复 |
| A-22 | 压测脚本 RS256 mint 说明 | sp01-test | 待修复 |

---

## 10. 总结

### 已完全吸收的 ESP 核心实践(15 项)
1. Pulsar 统一事件总线+CDC(ADR-02 承接 + ADR-D01 CDC 亦走 Pulsar，彻底不引入 Kafka)
2. 读写分离 SDK(AbstractRoutingDataSource + AOP, Feature flag 降级)
3. 多级缓存 L1+L2(Caffeine + Redis + Pulsar 广播失效)
4. Disruptor 高吞吐削峰(tryNext 背压 + 批量刷新)
5. JWT RS256 + JWKS(跨语言验证, AuthFilter 防伪造头, 私钥≥2048bit — sp01-sdd §5.3)
6. 缓存 Key 明文格式(禁 sha256, ESP 跨语言 MISS 血泪)
7. Jackson 纯 JSON(禁 DefaultTyping, ESP 跨语言炸弹血泪)
8. 探针三分离(ESP liveness timeout=1s 雪崩血泪 → ADR-T05)
9. HPA autoscaling/v2 + stabilizationWindow
10. OTel 双层 Collector(Edge tail-sampling + Gateway)
11. Fluent Bit → Loki + OTel → Tempo + Prometheus 可观测栈
12. ConfigMap 管理配置(ESP kubectl 丢失教训)
13. Pact 契约测试(file-based for Dubbo Triple)
14. Testcontainers 集成测试
15. LLM_MODE=mock(CI 不调真实 LLM)

### 第二轮穷尽审计新增吸收(7 项)
16. Dubbo Triple 禁 STRICT 序列化, 统一 Protobuf 契约(ADR-T09 — ESP 跨语言 ClassNotFoundException)
17. 大表强制 RANGE 分区, 防 TiDB OOM(ADR-T10 — ESP session_turns 82M 行 16 次重启)
18. Pulsar 3.3.x 锁定, 4.x 升级延迟至 v2.0(ADR-T11 — ESP TODO 评估完成)
19. JWT_SECRET<256bit 规避: Day 1 RS256 非对称签名(sp01-sdd §5.3 — ESP HS256 短密钥踩坑)
20. Dubbo timeout≥10s 冷缓存对策(sp01-sdd §5.4 — ESP DEADLINE_EXCEEDED 链路雪崩)
21. learning-worker 分离模式: EP_ROLE=api/worker 双部署 + Disruptor↔Pulsar 双路径降级(sp01-sdd §5.5 — ESP ingest 拆分经验)
22. 版本对齐: Redis 7.x→8.0, Granian 1.x→≥1.6.0(对齐 ESP 实际部署 manifest)

### EP 合理升级的维度(11 项)
1. 单元化(ESP 无 → EP 混合单元化 D-012)
2. 湖仓(ESP 仅 TiFlash → EP Flink+Iceberg+Spark)
3. AI 网关(ESP 直连 API → EP Kong AI/LiteLLM)
4. RAG(ESP 无 → EP BM25+Vector+Rerank+FGAC)
5. 数字人(ESP TTS+口型 → EP 事件驱动+模板+渲染栈：CosyVoice/Wav2Lip/Unity/SRS，sp04-sdd §2.5 已补全)
6. Redis 真集群(ESP 单实例 → EP Cluster)
7. 语义缓存(ESP 无 → EP Redis LangCache)
8. 模型路由(ESP 无 → EP RouteLLM)
9. ES 全文检索(ESP 用 Loki+TiDB → EP ES 8.14)
10. Chaos Mesh 混沌工程(ESP 基础 → EP 8 场景)
11. GitOps(ESP 无 → EP ArgoCD+Rollouts)

### EP 有意排除的 ESP 组件(4 项)
1. **Seata**(EP 用幂等键+乐观锁替代 — 需补 ADR)
2. **Spring Cloud Gateway 移除**(~~EP 反而用 SCG — 需补 ADR~~ → 2026-08-04 D-032/铁律6 反转: EP 改用 Netty edge+Disruptor+WebMVC, 与 ESP 完全对齐, ADR-T07 rev2)
3. **Litestar+Granian**(EP 未指定 Python 框架 — 需决策)
4. **单节点部署**(EP 从 Day 1 多节点)

### 待修复差距(9 项 P0/P1)
见 §9 行动项汇总表。
