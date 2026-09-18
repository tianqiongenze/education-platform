# SP-03 业务中台 测试方案

> **D-200 Naming Note:** This document's filename retains the legacy code `sp03` for reference continuity. Per D-137, sp03 → core-business. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-03-TEST | v1.0 | 2026-08-03 | Released | 测试组 | SP-03-SRS/SAD/SDD；NFR-P/T/I/A/S/O |

> 本测试方案覆盖单元/集成/契约/支付事务/风控规则/性能/安全 7 大类，承接 SRS 的 NFR 与验收标准。

---

## 1. 测试策略总览

```
┌──────────────────────────────────────────────────────────────────┐
│  L1 单元测试(聚合/领域逻辑, 覆盖率 ≥80%)                           │
│   Java(JUnit5+Mockito) │ Rust(cargo test) │ Go(testify)          │
├──────────────────────────────────────────────────────────────────┤
│  L2 集成测试(跨组件, Testcontainers)                              │
│   TiDB+Redis+Pulsar+ES+Milvus 真实容器                           │
├──────────────────────────────────────────────────────────────────┤
│  L3 契约测试(Pact, 中台↔BU/组件间)                                │
├──────────────────────────────────────────────────────────────────┤
│  L4 专项测试                                                      │
│   支付事务(幂等/对账/退款) │ 风控规则(热更新/误拦) │ 通知(模板/偏好)│
├──────────────────────────────────────────────────────────────────┤
│  L5 性能压测(Locust 分布式 + 压测环境)                            │
│   订单 OPS / 计费 P95 / 风控 P95 / 搜索 P95 / IM 延迟             │
├──────────────────────────────────────────────────────────────────┤
│  L6 安全测试(越权/FGAC/注入/审计)                                 │
├──────────────────────────────────────────────────────────────────┤
│  L7 混沌与容灾(单单元故障切换 <30s, 跨区 Raft)                    │
└──────────────────────────────────────────────────────────────────┘
```

质量门禁：
- L1/L2/L3 全绿方可合并
- L4 专项关键用例全绿
- L5 性能达标 NFR-P
- L6 安全无高危
- L7 容灾演练每季度一次

---

## 2. 单元测试(L1)

### 2.1 覆盖目标

| 组件 | 重点 | 覆盖率 |
|------|------|--------|
| UCM | User 聚合不变量(多端≤8/封禁吊销)、Credential 校验 | ≥85% |
| OPM | Order 状态机、计费纯函数(Rust)、幂等键逻辑 | ≥85% |
| MMP | CouponTemplate 预算校验、UserCoupon 状态机 | ≥80% |
| NTF | 模板渲染插值、偏好过滤、渠道路由 | ≥80% |
| CMP | Section 闭包表树操作、Course 状态机 | ≥80% |
| SRH | 查询构造、FGAC 过滤、suggest | ≥75% |
| TKT | Ticket 状态机、SLA 升级、分派算法 | ≥80% |
| RSK | RuleEngine 匹配(Rust)、评分、黑名单快查 | ≥85% |

### 2.2 示例：OPM 订单状态机(JUnit5)

```java
@Test
void shouldAdvanceToPaidOnCallback_whenStatusIsCreated() {
    Order order = OrderFixture.createdOrder();
    PaymentCallback cb = PaymentCallbackFixture.paid();
    order.applyPaymentSuccess(cb);
    assertEquals(OrderStatus.PAID, order.getStatus());
    assertNotNull(order.getPaidAt());
}

@Test
void shouldRejectDuplicatePaymentCallback_whenAlreadyPaid() {
    Order order = OrderFixture.paidOrder();
    assertThrows(DuplicateCallbackException.class,
        () -> order.applyPaymentSuccess(PaymentCallbackFixture.paid()));
}
```

### 2.3 示例：Rust 计费引擎(cargo test)

```rust
#[test]
fn fullcut_coupon_deducts_correctly() {
    let req = ComputeRequest {
        items: vec![OrderItem{ sku_id:1, qty:1, unit_price: dec!(100.0) }],
        user_coupon_ids: vec!["c1".into()],
        user_level: 1,
        points_to_use: 0,
    };
    let resp = BillingEngine::compute(&req, &Coupon{ threshold: dec!(100.0), amount: dec!(20.0), ..Default::default()});
    assert_eq!(resp.original, dec!(100.0));
    assert_eq!(resp.coupon_deduct, dec!(20.0));
    assert_eq!(resp.payable, dec!(80.0));
}
```

---

## 3. 集成测试(L2)

### 3.1 环境(Testcontainers)

```
@Testcontainers
class OpmIt {
  @Container static TiDBContainer tidb = ...
  @Container static GenericContainer redis = ...
  @Container static PulsarContainer pulsar = ...
}
```

### 3.2 跨组件用例

| IT-ID | 场景 | 涉及组件 |
|-------|------|---------|
| IT-01 | 注册→发欢迎通知→风控初评 | UCM+NTF+RSK |
| IT-02 | 下单→锁券→支付→核销券→发通知 | OPM+MMP+NTF |
| IT-03 | 支付回调重复到达→幂等 | OPM |
| IT-04 | 退款→券退回(有效期+7天) | OPM+MMP |
| IT-05 | 课程上架→ES 索引可见→可搜索 | CMP+SRH |
| IT-06 | 课程下架→已购仍可访问→新用户搜不到 | CMP+SRH+用户域Enrollment |
| IT-07 | 工单创建→自动分派→SLA 超时升级 | TKT+NTF |
| IT-08 | 刷券检测→风控拦截 | MMP+RSK |
| IT-09 | 封禁用户→吊销会话→全局黑名单生效 | UCM+RSK |
| IT-10 | 领券并发→防超发 | MMP(Redis Lua) |

---

## 4. 契约测试(L3, Pact)

### 4.1 中台 ↔ 事业群(BU)

- BU 作为消费方定义 Pact 期望(请求/响应)
- CI 中业务中台作为提供方验证 Pact
- 破坏性变更立即失败

```
BU(IT后端) ──Pact(期望 /opm/order 响应)──▶ OPM 提供方验证
BU(考公)   ──Pact(期望 /cmp/course)──▶ CMP 提供方验证
```

### 4.2 组件间契约

| 提供方 | 消费方 | 契约 |
|--------|--------|------|
| OPM | MMP(核销券回调) | proto/Pact |
| RSK | OPM/MMP/UCM(/risk.evaluate) | proto |
| CMP | SRH(变更事件 schema) | Avro Schema Registry |
| UCM | NTF/RSK/MMP(ucm.event schema) | Avro |

---

## 5. 支付事务专项(L4)

### 5.1 幂等测试

| 用例 | 期望 |
|------|------|
| 同 Idempotency-Key 连续 3 次支付 | 仅 1 次扣款, 3 次同结果 |
| 支付回调重复 N 次 | 订单状态仅推进 1 次 |
| 退款单号重复请求 | 仅 1 次执行 |
| Redis 幂等键过期后重放(>24h) | 走 DB 唯一索引兜底 |
| 并发同幂等键 | 1 成功, 其余等待回查 |

### 5.2 对账测试

| 用例 | 期望 |
|------|------|
| T+1 渠道对账文件匹配 | 无差异 |
| 渠道多一笔(平台漏记) | 挂起待人工 |
| 平台多一笔(渠道漏记) | 挂起待人工 |
| 退款对账差异 | 挂起 + 告警 |

### 5.3 退款测试

| 用例 | 期望 |
|------|------|
| 全额退款 | 余额/原路退回, 券退回(有效期+7天) |
| 部分退款 | 按比例, 券不退回 |
| 退款失败 | 状态 REFUND_FAILED, 告警 |
| 重复退款请求 | 幂等返回 |

---

## 6. 风控规则专项(L4)

### 6.1 规则匹配

| 用例 | 期望 |
|------|------|
| 黑名单 IP 注册 | BLOCK |
| 高频登录失败(1min 5 次) | REVIEW |
| 刷券(同 IP 10 次/min) | BLOCK |
| 正常用户下单 | PASS |
| 规则阈值边界 | 边界值正确决策 |

### 6.2 热更新

| 用例 | 期望 |
|------|------|
| Nacos 规则变更 | 5s 内全节点生效 |
| 灰度规则(10%) | 仅 10% 流量走新规则 |
| 规则回滚 | 版本回滚 <5s |
| 热更新不重启 | 服务零中断 |

### 6.3 性能与误拦

| 用例 | 期望 |
|------|------|
| 单实例 QPS | ≥30k |
| P95 | <10ms |
| 误拦率 | <5%(灰度观测) |

---

## 7. 通知专项(L4)

| 用例 | 期望 |
|------|------|
| 模板变量插值 | 渲染正确, 缺失变量回退 |
| 多语言路由 | 按 lang 选模板, 缺失回退 zh-CN |
| 用户关闭营销偏好 | 不收营销 Push, 仍收订单通知 |
| 厂商路由 | APNs/FCM/各厂商正确路由 |
| 退订 | 退订后不发邮件 |
| 模板版本变更 Pact | 消费方契约不破坏 |

---

## 8. 性能压测(L5)

### 8.1 环境

- 独立压测集群(与生产同规格缩小版)
- 分布式 Locust(独立节点, 避免压测端瓶颈)
- 数据预热: 1000 万用户 / 100 万课程 / 1 亿订单

### 8.2 场景与目标

| 场景 | 目标 | 通过条件 |
|------|------|---------|
| UCM 登录 | 1 万 QPS | P95<200ms, 错误率<0.1% |
| OPM 下单 | 5 万 OPS | P95<300ms, 错误率<0.1% |
| OPM 支付回调 | 2 万 QPS | P95<500ms |
| OPM Rust 计费 | 5 万 QPS | P95<5ms |
| RSK 规则引擎 | 3 万 QPS | P95<10ms |
| NTF 站内信端到端 | 1 万 ev/s | 延迟<3s |
| CMP 课程详情 | 5 万 QPS | P95<150ms |
| SRH 课程搜索 | 2 万 QPS | P95<200ms |
| TKT IM 消息 | 10 万并发连接 | P95<800ms |
| 综合(下单+支付+通知+风控) | 1 万订单/s 链路 | 全链路 P95 达标 |

### 8.3 监控

- Prometheus + Grafana 实时面板
- 关键指标: QPS/P50/P95/P99/错误率/资源
- 压测报告归档(附 SLO 对照)

---

## 9. 安全测试(L6)

### 9.1 越权与 FGAC

| 用例 | 期望 |
|------|------|
| 学员 A 查询学员 B 订单 | 403 |
| 学员 A 调用管理员封禁接口 | 403 |
| 跨事业群(BU)查询课程 | 仅本 BU 可见(FGAC tenant_id) |
| 客服查非本人负责工单 | 403 |
| JWT 篡改 tenant_id | 签名校验失败 401 |

### 9.2 注入与敏感数据

| 用例 | 期望 |
|------|------|
| SQL 注入(MyBatis 参数) | 参数化拦截 |
| XSS(昵称/工单内容) | 输出转义 |
| 证件号明文落日志 | 审计日志不含明文 |
| 证件号加密落库 | AES-256-GCM, 密钥分离 |
| 支付信息明文存储 | 不存储完整卡号/支付密钥 |

### 9.3 审计

| 用例 | 期望 |
|------|------|
| 管理员封禁操作 | 审计日志落 Iceberg |
| 客服退款操作 | 审计可追溯 |
| 风控规则变更 | 变更人/时间/版本可查 |
| 越权访问尝试 | 告警 + 审计 |

---

## 10. 混沌与容灾(L7)

| 用例 | 期望 |
|------|------|
| 单单元 Pod 故障 | <30s 切换, 无订单丢失 |
| 单元 TiDB 分片主节点挂 | Raft 多数派切主, 写不中断 |
| 跨区 Raft 少数派区挂 | 多数派写成功即返回 |
| Redis Cluster 单节点挂 | 自动 failover, 会话不丢 |
| Pulsar broker 挂 | 消息不丢(至少一次) |
| ES master 挂 | 集群自动选主, 搜索不中断 |
| 网络分区(单元间) | 跨单元写挂起, 不脑裂 |
| 风控规则中心(Nacos)挂 | 本地缓存兜底, 决策不中断 |

---

## 11. 测试数据与隐私

- 压测数据脱敏(假名+假证件号)
- 不使用生产真实证件号/支付信息
- 测试环境独立数据隔离
- 审计日志测试数据定期清理

---

## 12. 测试报告与回归

- 每次发布产出测试报告(L1-L6 全量)
- 性能报告附 SLO 对照表
- 缺陷分级: P0 阻断发布, P1 限期内修复, P2 排期
- 回归: 每日 nightly L1/L2, 每周 L3/L4, 发版前 L5/L6/L7

---

## 13. 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | 测试组 |
