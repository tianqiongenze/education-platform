| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| ADR-INF-04 | v1.0 | 2026-08-05 | 已批准 | 基础设施架构组 | 架构委员会 |

# ADR-INF-04: RPC 框架选型 — Dubbo3 vs gRPC+Istio vs Spring Cloud OpenFeign

> 关联：铁律 6（SCA+Dubbo 锁定，D-032）、铁律 8；SP-01~SP-07 全 Java 服务。

## 1. 问题陈述与约束条件

教育平台全 Java 服务（SP-01 技术中台、SP-02 数据中台 Java 侧、SP-03 业务中台、SP-05 IT 后端 BU、SP-07 知识中台 Java 侧）之间需统一 RPC 框架，要求：

1. **Java 21 虚拟线程兼容**（D-039）：不能 pin 虚拟线程。
2. **GraalVM AOT 兼容**（D-040）：service 层兼容 native（非强制）。
3. **多语言支持**：与 Python 侧（SP-04）互操作。
4. **服务治理**：注册发现、负载均衡、限流熔断、链路追踪。
5. **与 Nacos 集成**：注册中心已选 Nacos（SP-01）。
6. **铁律 8**：三方对比后定论。

## 2. 候选方案

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Apache Dubbo 3.3.6**（已选，铁律 6） | Java 原生 RPC，Triple 协议（HTTP/2 + Protobuf），虚拟线程友好 |
| B | **gRPC + Istio Service Mesh** | 多语言标准，Envoy sidecar 治理，云原生主流 |
| C | **Spring Cloud OpenFeign** | HTTP/REST，声明式，最简单 |

## 3. 对比维度表

| 维度 | Dubbo 3.3.6（A） | gRPC + Istio（B） | OpenFeign（C） |
|------|--------------------|--------------------|-----------------|
| **成熟度** | 2008 起源，国内最成熟 Java RPC，3.x GA 2022 | gRPC 2015 GA，Istio 2018 GA，云原生主流 | Spring Cloud 一等公民，最成熟 |
| **社区活跃度** | 40k★+，Apache 顶级，国内活跃 | gRPC 41k★+，Istio 36k★+，全球活跃 | Spring Cloud 8w★+ 生态 |
| **协议** | Triple（HTTP/2 + Protobuf，gRPC 兼容）+ Dubbo 私有协议 | HTTP/2 + Protobuf | HTTP/1.1 + JSON |
| **Java 21 虚拟线程** | 3.3 原生支持虚拟线程，synchronized 已消除 | gRPC Java 用 Netty，兼容虚拟线程 | OkHttp/HttpClient 兼容 |
| **GraalVM AOT** | 3.3.x 支持 AOT，native-maven-plugin 兼容 | gRPC Java AOT 支持，需 reachability metadata | OpenFeign AOT 支持有限（动态代理） |
| **多语言支持** | Triple 协议 gRPC 兼容，多语言可调；Java 体验最优 | 原生多语言（Go/Python/Node/JS/Rust） | 仅 HTTP，语言无关但无强类型契约 |
| **服务治理** | 内置（注册/负载/限流/熔断/路由/灰度），Sentinel 集成 | Sidecar 治理（Envoy），语言无关 | 依赖 SCA（Sentinel/Nacos） |
| **与 Nacos 集成** | 原生 Nacos 注册中心 starter | 需 Istio service registry 适配 | Spring Cloud Nacos Discovery 原生 |
| **与 SCA 生态** | Dubbo + SCA 一体（铁律 6），无缝 | 与 SCA 不重叠，需维护两套治理 | SCA 原生 |
| **性能** | Triple 接近 gRPC；私有协议更优 | gRPC 高性能 | HTTP/1.1 + JSON 性能最低 |
| **sidecar 开销** | 无 sidecar | 每 Pod +Envoy sidecar，资源/延迟开销 | 无 sidecar |
| **多租户隔离** | 上下文透传 tenant_id（Attachment） | Envoy header 透传 | HTTP header 透传 |
| **运维复杂度** | 仅注册中心 + SDK，简单 | Istio 控制面（Istiod）+ sidecar，复杂 | 仅注册中心 + SDK，简单 |

## 4. 结论与理由

**采纳方案 A：Apache Dubbo 3.3.6 + Spring Cloud Alibaba**（与铁律 6 / D-032 一致，记录对比理由）。

理由：

1. **Java 21 虚拟线程原生支持**：Dubbo 3.3 已消除 synchronized，与 D-039 虚拟线程铁律无缝；gRPC Java 也可但需额外配置。
2. **SCA 一体生态**：Dubbo + SCA + Nacos + Sentinel 一体化（铁律 6），与 SP-01 技术中台选型一致；gRPC+Istio 引入两套治理体系（SCA + Envoy），运维复杂；OpenFeign 性能劣势且无强类型契约。
3. **Triple 协议 gRPC 兼容**：多语言互操作（Python 侧 SP-04）通过 Triple（gRPC 兼容）解决，不放弃多语言能力；纯 gRPC+Istio 的多语言优势被 sidecar 复杂度抵消。
4. **GraalVM AOT 兼容**：Dubbo 3.3 支持 AOT，与 D-040 兼容；OpenFeign 的动态代理 AOT 支持弱。
5. **无 sidecar 开销**：100 万并发场景下 sidecar 资源/延迟开销显著；Dubbo SDK 模式更轻。
6. **国内生态最成熟**：阿里/京东/携程大规模落地，问题排查资料多。

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| Dubbo 多语言生态弱于 gRPC | Triple 协议 gRPC 兼容，Python 侧用 grpc-python 调用 |
| Istio 治理能力（金丝雀/熔断）强于 Dubbo 内置 | Dubbo 路由规则 + Sentinel 熔断 + K8s 金丝雀发布组合 |
| AOT 元数据可能不全 | CI 跑 native-image 冒烟，缺元数据用 `@RegisterReflectionForBinding` 补 |

---

**文档结束**。本 ADR 补齐 D-048 审计 P2-1 的 RPC 框架选型对比缺失，记录 Dubbo 已选理由（铁律 6），满足铁律 8 三方对比要求。
