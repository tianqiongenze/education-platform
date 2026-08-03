# SP-03 业务中台 详细设计说明书(SDD)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-03-SDD | v1.0 | 2026-08-03 | Released | 研发组 | SP-03-SRS/SAD；ADR-B01..B05；D-008/D-015/D-016/D-019 |

> 本 SDD 落地 8 个组件的 DDD 聚合/实体/值对象、关键表结构、支付幂等、风控规则引擎、通知模板引擎、搜索索引设计。承接 SP-03-SAD 架构。

---

## 1. DDD 战略设计回顾

限界上下文(来自 EP-SPEC-001 §2.2)与本组件映射：

| 限界上下文 | 归属 | 组件 |
|-----------|------|------|
| Account 用户账户 | 用户域 | UCM |
| Order 订单/支付 | 用户域 | OPM |
| Ops 运营活动 | 全局域+用户域 | MMP |
| Course 课程目录 | 全局域 | CMP |
| Teacher 教师档案 | 全局域 | (CMP 联动) |
| Ticket 客服工单 | 全局域 | TKT |
| RiskRule 全局风控规则 | 全局域+用户域 | RSK |
| (跨域通知) | 跨域 | NTF |
| (跨域搜索) | 全局域 | SRH |

上下文映射(DDD)：
```
   UCM ──DTS(防腐层)──▶ OPM(客户/供应商, UCM 是订单的客户)
   OPM ──DTS──▶ MMP(调券核销)
   OPM ──DTS──▶ RSK(风控决策)
   OPM ──OHS(开放主机)──▶ NTF(PULSAR 事件)
   CMP ──OHS──▶ SRH(变更事件驱动索引)
   TKT ──DTS──▶ UCM/OPM/CMP(上下文查询)
   RSK ──OHS──▶ 各组件(规则决策 gRPC)
```

---

## 2. UCM 用户中心 详细设计

### 2.1 聚合/实体/值对象

```
聚合根 User(用户域, 单元化 user_id)
├─ 实体 Credential(凭证, 一用户多凭证)
│   ├─ type: Phone|Email|Wechat|QQ|Apple
│   └─ identifier, secret_hash(BCrypt cost=12)
├─ 实体 Profile(资料)
│   ├─ nickname, avatar, bio, tags[]
├─ 值对象 RealName(实名, 不可变)
│   ├─ name, id_type, id_no(AES-256-GCM), verified_at
├─ 值对象 DeviceSession(多端会话)
│   └─ device_id, platform, login_at, refresh_token_jti
└─ 值对象 BanStatus(封禁状态)
    └─ banned, reason, until, operator
```

不变量：
- 单用户在线端 ≤ 8
- 封禁即吊销所有 refresh token
- 证件号加密落库，明文不落日志

### 2.2 关键表结构

```sql
-- t_ucm_user (TiDB, shard by user_id, AUTO_RANDOM(5)+PRE_SPLIT_REGIONS)
CREATE TABLE t_ucm_user (
  user_id      BIGINT NOT NULL /*AUTO_RANDOM(5)*/,
  phone        VARCHAR(20)  NOT NULL,
  email        VARCHAR(128),
  nickname     VARCHAR(64),
  avatar_url   VARCHAR(256),
  status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2封禁',
  ban_until    DATETIME,
  realname_verified TINYINT DEFAULT 0,
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_phone (phone),
  KEY idx_email (email)
) SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4;

-- t_ucm_credential
CREATE TABLE t_ucm_credential (
  user_id     BIGINT NOT NULL,
  cred_type   VARCHAR(16) NOT NULL COMMENT 'Phone/Email/Wechat/...',
  identifier  VARCHAR(128) NOT NULL,
  secret_hash VARCHAR(128),
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (user_id, cred_type, identifier),
  KEY idx_identifier (cred_type, identifier)
);

-- t_ucm_realname (证件号 AES-256-GCM 加密)
CREATE TABLE t_ucm_realname (
  user_id      BIGINT NOT NULL PRIMARY KEY,
  real_name    VARBINARY(256) NOT NULL COMMENT 'AES加密',
  id_type      TINYINT NOT NULL,
  id_no_enc    VARBINARY(256) NOT NULL COMMENT 'AES加密',
  verified_at  DATETIME NOT NULL,
  verifier     VARCHAR(32)
);

-- 多端会话存 Redis: zcode:session:{uid}:{device} = {platform, login_at, refresh_jti}
-- 全局黑名单: zcode:banlist (Set, 广播到各单元)
```

### 2.3 关键接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /ucm/register | POST | 注册, 返回 user_id + token |
| /ucm/login | POST | 登录, JWT RS256 |
| /ucm/.well-known/jwks.json | GET | JWKS 公钥 |
| /ucm/profile | GET/PUT | 资料 |
| /ucm/realname/verify | POST | 实名 |
| /ucm/ban | POST | 管理员封禁 |
| /ucm/sessions | GET/DELETE | 多端会话管理 |

---

## 3. OPM 订单支付 详细设计

### 3.1 聚合/实体/值对象

```
聚合根 Order(用户域, 单元化 user_id)
├─ 值对象 OrderItem(订单项)
│   └─ sku_id, name, qty, unit_price, course_id
├─ 实体 Payment(支付, 一订单一支付)
│   ├─ channel: Wechat|Alipay|Apple|Balance
│   ├─ channel_tx_id, amount, paid_at
│   └─ status: PENDING|PAID|FAILED|REFUNDED
├─ 实体 Refund(退款, 一支付多退款)
│   └─ refund_id, amount, status, reason, recon_status
├─ 值对象 CouponUse(用券)
│   └─ user_coupon_id, deduct_amount
└─ 值对象 OrderPrice(计费结果, Rust 算)
    └─ original, coupon_deduct, member_deduct, points_deduct, payable
```

订单状态机：
```
CREATED ──pay──▶ PAID ──refund(req)──▶ REFUNDING ──ok──▶ REFUNDED
   │                │                       │
   │timeout 30min   │cancel                │fail
   ▼                ▼                      ▼
 CANCELED        CANCELED              REFUND_FAILED
```

### 3.2 关键表结构

```sql
-- t_opm_order (TiDB, shard by user_id)
CREATE TABLE t_opm_order (
  order_id     BIGINT NOT NULL /*snowflake含单元标识*/,
  user_id      BIGINT NOT NULL,
  cell_id      TINYINT NOT NULL COMMENT '单元标识',
  status       TINYINT NOT NULL COMMENT '1待支付2已支付3取消4退款中5已退款',
  amount_orig  DECIMAL(10,2) NOT NULL,
  amount_pay   DECIMAL(10,2) NOT NULL,
  coupon_deduct DECIMAL(10,2) DEFAULT 0,
  items_json   JSON NOT NULL,
  pay_channel  VARCHAR(16),
  channel_tx_id VARCHAR(64),
  paid_at      DATETIME,
  expire_at    DATETIME NOT NULL COMMENT '30min',
  created_at   DATETIME NOT NULL,
  version      BIGINT NOT NULL DEFAULT 0 COMMENT 'CAS乐观锁',
  PRIMARY KEY (order_id),
  KEY idx_user (user_id, created_at),
  KEY idx_status_expire (status, expire_at)
) SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4;

-- t_opm_payment
CREATE TABLE t_opm_payment (
  payment_id   BIGINT NOT NULL PRIMARY KEY,
  order_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  channel      VARCHAR(16) NOT NULL,
  amount       DECIMAL(10,2) NOT NULL,
  status       TINYINT NOT NULL,
  channel_tx_id VARCHAR(64),
  idem_key     VARCHAR(64) NOT NULL COMMENT '幂等键',
  created_at   DATETIME NOT NULL,
  UNIQUE KEY uk_idem (idem_key),
  KEY idx_order (order_id)
);

-- t_opm_refund
CREATE TABLE t_opm_refund (
  refund_id    BIGINT NOT NULL PRIMARY KEY,
  payment_id   BIGINT NOT NULL,
  order_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  amount       DECIMAL(10,2) NOT NULL,
  status       TINYINT NOT NULL COMMENT '1处理中2成功3失败',
  reason       VARCHAR(256),
  recon_status TINYINT DEFAULT 0 COMMENT '对账状态',
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_payment (payment_id)
);

-- 幂等键 Redis: zcode:idem:{key} = {result_json} TTL 24h
```

### 3.3 支付幂等设计

```
客户端发起支付(携带 Idempotency-Key)
        │
        ▼
┌───────────────────────────────────────────┐
│ 1. 查 Redis zcode:idem:{key}             │
│    命中 → 直接返回缓存结果(幂等)          │
│ 2. 未命中 → 查 t_opm_payment.idem_key     │
│    命中 → 回填 Redis, 返回                │
│ 3. 都未命中:                              │
│    a. Redis SETNX zcode:idem:{key} (TTL 24h) 抢占
│       抢占失败 → 短暂等待后回查(并发去重)  │
│    b. 写 t_opm_payment(UNIQUE uk_idem)    │
│       冲突 → 回查并返回                    │
│    c. 调支付网关, 落结果                   │
│    d. 更新 Redis 结果                      │
│    e. 返回                                 │
└───────────────────────────────────────────┘

支付回调幂等:
  回调以 (channel, channel_tx_id, order_id) 联合幂等
  推进订单状态用 CAS: UPDATE ... SET status=2 WHERE order_id=? AND status=1 AND version=?
  重复回调 CAS 失败即幂等返回
```

### 3.4 Rust 计费引擎 BillingEngine

proto 契约(sp03-proto)：
```protobuf
service BillingEngine {
  rpc Compute(ComputeRequest) returns (ComputeResponse);
}
message ComputeRequest {
  repeated OrderItem items = 1;
  repeated string user_coupon_ids = 2;
  int32  user_level = 3;
  int64  points_to_use = 4;
}
message ComputeResponse {
  Decimal original = 1;
  Decimal coupon_deduct = 2;
  Decimal member_deduct = 3;
  Decimal points_deduct = 4;
  Decimal payable = 5;
  repeated Split splits = 6; // 分账
}
```
- 纯函数计算价(无副作用), Redis Lua 扣余额
- P95<5ms, 单实例 ≥50k QPS(tokio)

---

## 4. MMP 营销 详细设计

### 4.1 聚合/实体/值对象

```
聚合根 CouponTemplate(全局域)
├─ 值对象 Discount(折扣规则)
│   └─ type: FULLCUT|DISCOUNT|DIRECT|EXCHANGE, threshold, amount, rate
├─ 值对象 Budget(预算池)
│   └─ total, issued, used, per_user_limit
├─ 值对象 Validity(有效期)
│   └─ start, end, extend_days_on_refund
└─ 值对象 Targeting(人群定向)
    └─ user_levels, bus, channels

聚合根 UserCoupon(用户域, 单元化 user_id)
├─ template_id, user_id, status(UNUSED|LOCKED|USED|RETURNED|EXPIRED)
├─ locked_at, used_at, expire_at
└─ source(活动/手动)
```

### 4.2 关键表结构

```sql
-- t_mmp_coupon_template (全局域主库单写)
CREATE TABLE t_mmp_coupon_template (
  template_id  BIGINT NOT NULL PRIMARY KEY,
  name         VARCHAR(64) NOT NULL,
  discount_type TINYINT NOT NULL,
  threshold    DECIMAL(10,2),
  amount       DECIMAL(10,2),
  rate         DECIMAL(3,2),
  budget_total INT NOT NULL,
  budget_issued INT NOT NULL DEFAULT 0,
  per_user_limit INT DEFAULT 1,
  valid_start  DATETIME NOT NULL,
  valid_end    DATETIME NOT NULL,
  extend_days_on_refund INT DEFAULT 7,
  status       TINYINT NOT NULL COMMENT '1草稿2生效3下线',
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL
);

-- t_mmp_user_coupon (用户域, shard by user_id)
CREATE TABLE t_mmp_user_coupon (
  user_coupon_id BIGINT NOT NULL /*snowflake*/,
  user_id      BIGINT NOT NULL,
  template_id  BIGINT NOT NULL,
  status       TINYINT NOT NULL COMMENT '1未用2锁定3已用4退回5过期',
  locked_at    DATETIME,
  used_at      DATETIME,
  order_id     BIGINT,
  expire_at    DATETIME NOT NULL,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (user_coupon_id),
  KEY idx_user_status (user_id, status),
  KEY idx_template (template_id)
) SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4;

-- t_mmp_campaign (全局域)
CREATE TABLE t_mmp_campaign (
  campaign_id  BIGINT NOT NULL PRIMARY KEY,
  name         VARCHAR(128) NOT NULL,
  template_ids JSON NOT NULL,
  targeting_json JSON NOT NULL,
  start_at     DATETIME NOT NULL,
  end_at       DATETIME NOT NULL,
  status       TINYINT NOT NULL,
  created_at   DATETIME NOT NULL
);
```

### 4.3 领券防超发(Redis Lua)

```lua
-- KEYS[1] = budget:coupon:{template_id}  剩余库存
-- KEYS[2] = user:coupon:limit:{uid}:{tpl} 用户已领数
-- ARGV[1] = per_user_limit
local left = redis.call('DECR', KEYS[1])
if left < 0 then
  redis.call('INCR', KEYS[1]) -- 回滚
  return {-1, 'STOCK_OUT'}
end
local got = redis.call('INCR', KEYS[2])
if got > tonumber(ARGV[1]) then
  redis.call('DECR', KEYS[1]) -- 回滚库存
  redis.call('DECR', KEYS[2]) -- 回滚用户计数
  return {-2, 'USER_LIMIT'}
end
return {1, 'OK'}
```
Lua 原子成功后异步落 t_mmp_user_coupon；落库失败由补偿任务对账(库存回补)。

---

## 5. NTF 通知 详细设计

### 5.1 聚合/实体/值对象

```
聚合根 Notification(跨域)
├─ 值对象 Target(收件人)
│   └─ user_id, channels[]
├─ 值对象 Template(模板, 版本化)
│   └─ code, version, lang, body, vars_schema
├─ 实体 Dispatch(分发记录, 一通知多渠道)
│   └─ channel, status, ext_id, sent_at, delivered_at
└─ 值对象 Preference(用户偏好)
    └─ type_order|type_marketing|type_system 开关
```

### 5.2 关键表结构

```sql
-- t_ntf_template (全局域, 版本化)
CREATE TABLE t_ntf_template (
  id           BIGINT NOT NULL PRIMARY KEY,
  code         VARCHAR(64) NOT NULL,
  version      INT NOT NULL,
  lang         VARCHAR(8) NOT NULL DEFAULT 'zh-CN',
  channel      TINYINT NOT NULL COMMENT '1站内2Push3短信4邮件',
  title_tpl    VARCHAR(256),
  body_tpl     TEXT NOT NULL,
  vars_schema  JSON NOT NULL,
  tenant_id    VARCHAR(32) NOT NULL COMMENT 'FGAC',
  status       TINYINT NOT NULL,
  created_at   DATETIME NOT NULL,
  UNIQUE KEY uk_code_ver (code, version, lang, channel)
);

-- t_ntf_inbox (用户域, shard by user_id, 站内信)
CREATE TABLE t_ntf_inbox (
  inbox_id     BIGINT NOT NULL /*snowflake*/,
  user_id      BIGINT NOT NULL,
  template_code VARCHAR(64) NOT NULL,
  title        VARCHAR(256) NOT NULL,
  body         TEXT NOT NULL,
  is_read      TINYINT DEFAULT 0,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (inbox_id),
  KEY idx_user_read (user_id, is_read, created_at)
) SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4;

-- t_ntf_dispatch (跨域, 分发记录)
CREATE TABLE t_ntf_dispatch (
  dispatch_id  BIGINT NOT NULL PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  channel      TINYINT NOT NULL,
  ext_id       VARCHAR(128) COMMENT '厂商消息ID',
  status       TINYINT NOT NULL COMMENT '1待发2已发3送达4失败',
  error        VARCHAR(256),
  sent_at      DATETIME,
  delivered_at DATETIME,
  created_at   DATETIME NOT NULL,
  KEY idx_notif (notification_id)
);

-- 用户偏好存 Redis: zcode:ntf:pref:{uid} = {order:1, marketing:0, system:1}
```

### 5.3 模板引擎

- 模板变量插值：Mustache 风格 `{{var}}`，渲染前按 `vars_schema` 校验类型。
- 多语言：按 `lang` 路由模板版本；缺失回退 `zh-CN`。
- FGAC：模板带 `tenant_id`，渲染与查询强制 JWT tenant_id 过滤(D-019)。
- 版本化：`code+version+lang+channel` 唯一；变更走新版本，不原地改。
- Pact 契约：模板变量 schema 变更需 Pact 校验消费方。

---

## 6. CMP 课程目录 详细设计

### 6.1 聚合/实体/值对象

```
聚合根 Course(全局域)
├─ 实体 CourseSection(章节, 树形)
│   └─ parent_id, title, sort, type(章节/课时)
├─ 实体 Material(资料)
│   └─ type: VIDEO|PDF|ATTACH, object_key(S3), duration, size
├─ 值对象 Price(价格策略)
│   └─ original, activity, member
├─ 值对象 Category(分类)
└─ 值对象 CourseStatus(状态机)
    └─ DRAFT→PENDING→ONLINE→OFFLINE
```

### 6.2 关键表结构

```sql
-- t_cmp_course (全局域主库单写)
CREATE TABLE t_cmp_course (
  course_id    BIGINT NOT NULL PRIMARY KEY,
  title        VARCHAR(256) NOT NULL,
  subtitle     VARCHAR(512),
  cover_url    VARCHAR(256),
  intro        TEXT,
  teacher_id   BIGINT,
  category_id  BIGINT,
  price_orig   DECIMAL(10,2),
  price_activity DECIMAL(10,2),
  price_member DECIMAL(10,2),
  status       TINYINT NOT NULL COMMENT '1草稿2待审3上架4下架',
  schedule_online_at DATETIME COMMENT '定时上架',
  schedule_offline_at DATETIME,
  tenant_id    VARCHAR(32) NOT NULL COMMENT 'FGAC, BU隔离',
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_status (status),
  KEY idx_category (category_id),
  KEY idx_teacher (teacher_id)
);

-- t_cmp_section (闭包表存层级)
CREATE TABLE t_cmp_section (
  section_id   BIGINT NOT NULL PRIMARY KEY,
  course_id    BIGINT NOT NULL,
  parent_id    BIGINT NOT NULL DEFAULT 0,
  title        VARCHAR(256) NOT NULL,
  sort         INT NOT NULL,
  type         TINYINT NOT NULL COMMENT '1章节2课时',
  duration     INT COMMENT '秒',
  created_at   DATETIME NOT NULL,
  KEY idx_course_parent (course_id, parent_id, sort)
);
CREATE TABLE t_cmp_section_closure (
  ancestor_id   BIGINT NOT NULL,
  descendant_id BIGINT NOT NULL,
  depth         INT NOT NULL,
  PRIMARY KEY (ancestor_id, descendant_id)
);

-- t_cmp_material
CREATE TABLE t_cmp_material (
  material_id  BIGINT NOT NULL PRIMARY KEY,
  section_id   BIGINT NOT NULL,
  course_id    BIGINT NOT NULL,
  type         TINYINT NOT NULL COMMENT '1视频2PDF3附件',
  object_key   VARCHAR(256) NOT NULL COMMENT 'S3/MinIO key',
  duration     INT,
  size_bytes   BIGINT,
  created_at   DATETIME NOT NULL,
  KEY idx_section (section_id)
);
```

下架保护：Enrollment(已购记录)在用户域独立存储，CMP 下架仅改 `status`，不影响已购访问。

---

## 7. SRH 搜索 详细设计

### 7.1 索引设计

```
ES 索引: srh_course_v1
{
  "course_id":   { "type": "long" },
  "title":       { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
  "subtitle":    { "type": "text", "analyzer": "ik_max_word" },
  "intro":       { "type": "text", "analyzer": "ik_max_word" },
  "teacher":     { "type": "keyword" },
  "category":    { "type": "keyword" },
  "tags":        { "type": "keyword" },
  "price":       { "type": "double" },
  "tenant_id":   { "type": "keyword" },  // FGAC
  "status":      { "type": "keyword" },  // 仅 ONLINE 入索引
  "sales":       { "type": "long" },
  "rating":      { "type": "double" },
  "suggest":     { "type": "completion" }, // 补全
  "updated_at":  { "type": "date" }
}

ES 索引: srh_question_v1 (题库, 类似, 略)
```

### 7.2 索引同步

```
CMP 全局域主库变更
   │ TiCDC
   ▼
Kafka(cmp.change, Avro+SchemaRegistry)
   │
   ▼
ES Sink(Flink CDC 3.x YAML pipeline)
   │ upsert
   ▼
ES srh_course_v1
```
- 增量：CDC 实时(秒级)
- 全量重建：定时(周)蓝绿索引 + alias 切换，零阻塞
- 多租户：查询强制 `tenant_id` 过滤(JWT 驱动)

### 7.3 语义检索(可选, P2)

```
用户查询 → BM25(ES) + 向量(Milvus, namespace+FGAC) → Reranker → Top-K
```

---

## 8. TKT 工单 详细设计

### 8.1 聚合/实体/值对象

```
聚合根 Ticket(全局域)
├─ 实体 Session(IM 会话, 一工单一会话)
│   └─ messages[], started_at, closed_at
├─ 值对象 SLA
│   └─ respond_sla, resolve_sla, escalate_to
├─ 值对象 Assignee(分派)
│   └─ group_id, agent_id, assigned_at
└─ 值对象 TicketStatus(状态机)
    └─ NEW→PROCESSING→WAITING_USER→RESOLVED→CLOSED
```

工单状态机：
```
NEW ──dispatch──▶ PROCESSING ──wait──▶ WAITING_USER ──reply──▶ PROCESSING
                       │                       │
                       │resolve                │timeout escalate
                       ▼                       ▼
                   RESOLVED ──close──▶ CLOSED   升级组长
```

### 8.2 关键表结构

```sql
-- t_tkt_ticket (全局域)
CREATE TABLE t_tkt_ticket (
  ticket_id    BIGINT NOT NULL PRIMARY KEY,
  user_id      BIGINT NOT NULL,
  type         TINYINT NOT NULL COMMENT '1咨询2投诉3退款4其他',
  priority     TINYINT NOT NULL,
  title        VARCHAR(256),
  content      TEXT,
  status       TINYINT NOT NULL COMMENT '1新建2处理中3待用户4已解决5已关闭',
  group_id     BIGINT COMMENT '技能组',
  agent_id     BIGINT COMMENT '客服',
  respond_sla_at DATETIME,
  resolve_sla_at DATETIME,
  escalated    TINYINT DEFAULT 0,
  related_order_id BIGINT,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_user (user_id, created_at),
  KEY idx_group_status (group_id, status),
  KEY idx_sla (status, resolve_sla_at)
);

-- t_tkt_session (IM 会话)
CREATE TABLE t_tkt_session (
  session_id   BIGINT NOT NULL PRIMARY KEY,
  ticket_id    BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  agent_id     BIGINT,
  started_at   DATETIME NOT NULL,
  closed_at    DATETIME,
  KEY idx_ticket (ticket_id)
);

-- t_tkt_message (IM 消息, 存档)
CREATE TABLE t_tkt_message (
  message_id   BIGINT NOT NULL PRIMARY KEY,
  session_id   BIGINT NOT NULL,
  sender_type  TINYINT NOT NULL COMMENT '1用户2客服3系统',
  content_type TINYINT NOT NULL COMMENT '1文本2图片3文件',
  content      TEXT NOT NULL,
  object_key   VARCHAR(256) COMMENT '富媒体S3 key',
  created_at   DATETIME NOT NULL,
  KEY idx_session (session_id, created_at)
);
```

### 8.3 IM(Go WS 网关)

- 长连接：Go WS 网关，Redis 存在线状态 `zcode:tkt:online:{agent_id}`
- 消息流转：用户 → WS → 投递到客服在线连接；离线存档+Push
- 跨客服调度：DispatchService 按技能组+负载(当前会话数)分配

---

## 9. RSK 风控 详细设计

### 9.1 聚合/实体/值对象

```
聚合根 RiskRule(全局域)
├─ 值对象 Condition(条件)
│   └─ field, op, value
├─ 值对象 Action(处置)
│   └─ type: BLOCK|PASS|REVIEW, reason
├─ 值对象 Score(评分)
│   └─ weight, threshold
└─ 值对象 Version(版本)
    └─ version, status(草稿/生效/灰度), published_at

聚合根 RiskCase(案件, 全局域)
├─ user_id, scene, rule_id, action, score
├─ status(待审/已处置/已申诉)
└─ reviewer, decision, evidence
```

### 9.2 风控规则引擎设计(Drools/自研混合)

```
┌─────────────── RuleEngine(Rust, gRPC /risk.evaluate)───────────────┐
│ 1. 黑名单快查: 本地 LRU(10万)+Redis(广播缓存 zcode:rsk:blacklist)   │
│ 2. 规则匹配: Rete-like 编译规则集                                    │
│    ├─ 规则来源: Nacos(监听热更新, 原子 swap Arc<RuleSet>)            │
│    ├─ 规则版本: 灰度比例(0%/10%/100%)                                │
│    └─ 匹配: scene(register/pay/content) × ctx(uid/ip/device/...)    │
│ 3. 评分: 命中规则加权求和 → 与阈值比较                                │
│ 4. 决策: 拦截(BLOCK) / 放行(PASS) / 人审(REVIEW)                    │
│ 5. 输出: {action, reason, score, rule_hits[]}                       │
│ 性能: P95<10ms, 单实例 ≥30k QPS                                      │
│ 热更新: Nacos listener → swap, 5s 全节点生效, 不重启                  │
└────────────────────────────────────────────────────────────────────┘

复杂规则(需 Drools 表达力, 低频): Java 侧 Drools 引擎处理
  - 例: 跨周期组合规则(7天内3次退款+差评) 走 Drools
  - Drools 结果写回 Redis 供 Rust 快查
分层: Rust 高频简单规则 + Java Drools 低频复杂规则
```

### 9.3 关键表结构

```sql
-- t_rsk_rule (全局域, 版本化)
CREATE TABLE t_rsk_rule (
  rule_id      BIGINT NOT NULL PRIMARY KEY,
  scene        VARCHAR(32) NOT NULL COMMENT 'register/pay/content/...',
  name         VARCHAR(128) NOT NULL,
  conditions_json JSON NOT NULL,
  action       TINYINT NOT NULL COMMENT '1拦截2放行3人审',
  weight       INT NOT NULL,
  threshold    INT,
  version      INT NOT NULL,
  status       TINYINT NOT NULL COMMENT '1草稿2生效3灰度4下线',
  gray_percent INT DEFAULT 0,
  published_at DATETIME,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_scene_status (scene, status)
);

-- t_rsk_blacklist (全局域, 广播缓存)
CREATE TABLE t_rsk_blacklist (
  bl_id        BIGINT NOT NULL PRIMARY KEY,
  bl_type      TINYINT NOT NULL COMMENT '1IP2设备3用户4手机号',
  value        VARCHAR(128) NOT NULL,
  reason       VARCHAR(256),
  expire_at    DATETIME,
  created_at   DATETIME NOT NULL,
  UNIQUE KEY uk_type_value (bl_type, value)
);

-- t_rsk_case (全局域, 案件)
CREATE TABLE t_rsk_case (
  case_id      BIGINT NOT NULL PRIMARY KEY,
  user_id      BIGINT,
  scene        VARCHAR(32) NOT NULL,
  rule_id      BIGINT,
  action       TINYINT NOT NULL,
  score        INT,
  status       TINYINT NOT NULL COMMENT '1待审2已处置3已申诉4已关闭',
  reviewer     BIGINT,
  decision     VARCHAR(256),
  evidence_json JSON,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_status (status, created_at)
);
```

### 9.4 Flink 实时行为分析

```
Pulsar rsk.behavior(各组件上报: 注册/登录/下单/领券/搜索...)
   │
   ▼
Flink Job: BehaviorAnalysis
   ├─ 滑动窗口(5min/10min) 统计频次
   ├─ 序列模式(CEP): 如 "1min内 5 次登录失败"
   ├─ 输出: 风险评分 → Redis(实时, zcode:rsk:score:{uid})
   └─ 落 Iceberg(历史行为, 供模型训练)
```

---

## 10. 跨组件一致性策略

| 场景 | 策略 |
|------|------|
| 下单+锁券+扣库存 | 单元内本地事务(强一致) |
| 支付回调推进订单 | CAS 乐观锁(幂等) |
| 领券扣预算+落库 | Redis Lua 原子 + 补偿对账(最终一致) |
| 课程变更→索引 | CDC 异步(最终一致, 秒级) |
| 通知发送 | Pulsar 事件(至少一次 + 幂等) |
| 风控规则更新 | Nacos 广播(最终一致, 5s) |
| 全局域写→副本读 | eventual <1s, 强一致读走主库 |

---

## 11. 接口契约治理

- OpenAPI 3.0 契约存 `sp03-contracts` 仓库
- Dubbo Triple RPC proto 存 `sp03-proto` 仓库, CI 生成 Java/Rust stub
- 破坏性变更走版本化(v1→v2 并存 ≥2 版本周期)
- Pact 契约测试: 每个消费方(BU/组件)定义期望, CI 校验提供方
- Schema Registry: Pulsar(Avro) + Kafka(Avro) 强制 schema 兼容

---

## 12. 变更记录

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| v1.0 | 2026-08-03 | 初始版本 | 研发组 |
