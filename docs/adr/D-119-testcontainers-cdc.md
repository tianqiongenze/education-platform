# D-119: Testcontainers + 消费者驱动契约（CDC）— 真实基础设施集成测试

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-119 | v1.0 | 2026-08-09 | 已批准 | 平台架构组 | 架构委员会 |

> 关联：D-113（Seata Saga 迁移）、D-045（ep_memory 四级记忆）、D-048（Redis Cluster 部署）、铁律 8（决策对比分析 ≥3 方案）、铁律 0（后端连接失败即 ConnectionError，dev/CI 降级 in-memory）。

## 1. 问题陈述与约束条件

平台 ~849 个测试，但集成测试层存在两处系统性空洞：

1. **数据库集成测试全部 mock 或 H2**：`sp03-order-service` 的 `OrderServiceImplTest` 用 H2 + Mockito stub `PaymentService`；`sp03-payment-service` 用 `ConcurrentHashMap` 内存存储，从未跑过真实 MySQL/TiDB SQL 路径。H2 与 MySQL 方言差异（`IDENTITY` vs `AUTO_INCREMENT`、唯一约束行为、事务隔离）让「单测绿、集成红」成为常态。
2. **无消费者驱动契约（CDC）**：order → payment 跨服务调用（`@DubboReference PaymentService`）仅靠 `Mockito.mock(PaymentService.class)` 验证，生产者改返回字段名或状态码时消费者无感知，契约漂移只能在上联调时暴露。
3. **Python 侧 Redis 集成测试需 `EP_LIVE=1` 连真实集群**：`tests/test_live_redis.py` 依赖 `kubectl port-forward` 到 K8s Redis Cluster，CI 中始终 skip，`ep_memory` L0/L1 的 Redis 路径在 CI 中 0 覆盖。

约束条件（铁律 + 任务硬约束）：

1. **不连共享集群基础设施**：TiDB / Redis Cluster / Pulsar 一律不连，用 Testcontainers 隔离容器。
2. **自包含**：Testcontainers 测试自己起停容器，不留外部状态。
3. **`@Tag("testcontainers")`**：所有 Testcontainers 测试打 tag，CI 可选排除以做 fast feedback。
4. **技术栈对齐**：Java 21 / Spring Boot 4.0.7 / Dubbo 3.3.6 / Spring Cloud 2025.1.2；Python 3.12 + testcontainers 4.x。
5. **Maven 依赖版本由 BOM 管理**：testcontainers 2.0.5 由 spring-boot-dependencies BOM 管理；spring-cloud-contract 5.0.3 由 spring-cloud-dependencies BOM 管理，不手写版本。
6. **铁律 8**：≥3 方案对比后定论。
7. **铁律 0**：dev/CI 下 ep_memory 降级 in-memory fallback 不变；Testcontainers 测试是新增的「真实 Redis 验证」层，不替代 fallback 行为。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Testcontainers + Spring Cloud Contract（已选）** | Java 侧 Testcontainers 起 MySQL 容器 + SCC Groovy DSL 契约；Python 侧 testcontainers 起 Redis 容器 |
| B | **WireMock + Pact** | Java 侧全 WireMock stub，Pact 做跨服务 CDC；Python 侧 fakeredis 替代真实 Redis |
| C | **嵌入式 H2 + Mockito** | 维持现状：H2 内存库 + Mockito mock 所有跨服务依赖，Python 侧仅 in-memory fallback |

## 3. 对比维度表

| 维度 | A. Testcontainers + SCC（已选） | B. WireMock + Pact | C. 嵌入式 H2 + Mockito |
|------|--------------------------------|---------------------|--------------------------|
| **真实 SQL 路径** | 是（真实 MySQL 8.0 容器，TiDB 兼容） | 否（WireMock 不起 DB） | 否（H2 方言 ≠ MySQL） |
| **契约验证** | SCC 双向：生产者 verifier 生成测试 + 消费者 stub runner | Pact 双向：消费者生成 pact 文件 + 生产者 verify | 单向，仅消费者 mock，生产者无约束 |
| **Python Redis 覆盖** | 是（testcontainers Redis 容器，真实 RPUSH/LRANGE） | 否（fakeredis 行为近似但不保证与 Redis 一致） | 否（仅 in-memory dict） |
| **CI 依赖** | Docker（ubuntu-latest 自带） | 无额外（纯 JVM） | 无额外 |
| **方言一致性** | MySQL 8.0（生产 TiDB MySQL 协议兼容） | N/A | H2（`IDENTITY`、无 `AUTO_INCREMENT`、唯一约束报错码不同） |
| **契约漂移检测** | 生产者改字段 → verifier 测试红 | 生产者改字段 → pact verify 红 | 生产者改字段 → 消费者 mock 不变，静默漂移 |
| **启动开销** | ~5-15s/容器（MySQL） | <1s | 0 |
| **生态对齐** | Spring Boot 4.0.7 原生支持 testcontainers BOM；SCC 是 Spring Cloud 生态一等公民 | Pact 跨语言但 Java 侧需 pact-jvm，与 Spring 集成需额外配置 | 无新依赖 |
| **维护成本** | 低（BOM 管理版本，DSL 声明式） | 中（Pact broker 需部署，pact 文件版本管理） | 低但无效（覆盖假象） |

## 4. 决策

**选 A：Testcontainers + Spring Cloud Contract。**

理由：

1. **覆盖真实 SQL 路径**：H2 与 MySQL 方言差异是既有 bug 的温床（`OrderModel` 用 `@GeneratedValue(strategy = IDENTITY)`，H2 与 MySQL 行为不同）。A 方案用真实 MySQL 8.0 容器（TiDB 兼容 MySQL 协议），让 `ddl-auto=create-drop` + JPA 生成的 DDL 在真实库上验证，唯一约束、事务隔离、乐观锁（`@Version lockVersion`）全部落地。B/C 均无法覆盖。
2. **双向 CDC**：SCC 的 verifier 插件在生产者（sp03-payment）侧从 Groovy 契约**生成测试**，消费者（sp03-order）侧用 `@AutoConfigureStubRunner` 加载 WireMock stub 验证调用。任一侧改契约都会立即红，契约漂移在 CI 阶段即暴露。Pact（B）也能双向但需额外部署 Pact broker，运维成本高；C 完全无生产者侧约束。
3. **生态原生**：Spring Boot 4.0.7 BOM 已管理 `testcontainers 2.0.5` 与 `spring-boot-testcontainers 4.0.7`；Spring Cloud 2025.1.2 BOM 管理 `spring-cloud-contract 5.0.3`。零手写版本，零额外 broker。与既有 Dubbo / Spring Cloud Alibaba 技术栈同源。
4. **Python 侧真实 Redis**：`ep_memory` L0（`RPUSH`/`LRANGE`/`EXPIRE`）、L1（`SET ... EX`/`GET`）的 Redis 命令路径在 CI 中 0 覆盖。fakeredis（B）行为近似但不保证与 Redis 集群行为一致（如 `EXPIRE` 在集群模式下的 slot 行为）；Testcontainers 用真实 Redis 容器，覆盖到位。
5. **C 是现状，已被证伪**：~849 测试但 0 真实集成，维持 C 即维持覆盖假象。

## 5. 实施细节

### 5.1 sp03-order-service（Java — Testcontainers MySQL）

- `pom.xml` 新增（test scope，BOM 管理版本）：
  - `org.testcontainers:testcontainers`、`org.testcontainers:mysql`、`org.testcontainers:junit-jupiter`
  - `org.springframework.boot:spring-boot-testcontainers`
  - `org.springframework.cloud:spring-cloud-starter-contract-stub-runner`（消费者侧 CDC）
- `surefire-plugin` 配 `<excludedGroups>live</excludedGroups>`，注释标注 `@Tag("testcontainers")` 可追加排除。
- `src/test/java/com/ep/order/TestcontainersConfig.java`：
  - `@Testcontainers @TestConfiguration`，`@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")`（db=orderdb_test，withReuse(false)）。
  - `@Bean @Primary StateMachineEngine` 返回 `StubStateMachineEngine`（no-op），让 Spring context 在无 Seata TC 时也能 boot。
- `src/test/java/com/ep/order/OrderServiceTestcontainersIT.java`：
  - `@Tag("testcontainers")`、`@SpringBootTest`、`@ActiveProfiles("test")`、`@DynamicPropertySource` 注入容器 JDBC URL。
  - 测试：`createOrder_persistsToRealMySql`、`createOrder_duplicateIdempotencyKey_rejectedByUniqueConstraint`、`payOrder_stateTransitionPersistedToRealMySql`、`refundOrder_stateTransitionPersistedToRealMySql`、`getOrderById_readsFromRealMySql`。
  - `StubPaymentService` 实现 `PaymentService` 接口，createPayment/refund 返回 SUCCESS。
- `src/test/resources/application-test.yml`：port 0、MySQLDialect、ddl-auto=create-drop、Dubbo registry N/A、Pulsar disabled、Seata enabled=false。

### 5.2 sp03-payment-service（Java — Testcontainers MySQL + SCC 生产者）

- `pom.xml` 新增（test scope）：
  - Testcontainers 三件套 + `spring-boot-testcontainers`
  - `spring-cloud-starter-contract-verifier` + `io.rest-assured:spring-mock-mvc`
  - `spring-cloud-contract-maven-plugin` 5.0.3（baseClassMappings → `PaymentServiceContractBase`，executions: convert / generateStubs / generateTests）
- `src/main/java/com/ep/payment/controller/PaymentController.java`（**新增**）：
  - payment 服务此前**仅 Dubbo RPC，无 HTTP controller**。新增 `@RestController @RequestMapping("/api/payments")`，`POST /api/payments` → 201，`POST /api/payments/{orderId}/refund` → 200。
  - Dubbo 路径仍是生产规范；REST surface 是 SCC 契约验证的薄适配层，亦可供 BFF / 外部客户端调用。
- `src/test/java/com/ep/payment/PaymentServiceContractBase.java`：
  - `@SpringBootTest(webEnvironment = MOCK)`、`@MockBean PaymentService`、`RestAssuredMockMvc.webAppContextSetup(context)`。
- `src/test/resources/contracts/payment_create.groovy`、`payment_refund.groovy`：
  - SCC DSL：`Contract.make { request { method POST() url '/api/payments' body([...]) } response { status 201 body([...]) } }`。
- `src/test/java/com/ep/payment/PaymentServiceTestcontainersIT.java`：
  - `@Tag("testcontainers")`，MySQL 容器（db=paymentdb_test），验证 Spring context boot + MockPaymentChannel SPI 流程。
  - **诚实声明**：payment 生产存储仍是 `ConcurrentHashMap`（非 JPA），TiDB-backed repository swap 已设计但未接线；IT 验证 boot + SPI 流程，待 repository 落地后补真实持久化断言。

### 5.3 sp03-order-service 消费者侧 CDC

- `src/test/java/com/ep/order/PaymentServiceContractTest.java`：
  - **不**打 `@Tag("testcontainers")`（用 WireMock in-process，不起容器）。
  - `@AutoConfigureStubRunner(ids = "com.ep:sp03-payment-service:+:stubs:8090", stubsMode = LOCAL)`。
  - 用 `java.net.http.HttpClient` 发请求到 `http://localhost:8090`，断言 201/200 + body 字段。
  - 前置：sp03-payment-service 须先 `mvn install` 生成 stubs jar 到本地仓库。

### 5.4 sp04-ai（Python — Testcontainers Redis）

- `pyproject.toml` `[tool.poetry.group.dev.dependencies]` 新增：
  - `testcontainers = "^4.8"`、`testcontainers[redis] = "^4.8"`
- `[tool.pytest.ini_options] markers` 新增 `integration` marker。
- `tests/integration/test_redis_integration.py`（**新建 `tests/integration/` 目录**）：
  - `pytestmark = [pytest.mark.asyncio, pytest.mark.integration]`
  - `redis_container` fixture：`from testcontainers.redis import RedisContainer`，起容器、yield `redis://host:port/0`、stop 容器。Docker 不可用时 `pytest.skip`。
  - 测试：
    - `test_l0_immediate_add_then_get_real_redis` — L0 RPUSH/LRANGE 真实 Redis。
    - `test_l0_immediate_overflow_triggers_eviction` — L0 超 `l0_max_messages` 触发溢出。
    - `test_l1_working_summary_round_trip_real_redis` — L1 SET/GET 摘要。
    - `test_manager_add_propagates_to_l0_and_l1` — MemoryManager.add() 路由 L0 + 溢出摘要进 L1。
    - `test_manager_get_context_aggregates_l0_and_l1` — get_context() 聚合 L0+L1 进 ContextWindow。
    - `test_l0_persistence_is_real_redis_not_fallback` — 新建 ImmediateMemory 实例从同一 Redis 读到旧数据，证明落盘真实 Redis 而非 per-instance fallback dict。

### 5.5 CI

- `.github/workflows/java-test.yml` 的 `Run tests (exclude live)` 步骤加注释，标注 sp03-order-service / sp03-payment-service 含 `@Tag("testcontainers")` 测试，ubuntu-latest 自带 Docker 默认运行；fast feedback job 可追加 `-DexcludedGroups=live,testcontainers`。sp04-ai 的 Python Testcontainers IT 由 Python CI workflow 运行。

### 5.6 验证

- 本 ADR 不连 live 集群。Testcontainers 测试在 CI（ubuntu-latest + Docker）中运行；本地无 Docker 时 `pytest.skip` / Maven 不选该 tag。
- SCC 契约：生产者侧 `mvn test` 触发 contract-maven-plugin 生成 verifier 测试；消费者侧 `PaymentServiceContractTest` 从本地 Maven repo 加载 stubs。

## 6. 后果

### 正面

- **真实 SQL 路径覆盖**：`OrderModel` 的 `@Id IDENTITY`、`@Version lockVersion` 乐观锁、`idempotencyKey` 唯一约束在真实 MySQL 8.0 上验证，「单测绿、集成红」的方言差异空洞被填补。
- **契约漂移 CI 即暴露**：order / payment 任一侧改契约字段，SCC verifier 或 stub runner 立即红，无需上联调。
- **Python Redis 路径 CI 覆盖**：`ep_memory` L0/L1 的 `RPUSH`/`LRANGE`/`SET ... EX`/`GET` 命令路径从 CI 0 覆盖升到 6 个测试，`EXPIRE` TTL、溢出驱逐、跨实例持久化均验证。
- **隔离无副作用**：Testcontainers 容器随测试起停，不污染共享集群，符合 D-048 Redis Cluster / TiDB 生产隔离要求。
- **fast feedback 可选**：`@Tag("testcontainers")` + pytest `integration` marker 允许 CI 分层——PR 预检跑 fast（排除 testcontainers），合并前跑 full。

### 负面 / 风险

- **CI 耗时增加**：MySQL 容器冷启 ~5-15s，order + payment 两个 IT 约增 30-60s。缓解：`withReuse(false)` 保证隔离但牺牲复用；后续可评估 `~/.testcontainers.properties` 启用 reuse（开发机）。
- **Docker 依赖**：CI runner 必须有 Docker（ubuntu-latest 自带，自建 runner 需确认）。无 Docker 时测试 skip 而非 fail，不阻塞核心套件。
- **payment REST controller 新增攻击面**：`PaymentController` 是新 HTTP surface，虽仅薄适配 `PaymentService`，但需纳入后续 API 安全审计（Aikido / 42Crunch）。
- **SCC 学习成本**：Groovy DSL 对团队是新引入，契约文件维护需约定归属（生产者维护契约，消费者提 PR 改契约）。
- **payment 持久化空洞仍在**：`PaymentServiceTestcontainersIT` 诚实声明生产存储仍是 `ConcurrentHashMap`，TiDB repository 未接线。容器起了但真实持久化断言待补——本 ADR 不掩盖此既有技术债，而是显式记录。

### 中性

- 方案 B（Pact）推迟评估。若未来跨语言 CDC 需求扩展到 Python ↔ Java 混合栈，Pact 的跨语言 pact 文件交换可能比 SCC（JVM 生态内）更合适，可在 D-12x 重新评估。当前 order↔payment 是纯 JVM，SCC 足够。

## 7. 审计项

- A-27：Testcontainers 测试在 CI 中实际运行验证（D-119 落地后跟踪首个 PR 的 CI 日志）。
- A-28：SCC 契约漂移检测有效性验证（人为改 payment 返回字段名，确认 verifier/stub runner 红）。
- A-29：`PaymentController` REST surface 纳入 API 安全审计（Aikido SAST + 42Crunch audit）。
- A-30：payment TiDB-backed repository 落地后补真实持久化断言（清偿 D-119 诚实声明中的技术债）。
