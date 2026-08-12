# TOGAF 解决方案构建块登记表 (Solution Building Blocks Registry)

| 编号 | 版本 | 日期 | 状态 | 编制 | 对应 |
|------|------|------|------|------|------|
| EP-TOGAF-SBB | v1.0 | 2026-08-12 | Approved | ZCode(架构治理) | D-157 / 00-顶层§6 |

> **文档定位**：TOGAF ADM Phase E 产物。登记 education-platform 选用的具体产品/技术(SBB)，每个 SBB 映射到一个或多个 ABB(见 02-ABB Registry)。版本号锁定与 ADR 锚点见 00-顶层§6.1 基础设施栈。

---

## 1. SBB 登记表

| SBB-ID | 产品/技术 | 版本 | 供应商 | 用途 | 映射 ABB | TOGAF分类 |
|--------|----------|------|--------|------|---------|----------|
| SBB-01 | Spring Boot | 4.0.7 | VMware/Pivotal | Java 全栈基座 + 虚拟线程(D-039) + GraalVM AOT(D-040) | ABB-01/03/04/05/06/07/08/09/10/11/13/14/17/18/20/21/22 | Application/Technology |
| SBB-02 | Spring Cloud Alibaba | 2025.1.0.0 | Alibaba | Nacos 注册/配置 + Sentinel 限流 + Dubbo Triple(SC 2025.1.x 对齐 SB4.0) | ABB-01/03/04..14 | Application/Governance |
| SBB-03 | Dubbo | 3.3.6 | Apache | Triple(HTTP/2+Protobuf) 契约先行 RPC,禁 STRICT(ADR-T09) | ABB-04/05/08/11/12/14 | Application |
| SBB-04 | TiDB | 8.5 LTS | PingCAP | HTAP 在线库 + TiFlash 列存 + AUTO_RANDOM + TiCDC | ABB-25 | Data/Technology |
| SBB-05 | Redis | 8.0 | Redis Ltd. | L2 缓存/会话/路由表/CRDT 多活(redis:8.0-alpine) | ABB-26 | Technology |
| SBB-06 | Apache Pulsar | 3.3.x | Apache | CDC(ns=cdc://) + 领域事件 + geo-rep + Avro Schema Registry(4.x 延至 v2.0, ADR-T11) | ABB-24 | Technology |
| SBB-07 | Milvus | 2.5+ | Zilliz | 向量索引/语义检索/查重,namespace+FGAC | ABB-27 | Data/AI |
| SBB-08 | Nacos | 2.4.3 | Alibaba | 注册中心/配置中心/路由表/限流规则下发(pin,不升 v3) | ABB-01/03/02/08 | Technology/Governance |
| SBB-09 | Sentinel | 1.8+ | Alibaba | 网关+服务双层限流降级,规则走 Nacos 分布式(ADR-T08) | ABB-01 | Technology |
| SBB-10 | Kubernetes | 1.28+ | CNCF | 容器编排/HPA/PDB/NetworkPolicy | 全部部署 | Technology |
| SBB-11 | ArgoCD | — | CNCF | GitOps 声明式部署(D-114) | 全部部署 | Technology |
| SBB-12 | JIB | — | Google | 无 Dockerfile 镜像构建(D-115) | 全部 Java 服务 | Technology |
| SBB-13 | Resilience4j | — | RobWin | 熔断/重试/限流(SB4 推荐,替代 Hystrix) | ABB-01/06/07 | Application |
| SBB-14 | Seata Saga(GO模式) | — | Alibaba | Saga 状态机引擎(ADR-D113: 由自研迁至 Seata Saga) | ABB-17 | Application |
| SBB-15 | ArchUnit | — | TNG | 架构守卫(DDD 分层/包规范/依赖方向, P-05) | 全部 Java | Governance |
| SBB-16 | PITest | — | hcoles | 变异测试,CI 门禁 | 全部 Java | Governance |
| SBB-17 | JaCoCo | — | EclEmma | 覆盖率采集 ≥80%,安全核心 100%(P-05) | 全部 Java | Governance |
| SBB-18 | Playwright | — | Microsoft | E2E 跨浏览器测试 | ABB-28 | Governance |
| SBB-19 | k6 | — | Grafana Labs | 性能/压测(替代 Locust) | 全部 | Governance |
| SBB-20 | Vite | 6 | Evan You | 前端构建(pnpm monorepo + turbo) | ABB-28 | Application |
| SBB-21 | React | 19 | Meta | 前端 UI 框架(concurrent + server components) | ABB-28 | Application |
| SBB-22 | Ant Design | 5 | Ant Group | 企业级 UI 组件库 | ABB-28 | Application |
| SBB-23 | Litestar + Granian | 2.x / ≥1.6.0 | Litestar Org | Python ASGI(Rust 内核,替代 FastAPI/Uvicorn, ADR-09) | ABB-13/15 | Application |
| SBB-24 | Go stdlib + gorilla/websocket | 1.22+ | Google | WS 网关 Go 实现 | ABB-02 | Application |
| SBB-25 | OpenTelemetry SDK | — | CNCF | W3C traceparent + OTLP 导出 + tail-sampling | ABB-23 | Technology |
| SBB-26 | Caffeine | — | Ben Manes | L1 进程内缓存(JVM) | ABB-18 | Technology |
| SBB-27 | GraalVM native-image | 0.10.4(plugin) | Oracle | AOT 原生镜像(D-040, JIT/Native 双模式) | 全部 SB4 服务 | Technology |
| SBB-28 | Karmada | — | CNCF | 多集群调度(4-cell, D-041) | 跨 cell 部署 | Technology |
| SBB-29 | Apache Flink | 1.x | Apache | CDC 入湖 + 实时指标 + CEP 风控 | ABB-16 | Data |
| SBB-30 | Apache Iceberg | 1.x | Apache | Lakehouse 流批一体表格式(D-013) | ABB-16 | Data |
| SBB-31 | Testcontainers | — | Atomic Jar | 集成测试真依赖(D-119) | 全部 | Governance |

---

## 2. 版本锁定与 ADR 锚点

| SBB | 锁定版本 | ADR | 升级窗口 |
|-----|---------|-----|---------|
| SBB-01 Spring Boot | 4.0.7(铁律,不可降级) | D-032/D-039 | 主版本禁降级 |
| SBB-02 SCA | 2025.1.0.0(强映射 SB4.0) | D-032 | 跟随 SB4 release train |
| SBB-03 Dubbo | 3.3.6(禁 STRICT) | ADR-T09 | 待社区 Triple 稳定后评估 |
| SBB-04 TiDB | 8.5 LTS | ADR-T-DB | LTS 切换需 ADR |
| SBB-06 Pulsar | 3.3.x(4.x 延至 v2.0) | ADR-T11 | v2.0 周期评估 |
| SBB-08 Nacos | 2.4.3(不升 v3 直到镜像可用) | — | v3 GA 后评估 |
| SBB-27 GraalVM plugin | 0.10.4(pin) | D-040 | 跟随 SB4 AOT 演进 |

## 3. 禁用清单(铁律)

- ❌ Spring Cloud Netflix / SCG 原生组件(D-032 / 铁律6)
- ❌ Kafka(CDC 统一走 Pulsar, ADR-D01)
- ❌ Seata AT/AT 模式(仅用 Saga, ADR-T06; 自研→Seata Saga 见 D-113)
- ❌ Dubbo STRICT 序列化(ADR-T09)
- ❌ FastAPI/Uvicorn(用 Litestar+Granian, ADR-09)
- ❌ Spring AI Alibaba(与 SB4.0 铁律冲突,见 00-顶层§19)
- ❌ Docker/gVisor 沙箱(用 Firecracker, D-022)

## 4. 变更管理

- 新增 SBB：提交 ADR + 本表登记，经架构委员会批准
- SBB 升级：版本锁定列必须更新，触发回归测试 + 兼容性验证
- SBB 废弃：映射的 ABB 需同步迁移，关联 SBB 标记 Deprecated

---

**关联文档**：00-顶层§6.1 基础设施栈 / 02-ABB Registry / 04-SIB / 06-Governance Log
