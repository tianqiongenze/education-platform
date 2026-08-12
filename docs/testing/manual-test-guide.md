# 教育平台人工测试操作指南

> **版本**: 1.0 | **日期**: 2026-08-09 | **适用**: QA团队 | **前置**: 开发环境可访问 (K8s 集群 + 本地构建)
>
> 本指南覆盖教育平台 13 个服务 (portal-bff / ws-gateway / core-business / order-platform / payment-platform /
> grading-service / ai-platform / knowledge-platform / data-platform / portal-web / admin-web / student-h5)
> 的完整人工测试流程, 包含冒烟测试、功能矩阵、边界异常、跨服务联调、性能、安全与回归最小集。

---

## 1. 测试环境准备

### 1.1 基础设施端口转发

教育平台后端依赖 K8s 集群中的 TiDB / Redis / Nacos / Seata。先建立 port-forward:

```bash
# 启动 port-forward (后台, PID 写入 /tmp/ep-port-forward-pids.txt)
./scripts/port-forward-infra.sh start

# 验证基础设施可达
mysql -h 127.0.0.1 -P 4000 -u root -e "SELECT 1"              # TiDB
redis-cli -h 127.0.0.1 -p 6379 PING                            # Redis (期望 PONG)
curl -s http://127.0.0.1:8848/nacos/v1/ns/operator/metrics     # Nacos
curl -s http://127.0.0.1:7091/health                           # Seata TC

# 测试结束后清理
./scripts/port-forward-infra.sh stop
```

端口映射 (来自 `scripts/port-forward-infra.sh`):

| 组件 | 本地端口 | K8s Service |
|------|---------|-------------|
| TiDB (MySQL) | 4000 | ekb-m2-aws-us-east-1/tidb-cluster-tidb |
| TiDB Status | 10080 | ekb-m2-aws-us-east-1/tidb-cluster-tidb |
| Redis | 6379 | ekb-m2-aws-us-east-1/redis-cluster-new |
| Nacos | 8848 | esp-prod/nacos |
| Seata TC | 7091 | esp-prod/seata-tc-server |

### 1.2 服务启动顺序

按依赖顺序启动 13 个服务 (infra → 注册中心 → 业务服务 → BFF/网关 → AI → 前端):

```bash
# 1. Java 业务服务 (注册到 Nacos, 暴露 Dubbo provider)
cd services/core-business && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/order-platform/order-platform-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/payment-platform/payment-platform-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/grading-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/knowledge-platform/knowledge-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/knowledge-platform/retrieval-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/knowledge-platform/agent-service && mvn spring-boot:run -Dspring-boot.run.profiles=dev &
cd services/knowledge-platform/knowledge-bff && mvn spring-boot:run -Dspring-boot.run.profiles=dev &

# 2. portal-bff (聚合入口, Dubbo consumer)
cd services/portal-bff && EP_PROXY_ENABLED=true mvn spring-boot:run -Dspring-boot.run.profiles=dev &

# 3. ws-gateway (WebSocket 网关)
cd services/ws-gateway && PORT=8081 go run ./src &

# 4. ai-platform (Python Litestar)
cd services/ai-platform && poetry run uvicorn ai_gateway.app:app --host 0.0.0.0 --port 8086 &

# 5. 前端 (3 个 app)
cd frontend/apps/portal-web && pnpm dev --port 3004 &
cd frontend/apps/admin-web && pnpm dev --port 5173 &
cd frontend/apps/student-h5 && pnpm dev --port 3001 &
```

> 备注: `portal-bff` 默认禁用 HTTP 代理 (`ep.proxy.enabled=false`), 测试 `/proxy/**` 路径前需设置 `EP_PROXY_ENABLED=true`。

### 1.3 数据初始化

```bash
# 插入测试课程 / 用户 / 作业 (默认 ep_business + ep_itbackend 库)
python3 scripts/seed-loadtest-data.py --level=basic

# 预热缓存 (Redis)
python3 scripts/seed-cache-data.py

# 列出已有种子数据表
python3 scripts/seed-loadtest-data.py --list
```

### 1.4 Token 获取 (三角色 JWT)

```bash
# 批量生成学生 / 教师 / 管理员三组 RS256 JWT 到 /tmp/ep-loadtest-tokens.json
python3 scripts/gen-loadtest-tokens.py

# 使用示例 token (从 /tmp/ep-loadtest-tokens.json 读取)
STUDENT_TOKEN=$(jq -r '.students[0].token' /tmp/ep-loadtest-tokens.json)
TEACHER_TOKEN=$(jq -r '.teachers[0].token' /tmp/ep-loadtest-tokens.json)
ADMIN_TOKEN=$(jq -r '.admins[0].token' /tmp/ep-loadtest-tokens.json)

echo "student token (len=${#STUDENT_TOKEN})"
echo "teacher token (len=${#TEACHER_TOKEN})"
echo "admin   token (len=${#ADMIN_TOKEN})"
```

> JWT 头部包含 RS256 签名, payload 含 `userId` / `role` / `tenant_id` / `exp` (7 天)。`portal-bff` 的 `AuthFilter` 白名单: `/api/auth/`, `/api/v1/auth/`, `/healthz`, `/actuator/`, `/.well-known/`。

---

## 2. 冒烟测试清单 (15 分钟快速验证)

执行 `./scripts/e2e-smoke-test.sh` 可自动启动并检查部分服务。手工冒烟清单 (13 服务 × 健康检查):

| # | 服务 | 端口 | 健康检查 URL | 预期 HTTP | 实际结果 |
|---|------|------|-------------|----------|---------|
| 1 | core-business | 8083 | http://127.0.0.1:8083/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 2 | order-platform | 8084 | http://127.0.0.1:8084/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 3 | payment-platform | 8087 | http://127.0.0.1:8087/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 4 | grading-service | 8085 | http://127.0.0.1:8085/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 5 | knowledge-service | 8071 | http://127.0.0.1:8071/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 6 | knowledge-bff | 8070 | http://127.0.0.1:8070/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 7 | retrieval-service | 8072 | http://127.0.0.1:8072/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 8 | agent-service | 8073 | http://127.0.0.1:8073/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 9 | tenant-service | 8074 | http://127.0.0.1:8074/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 10 | portal-bff | 8090 | http://127.0.0.1:8090/actuator/health | 200 `{"status":"UP"}` | ☐ |
| 11 | ws-gateway | 8081 | http://127.0.0.1:8081/healthz | 200 `ok` | ☐ |
| 12 | ai-platform | 8086 | http://127.0.0.1:8086/health | 200 `{"status":"ok"}` | ☐ |
| 13 | data-platform | 8082 | http://127.0.0.1:8082/actuator/health | 200 `{"status":"UP"}` | ☐ |

一行验证 (复制粘贴):

```bash
for p in 8083 8084 8087 8085 8071 8070 8072 8073 8074 8090 8082; do
  echo "port=$p $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$p/actuator/health)"
done
echo "ws-gateway $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/healthz)"
echo "ai-platform   $(curl -s http://127.0.0.1:8086/health)"
```

通过门槛: 全部返回 2xx 即视为冒烟通过。任一红则停止后续测试, 先排查服务日志。

---

## 3. 功能测试矩阵

### 3.1 用户管理域 (portal-bff / core-business)

| 用例ID | 场景 | 前置条件 | 操作步骤 | 预期结果 | 优先级 |
|--------|------|---------|---------|---------|--------|
| TC-USER-01 | 创建用户 | admin token | `POST /api/v1/users` 见下方 curl | 200, 返回 `userId` | P0 |
| TC-USER-02 | 按主键查询 | 用户已存在 | `GET /api/v1/users/{id}` | 200, `username` 一致 | P0 |
| TC-USER-03 | 按手机查询 | 用户已存在 | `GET /api/v1/users/phone/{phone}` | 200 | P1 |
| TC-USER-04 | 列表查询 | 至少 1 条数据 | `GET /api/v1/users` | 200, 数组非空 | P1 |
| TC-USER-05 | 更新昵称 | 用户已存在 | `PUT /api/v1/users/{id}` | 204 | P1 |
| TC-USER-06 | 冻结用户 | 用户 ACTIVE | `POST /api/v1/users/{id}/freeze` | 204, `status=FROZEN` | P0 |
| TC-USER-07 | 重复用户名 | 同名已存在 | 再发创建请求 | 4xx (重复键冲突) | P1 |

```bash
# TC-USER-01 创建用户 (走 portal-bff, Dubbo 到 core-business)
curl -s -X POST http://127.0.0.1:8090/api/v1/users \
  -H "Content-Type: application/json" \
  -H "X-Role: admin" \
  -H "X-Tenant-Id: 1" \
  -d '{"username":"qa-student01","phone":"13800000001","email":"qa-s01@example.com","password":"Pass123!","nickname":"QA学生01"}'
# 预期: {"userId":...,"username":"qa-student01","status":1,"tenantId":1,...}

# TC-USER-06 冻结用户
curl -s -X POST http://127.0.0.1:8090/api/v1/users/10001/freeze \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"reason":"测试冻结"}'  -i | grep "^HTTP"
# 预期: HTTP/1.1 204
```

### 3.2 课程管理域 (portal-bff / core-business)

| 用例ID | 场景 | 优先级 |
|--------|------|--------|
| TC-COURSE-01 | 创建课程 | P0 |
| TC-COURSE-02 | 查询课程详情 | P0 |
| TC-COURSE-03 | 分页列表 (`?page=1&size=10`) | P1 |
| TC-COURSE-04 | 关键字搜索 (`/search?keyword=Java`) | P1 |
| TC-COURSE-05 | 发布课程 (`/{id}/publish`) | P1 |
| TC-COURSE-06 | 归档课程 (`/{id}/archive`) | P1 |

```bash
# TC-COURSE-01 创建课程 (字段名来自 CreateCourseCmd)
curl -s -X POST http://127.0.0.1:8090/api/v1/courses \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"title":"CAP定理与分布式系统","description":"分布式一致性入门","teacherId":9501,"category":1,"difficulty":2,"price":99.00}'
# 预期: 200, 返回 courseId

# TC-COURSE-04 搜索
curl -s "http://127.0.0.1:8090/api/v1/courses/search?keyword=CAP" -H "X-Role: admin"
```

### 3.3 订单 Saga 域 (order-platform / payment-platform) — 核心

全链路: 创建订单 → 调起支付 → 状态推进 PAID → 退款。

```bash
# Step 1: 创建订单 (字段来自 CreateOrderCmd record; idempotencyKey 必填)
ORDER_RESP=$(curl -s -X POST http://127.0.0.1:8084/api/v1/orders \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"userId":9001,"courseId":1,"amount":99.00,"idempotencyKey":"idem-qa-order-001","tenantId":"default","productType":"COURSE"}')
ORDER_ID=$(echo $ORDER_RESP | jq -r '.orderId')
echo "orderId=$ORDER_ID  status=$(echo $ORDER_RESP | jq -r '.status')"
# 预期: orderId 非空, status=0 (CREATED)

# Step 2: 调起支付 (字段来自 PaymentController.CreatePaymentHttp)
curl -s -X POST http://127.0.0.1:8087/api/payments \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d "{\"orderId\":\"$ORDER_ID\",\"tenantId\":\"default\",\"channel\":\"MOCK\",\"amount\":\"99.00\",\"currency\":\"CNY\"}"
# 预期: 201, {"paymentId":...,"status":"SUCCESS","channelTxId":"...","orderId":"..."}

# Step 3: 通过 BFF 触发订单状态推进
curl -s -X POST http://127.0.0.1:8090/api/v1/orders/$ORDER_ID/pay \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"payMethod":"MOCK"}' -i | grep "^HTTP"
# 预期: HTTP/1.1 204

# Step 4: 查询订单, 验证状态
curl -s http://127.0.0.1:8090/api/v1/orders/$ORDER_ID -H "X-Role: admin" | jq '{orderId,status,payMethod}'
# 预期: status=1 (PAID) 或 2 (CONFIRMED)

# Step 5: 退款
curl -s -X POST http://127.0.0.1:8087/api/payments/$ORDER_ID/refund \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"amount":"99.00","reason":"测试退款"}'
# 预期: 200, {"refundId":...,"status":"SUCCESS",...}
```

幂等性重点: 用同一 `idempotencyKey` 重复提交 Step 1 应返回相同 `orderId`, 不产生新订单。

### 3.4 作业评分域 (grading-service)

```bash
# Step 1: 教师创建作业 (AssignmentCommand)
curl -s -X POST http://127.0.0.1:8085/api/v1/assignments \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{"title":"CAP定理小测","description":"简述CAP三选二","courseId":1,"teacherId":9501,"maxScore":100,"deadline":"2026-09-30T23:59:59"}'
# 预期: 201, 返回 assignmentId

# Step 2: 学生提交作业 (SubmissionCommand — idempotencyKey 必填)
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions \
  -H "Content-Type: application/json" -H "X-Role: student" \
  -d '{"assignmentId":1,"studentId":9001,"content":"CAP = Consistency, Availability, Partition tolerance","idempotencyKey":"idem-qa-sub-001"}'
# 预期: 201, 返回 submissionId

# Step 3: 教师启动评分流水线 (StartGradingCmd)
curl -s -X POST http://127.0.0.1:8085/api/v1/grading/start \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{"submissionId":1,"gradedBy":9501,"idempotencyKey":"idem-qa-grade-001"}'
# 预期: 201, 返回 pipelineId

# Step 4: 教师打分 (GradeSubmissionCommand)
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions/1/grade \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d '{"submissionId":1,"score":85,"feedback":"理解到位, 举例清晰","graderId":9501,"idempotencyKey":"idem-qa-score-001"}'
# 预期: 200, 返回 score=85

# Step 5: 查询评分流水线阶段
curl -s http://127.0.0.1:8085/api/v1/grading/1/stages -H "X-Role: teacher" | jq '.[].stageName'
```

### 3.5 知识库 RAG 域 (knowledge-service / retrieval-service / agent-service)

```bash
# Step 1: 上传知识库文档 (multipart; 需要 X-Tenant-Id)
curl -s -X POST http://127.0.0.1:8071/api/v1/kb/documents \
  -H "X-Tenant-Id: tenant-qa" -H "X-Role: teacher" \
  -F "file=@docs/testing/sp03-test.md" -F "categoryId=cat-001"
# 预期: 200, 返回 documentId

# Step 2: 混合检索 (RetrievalController.search)
curl -s -X POST http://127.0.0.1:8072/api/v1/retrieval/search \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"query":"什么是CAP定理","knowledgeBaseIds":[],"topK":5}'
# 预期: 200, hits 数组非空

# Step 3: RAG 问答
curl -s -X POST http://127.0.0.1:8072/api/v1/retrieval/rag \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"query":"解释分布式一致性","topK":3,"enableRerank":true}'
# 预期: 200, 含 answer 字段

# Step 4: Agent 对话 (AgentController.chat)
curl -s -X POST http://127.0.0.1:8073/api/v1/agents/agent-001/chat \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" -H "X-Role: student" \
  -d '{"sessionId":"sess-qa-001","userId":"9001","message":"一句话解释Raft"}'
# 预期: 200, 含 answer / sessionId / elapsedMillis

# Step 5: BFF 聚合 RAG (knowledge-bff.BffController)
curl -s -X POST http://127.0.0.1:8070/api/v1/bff/knowledge/rag \
  -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-qa" \
  -d '{"query":"什么是Raft","topK":3,"enableRerank":true}'
```

### 3.6 WebSocket 实时推送域 (ws-gateway)

```bash
# Step 1: 健康检查
curl -s http://127.0.0.1:8081/healthz   # 期望: ok

# Step 2: 使用 wscat 连接 (JWT 作为 query 参数; dev 模式可省略)
wscat -c "ws://127.0.0.1:8081/ws?token=$STUDENT_TOKEN"
# 连接成功后输入:
> {"type":"broadcast","channel":"course-1","message":"hello"}

# Step 3: 断线重连 — 关闭 wscat (Ctrl+C), 5 秒内重新连接, 验证是否补发未读消息
```

> 验证点: RS256 JWT 校验通过后才允许订阅; 无效 token 应被网关立即关闭连接。

---

## 4. 边界与异常测试

### 4.1 幂等性测试

| 用例 | 操作 | 预期 |
|------|------|------|
| IDEM-01 | 同 `idempotencyKey` 连续 3 次创建订单 | 仅 1 次落库, 3 次返回同 `orderId` |
| IDEM-02 | 重复发起支付回调 | 订单状态仅推进 1 次 |
| IDEM-03 | 同 `idempotencyKey` 重复提交作业 | 仅 1 个 submission |
| IDEM-04 | 同 `idempotencyKey` 重复评分 | 仅 1 条评分记录 |
| IDEM-05 | Redis 幂等键过期 (>24h) 后重放 | DB 唯一索引兜底拒绝 |

### 4.2 并发库存测试

```bash
# 用 Apache bench 模拟 100 并发抢同一课程 (假定库存=10)
ab -n 100 -c 20 -p body.json -T application/json \
   -H "X-Role: admin" \
   http://127.0.0.1:8084/api/v1/orders
# 预期: 仅 10 个请求 2xx, 其余返回 409/422 (库存不足)
```

### 4.3 JWT 安全测试

| 用例 | 操作 | 预期 |
|------|------|------|
| JWT-01 | 携带过期 token (>7d) 调 `/api/v1/users` | 401 Unauthorized |
| JWT-02 | 伪造签名 (改 payload 后重签) | 401, 签名校验失败 |
| JWT-03 | 学生 token 调 admin 接口 (`X-Role: admin` 缺失) | 403 (RoleChecker.requireRole) |
| JWT-04 | 无 token 访问受保护接口 | 401 (AuthFilter) |
| JWT-05 | token 中 `tenant_id` 与 `X-Tenant-Id` 不一致 | 数据隔离生效或 403 |

### 4.4 租户隔离测试

```bash
# 租户 A 上传文档
curl -X POST http://127.0.0.1:8071/api/v1/kb/documents -H "X-Tenant-Id: tenant-A" \
     -H "X-Role: teacher" -F "file=@a.md"
# 租户 B 查询, 应看不到 A 的文档
curl -s http://127.0.0.1:8072/api/v1/retrieval/search -H "X-Tenant-Id: tenant-B" \
     -H "X-Role: student" -d '{"query":"a","topK":10}' | jq '.hits | length'
# 预期: 0 (租户隔离生效)
```

### 4.5 限流熔断测试

| 用例 | 操作 | 预期 |
|------|------|------|
| RL-01 | 单 IP 高频 (>100 QPS) 调登录 | 触发 429 |
| RL-02 | 下游 payment-platform 宕机 | portal-bff 快速失败 502, 不耗尽线程池 |
| RL-03 | ai-platform 响应 >10s | 网关超时返回 504 |

---

## 5. 跨服务联调测试

完整业务链路: 注册 → 浏览课程 → 下单 → 支付 → 作业 → 评分。

```bash
RUN_ID="qa-$(date +%s)"

# Step 1: 注册 (portal-bff → core-business via Dubbo)
curl -s -X POST http://127.0.0.1:8090/api/v1/users \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d "{\"username\":\"$RUN_ID\",\"phone\":\"138$(date +%H%M%S)\",\"email\":\"$RUN_ID@qa.com\",\"password\":\"Pass123!\"}"
# 验证点: 返回 userId

# Step 2: 浏览课程列表
curl -s "http://127.0.0.1:8090/api/v1/courses?page=1&size=10" -H "X-Role: admin" | jq 'length'
# 验证点: 返回 ≥1 条

# Step 3: 创建订单 (idempotencyKey 用 RUN_ID 隔离)
ORDER=$(curl -s -X POST http://127.0.0.1:8084/api/v1/orders \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d "{\"userId\":9001,\"courseId\":1,\"amount\":99.00,\"idempotencyKey\":\"idem-$RUN_ID\",\"tenantId\":\"default\",\"productType\":\"COURSE\"}")
OID=$(echo $ORDER | jq -r '.orderId')
# 验证点: orderId 非空, status=0

# Step 4: 支付
curl -s -X POST http://127.0.0.1:8087/api/payments \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d "{\"orderId\":\"$OID\",\"channel\":\"MOCK\",\"amount\":\"99.00\"}"
# 验证点: 201, status=SUCCESS

# Step 5: 提交作业
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions \
  -H "Content-Type: application/json" -H "X-Role: student" \
  -d "{\"assignmentId\":1,\"studentId\":9001,\"content\":\"$RUN_ID submission\",\"idempotencyKey\":\"idem-$RUN_ID-sub\"}"
# 验证点: 201, submissionId

# Step 6: 教师评分
curl -s -X POST http://127.0.0.1:8085/api/v1/submissions/1/grade \
  -H "Content-Type: application/json" -H "X-Role: teacher" \
  -d "{\"submissionId\":1,\"score\":90,\"feedback\":\"$RUN_ID ok\",\"graderId\":9501,\"idempotencyKey\":\"idem-$RUN_ID-grade\"}"
# 验证点: 200, score=90

# 自动化版本: ./scripts/e2e-full-chain-test.sh  (生成 markdown 报告)
```

---

## 6. 性能验收测试

使用 k6 执行 1K 并发基线 (`deploy/load-tests/k6/full-chain-1000.js`):

```bash
# 准备 token
python3 scripts/gen-loadtest-tokens.py
export K6_TOKENS_FILE=/tmp/ep-loadtest-tokens.json

# 执行 1000 VU 5 分钟压测
k6 run --vus 1000 --duration 5m deploy/load-tests/k6/full-chain-1000.js

# 单域冒烟 (5 VU 1 分钟)
k6 run --vus 5 --duration 1m deploy/load-tests/k6/bff-smoke.js

# WebSocket 长连压测
k6 run deploy/load-tests/k6/websocket-connect.js
```

预期阈值 (来自 `full-chain-1000.js`):

| 指标 | 目标 |
|------|------|
| `http_req_duration` P95 | < 3000ms |
| `http_req_duration` P99 | < 5000ms |
| `http_req_failed` | < 5% |
| 1000 VU 持续 5m | 0 个 VU 异常退出 |

> 详细阈值与分域目标见 `deploy/load-tests/k6/full-chain-1000.js` 与 `loadtest-1000vu-report.md`。

---

## 7. 安全测试清单

| 项 | 操作 | 预期 |
|----|------|------|
| RBAC 越权 | 学生 token + `X-Role: student` 调 `POST /api/v1/users` | 403 |
| SQL 注入 | `GET /api/v1/courses/search?keyword=' OR 1=1--` | 200 但空集, 日志无 SQL 拼接 |
| XSS | 提交作业 content 含 `<script>alert(1)</script>` | 入库转义或拦截 |
| CORS | `curl -H "Origin: https://evil.com" -I http://127.0.0.1:8090/api/v1/courses` | 响应无 `Access-Control-Allow-Origin: *` |
| JWT 伪造 | 篡改 payload 后用错私钥签名 | 401 |
| 越权访问订单 | `GET /api/v1/orders/{他人orderId}` 用学生 token | 403 (非本人订单) |
| 租户越权 | 跨 `X-Tenant-Id` 查询文档 | 404 或空集 |
| 弱口令 | 注册密码 "123" | 400 (校验失败) |

```bash
# 示例: SQL 注入 (期望参数化查询生效)
curl -s "http://127.0.0.1:8090/api/v1/courses/search?keyword=' OR 1=1--" -H "X-Role: admin" | jq
# 预期: 空数组或 400, 不返回全表
```

---

## 8. 回归测试最小集 (发版必跑 20 例)

| # | 用例 | 端点 |
|---|------|------|
| 1 | 创建用户 | POST /api/v1/users |
| 2 | 查询用户 | GET /api/v1/users/{id} |
| 3 | 冻结用户 | POST /api/v1/users/{id}/freeze |
| 4 | 创建课程 | POST /api/v1/courses |
| 5 | 课程搜索 | GET /api/v1/courses/search |
| 6 | 发布课程 | POST /api/v1/courses/{id}/publish |
| 7 | 创建订单 (含幂等) | POST /api/v1/orders |
| 8 | 支付 | POST /api/payments |
| 9 | 退款 | POST /api/payments/{orderId}/refund |
| 10 | 查订单状态 | GET /api/v1/orders/{id} |
| 11 | 创建作业 | POST /api/v1/assignments |
| 12 | 提交作业 | POST /api/v1/submissions |
| 13 | 启动评分 | POST /api/v1/grading/start |
| 14 | 打分 | POST /api/v1/submissions/{id}/grade |
| 15 | 知识库上传 | POST /api/v1/kb/documents |
| 16 | RAG 检索 | POST /api/v1/retrieval/rag |
| 17 | Agent 对话 | POST /api/v1/agents/{id}/chat |
| 18 | AI chat | POST /v1/chat/completions |
| 19 | 健康检查全服务 | GET /actuator/health × 11 |
| 20 | WebSocket 连接 | ws://127.0.0.1:8081/ws |

一键回归: `./scripts/e2e-full-chain-test.sh` (覆盖 1-18 + 健康检查)。

---

## 9. 缺陷报告模板

```markdown
### [P0/P1/P2] <简明标题, 含端点与现象>

- **模块/服务**: portal-bff / core-business / grading-service / ...
- **环境**: dev / staging / prod-like;   集群: <namespace>
- **复现步骤**:
  1. <前置数据准备>
  2. `curl ...` (粘贴完整命令)
  3. 观察响应
- **预期**: <HTTP 状态 + 业务字段>
- **实际**: <实际响应, 含完整 body>
- **traceId**: `<从响应头 X-Trace-Id 取>`
- **日志摘录**:
  ```
  <服务日志关键行>
  ```
- **截图**: <UI 缺陷附 GIF/PNG, API 缺陷附 curl 截图>
- **复现率**: 100% / 偶发 (n/10)
- **影响范围**: <影响哪些用户/业务流程>
- **报告人**: <姓名>   **报告日期**: 2026-08-09
```

> traceId 贯穿 portal-bff → Dubbo RPC → 下游服务 (D-070), 报缺陷时务必附上, 便于研发定位。

---

## 10. 安全测试 (D-154)

本节覆盖 D-154 安全加固后的人工验证用例, 重点确认 Actuator 端点鉴权、Bean Validation、
限流、CORS 与 WebSocket fail-closed 策略在生产态下生效。

### 10.1 Actuator 端点鉴权验证

D-154 后除 `/actuator/health` 外, 其余 Actuator 端点 (`/metrics`、`/env`、`/beans` 等)
均需认证方可访问。

```bash
# (1) 健康检查端点匿名可访问
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/actuator/health
# 预期: 200

# (2) metrics 端点无凭据访问被拒
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/actuator/metrics
# 预期: 401

# (3) 带凭据访问 metrics 端点
curl -s -o /dev/null -w '%{http_code}' \
  -u "$ACTUATOR_USER:$ACTUATOR_PASS" \
  http://127.0.0.1:8090/actuator/metrics
# 预期: 200
```

验证点: 逐个 Java 服务 (core-business / order-platform / payment-platform /
grading-service / knowledge-* / portal-bff) 均需满足上述三步, 任一服务的
`/actuator/env` 或 `/actuator/beans` 匿名可读视为缺陷。

### 10.2 Bean Validation 测试

D-154 后 GlobalExceptionHandler 统一拦截 `MethodArgumentNotValidException`,
返回 400 及字段级错误明细。

```bash
# 创建用户缺少必填字段 (username / phone)
curl -s -X POST http://127.0.0.1:8090/api/v1/users \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"email":"bad@example.com"}' -i | grep -E "^HTTP|field"
# 预期: HTTP/1.1 400, 响应体含 field 级错误 (username / phone required)

# 创建订单 amount 为负数 (Bean Validation @Min(0))
curl -s -X POST http://127.0.0.1:8084/api/v1/orders \
  -H "Content-Type: application/json" -H "X-Role: admin" \
  -d '{"userId":9001,"courseId":1,"amount":-1,"idempotencyKey":"idem-bv-001","tenantId":"default","productType":"COURSE"}' \
  -i | grep "^HTTP"
# 预期: HTTP/1.1 400, 含 amount 字段错误
```

验证点: 响应结构包含 `field`、`message` 字段, 不暴露堆栈。

### 10.3 Rate Limiting 测试

D-154 后登录/秒杀等敏感接口配置 Rate Limit, 超阈值返回 429。

```bash
# 高频访问登录接口 (1 秒内 100 次)
for i in $(seq 1 100); do
  curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST http://127.0.0.1:8090/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"fake","password":"fake"}'
done | sort | uniq -c
# 预期: 前若干次 401/400, 后续返回 429 Too Many Requests

# 或使用 ab 压测
ab -n 200 -c 50 -p login.json -T application/json \
   http://127.0.0.1:8090/api/v1/auth/login
# 预期: 部分请求返回 429, 非 5xx
```

验证点: 429 响应包含 `Retry-After` 头, 客户端可据此退避。

### 10.4 CORS 验证

D-154 加固 CORS 白名单, 仅允许生产域名, 拒绝通配符与未授权来源。

```bash
# (1) 未授权来源预检请求
curl -s -i -X OPTIONS http://127.0.0.1:8090/api/v1/courses \
  -H "Origin: https://evil.com" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: X-Role"
# 预期: 响应无 Access-Control-Allow-Origin, 或仅返回默认策略不回显 evil.com

# (2) 授权来源预检请求 (替换为生产 portal 域名)
curl -s -i -X OPTIONS http://127.0.0.1:8090/api/v1/courses \
  -H "Origin: https://portal.example.com" \
  -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: X-Role,X-Tenant-Id"
# 预期: 200/204, Access-Control-Allow-Origin: https://portal.example.com,
#       Access-Control-Allow-Headers 含 X-Role, X-Tenant-Id
```

验证点: 任何接口都不应返回 `Access-Control-Allow-Origin: *`。

### 10.5 WebSocket fail-closed 验证

D-154 后 ws-gateway 在生产态 (`APP_ENV=production`) 下, 若 JWT 签名密钥未配置,
必须 fail-closed 拒绝启动 / 拒绝连接, 不得降级为匿名模式。

```bash
# (1) 模拟生产态缺失 JWT 密钥启动 ws-gateway
APP_ENV=production JWT_PUBLIC_KEY="" go run ./src 2>&1 | head -5
# 预期: 日志输出 fail-closed 错误, 进程退出或不接受 /ws 连接

# (2) 在 fail-closed 状态下尝试连接
APP_ENV=production JWT_PUBLIC_KEY="" PORT=8081 go run ./src &
sleep 2
wscat -c "ws://127.0.0.1:8081/ws?token=$STUDENT_TOKEN"
# 预期: 连接被立即拒绝 (close 1008 / 401), 不进入订阅态

# (3) 对照: 配置正确密钥后连接正常
APP_ENV=production JWT_PUBLIC_KEY="$JWT_PUB" PORT=8081 go run ./src &
sleep 2
wscat -c "ws://127.0.0.1:8081/ws?token=$STUDENT_TOKEN"
# 预期: 连接成功, 可订阅频道
```

验证点: 生产态任何情况下都不允许无密钥放行; dev 态可保留匿名以便联调。

---

## 附录: 服务端口与启动文件速查

| 服务 | 端口 | 启动模块 | profile |
|------|------|---------|---------|
| core-business | 8083 | services/core-business | dev |
| order-platform | 8084 | services/order-platform/order-platform-service | dev |
| payment-platform | 8087 | services/payment-platform/payment-platform-service | dev |
| grading-service | 8085 | services/grading-service | dev |
| knowledge-service | 8071 | services/knowledge-platform/knowledge-service | dev |
| knowledge-bff | 8070 | services/knowledge-platform/knowledge-bff | dev |
| retrieval-service | 8072 | services/knowledge-platform/retrieval-service | dev |
| agent-service | 8073 | services/knowledge-platform/agent-service | dev |
| tenant-service | 8074 | services/knowledge-platform/tenant-service | dev |
| portal-bff | 8090 | services/portal-bff | dev |
| ws-gateway | 8081 | services/ws-gateway/src | (env PORT=8081) |
| ai-platform | 8086 | services/ai-platform/ai_gateway.app:app | (Litestar) |
| data-platform | 8082 | services/data-platform | dev |
| portal-web | 3004 | frontend/apps/portal-web | (vite) |
| admin-web | 5173 | frontend/apps/admin-web | (vite) |
| student-h5 | 3001 | frontend/apps/student-h5 | (vite) |
