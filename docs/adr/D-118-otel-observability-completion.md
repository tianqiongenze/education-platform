# D-118: OTel 可观测性补全 — gateway-go + sp04-ai 链路追踪接入

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-118 | v1.0 | 2026-08-09 | 已批准 | 可观测性组 | 架构委员会 |

> 关联：D-035（K8s 部署运维铁律 / 铁律 5 尾采样）、D-117（JWT 密钥外化）、ADR-A02（RS256 签名）、铁律 8（决策对比分析 ≥3 方案）、审计项 A-24（链路连续性）。

## 1. 问题陈述与约束条件

平台已部署 OTel Collector（`deploy/k8s/base/otel-collector.yaml`），含 tail_sampling、Prometheus、Jaeger、Loki 四类 exporter，Java 服务（bff-java 等）通过 Spring OTel starter 已接入。但两个关键服务 **零 OTel 代码**：

- `services/gateway-go/` — Go API/WebSocket 网关（端口 8081），入口流量无 trace 上下文。
- `services/sp04-ai/` — Python AI 中台（端口 8086，Litestar + granian ASGI），AI 调用链断在入口。

**后果：分布式 trace 链断裂。** 请求经 gateway-go 进入时无 traceparent 注入，下游服务（bff-java / sp04-ai / sp03-business）即便各自产生 span，也无法拼成一条完整链路，Jaeger 上只能看到孤立分片。尾采样（tail_sampling）依赖跨服务 span 关联做决策，链路断裂直接导致采样策略失效——错误链路可能被误丢，正常链路可能被误留。

约束条件：

1. **仅代码变更**：不连接 live OTel Collector，CI 中仅编译/单测验证。
2. **Go 版本对齐**：`go.mod` 声明 `go 1.25`，OTel 依赖须兼容（`go.opentelemetry.io/otel v1.30.0` + `contrib v0.55.0`）。
3. **框架对齐**：sp04-ai 使用 **Litestar 2.14**（非 FastAPI），ASGI 协议；instrumentation 须选 ASGI 而非 FastAPI 专用。
4. **失败不宕机**：tracer 初始化失败时只打日志、降级为 no-op tracer，绝不 panic / crash 服务（D-118 硬约束）。
5. **低开销**：中间件每请求 < 1ms 额外延迟。
6. **W3C TraceContext**：标准 `traceparent` / `tracestate` 头传播，与 Java/Go 既有实现互通。
7. **铁律 8**：≥3 方案对比后定论。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **OTel SDK 手动 instrumentation（已选）** | 各服务内显式初始化 TracerProvider + 中间件，OTLP gRPC 导出到 collector |
| B | **OTel Operator 自动注入** | K8s 集群装 OpenTelemetry Operator，通过 annotation 自动注入 language agent / init 容器，零代码 |
| C | **Jaeger 原生 client** | 直接用 Jaeger client（Java/Go/Python）发 UDP/thrift 到 jaeger-agent，绕过 OTel 生态 |

## 3. 对比维度表

| 维度 | A. OTel SDK 手动（已选） | B. Operator 自动注入 | C. Jaeger 原生 client |
|------|---------------------------|----------------------|------------------------|
| **代码改动** | 中（每服务 ~100 行） | 零代码，仅 K8s annotation | 中（每服务 ~80 行） |
| **生态对齐** | OTel 全栈（trace/metrics/logs 统一） | OTel 全栈 | 仅 trace，metrics/logs 需另接 |
| **Collector 兼容** | OTLP，已是 collector 主协议 | OTLP | thrift/UDP，需 jaeger-receiver，与现有 OTLP pipeline 并行 |
| **跨语言一致** | 是（Go/Python/Java 同一套 API 语义） | 是 | 否（各语言 client 行为不一） |
| **运维负担** | 低（随服务发版，无集群侧组件） | 高（需装 Operator + instrument CRD + 版本维护） | 低 |
| **升级路径** | OTel GA，稳定 | Operator 仍 beta，instrumentation 模板版本耦合 | Jaeger client 已 deprecate，官方推 OTel |
| **失败隔离** | tracer init 失败仅降级单服务 | Operator 故障影响全集群所有服务 | client 崩溃可能拖垮进程 |
| **可观测性自身** | SDK 自带 self telemetry | Operator 有 self telemetry | 无 |

## 4. 决策

**选 A：OTel SDK 手动 instrumentation。**

理由：

1. **生态一致性**：平台已选定 OTel 为统一可观测后端（D-035 铁律 5 的尾采样即建在 OTel Collector 之上）。A 方案与 Java 服务的 OTel starter 行为对称，traceparent 头跨语言互通，tail_sampling 跨服务关联才能生效。
2. **失败隔离**：手动 init 允许在 collector 不可达时降级为 no-op tracer（D-118 硬约束），不影响业务流量。Operator 自动注入一旦 instrument CRD 配置错误或 agent 镜像拉取失败，会阻塞所有被注入 Pod 的启动。
3. **运维简单**：A 方案无集群侧新组件，随服务滚动发版上线，符合 D-035「K8s 部署最小化」精神。B 方案引入 Operator 是新的控制平面故障域。
4. **Jaeger client 已 deprecate**：CNCF 已将 Jaeger client 迁移至 OTel SDK，C 方案是技术倒退，且与 metrics/logs 双线生态割裂。

## 5. 实施细节

### 5.1 gateway-go（Go）

- 新增 `services/gateway-go/src/middleware/otel.go`：
  - `InitTracer()` — 创建 OTLP gRPC exporter（`otlptracegrpc`），`TracerProvider` 资源属性含 `service.name=gateway-go`、`service.namespace=education-platform`、`deployment.environment`。设全局 propagator 为 `TraceContext + Baggage`。
  - `OTelHTTPMiddleware` — `otelhttp.NewHandler` 包裹 mux，每请求一个 server span；`/healthz` `/health` `/metrics` 等探针路径跳过 tracing 以降噪。
  - `ShutdownTracer(ctx)` — 优雅 flush。
  - 任何 init 失败只 `log.Printf` 警告并返回 nil，全局 tracer 保持 no-op。
- `src/main.go`：启动调 `InitTracer()`，deferred `ShutdownTracer`；`http.Server` 用 `OTelHTTPMiddleware(mux)`；加 graceful shutdown（SIGTERM 先停 HTTP 再 flush span）。
- `go.mod` 新增：`go.opentelemetry.io/otel v1.30.0`、`.../sdk v1.30.0`、`.../otlp/otlptrace/otlptracegrpc v1.30.0`、`go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.55.0`、`google.golang.org/grpc v1.66.0`。

### 5.2 sp04-ai（Python / Litestar）

- 新增 `ai_gateway/observability/otel.py`：
  - `setup_telemetry()` — 构 `Resource`（service.name=sp04-ai、service.version、deployment.environment），`TracerProvider` + `BatchSpanProcessor`，OTLP exporter 支持 gRPC（默认）与 http/protobuf（`OTEL_EXPORTER_OTLP_PROTOCOL` 切换）。设全局 propagator 为 `TraceContext`。
  - `shutdown_telemetry()` — 优雅 flush。
  - 任何 init 失败只 `logger.error` 返回 None。
- `ai_gateway/app.py` `create_app()`：
  - 调 `setup_telemetry()`。
  - `OpenTelemetryASGIMiddleware`（来自 `opentelemetry-instrumentation-asgi`）加入 Litestar `middleware` 列表 —— **非 FastAPIInstrumentor**，因为 sp04-ai 是 Litestar（ASGI）。
  - `HTTPXClientInstrumentor().instrument()` 自动 instrument 出站 httpx 调用（LLM / 向量库）。
  - `on_shutdown` 注册 `shutdown_telemetry` hook。
- `pyproject.toml` 新增：`opentelemetry-sdk ^1.30`、`opentelemetry-exporter-otlp ^1.30`、`opentelemetry-instrumentation-asgi ^0.55b0`、`opentelemetry-instrumentation-httpx ^0.55b0`。

### 5.3 K8s 配置

- `deploy/k8s/base/configmap.yaml`：`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`、`OTEL_EXPORTER_OTLP_PROTOCOL=grpc`、`OTEL_TRACES_SAMPLER=parentbased_traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.1`（head 采样仅降 SDK egress，最终保留由 collector tail_sampling 决策）。
- `gateway-go.yaml` / `sp04-ai.yaml`：显式声明上述 env，覆盖 ConfigMap 默认以便单服务调参。

### 5.4 验证

- `services/integration-tests/.../TracePropagationTest.java`：纯单测，校验 W3C traceparent 格式（`00-<32hex>-<16hex>-<2hex>`）、跨跳 trace-id 不变、malformed 被拒、version 固定 00。无需 live collector。

## 6. 后果

### 正面

- **链路连续**：gateway-go → bff-java → sp04-ai → 下游 LLM/向量库 整条 trace 在 Jaeger 可视，故障定位从「分片猜测」变「一条链路定位」。
- **尾采样生效**：tail_sampling 跨服务 span 关联成立，100% 错误链路保留、10% 正常链路采样的策略真正落地（D-035 铁律 5）。
- **metrics/logs 关联**：trace_id 可注入日志（后续 D-119 日志关联），排查效率再提。
- **统一语义**：Go/Python/Java 三语言同用 OTel API，跨语言 trace 语义一致。

### 负面 / 风险

- **每请求 ~0.3-0.8ms 开销**：otelhttp / ASGI middleware span 创建 + OTLP 序列化。在 < 1ms 约束内，但高 QPS 下需观察 P99。缓解：探针路径跳过 tracing；BatchSpanProcessor 5s 批量。
- **依赖膨胀**：gateway-go go.mod 增 5 个 OTel + grpc 依赖；sp04-ai 增 4 个 opentelemetry-* 包。CVE 面略扩，纳入 dependabot。
- **collector 单点依赖**：tracer init 失败已降级 no-op，但 collector 长期不可达会静默丢 trace。需配套 collector 可用性告警（D-035 已有 PDB minAvailable=1）。
- **手动 instrumentation 维护成本**：新增 HTTP 端点 / 出站调用须记得 wrap；后续可评估 Operator 自动注入作为补充（非替代）。

### 中性

- ADR 推迟了 Operator 自动注入（方案 B）的评估。若未来 Java 服务也需统一接入，可在 D-12x 重新评估 B 作为「零代码补齐存量服务」手段，但 gateway-go / sp04-ai 仍以 SDK 方式保留显式控制。

## 7. 审计项

- A-24：trace 链路连续性验证（本 ADR + `TracePropagationTest.java`）。
- A-25：tracer init 失败降级路径单测（后续补 Go/Python 侧单测）。
- A-26：OTel 依赖 CVE 监控纳入 dependabot / Renovate。
