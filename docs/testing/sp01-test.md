# SP-01 技术中台 —— 测试计划文档

> **D-200 Naming Note:** This document's filename retains the legacy code `sp01` for reference continuity. Per D-137, sp01 → user. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP01-TEST-001 | v1.0 | 2026-08-03 | Approved | ZCode(文档工程师) | SP01-SRS-001 / SP01-SAD-001 / SP01-SDD-001 / G-01~G-10 SLO |

> 本文定义 SP-01 技术中台的完整测试策略与计划，涵盖单元测试、集成测试、契约测试、性能测试、混沌测试、安全测试六大维度。已实现的 3 个 SDK 测试结果已列出(51 tests pass，覆盖率均超 93%)。

---

## 目录

1. [测试策略总览](#1-测试策略总览)
2. [单元测试](#2-单元测试)
3. [已实现 SDK 测试结果(基线)](#3-已实现-sdk-测试结果基线)
4. [集成测试](#4-集成测试)
5. [契约测试(Pact)](#5-契约测试pact)
6. [性能测试(Locust)](#6-性能测试locust)
7. [混沌测试(故障注入)](#7-混沌测试故障注入)
8. [安全测试](#8-安全测试)
9. [测试环境矩阵](#9-测试环境矩阵)
10. [验收标准](#10-验收标准)
11. [测试追溯矩阵](#11-测试追溯矩阵)

---

## 1. 测试策略总览

### 1.1 测试金字塔

```
                  /\
                 /  \         安全测试(sandbox escape / 渗透)
                /────\
               /      \       混沌测试(Chaos Mesh 故障注入)
              /────────\
             /          \     性能测试(Locust 压测)
            /────────────\
           /              \   契约测试(Pact 跨服务接口)
          /────────────────\
         /                  \ 集成测试(Testcontainers)
        /────────────────────\
       /                      \单元测试(JUnit5 + Mockito)
      /────────────────────────\
```

### 1.2 测试范围与目标

| 维度 | 目标 | 覆盖率要求 |
|------|------|-----------|
| 单元测试 | 每个类/方法的独立逻辑 | 行覆盖 ≥80%，安全核心 100% |
| 集成测试 | 组件间协作、外部依赖 | 关键路径 100% |
| 契约测试 | 跨服务接口契约 | 全部对外接口 100% |
| 性能测试 | SLO 达标验证 | G-01~G-10 全部达标 |
| 混沌测试 | 容错与弹性 | 5 大故障场景通过 |
| 安全测试 | OWASP Top10 + 沙箱逃逸 | 0 高危漏洞 |

### 1.3 测试工具栈

| 类别 | 工具 | 版本 |
|------|------|------|
| 单元测试 | JUnit 5 + Mockito | 5.14.4 / 5.18.0 |
| 覆盖率 | JaCoCo | 0.8.12 |
| 集成测试 | Testcontainers | 1.20+ |
| 契约测试 | Pact JVM | 4.6+ |
| 性能测试 | Locust | 2.31+ |
| 混沌工程 | Chaos Mesh | 2.7+ |
| 安全测试 | OWASP ZAP + 自研沙箱逃逸 | 2.15+ |
| 静态分析 | SonarQube + Aikido SAST | 10.x |

---

## 2. 单元测试

### 2.1 覆盖率门禁

| 模块 | 行覆盖 | 分支覆盖 | 安全核心 |
|------|--------|---------|---------|
| ep-rw-java | ≥95% | ≥90% | N/A |
| ep-disruptor | ≥90% | ≥85% | N/A |
| ep-cache | ≥95% | ≥90% | 100%(序列化/反序列化路径) |
| CellRouter | ≥90% | ≥85% | 100%(纠正算法 + 写挂起) |
| AuthFilter | ≥95% | ≥90% | 100%(JWT 验签) |
| SentinelFilter | ≥85% | ≥80% | N/A |
| Rust 热点服务 | ≥85% | ≥80% | 100%(风控规则匹配) |

**门禁策略**：CI 流水线强制检查，覆盖率不达标阻塞合并。

### 2.2 测试用例规范

#### 2.2.1 命名规范

```
should_<expected>_when_<condition>
例: should_returnWrite_when_noAnnotation
    should_drop_when_ringBufferFull
    should_hitL1_when_caffeineHasKey
```

#### 2.2.2 测试结构(AAA)

```java
@Test
void should_redirect_when_misrouteAndCounterWithinLimit() {
    // Arrange
    RouteTable table = mock(RouteTable.class);
    when(table.hashRoute("user123")).thenReturn(0);  // 目标 Cell-0
    CellRouter router = new CellRouter(table, holdRegistry);

    // Act
    RouteResult result = router.route("user123", "2", "GET");  // 当前 Cell-2

    // Assert
    assertEquals(RouteResult.Type.REDIRECT, result.type());
    assertEquals(0, result.targetCell());
}
```

---

## 3. 已实现 SDK 测试结果(基线)

> 以下为 3 个已实现 SDK 的实际测试结果，作为后续新增组件的测试基线。

### 3.1 ep-rw-java(读写分离 SDK)

**版本**：v1.0.0
**测试命令**：`mvn test`
**测试结果**：

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**覆盖率**：97.7% line coverage(JaCoCo)

**测试用例清单**：

| # | 测试类 | 测试方法 | 覆盖点 |
|---|--------|---------|--------|
| 1 | DataSourceTypeTest | enum_values | WRITE/READ 枚举值 |
| 2 | RoutingHolderTest | set_get_clear | ThreadLocal 上下文管理 |
| 3 | RoutingHolderTest | clear_after_set | clear 防泄漏 |
| 4 | DataSourceAspectTest | should_routeWrite_when_writeAnnotation | WRITE 模式 |
| 5 | DataSourceAspectTest | should_routeRead_when_readAnnotation | READ 模式 |
| 6 | DataSourceAspectTest | should_routeWrite_when_consistentTrue | consistent 强制走 Primary |
| 7 | DataSourceAspectTest | should_clear_when_writeOperation | 写操作清残留 |
| 8 | DataSourceAspectTest | should_restorePrev_when_nestedReadAfterWrite | 嵌套调用恢复 |
| 9 | RoutingDataSourceTest | should_returnWrite_when_nullContext | 默认走 WRITE |
| 10 | RoutingDataSourceTest | should_returnRead_when_readSet | 路由 READ |
| 11 | DataSourceConfigTest | should_assemble_when_enabled | Bean 装配 + 条件开关 |

### 3.2 ep-disruptor(高并发 ingest SDK)

**版本**：v1.0.0
**测试命令**：`mvn test`
**测试结果**：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**覆盖率**：93% line coverage(JaCoCo)

**测试用例清单**：

| # | 测试类 | 测试方法 | 覆盖点 |
|---|--------|---------|--------|
| 1 | EnumsTest | publishResult_values | PUBLISHED/DROPPED |
| 2 | EnumsTest | waitStrategyType_toWaitStrategy | 5 种 WaitStrategy 映射 |
| 3 | DisruptorWorkerTest | should_publish_when_capacity | 正常发布 |
| 4 | DisruptorWorkerTest | should_drop_when_ringBufferFull | 满则 drop |
| 5 | DisruptorWorkerTest | should_countPublished | published 计数 |
| 6 | DisruptorWorkerTest | should_countConsumed | consumed 计数(counting handler 前置) |
| 7 | DisruptorWorkerTest | should_startAndShutdown | 生命周期 |
| 8 | DisruptorPipelineTest | should_forward_toNextStage | forward 转发 |
| 9 | DisruptorPipelineTest | should_shutdown_reverseOrder | 逆序 shutdown |

### 3.3 ep-cache(多级缓存 SDK)

**版本**：v1.0.0
**测试命令**：`mvn test`
**测试结果**：

```
[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**覆盖率**：95.8% line coverage(JaCoCo)

**测试用例清单(按测试类分组)**：

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| CacheServiceTest | 12 | getOrLoad(L1/L2/loader/backfill)、put、evict(四步+双删)、空值哨兵、降级 |
| CacheConfigTest | 5 | Caffeine Bean、RedisTemplate 序列化、DefaultTyping 禁用、条件开关 |
| CacheBroadcastTest | 5 | lazy-init、无 Pulsar 降级、publishEvict、sendAsync 失败处理 |
| InvalidateMsgTest | 2 | DTO 序列化/反序列化、getter/setter |
| PulsarClientHolderTest | 3 | 延迟获取、重复 get 幂等、close |
| EventPublisherTest | 4 | publish、producer 缓存(computeIfAbsent)、无 Pulsar 降级、sendAsync 失败 |

### 3.4 汇总

| SDK | 测试数 | 通过 | 失败 | 跳过 | 行覆盖率 |
|-----|--------|------|------|------|---------|
| ep-rw-java | 11 | 11 | 0 | 0 | 97.7% |
| ep-disruptor | 9 | 9 | 0 | 0 | 93.0% |
| ep-cache | 31 | 31 | 0 | 0 | 95.8% |
| **合计** | **51** | **51** | **0** | **0** | **平均 95.5%** |

---

## 4. 集成测试

### 4.1 Testcontainers 集成测试

使用 Testcontainers 启动真实容器化依赖进行集成测试。

#### 4.1.1 ep-rw-java 集成测试

| 测试场景 | 容器 | 验证点 |
|---------|------|--------|
| 主从分离写读 | MySQL 8.4 主从 | 写 Primary → 读 Follower(含延迟窗口) |
| consistent 强制主 | MySQL 主从 | consistent=true 读到最新写入 |
| 连接池配置 | HikariCP | 池满不阻塞、超时降级 |

```java
@Testcontainers
class RwIntegrationTest {
    @Container static MySQLContainer<?> master = new MySQLContainer<>("mysql:8.4");
    @Container static MySQLContainer<?> slave = new MySQLContainer<>("mysql:8.4");

    @Test
    void should_writeMaster_readSlave() {
        // 配置 routingDataSource 指向 master/slave
        // 写 → master, 读 → slave
    }
}
```

#### 4.1.2 ep-disruptor 集成测试

| 测试场景 | 验证点 |
|---------|--------|
| 多生产者并发 | 1000 并发 publish 无丢失(drop 计数正确) |
| 多阶段 pipeline | 3 阶段串联，forward 正确传递 |
| 高背压 | 消费者慢于生产者，drop-on-full 触发 |

#### 4.1.3 ep-cache 集成测试

| 测试场景 | 容器 | 验证点 |
|---------|------|--------|
| L1+L2 二级缓存 | Redis 7.4 | L1 miss → L2 hit → 回填 L1 |
| Pulsar 广播失效 | Pulsar 3.3 | Pod-A evict → Pod-B 收到广播 → L1 失效 |
| 延迟双删 | Redis | evict 后 100ms 二次删除生效 |
| Redis 故障降级 | Redis(模拟宕机) | L2 不可用，L1 仍工作 |

### 4.2 端到端集成测试

| 场景 | 涉及组件 | 验证点 |
|------|---------|--------|
| 用户登录→获取课程 | AuthFilter → UCM → CMP → CacheService | JWT 验签、缓存命中、trace 链路完整 |
| 实时课堂 WS 连接 | WS Gateway → Hub → Pulsar(跨pod广播) | 跨 pod 广播、心跳保活 |
| 下单→支付→风控 | BFF → Order → Rust 风控 → Billing | 风控规则匹配、事件总线发布 |
| CellRouter 流量纠正 | Gateway → CellRouter | misroute 重路由、纠正计数器 |
| Failover 写挂起 | CellRouter → WriteHoldRegistry | 写请求 503 + Retry-After |

---

## 5. 契约测试(Pact)

### 5.1 Pact 契约定义

跨服务接口使用 Pact 定义消费者驱动的契约。

#### 5.1.1 UCM 服务契约(消费者: BFF)

```json
{
  "consumer": { "name": "bff" },
  "provider": { "name": "ucm-service" },
  "interactions": [
    {
      "description": "获取用户信息",
      "request": {
        "method": "GET",
        "path": "/api/users/123",
        "headers": { "X-User-Id": "123", "X-Tenant-Id": "it-backend" }
      },
      "response": {
        "status": 200,
        "headers": { "Content-Type": "application/json" },
        "body": {
          "id": "123",
          "name": "张三",
          "roles": ["student"]
        }
      }
    }
  ]
}
```

### 5.2 契约测试矩阵

| 消费者 | 提供者 | 契约数 | 验证频率 |
|--------|--------|--------|---------|
| BFF | UCM-Service | 8 | 每次 PR |
| BFF | CMP-Service | 12 | 每次 PR |
| BFF | Order-Service | 6 | 每次 PR |
| Order-Service | Rust 风控(gRPC) | 4 | 每次 PR |
| CacheService | Pulsar | 2 | 每日 |
| CellRouter | Nacos | 3 | 每日 |

### 5.3 契约 Broker

- 部署 Pact Broker
- CI 流水线自动发布契约
- 提供者验证失败阻塞合并

---

## 6. 性能测试(Locust)

> **ESP 血泪铁律**：禁止单节点 Locust 压测推断生产容量。ESP 曾用单节点 Locust 压测推断 5000 CCU 可行，实际多实例部署后因连接池/线程池/缓存竞争失败（3 倍差距）。所有 ≥1 万 CCU 场景必须使用 **Locust 分布式独立节点**（master + ≥3 worker，独立 K8s 命名空间），且 worker 节点资源与被测目标隔离。

### 6.1 SLO 对齐

| SLO 编号 | 指标 | 目标 | 测试场景 |
|---------|------|------|---------|
| G-01 | API 网关 P99 | <100ms | 10万 QPS 压测 |
| G-02 | WS 消息推送 P99 | <50ms | 10万 CCU 并发 |
| G-03 | Dubbo Triple P99 | <30ms | 5万 QPS |
| G-04 | 缓存 L1+L2 P99 | <5ms | 50万 QPS |
| G-05 | 事件发布 P99 | <10ms | 100万 events/s |
| G-06 | Disruptor ingest | 100万/s | 持续 10min |
| G-07 | Rust 风控 P99 | <1ms | 5万 QPS |
| G-08 | 路由纠正 | <1ms | 10万 QPS |
| G-09 | 可用性 | 99.95% | 7×24 持续 |
| G-10 | MTTR | <15min | 故障注入 |

### 6.2 Locust 测试脚本示例

```python
from locust import HttpUser, task, between

class GatewayUser(HttpUser):
    wait_time = between(0.1, 0.5)

    @task(10)
    def get_course(self):
        self.client.get("/api/courses/C001",
            headers={"Authorization": "Bearer " + self.token})

    @task(1)
    def login(self):
        resp = self.client.post("/api/auth/login", json={
            "username": "loadtest", "password": "xxx"
        })
        self.token = resp.json()["token"]
```

### 6.3 压测场景

| 场景 | 并发用户 | 目标 QPS | 持续时间 | 通过标准 |
|------|---------|---------|---------|---------|
| 网关日常 | 10万 | 10万 | 30min | P99<100ms，0 错误 |
| 网关峰值 | 30万 | 20万 | 10min | P99<200ms，错误率<0.1% |
| WS 并发 | 10万 CCU | 50万 msg/s | 30min | P99<50ms |
| 缓存命中 | 50万 QPS | 50万 | 30min | P99<5ms，命中率>95% |
| Disruptor | 100万/s | 100万 | 10min | drop 率<0.01% |
| Rust 风控 | 5万 QPS | 5万 | 30min | P99<1ms |
| 混合负载 | 20万 | 15万 | 60min | 全部 SLO 达标 |

### 6.4 性能回归门禁

- 每次 release 前执行全量压测
- P99 延迟回归 >10% 阻塞发布
- QPS 容量下降 >5% 阻塞发布

---

## 7. 混沌测试(故障注入)

### 7.1 Chaos Mesh 故障场景

| # | 故障类型 | 注入对象 | 持续 | 预期行为 | 验证 SLO |
|---|---------|---------|------|---------|---------|
| CH-01 | Pod Kill | UCM Pod | 随机杀 | HPA 自动扩容，请求不中断 | G-09 |
| CH-02 | Network Delay | Redis → Pod | +500ms | L2 超时降级，L1 继续工作 | G-04 |
| CH-03 | Network Partition | Cell-0 ↔ Cell-1 | 30s | 流量纠正生效，单 cell 自治 | ADR-T01 |
| CH-04 | Disk Fill | 日志盘 | 90% | 告警触发，日志降级不阻塞业务 | G-09 |
| CH-05 | CPU Stress | 网关 Pod | 80% | 限流触发，P99 不恶化超 2x | G-01 |
| CH-06 | DNS 故障 | Nacos 解析 | 5min | 本地缓存兜底，路由表不失效 | ADR-T01 |
| CH-07 | Pulsar Broker 宕机 | 事件总线 | 1min | 事件堆积不丢失，broker 恢复后消费 | G-05 |
| CH-08 | MySQL 主库故障 | Primary | failover | 只读自动切备，写挂起 503 | ADR-T01 |

### 7.2 混沌测试自动化

```yaml
# Chaos Mesh 实验: CH-03 Network Partition
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: cell-partition
spec:
  action: partition
  mode: all
  selector:
    namespaces: [prod]
    labelSelectors: { "cell": "cell-0" }
  direction: both
  target:
    selector:
      namespaces: [prod]
      labelSelectors: { "cell": "cell-1" }
  duration: "30s"
```

### 7.3 通过标准

- 所有故障场景下，可用性 ≥99.95%
- 无数据丢失(事件最终一致)
- 故障恢复后 5min 内全部指标恢复正常

---

## 8. 安全测试

### 8.1 OWASP Top10 覆盖

| OWASP 风险 | 测试方法 | 通过标准 |
|-----------|---------|---------|
| A01 失效访问控制 | 越权测试(水平/垂直) | 0 越权成功 |
| A02 加密失败 | TLS 配置扫描、密钥审计 | 全链路 TLS 1.3 |
| A03 注入 | SQL/NoSQL/命令注入 fuzzing | 0 注入成功 |
| A04 不安全设计 | 威胁建模审查 | 0 设计级漏洞 |
| A05 配置错误 | CIS Benchmark 扫描 | 0 配置缺陷 |
| A06 易受攻击组件 | SCA(Aikido) | 0 高危 CVE |
| A07 认证失败 | JWT 篡改/重放测试 | 0 认证绕过 |
| A08 软件与数据完整性 | 签名验证测试 | 0 完整性破坏 |
| A09 日志与监控失败 | 日志完整性审计 | 关键事件 100% 记录 |
| A10 服务端请求伪造 | SSRF 测试 | 0 SSRF 成功 |

### 8.2 JWT 安全测试

| 测试项 | 测试方法 | 通过标准 |
|--------|---------|---------|
| 算法降级 | 篡改 alg=none / HS256 | 拒绝(RS256 强制) |
| 密钥泄露 | JWKS 端点越权 | 仅公钥可访问 |
| Token 重放 | 同一 token 短时复用 | jti 去重生效 |
| Token 过期 | 使用过期 token | 401 拒绝 |
| Claims 篡改 | 修改 payload | 验签失败 |
| 跨租户越权 | A 租户 token 访问 B | X-Tenant-Id 校验拒绝 |

### 8.3 沙箱逃逸测试

> Rust 热点服务运行在独立沙箱，需验证隔离边界不可突破。

| 测试项 | 测试方法 | 通过标准 |
|--------|---------|---------|
| 内存越界 | 模糊测试(AFL) | 无 OOB 读写 |
| 系统调用 | strace 监控 | 仅允许白名单 syscall |
| 文件系统 | 尝试读写沙箱外路径 | 拒绝(只读 rootfs) |
| 网络 | 尝试连接非白名单地址 | 拒绝(NetworkPolicy) |
| 进程注入 | 尝试 fork/exec 外部进程 | 拒绝(seccomp) |
| CPU 逃逸 | 尝试占用全部 CPU | cgroup 限制生效 |
| 依赖库 RCE | 已知 CVE 模糊测试 | 0 RCE |

### 8.4 渗透测试

- M1 阶段引入第三方渗透测试
- 覆盖黑盒(外部) + 灰盒(持有有效 token) + 白盒(源码审计)
- 发现的高危漏洞 24h 内修复

### 8.5 SAST/SCA 自动化

| 工具 | 扫描时机 | 阻塞级别 |
|------|---------|---------|
| Aikido SAST | 每次 PR | 高危阻塞 |
| Aikido SCA | 每次 PR | 高危 CVE 阻塞 |
| SonarQube | 每次 PR | Critical 阻塞 |
| Trivy(容器) | 每次构建 | High 阻塞 |

---

## 9. 测试环境矩阵

| 环境 | 用途 | 数据 | 隔离 |
|------|------|------|------|
| dev | 开发自测 | mock | 命名空间 |
| test | 集成测试 | Testcontainers | 独立集群 |
| staging | 预发布压测 | 脱敏数据 | 独立集群 |
| perf | 性能压测 | 模拟数据 | 独立集群 |
| chaos | 混沌实验 | 生产镜像 | 独立集群 |
| prod | 生产 | 真实 | 单元隔离 |

---

## 10. 验收标准

### 10.1 M0 验收

| 项 | 标准 | 状态 |
|----|------|------|
| 3 个 SDK 单元测试 | 51 tests pass，覆盖率达标 | ✅ 已通过 |
| SDK 集成测试 | Testcontainers 全绿 | 待执行 |
| SAST | 0 高危 | 待扫描 |

### 10.2 M1 验收

| 项 | 标准 |
|----|------|
| 单元测试 | 全部新增组件覆盖率 ≥80% |
| 契约测试 | 全部对外接口契约 100% |
| 性能测试 | G-01~G-10 达标 |
| 混沌测试 | 5 大故障场景通过 |
| 安全测试 | OWASP 0 高危 |
| 渗透测试 | 第三方报告 0 高危 |

### 10.3 M3 验收

| 项 | 标准 |
|----|------|
| 性能回归 | P99 无 >10% 回归 |
| 可用性 | 7×24 监控 99.95% |
| MTTR | 故障演练 <15min |

---

## 11. 测试追溯矩阵

| 需求 | 单元测试 | 集成测试 | 性能测试 | 混沌测试 | 安全测试 |
|------|---------|---------|---------|---------|---------|
| FR-RW-01 读写分离 | DataSourceAspectTest | RwIntegrationTest | - | - | - |
| FR-RW-04 consistent | DataSourceAspectTest | RwIntegrationTest | - | - | - |
| FR-DIS-01 高并发 ingest | DisruptorWorkerTest | DisruptorIT | G-06 压测 | CH-05 | - |
| FR-DIS-04 drop-on-full | DisruptorWorkerTest | - | G-06 | - | - |
| FR-CA-01 L1+L2 缓存 | CacheServiceTest | CacheIT | G-04 压测 | CH-02 | - |
| FR-CA-04 广播失效 | CacheBroadcastTest | CacheIT(Pulsar) | - | CH-07 | - |
| FR-CA-05 延迟双删 | CacheServiceTest | CacheIT | - | - | - |
| FR-CR-01 hash 路由 | CellRouterTest | CellRouterIT | G-08 | CH-03 | - |
| FR-CR-02 流量纠正 | CellRouterTest | - | - | CH-03 | - |
| FR-CR-04 写挂起 | CellRouterTest | - | - | CH-08 | - |
| FR-XLANG-01 JWT RS256 | AuthFilterTest | - | - | - | JWT 安全测试 |
| FR-RUST-01 风控规则 | RustUnitTest | - | G-07 | - | 沙箱逃逸测试 |
| G-01 网关 P99 | - | E2E | Locust 压测 | CH-05 | - |
| G-09 可用性 | - | - | 7×24 | CH-01~08 | - |
| NFR-SEC-01 | - | - | - | - | OWASP/渗透/沙箱 |

---

**文档结束**

> 本测试计划是 SP-01 技术中台的质量基线。已实现 3 个 SDK 的 51 项测试全部通过(覆盖率 97.7%/93%/95.8%)，为后续组件测试树立了基线标准。所有新增组件必须按本计划执行测试，未达标不得进入下一里程碑。
