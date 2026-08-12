# API 测试用例集

> **版本**: 1.0 | **日期**: 2026-08-09 | **依据**: 各服务 Controller + DTO/Cmd 实体类
>
> 本用例集覆盖教育平台核心 API, 每个用例包含 **请求 curl** + **预期响应** + **断言** + **边界测试**。
> 字段名严格对齐 `CreateUserCmd` / `CreateCourseCmd` / `CreateOrderCmd` / `SubmissionCommand` 等 record/class。

通用请求头:
- `X-Role: admin|teacher|student|platform_admin` — RBAC 角色 (RoleChecker)
- `X-Tenant-Id: <tenantId>` — 多租户隔离 (sp07 / sp02 必填)
- `Authorization: Bearer <jwt>` — bff-java 受保护接口 (AuthFilter)

---

## bff-java (8090)

bff-java 是统一 API 入口, 通过 Dubbo 调用 sp03-business / sp03-order, 通过 `/proxy/**` HTTP 转发到其它服务。

### POST /api/v1/users — 创建用户

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8090/api/v1/users \
  -H "Content-Type: application/json" \
  -H "X-Role: admin" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "username":"qa-user01",
    "phone":"13800000001",
    "email":"qa-user01@example.com",
    "password":"Pass123!",
    "nickname":"QA用户01"
  }'
```

**预期响应** (200):
```json
{
  "userId": 10001,
  "username": "qa-user01",
  "phone": "13800000001",
  "email": "qa-user01@example.com",
  "nickname": "QA用户01",
  "status": 1,
  "tenantId": 1,
  "createdAt": 1723171200000,
  "updatedAt": 1723171200000
}
```

**断言**:
- HTTP 200
- 响应包含 `userId` (Long), `status=1` (ACTIVE)
- DB: `SELECT * FROM ep_user WHERE username='qa-user01'` 返回 1 行, `password_hash` 非空 (不存明文)

**边界测试**:
- 缺少必填字段 (无 `phone`) → 400
- 重复 `username` → 409
- 无 `X-Role: admin` 头 → 403 (`RoleChecker.requireRole(request, "admin","super_admin")`)
- 请求体非 JSON → 400

### GET /api/v1/users/{id} — 按主键查询用户

**请求**:
```bash
curl -s http://127.0.0.1:8090/api/v1/users/10001 -H "X-Role: admin"
```
**预期响应** (200): 同上创建响应体。
**断言**: `userId` 路径参数 == 响应 `userId`。
**边界**: 不存在的 id → 404 (`ResponseEntity.notFound()`)。

### GET /api/v1/users/phone/{phone} — 按手机号查询

**请求**:
```bash
curl -s http://127.0.0.1:8090/api/v1/users/phone/13800000001 -H "X-Role: admin"
```
**预期**: 200, `phone` 与路径参数一致。
**边界**: 未注册手机 → 404。

### PUT /api/v1/users/{id} — 更新用户

**请求**:
```bash
curl -s -X PUT http://127.0.0.1:8090/api/v1/users/10001 \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"nickname":"新昵称","email":"new@example.com"}' -i | grep "^HTTP"
```
**预期**: 204 (No Content)。
**断言**: 二次 `GET` 返回新昵称。
**边界**: body 为 null → 500 (`Objects.requireNonNull`)。

### POST /api/v1/users/{id}/freeze — 冻结用户

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8090/api/v1/users/10001/freeze \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"reason":"测试冻结"}' -i | grep "^HTTP"
```
**预期**: 204; `GET` 返回 `status=2` (FROZEN)。
**边界**: 二次冻结 → 500 (`User already FROZEN`)。

### POST /api/v1/courses — 创建课程

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8090/api/v1/courses \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{
    "title":"CAP定理与分布式系统",
    "description":"分布式一致性入门",
    "teacherId":9501,
    "category":1,
    "difficulty":2,
    "price":99.00
  }'
```
**预期响应** (200): 返回 `courseId` 与完整 Course 对象。
**断言**: `teacherId=9501`, `price=99.00`。
**边界**: `price` 为负 → 400; 缺 `title` → 400。

### GET /api/v1/courses/{id} — 课程详情

```bash
curl -s http://127.0.0.1:8090/api/v1/courses/1 -H "X-Role: admin"
```
**预期**: 200, 返回 Course。不存在 → 404。

### GET /api/v1/courses?page=1&size=10 — 分页列表

```bash
curl -s "http://127.0.0.1:8090/api/v1/courses?page=1&size=10" -H "X-Role: admin"
```
**预期**: 200, 返回数组 (非分页对象, 由 `CourseService.listCourses(page,size)` 决定)。

### GET /api/v1/courses/search?keyword=xxx — 搜索

```bash
curl -s "http://127.0.0.1:8090/api/v1/courses/search?keyword=CAP" -H "X-Role: admin"
```
**预期**: 200, 返回数组。`keyword` 必填, 缺失 → 400 (`@RequestParam String keyword`)。

### POST /api/v1/courses/{id}/publish 和 /archive

```bash
curl -X POST http://127.0.0.1:8090/api/v1/courses/1/publish -H "X-Role: admin"
curl -X POST http://127.0.0.1:8090/api/v1/courses/1/archive -H "X-Role: admin"
```
**预期**: 均 204。

### POST /api/v1/orders — 创建订单 (Dubbo → sp03-order)

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8090/api/v1/orders \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{
    "userId":9001,
    "courseId":1,
    "amount":99.00,
    "idempotencyKey":"idem-api-001",
    "tenantId":"default",
    "productType":"COURSE"
  }'
```
**预期响应** (200):
```json
{"orderId":5001,"userId":9001,"courseId":1,"tenantId":"default","productType":"COURSE",
 "amount":99.00,"status":0,"payMethod":null,"idempotencyKey":"idem-api-001",
 "createdAt":1723171200000,"updatedAt":1723171200000}
```
**断言**: `status=0` (CREATED); 同 `idempotencyKey` 二次请求返回同 `orderId`。
**边界**: 缺 `idempotencyKey` → 400; `amount<=0` → 400。

### POST /api/v1/orders/{id}/pay 和 /refund

```bash
curl -X POST http://127.0.0.1:8090/api/v1/orders/5001/pay \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"payMethod":"MOCK"}'
curl -X POST http://127.0.0.1:8090/api/v1/orders/5001/refund \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"reason":"用户取消"}'
```
**预期**: 均 204。

### GET /proxy/** — HTTP 代理透传 (需 EP_PROXY_ENABLED=true)

```bash
curl -s http://127.0.0.1:8090/proxy/ai/health      # → sp04-ai:8086/health
curl -s http://127.0.0.1:8090/proxy/itbackend/actuator/health
curl -s http://127.0.0.1:8090/proxy/knowledge/actuator/health
```
**预期**: 200, 返回下游真实响应。
**边界**: `ep.proxy.enabled=false` → 503 `{"error":"proxy disabled"}`; 未配置路由 → 404; 下游不可达 → 502。

---

## sp03-payment (8087)

REST 适配层, 主路径仍是 Dubbo (供 sp03-order 调用)。REST 用于契约测试与 BFF 外部客户端。

### POST /api/payments — 创建支付

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8087/api/payments \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{
    "orderId":"5001",
    "tenantId":"default",
    "channel":"MOCK",
    "amount":"99.00",
    "currency":"CNY"
  }'
```
**预期响应** (201):
```json
{"paymentId":"pay-...","status":"SUCCESS","channelTxId":"mock-tx-...","orderId":"5001"}
```
**断言**: `status=SUCCESS` (MockChannel 立即成功); `paymentId` 非空。
**边界**: `channel` 不识别 → 走 MOCK (`parseChannel` 兜底); `amount` 格式错误 → 500。

### POST /api/payments/{orderId}/refund — 退款

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8087/api/payments/5001/refund \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"amount":"99.00","reason":"测试退款"}'
```
**预期响应** (200):
```json
{"refundId":"rfd-...","orderId":"5001","status":"SUCCESS","amount":"99.00"}
```
**断言**: 订单原支付的关联退款单创建; `status=SUCCESS`。
**边界**: 重复退款同 `orderId` → 取决于幂等键; 金额超原支付 → 400。

---

## sp05-itbackend (8085)

作业 / 提交 / 评分三域, 全部使用 `@DubboReference` + `RoleChecker.requireRole` 双重保护。

### POST /api/v1/assignments — 创建作业 (教师)

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8085/api/v1/assignments \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{
    "title":"CAP定理小测",
    "description":"简述CAP三选二并举例",
    "courseId":1,
    "teacherId":9501,
    "maxScore":100,
    "deadline":"2026-09-30T23:59:59"
  }'
```
**预期响应** (201): 返回 `assignmentId`, `status=DRAFT`。
**断言**: `maxScore` 缺省时默认 100 (`AssignmentCommand` compact constructor)。
**边界**: 学生角色 → 403 (`RoleChecker.requireRole(request, "admin","super_admin","teacher")`)。

### POST /api/v1/assignments/{id}/publish 和 /close

```bash
curl -X POST http://127.0.0.1:8085/api/v1/assignments/1/publish -H "X-Role: teacher"
curl -X POST http://127.0.0.1:8085/api/v1/assignments/1/close    -H "X-Role: teacher"
```
**预期**: 200, 返回状态变更后的 Assignment。

### GET /api/v1/assignments?courseId=1 — 按课程列出

```bash
curl -s "http://127.0.0.1:8085/api/v1/assignments?courseId=1" -H "X-Role: teacher"
```
**预期**: 200, 数组。`courseId` 必填 (`@RequestParam("courseId")`)。

### POST /api/v1/submissions — 提交作业 (学生)

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions \
  -H "Content-Type: application/json" -H "X-Role: student" \
  -d '{
    "assignmentId":1,
    "studentId":9001,
    "content":"CAP = Consistency, Availability, Partition tolerance",
    "idempotencyKey":"idem-sub-001"
  }'
```
**预期响应** (201): 返回 `submissionId`, `status=SUBMITTED`。
**断言**: 同 `idempotencyKey` 重复提交 → 返回同 `submissionId`。
**边界**: 缺 `idempotencyKey` → 400; 教师角色提交 → 403 (允许角色: student/admin/super_admin)。

### POST /api/v1/submissions/{id}/grade — 打分 (教师)

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions/1/grade \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{
    "submissionId":1,
    "score":85,
    "feedback":"理解到位, 举例清晰",
    "graderId":9501,
    "idempotencyKey":"idem-grade-001"
  }'
```
**预期响应** (200): 返回 `score=85`, `feedback` 持久化。
**断言**: path 中 `{id}` 与 body 中 `submissionId` 不一致时, 控制器以 path id 为准 (`effective = new GradeSubmissionCommand(id, ...)`)。

### POST /api/v1/grading/start — 启动评分流水线

```bash
curl -s -X POST http://127.0.0.1:8085/api/v1/grading/start \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{"submissionId":1,"gradedBy":9501,"idempotencyKey":"idem-pipe-001"}'
```
**预期**: 201, 返回 `pipelineId`。

### GET /api/v1/grading/{id}/stages — 查询流水线阶段

```bash
curl -s http://127.0.0.1:8085/api/v1/grading/1/stages -H "X-Role: teacher"
```
**预期**: 200, 返回 `GradeStage[]`, 含 AI 评分 / 教师复核 / 查重 等阶段。

### GET /api/v1/grading?submissionId=1 — 按 submission 查询

```bash
curl -s "http://127.0.0.1:8085/api/v1/grading?submissionId=1" -H "X-Role: teacher"
```
**预期**: 200 或 404 (无流水线记录)。

---

## sp04-ai (8086) — Python Litestar

OpenAI 兼容的 AI Gateway, 默认走 MockLLMProvider (`config.llm_mode=mock`)。

### POST /v1/chat/completions — 对话补全

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8086/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "messages":[
      {"role":"system","content":"你是教育助手"},
      {"role":"user","content":"一句话解释CAP定理"}
    ],
    "model":"gpt-4-mock",
    "temperature":0.7,
    "max_tokens":256,
    "stream":false
  }'
```
**预期响应** (200):
```json
{
  "id":"chatcmpl-...",
  "model":"gpt-4-mock",
  "choices":[{"message":{"role":"assistant","content":"..."}}],
  "usage":{"prompt_tokens":25,"completion_tokens":30,"total_tokens":55}
}
```
**断言**: `choices[0].message.role="assistant"`; `usage.total_tokens>0`。
**边界**: `messages` 为空数组 → 400 (`ValueError` → HTTPException)。

### POST /v1/embeddings — 文本向量

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8086/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"input":"分布式一致性入门","model":"text-embedding-3-small"}'
```
**预期响应** (200): `data[0].embedding` 为浮点数组。
**断言**: 向量维度与配置一致 (默认 1536)。
**边界**: `input` 同时支持 `str` 与 `list[str]`。

### GET /health

```bash
curl -s http://127.0.0.1:8086/health
# 预期: {"status":"ok","service":"ai-gateway","llm_mode":"mock"}
```

---

## sp07-knowledge (8070-8074)

多模块, BFF (8070) 聚合 retrieval (8072) / agent (8073), 通过 Dubbo 调用。

### sp07-bff (8070)

#### GET /api/v1/bff/knowledge/overview

```bash
curl -s http://127.0.0.1:8070/api/v1/bff/knowledge/overview -H "X-Tenant-Id: tenant-qa"
```
**预期**: 200, `{"tenantId":"tenant-qa","topDocuments":[...],"total":N}`。
**断言**: `X-Tenant-Id` 必填 (`@RequestHeader`)。

#### POST /api/v1/bff/knowledge/rag

```bash
curl -s -X POST http://127.0.0.1:8070/api/v1/bff/knowledge/rag \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" \
  -d '{"query":"什么是CAP","knowledgeBaseIds":[],"topK":5,"enableRerank":true}'
```
**预期**: 200, 返回 `RagResult` (answer + 引用片段)。

#### POST /api/v1/bff/agent/dashboard

```bash
curl -s -X POST http://127.0.0.1:8070/api/v1/bff/agent/dashboard \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" \
  -d '{"agentId":"agent-001","message":"Hello"}'
```
**预期**: 200, `{"answer":"...","sessionId":"...","elapsedMillis":N}`。

### sp07-knowledge-service (8071)

#### POST /api/v1/kb/documents — 文档上传

**请求**:
```bash
curl -s -X POST http://127.0.0.1:8071/api/v1/kb/documents \
  -H "X-Tenant-Id: tenant-qa" -H "X-Role: teacher" \
  -F "file=@docs/testing/sp03-test.md" \
  -F "categoryId=cat-001"
```
**预期响应** (200): 返回 `documentId`, `status=PROCESSED`。
**断言**: 文件落 MinIO, 向量写入 Milvus。
**边界**: 学生角色 → 403 (writeRoles=`teacher,admin,platform_admin`); 缺 `file` → 400。

#### GET /api/v1/kb/documents/{id} 和 DELETE

```bash
curl -s http://127.0.0.1:8071/api/v1/kb/documents/doc-001 -H "X-Tenant-Id: tenant-qa" -H "X-Role: student"
curl -X DELETE http://127.0.0.1:8071/api/v1/kb/documents/doc-001 -H "X-Tenant-Id: tenant-qa" -H "X-Role: admin"
```
**预期**: GET 200 / 不存在 404; DELETE 204。
**断言**: 跨租户访问 → 404 (按 tenantId 过滤)。

#### GET /api/v1/kb/categories

```bash
curl -s http://127.0.0.1:8071/api/v1/kb/categories -H "X-Tenant-Id: tenant-qa" -H "X-Role: student"
```
**预期**: 200, 返回当前租户分类数组。

### sp07-retrieval (8072)

#### POST /api/v1/retrieval/search

```bash
curl -s -X POST http://127.0.0.1:8072/api/v1/retrieval/search \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"query":"CAP","knowledgeBaseIds":[],"topK":10}'
```
**预期**: 200, `{"hits":[...],"total":N}`。

#### POST /api/v1/retrieval/rag

```bash
curl -s -X POST http://127.0.0.1:8072/api/v1/retrieval/rag \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"query":"解释Raft","topK":3,"enableRerank":true}'
```
**预期**: 200, `{"answer":"...","citations":[...]}`。

### sp07-agent (8073)

#### POST /api/v1/agents — 创建 Agent

```bash
curl -s -X POST http://127.0.0.1:8073/api/v1/agents \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: admin" \
  -d '{
    "name":"教学助手",
    "systemPrompt":"你是教育领域的助手",
    "knowledgeBaseIds":["kb-001"],
    "tools":["search"],
    "mode":"ROUTER"
  }'
```
**预期响应** (200): 返回 `agentId`, `mode=ROUTER`。
**断言**: `mode` 默认 `ROUTER` (`body.getOrDefault("mode","ROUTER")`)。
**边界**: 非 admin/platform_admin → 403。

#### POST /api/v1/agents/{id}/chat

```bash
curl -s -X POST http://127.0.0.1:8073/api/v1/agents/agent-001/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"sessionId":"sess-001","userId":"9001","message":"一句话解释Paxos"}'
```
**预期响应** (200): `{"answer":"...","sessionId":"sess-001","elapsedMillis":N}`。
**断言**: 同 `sessionId` 多轮对话保留上下文。

### sp07-tenant (8074)

#### POST /api/v1/tenants — 创建租户

```bash
curl -s -X POST http://127.0.0.1:8074/api/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-new","name":"新租户","quota":10000}'
```
**预期**: 200, 返回 Tenant。

#### PUT /api/v1/tenants/{id}/quota — 修改配额 (仅 platform_admin)

```bash
curl -s -X PUT http://127.0.0.1:8074/api/v1/tenants/tenant-new/quota \
  -H "Content-Type: application/json" -H "X-Role: platform_admin" \
  -d '{"quota":50000}'
```
**预期**: 200。
**边界**: 非 platform_admin → 403 (`RoleChecker.PLATFORM_ADMIN`); 缺 `quota` 字段 → 400。

---

## gateway-go (8081)

WebSocket 网关, 健康检查 + WS 鉴权。

### GET /healthz

```bash
curl -s http://127.0.0.1:8081/healthz   # 预期: ok
```

### WS 连接 (需 RS256 JWT)

```bash
# 安装 wscat: npm i -g wscat
wscat -c "ws://127.0.0.1:8081/ws?token=$STUDENT_TOKEN"
# 连接成功后发送消息
> {"type":"subscribe","channel":"course-1"}
> {"type":"broadcast","channel":"course-1","message":"hello"}
```
**预期**: 订阅成功, 收到自己广播的消息。
**边界**: 无效 token → 立即断连; dev 模式无公钥 → 控制台 WARN, 连接放行。

---

## 附录: 角色权限速查

| 端点前缀 | 写操作允许角色 | 读操作允许角色 |
|---------|---------------|---------------|
| `/api/v1/users` (sp03) | admin, super_admin | 任意 (查询) |
| `/api/v1/assignments` (sp05) | admin, super_admin, teacher | 任意 |
| `/api/v1/submissions` (sp05) | student, admin, super_admin (提交); teacher, admin, super_admin (评分) | 任意 |
| `/api/v1/grading/*` (sp05) | teacher, admin, super_admin | 任意 |
| `/api/v1/kb/documents` (sp07) | teacher, admin, platform_admin | student, teacher, admin, platform_admin |
| `/api/v1/agents` (sp07 创建) | admin, platform_admin | — |
| `/api/v1/agents/{id}/chat` (sp07) | student, teacher, admin, platform_admin | — |
| `/api/v1/tenants/{id}/quota` (sp07) | platform_admin | — |
