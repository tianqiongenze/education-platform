# SP-03 业务中台 需求分析说明书(SRS)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp03` for reference continuity. Per D-137, sp03 → core-business. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-03-SRS | v1.0 | 2026-08-03 | Released | 文档工程师组 | EP-SPEC-001 §1/§2/§5；ADR-B01/B02；D-008/D-009/D-011/D-012/D-014 |

> 本 SRS 覆盖业务中台 8 个共享业务领域组件(UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK)的功能需求、非功能需求、用户故事、约束与验收标准。事业群(BU)调中台 API 而非 fork(ADR-B01)。

---

## 1. 引言

### 1.1 目的
为业务中台 SP-03 的设计、实现、测试、验收提供唯一的需求基线，约束 4 大事业群(IT 后端/IT 前端/考公/考研/养生)对中台能力的调用方式与质量期望。

### 1.2 范围
- 范围内：UCM/OPM/MMP/NTF/CMP/SRH/TKT/RSK 共 8 个组件的功能/非功能/接口语义/验收。
- 范围外：事业群自有领域(如 IT 后端的代码评测、AI 中台的批改 Agent、数据中台的 Flink 作业)，仅以契约面引用。

### 1.3 术语
| 缩写 | 全称 | 说明 |
|------|------|------|
| UCM | User Center Management | 用户中心 |
| OPM | Order & Payment Management | 订单支付 |
| MMP | Marketing & Promotion | 营销 |
| NTF | Notification | 通知 |
| CMP | Course Catalog Management | 课程目录 |
| SRH | Search | 搜索 |
| TKT | Ticket | 工单 |
| RSK | Risk Control | 风控 |
| FGAC | Fine-Grained Access Control | 细粒度访问控制 |
| SLO | Service Level Objective | 服务等级目标 |
| 幂等键 | Idempotency-Key | 客户端生成的去重键，24h 有效 |

### 1.4 关联文档
- EP-SPEC-001 顶层蓝图与四大中台 SAD(`docs/superpowers/specs/2026-08-03-education-platform-blueprint-and-platforms-sad.md`)
- SP-03-SAD 业务中台架构设计(`docs/architecture/sp03-sad.md`)
- SP-03-SDD 业务中台详细设计(`docs/design/sp03-sdd.md`)
- SP-03-TEST 业务中台测试方案(`docs/testing/sp03-test.md`)
- SP-03-DEPLOY 业务中台部署方案(`docs/deployment/sp03-deploy.md`)

---

## 2. 总体约束

### 2.1 架构约束(来自 EP-SPEC-001)
| ID | 约束 | 来源 |
|----|------|------|
| C-01 | 混合单元化：用户域强单元化(user_id shard)，全局域共享+多副本读 | D-012 |
| C-02 | 单元化采用流量纠正+跨单元写挂起+Local/Central 二分 | D-014 |
| C-03 | TiDB 手动 user_id 分表：AUTO_RANDOM(5)+PRE_SPLIT_REGIONS+SPLIT TABLE | D-015 |
| C-04 | 语言栈：Java 主(重业务) + Rust(风控/计费热点) + Go(网关) | D-009 |
| C-05 | 会话状态外化 Redis Cluster(`zcode:session:*`) | D-006 |
| C-06 | 事件驱动 + CDC 统一走 Pulsar(跨单元 geo-rep, ns=event:///cdc:// 隔离) | D-016 |
| C-07 | 海外单元独立数据隔离，不回流国内 | D-004 |

### 2.2 业务约束(ADR-B01/B02)
| ID | 约束 |
|----|------|
| BC-01 | 业务中台能力=共享领域服务，BU 调而非 fork；新 BU 接入=调中台 API+自建专属能力 |
| BC-02 | 中台演进通过契约(OpenAPI/Dubbo Triple)+Pact 不破坏消费方 |
| BC-03 | 订单/支付强单元化：订单事务在 user 单元内闭环 |
| BC-04 | 计费/风控高频规则用 Rust 热点服务保证 P95 |
| BC-05 | 营销券模板全局单写，个人券单元化落 user 单元 |
| BC-06 | 工单跨客服调度，全局域共享 |

---

## 3. 功能需求清单(按组件)

### 3.1 用户中心 UCM(用户域，单元化)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| UCM-F01 | 注册：手机号/邮箱/第三方(微信/QQ/Apple) | P0 | 密码 BCrypt(cost=12)；手机号 OTP 60s 限频 |
| UCM-F02 | 登录：账密/OTP/SSO/JWT RS256 签发 | P0 | access 2h+refresh 7d；JWKS 暴露 `/ucm/.well-known/jwks.json` |
| UCM-F03 | 多端会话：web/h5/小程序/PC 客户端并行在线 | P0 | 会话状态外化 Redis `zcode:session:{uid}:{device}`，单用户最多 8 端 |
| UCM-F04 | 资料管理：昵称/头像/简介/标签 | P1 | 昵称敏感词过滤(走 RSK) |
| UCM-F05 | 实名认证：姓名+证件号+人脸 | P0 | 对接公安二要素/三要素；证件号 AES-256 加密落库 |
| UCM-F06 | 账号封禁/解封：管理员操作 | P0 | 封禁即吊销所有 refresh token；写全局黑名单广播 |
| UCM-F07 | 跨单元迁移：user 主动切换单元 | P1 | 走 D-014 流量纠正，迁移期间写挂起 |
| UCM-F08 | 子账号/家庭组：1 主 + 最多 5 子 | P2 | 子账号共享部分已购课程 |

### 3.2 订单支付 OPM(用户域，单元化)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| OPM-F01 | 下单：选 SKU+优惠券+计算价 → 生成订单 | P0 | 订单号 snowflake+user 单元标识；30min 待支付超时 |
| OPM-F02 | 支付：微信/支付宝/Apple/余额 | P0 | 调统一支付网关；幂等键去重 24h |
| OPM-F03 | 支付回调：异步通知+主动查询双保险 | P0 | 回调验签；订单状态机 CAS 推进 |
| OPM-F04 | 退款：全额/部分；T+1 到账 | P0 | 退款单独立状态机；财务对账凭证 |
| OPM-F05 | 对账：T+1 与渠道对账文件比对 | P0 | 差异挂起人工介入；对账报告归档 |
| OPM-F06 | 计费热点：Rust 实现价计算/分账/积分 | P0 | P95<5ms；余额扣减走 Redis Lua 原子 |
| OPM-F07 | 发票：电子发票申请/开具 | P1 | 对接税控开票商 |
| OPM-F08 | 订单查询：用户/运营多维查询 | P0 | 走 TiKV 索引+TiFlash 列存分析 |

### 3.3 营销 MMP(全局域+用户域)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| MMP-F01 | 券模板：满减/折扣/直减/兑换 | P0 | 全局域主库单写；预算池+有效期 |
| MMP-F02 | 活动管理：领券/用券/核销 | P0 | 活动周期+人群定向 |
| MMP-F03 | 个人券领取：用户领券落 user 单元 | P0 | 防超发：Redis Lua 原子扣预算+落库 |
| MMP-F04 | 用券核销：下单时校验+核销 | P0 | 与 OPM 联动；券锁定 5min 内有效 |
| MMP-F05 | 券退回：订单取消/退款后回退 | P0 | 退回后有效期延长 7 天 |
| MMP-F06 | 风控：刷券检测(同 IP/设备/行为) | P0 | 走 RSK 实时拦截 |

### 3.4 通知 NTF(跨域，事件驱动)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| NTF-F01 | 站内信：用户收件箱 | P0 | Pulsar `ntf.inbox` topic 消费 |
| NTF-F02 | Push：APP/小程序推送 | P0 | 厂商通道(FCM/APNs/华为/小米/OPPO/VIVO)路由 |
| NTF-F03 | 短信：验证码/通知 | P0 | 多供应商路由+回执；短信签名管理 |
| NTF-F04 | 邮件：通知/营销 | P1 | 模板渲染；退订管理 |
| NTF-F05 | 模板引擎：变量插值+多语言 | P0 | 模板版本化；FGAC 隔离租户 |
| NTF-F06 | 通知偏好：用户设置渠道开关 | P1 | 分类型(订单/营销/系统)开关 |
| NTF-F07 | 触达统计：送达/打开/点击 | P1 | Flink 实时聚合 |

### 3.5 课程目录 CMP(全局域)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| CMP-F01 | 课程 CRUD：基本信息/封面/简介 | P0 | 全局域主库单写；多副本读 |
| CMP-F02 | 章节树：多级章节+课时 | P0 | 闭包表存层级；树形缓存 |
| CMP-F03 | 资料挂载：视频/PDF/附件 | P0 | 元数据落 CMP，对象落 S3/MinIO |
| CMP-F04 | 课程上下架：状态机+定时上下架 | P0 | 下架不影响已购用户学习 |
| CMP-F05 | 课程分类/标签：多维筛选 | P1 | 分类树+标签索引 |
| CMP-F06 | 课程价格策略：原价/活动价/会员价 | P1 | 与 MMP 联动 |

### 3.6 搜索 SRH(全局域)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| SRH-F01 | 课程搜索：关键词+筛选+排序 | P0 | ES 索引；分面聚合 |
| SRH-F02 | 题库搜索：知识点/难度/题型 | P0 | 与题库领域联动 |
| SRH-F03 | 搜索建议：补全/纠错/热搜 | P1 | ES completion suggester |
| SRH-F04 | 语义搜索(可叠 RAG)：向量召回+重排 | P2 | Milvus 向量库；混合检索 |
| SRH-F05 | 索引同步：CDC 增量+全量重建 | P0 | TiCDC→Pulsar(ns=cdc://)→ES Sink |
| SRH-F06 | 多租户 FGAC：事业群只见本 BU 课程 | P0 | JWT 驱动 tenant_id 过滤 |

### 3.7 工单 TKT(全局域)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| TKT-F01 | 工单创建：用户/客服/系统自动 | P0 | 类型/优先级/SLA |
| TKT-F02 | 工单分派：技能组+负载均衡 | P0 | 跨客服调度；自动/人工分派 |
| TKT-F03 | IM 会话：实时消息+富媒体 | P0 | WS 长连接；消息存档 |
| TKT-F04 | 工单状态机：新建→处理中→待用户→已解决→关闭 | P0 | SLA 倒计时；超时升级 |
| TKT-F05 | 工单转派/合并 | P1 | 跨组转派；重复工单合并 |
| TKT-F06 | 客服工作台：待办/历史/知识库 | P1 | 与 CMP 联动 |
| TKT-F07 | 工单统计：响应/解决时长 | P1 | Flink 聚合 |

### 3.8 风控 RSK(全局域规则+用户域行为)

| FR-ID | 需求 | 优先级 | 说明 |
|-------|------|--------|------|
| RSK-F01 | 规则引擎：Drools/自研；规则热更新 | P0 | Rust 热点服务；P95<10ms |
| RSK-F02 | 黑名单：IP/设备/用户/手机号 | P0 | 全局广播缓存 |
| RSK-F03 | 实时行为分析：Flink 流计算 | P0 | 滑动窗口；频次/序列模式 |
| RSK-F04 | 注册风控：机器注册/批量注册拦截 | P0 | 设备指纹+行为评分 |
| RSK-F05 | 交易风控：盗刷/套现/刷券 | P0 | 与 OPM/MMP 联动；拦截/放行/人审 |
| RSK-F06 | 内容风控：敏感词/违禁内容 | P0 | 多模态(文本/图片/视频) |
| RSK-F07 | 风控案件中心：人审/处置/申诉 | P1 | 工作流引擎 |
| RSK-F08 | 风控指标看板：拦截率/误拦率 | P1 | Flink+Iceberg |

---

## 4. 非功能需求(NFR)

### 4.1 性能

| NFR-ID | 指标 | 目标 | 测量 |
|--------|------|------|------|
| NFR-P01 | UCM 登录 P95 | <200ms | Prometheus histogram |
| NFR-P02 | OPM 下单 P95 | <300ms | 同上 |
| NFR-P03 | OPM 支付回调 P95 | <500ms | 同上 |
| NFR-P04 | OPM Rust 计费 P95 | <5ms | Rust 内建 metric |
| NFR-P05 | RSK 规则引擎 P95 | <10ms | 同上 |
| NFR-P06 | NTF 站内信延迟 | <3s | 端到端 trace |
| NFR-P07 | CMP 课程详情 P95 | <150ms | Prometheus |
| NFR-P08 | SRH 搜索 P95 | <200ms | ES slowlog+Prom |
| NFR-P09 | TKT IM 消息延迟 | <800ms P95 | WS 端到端压测 |
| NFR-P10 | 峰值订单 OPS | ≥50,000 ops/s | Locust 分布式压测 |

### 4.2 事务一致性

| NFR-ID | 要求 |
|--------|------|
| NFR-T01 | 订单创建+扣库存+锁券 强一致(单元内本地事务) |
| NFR-T02 | 支付回调推进订单状态 CAS(乐观锁，避免重复推进) |
| NFR-T03 | 退款 T+1 对账，差异挂起人工 |
| NFR-T04 | 领券防超发：Redis Lua 原子扣预算+落库，二者最终一致(补偿) |
| NFR-T05 | 全局域写主库单写，跨副本读 eventual(<1s) |

### 4.3 支付幂等

| NFR-ID | 要求 |
|--------|------|
| NFR-I01 | 客户端必传 `Idempotency-Key`(UUID)，服务端 24h 内同键同请求返回同结果 |
| NFR-I02 | 支付回调以渠道流水号+订单号联合幂等 |
| NFR-I03 | 退款以退款单号幂等，多次请求只执行一次 |
| NFR-I04 | 幂等键落 Redis(`zcode:idem:{key}` TTL 24h)+TiDB 双写 |

### 4.4 可用性

| NFR-ID | 目标 |
|--------|------|
| NFR-A01 | 核心组件可用性 99.99%(UCM/OPM/RSK) |
| NFR-A02 | 辅助组件 99.95%(NTF/CMP/SRH/TKT/MMP) |
| NFR-A03 | 单单元故障切换 <30s |
| NFR-A04 | 跨区 Raft 多数派写成功即返回 |

### 4.5 安全

| NFR-ID | 要求 |
|--------|------|
| NFR-S01 | 证件号/手机号/支付信息加密落库(AES-256-GCM) |
| NFR-S02 | JWT RS256，JWKS 公开；refresh token 滚动+吊销 |
| NFR-S03 | 多租户 FGAC：JWT 驱动 tenant_id，禁 app 层拼(参考 D-019) |
| NFR-S04 | 越权防护：每接口校验资源 owner==jwt sub 或管理员 |
| NFR-S05 | 审计日志：管理员/客服操作全量审计 |

### 4.6 可观测

| NFR-ID | 要求 |
|--------|------|
| NFR-O01 | 全链路 trace(W3C traceparent 注入) |
| NFR-O02 | 指标暴露 Prometheus `/metrics` |
| NFR-O03 | 结构化日志(JSON)落 Loki |
| NFR-O04 | 关键事件(支付/封禁/退款)审计落 Iceberg |

---

## 5. 用户故事

### 5.1 学员

- US-L01：作为学员，我想用手机号一键登录，以便快速开始学习。
- US-L02：作为学员，我想在 web 和小程序同时在线，互不踢下线。
- US-L03：作为学员，我想下单时自动用最优券，以便省钱。
- US-L04：作为学员，我想支付失败后重试不重复扣款。
- US-L05：作为学员，我想收到订单/学习/活动的多渠道通知，且可关闭营销类。
- US-L06：作为学员，我想搜索课程时支持错别字纠错。
- US-L07：作为学员，我想提交工单后实时与客服 IM 沟通。
- US-L08：作为学员，我想退款 T+1 到账且有进度可查。

### 5.2 教师

- US-T01：作为教师，我想在课程目录中创建多级章节并挂载资料。
- US-T02：作为教师，我想课程上下架不影响已购学员学习。
- US-T03：作为教师，我想看到我课程的搜索排名与点击。

### 5.3 运营

- US-O01：作为运营，我想创建券模板并设定预算池，防超发。
- US-O02：作为运营，我想活动到期自动下线，无需人工。
- US-O03：作为运营，我想看营销活动的领券/核销/ROI 看板。
- US-O04：作为运营，我想批量上下架课程并定时生效。

### 5.4 客服

- US-C01：作为客服，我想工单自动按技能组分派到我。
- US-C02：作为客服，我想在工单会话中查看用户订单/学习记录上下文。
- US-C03：作为客服，我想 SLA 超时自动升级提醒。
- US-C04：作为客服，我想转派工单给其他组并带历史会话。

### 5.5 管理员

- US-A01：作为管理员，我想封禁用户即吊销所有会话。
- US-A02：作为管理员，我想热更新风控规则不停机。
- US-A03：作为管理员，我想对账差异自动挂起待我处理。
- US-A04：作为管理员，我想审计客服/运营的所有敏感操作。
- US-A05：作为管理员，我想单单元故障切换 <30s 可演练验证。

---

## 6. 验收标准(按组件，Gherkin)

### 6.1 UCM

```gherkin
Scenario: 手机号 OTP 登录
  Given 学员手机号 138****8888 已注册
  When 请求 OTP 并在 60s 内提交正确验证码
  Then 返回 access_token(2h)+refresh_token(7d)
  And JWKS 可从 /ucm/.well-known/jwks.json 获取
  And 会话写入 Redis zcode:session:{uid}:{device}

Scenario: 多端并行在线
  Given 学员已在 web 端登录
  When 同账号在小程序登录
  Then web 端会话不被踢下线
  And 单用户在线端数 <= 8
```

### 6.2 OPM

```gherkin
Scenario: 支付幂等
  Given 学员下单生成订单 ORDER_123
  When 携带同一 Idempotency-Key 连续发起 3 次支付
  Then 仅第一次执行扣款
  And 三次返回相同结果(payment_id, status)

Scenario: 退款 T+1
  Given 订单 ORDER_123 已支付 100 元
  When 学员申请全额退款
  Then 生成退款单 REF_456 状态 PROCESSING
  And T+1 渠道对账后状态 SUCCESS
  And 余额/原路退回
```

### 6.3 MMP

```gherkin
Scenario: 防超发领券
  Given 券模板 COUPON_A 预算池剩 1 张
  When 两个并发请求同时领券
  Then 仅一个成功，另一个返回 STOCK_OUT
  And Redis Lua 原子扣预算+落库一致
```

### 6.4 NTF

```gherkin
Scenario: 通知偏好
  Given 学员关闭营销类通知
  When 运营发送营销 Push
  Then 该学员不收到 Push
  And 仍收到订单/系统类通知
```

### 6.5 CMP

```gherkin
Scenario: 课程下架不影响已购
  Given 课程 C_1 已下架
  And 学员 U_1 已购 C_1
  When U_1 访问 C_1 学习页
  Then 正常访问
  And 新用户搜索不到 C_1
```

### 6.6 SRH

```gherkin
Scenario: 错别字纠错
  When 学员搜索 "jaav 入门"
  Then 返回 "java 入门" 相关课程
  And P95 < 200ms
```

### 6.7 TKT

```gherkin
Scenario: 工单 SLA 升级
  Given 工单 T_1 SLA 30min
  When 30min 内无人接单
  Then 自动升级到组长
  And 推送升级通知
```

### 6.8 RSK

```gherkin
Scenario: 风控规则热更新
  Given 规则 RULE_A 当前为拦截
  When 管理员热更新为放行+人审
  Then 5s 内全节点生效
  And 不重启服务
  And 规则引擎 P95 < 10ms
```

---

## 7. 需求追溯矩阵(节选)

| 需求 ID | 关联 ADR/决策 | 关联组件 | 验收场景 |
|---------|--------------|---------|---------|
| OPM-F02 支付 | ADR-B02 | OPM | 6.2 支付幂等 |
| OPM-F06 计费热点 | ADR-B02, D-009 | OPM(Rust) | NFR-P04 |
| RSK-F01 规则引擎 | ADR-B02, D-009 | RSK(Rust) | 6.8 热更新 |
| MMP-F03 领券 | D-012 | MMP+UCM | 6.3 防超发 |
| NTF-F05 模板引擎 | D-006 | NTF | 6.4 偏好 |
| SRH-F06 FGAC | D-019 | SRH | NFR-S03 |
| UCM-F06 封禁 | D-014 | UCM+RSK | US-A01 |

---

## 8. 风险与开放问题

| ID | 风险/问题 | 缓解 |
|----|----------|------|
| R-01 | 跨单元写挂起期间用户体验 | 前端提示"系统繁忙，稍后重试"+写挂起队列自动重放 |
| R-02 | Rust 热点服务团队经验不足 | 先 Java 兜底，Rust 逐步替换计费/规则；CI 交叉编译 |
| R-03 | 券超发在 Redis 与 DB 不一致 | 补偿任务对账+告警；超发人工兜底 |
| R-04 | 风控误拦率高影响正常用户 | 人审兜底+申诉通道；规则灰度上线 |
| R-05 | 海外单元课程目录隔离与白名单同步 | 增量 CDC 到海外+白名单审核流 |

---

## 9. 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | 文档工程师组 |
