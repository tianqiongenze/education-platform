# 需求追溯矩阵 (Requirements Traceability Matrix)

> **D-210** — 铁律 10 (Design-Implementation Traceability) 强制律的可追溯性矩阵。
> 创建日期: 2026-08-12 | 关联: D-196 (铁律 10 立法), D-199 (ArchUnit 守护)

## 1. Introduction

Iron Law 10 (铁律 10) 要求 **设计→实现可追溯性**: 每个 SRS 需求必须有对应的 ADR 决策、代码实现和测试验证。本矩阵追溯 10 个核心业务场景,从需求到决策到代码到测试的全链路。

追溯链路:
```
SRS需求 → ADR决策 → 代码实现 → 测试验证 → 状态
```

每条记录的文件路径均为代码库中真实存在的文件。

---

## 2. Traceability Table — 10 Core Scenarios

| # | 业务场景 | SRS需求 | ADR决策 | 代码实现 | 测试验证 | 状态 |
|---|---------|---------|---------|---------|---------|------|
| 1 | 课程发布 (Course Publishing) | `docs/requirements/sp03-srs.md` | D-066 (Pulsar domain events), D-201 (DDD enrichment) | `services/core-business/src/main/java/com/ep/business/course/CourseController.java`<br>`services/core-business/src/main/java/com/ep/business/course/CourseService.java`<br>`services/core-business/src/main/java/com/ep/business/course/CoursePublishedEvent.java` | `services/core-business/src/test/java/com/ep/business/course/CourseControllerTest.java`<br>`services/core-business/src/test/java/com/ep/business/course/CourseServiceImplTest.java`<br>`services/core-business/src/test/java/com/ep/business/course/CourseRepositoryTest.java` | ✅ Code Complete |
| 2 | 作业批改 (Assignment Grading) | `docs/requirements/sp05-srs.md` | D-078 (AI grading Dubbo), D-201 (Assignment state machine) | `services/grading-service/src/main/java/com/ep/grading/controller/AssignmentController.java`<br>`services/grading-service/src/main/java/com/ep/grading/homework/event/AssignmentPublishedEvent.java`<br>`services/grading-service/src/main/java/com/ep/grading/grading/impl/AiGradingProviderImpl.java`<br>`services/grading-api/src/main/java/com/ep/grading/api/grading/AiGradingRpcService.java` | `services/grading-service/src/test/java/com/ep/grading/controller/AssignmentControllerTest.java`<br>`services/grading-service/src/test/java/com/ep/grading/grading/impl/AiGradingIntegrationTest.java`<br>`services/grading-service/src/test/java/com/ep/grading/grading/impl/AiGradingProviderImplTest.java` | ✅ Code Complete |
| 3 | 订单支付 (Order & Payment with Saga) | `docs/requirements/sp03-srs.md` | D-113 (Seata Saga migration) | `services/order-platform/order-platform-service/src/main/java/com/ep/order/service/OrderServiceImpl.java`<br>`services/order-platform/order-platform-service/src/main/java/com/ep/order/saga/OrderCompensableAction.java`<br>`services/order-platform/order-platform-service/src/main/java/com/ep/order/saga/PaymentCompensableAction.java`<br>`services/order-platform/order-platform-service/src/main/java/com/ep/order/saga/StockCompensableAction.java`<br>`services/payment-platform/payment-platform-service/src/main/java/com/ep/payment/service/PaymentServiceImpl.java`<br>`services/order-platform/order-platform-service/src/main/resources/saga/create-order-saga.json`<br>`services/order-platform/order-platform-service/src/main/resources/saga/cancel-order-saga.json` | `services/integration-tests/src/test/java/com/ep/integration/SagaCompensationIntegrationTest.java`<br>`services/integration-tests/src/test/java/com/ep/integration/SagaTransactionTest.java`<br>`services/integration-tests/src/test/java/com/ep/integration/SagaE2eIntegrationTest.java`<br>`services/integration-tests/src/test/java/com/ep/integration/EndToEndOrderFlowTest.java`<br>`services/order-platform/order-platform-service/src/test/java/com/ep/order/OrderServiceTestcontainersIT.java`<br>`services/payment-platform/payment-platform-service/src/test/java/com/ep/payment/PaymentServiceTestcontainersIT.java` | ✅ Code Complete |
| 4 | 知识检索 (Knowledge Retrieval / RAG) | `docs/requirements/sp07-srs.md` | D-046 (Agent architecture), D-047 (MCP SDK) | `services/knowledge-platform/retrieval-service/src/main/java/com/ep/knowledge/retrieval/controller/RetrievalController.java`<br>`services/knowledge-platform/retrieval-service/src/main/java/com/ep/knowledge/retrieval/rpc/RetrievalRpcServiceImpl.java`<br>`services/knowledge-platform/knowledge-api/src/main/java/com/ep/knowledge/api/retrieval/RetrievalRpcService.java`<br>`services/knowledge-platform/agent-service/src/main/java/com/ep/knowledge/agent/service/AgentService.java`<br>`services/knowledge-platform/mcp-server/src/main/java/com/ep/mcp/tools/KnowledgeTools.java` | `services/knowledge-platform/retrieval-service/src/test/java/com/ep/knowledge/retrieval/controller/RetrievalControllerTest.java`<br>`services/knowledge-platform/retrieval-service/src/test/java/com/ep/knowledge/retrieval/rpc/RetrievalRpcServiceImplTest.java`<br>`services/knowledge-platform/mcp-server/src/test/java/com/ep/mcp/tools/KnowledgeToolsTest.java`<br>`services/integration-tests/src/test/java/com/ep/integration/EndToEndKnowledgeFlowTest.java` | ✅ Code Complete |
| 5 | 直播课堂 (Live Streaming) | `docs/requirements/sp05-srs.md` | D-187 (SRS+LiveKit) | `services/live-go/main.go`<br>`services/live-go/srs/proxy.go`<br>`services/live-go/srs/origin.go`<br>`services/live-go/livekit/client.go`<br>`services/live-go/livekit/sfu.go`<br>`services/live-go/danmaku/pipeline.go`<br>`services/grading-service/src/main/java/com/ep/grading/live/controller/LiveRoomController.java` | `services/live-go/srs/origin_test.go`<br>`services/live-go/danmaku/pipeline_test.go`<br>`services/integration-tests/src/test/java/com/ep/live/CrossServiceFlowIT.java`<br>`services/integration-tests/src/test/java/com/ep/live/SeataLiveIT.java`<br>`services/integration-tests/src/test/java/com/ep/live/RedisIntegrationIT.java`<br>`services/integration-tests/src/test/java/com/ep/live/TiDBIntegrationIT.java` | ✅ Code Complete |
| 6 | 棋类对弈 (Boardgame) | `docs/requirements/sp05-srs.md` | D-191 (Boardgame Phase 2), D-192 (Phase 3), D-193 (AI sidecar) | `services/boardgame-service/src/main/java/com/ep/boardgame/room/GameRoomService.java`<br>`services/boardgame-service/src/main/java/com/ep/boardgame/room/GameMatch.java`<br>`services/boardgame-service/src/main/java/com/ep/boardgame/matchmaking/MatchmakingService.java`<br>`services/boardgame-service/src/main/java/com/ep/boardgame/ai/StockfishClient.java`<br>`services/boardgame-service/src/main/proto/StockfishProto` | `services/boardgame-service/src/test/java/com/ep/boardgame/room/GameRoomTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/room/GameMatchTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/matchmaking/MatchmakingServiceTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/ai/StockfishSidecarTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/rule/GoRuleEngineTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/tournament/SwissPairingTest.java` | ✅ Code Complete |
| 7 | 养老照护 (Senior Care) | `docs/requirements/sp05-srs.md` | D-144 (sp08-elder) | `services/senior-care/src/main/java/com/ep/senior/care/CarePlan.java`<br>`services/senior-care/src/main/java/com/ep/senior/care/CarePlanServiceImpl.java`<br>`services/senior-care/src/main/java/com/ep/senior/profile/ElderProfile.java`<br>`services/senior-care/src/main/java/com/ep/senior/profile/ElderProfileServiceImpl.java`<br>`services/senior-care/src/main/java/com/ep/senior/health/HealthRecord.java`<br>`services/senior-care/src/main/java/com/ep/senior/health/HealthRecordService.java` | `services/senior-care/src/test/java/com/ep/senior/care/CarePlanTest.java`<br>`services/senior-care/src/test/java/com/ep/senior/care/CarePlanServiceImplTest.java`<br>`services/senior-care/src/test/java/com/ep/senior/profile/ElderProfileTest.java`<br>`services/senior-care/src/test/java/com/ep/senior/profile/ElderProfileServiceImplTest.java`<br>`services/senior-care/src/test/java/com/ep/senior/health/HealthRecordTest.java` | ✅ Code Complete |
| 8 | AI Agent 对话 (AI Agent Conversation) | `docs/requirements/sp04-srs.md` | D-046 (Agent architecture), D-047 (MCP SDK) | `services/ai-platform/ai_agent/graph.py`<br>`services/ai-platform/ai_agent/reflexion.py`<br>`services/ai-platform/ai_agent/nodes.py`<br>`services/ai-platform/ai_agent/state.py`<br>`services/ai-platform/ai_mcp/server.py`<br>`services/ai-platform/ai_mcp/client.py`<br>`services/ai-platform/ai_a2a/server.py`<br>`services/ai-platform/ai_a2a/client.py`<br>`services/knowledge-platform/agent-service/src/main/java/com/ep/knowledge/agent/service/impl/AgentOrchestratorImpl.java` | `services/ai-platform/tests/test_agent.py`<br>`services/ai-platform/tests/test_graph_registry.py`<br>`services/ai-platform/tests/test_mcp.py`<br>`services/ai-platform/tests/test_a2a.py`<br>`services/ai-platform/tests/test_review_agent.py`<br>`services/ai-platform/tests/test_multi_agent.py` | ✅ Code Complete |
| 9 | 多租户隔离 (Multi-tenant Isolation) | `docs/requirements/sp03-srs.md` | D-198 (TenantContext extraction) | `services/ep-web-common/src/main/java/com/ep/web/common/tenant/TenantContext.java`<br>`services/ep-web-common/src/main/java/com/ep/web/common/tenant/TenantContextFilter.java` | `services/integration-tests/src/test/java/com/ep/integration/TenantIsolationE2ETest.java`<br>`services/order-platform/order-platform-service/src/test/java/com/ep/order/TenantIsolationTest.java`<br>`services/payment-platform/payment-platform-service/src/test/java/com/ep/payment/TenantIsolationTest.java`<br>`services/boardgame-service/src/test/java/com/ep/boardgame/config/TenantContextFilterTest.java`<br>`services/senior-care/src/test/java/com/ep/senior/config/TenantContextFilterTest.java` | ✅ Code Complete |
| 10 | 缓存一致性 (Cache Consistency) | `docs/requirements/sp01-srs.md` | D-197 (Pulsar cache invalidation), D-182 (ep-cache SDK) | `services/ep-cache/src/main/java/com/ep/cache/CacheService.java`<br>`services/ep-cache/src/main/java/com/ep/cache/PulsarCacheBroadcast.java`<br>`services/ep-cache/src/main/java/com/ep/cache/PulsarCacheEvictionConsumer.java`<br>`services/ep-cache/src/main/java/com/ep/cache/InvalidateMsg.java`<br>`services/ep-common-go/cache/cache.go`<br>`services/ep-common-python/ep_common/cache.py` | `services/ep-cache/src/test/java/com/ep/cache/CacheServiceTest.java`<br>`services/ep-cache/src/test/java/com/ep/cache/PulsarCacheBroadcastTest.java`<br>`services/ep-cache/src/test/java/com/ep/cache/PulsarCacheEvictionConsumerTest.java`<br>`services/ep-cache/src/test/java/com/ep/cache/CacheConsistencyTest.java`<br>`services/ep-common-go/cache/cache_test.go`<br>`services/ep-common-python/tests/test_cache.py`<br>`services/integration-tests/src/test/java/com/ep/integration/CacheConsistencyTest.java`<br>`deploy/load-tests/k6/cache-consistency.js` | ✅ Code Complete |

---

## 3. Coverage Summary

| 指标 | 数值 |
|------|------|
| 核心场景总数 | 10 |
| 完整追溯 (SRS→ADR→Code→Test 全部存在) | 10 |
| 部分追溯 (缺少某环节) | 0 |
| 追溯覆盖率 | **100%** |

### 追溯环节明细

| 环节 | 存在场景数 | 覆盖率 |
|------|-----------|--------|
| SRS 需求 | 10/10 | 100% |
| ADR 决策 | 10/10 | 100% |
| 代码实现 | 10/10 | 100% |
| 测试验证 | 10/10 | 100% |

---

## 4. Gaps (缺口)

当前 10 个核心业务场景均具备完整的 SRS→ADR→Code→Test 追溯链路,无缺口。

以下为非核心场景的已知追溯缺口 (非本矩阵覆盖范围,记录备查):

| 缺口 | 说明 | 建议补全方式 |
|------|------|-------------|
| 部分 ADR 缺少 `## Implementation` 章节 | `D-114`, `D-120`, `D-197`, `D-198`, `D-203` 等 ADR 文件中未包含 `services/` 或 `src/` 路径引用 | Iron Law 10 CI 检查脚本 (`scripts/check-iron-law-10.py`) 将标记这些违规 |
| 前端 SRS (sp06) 未列入核心场景 | 前端测试覆盖存在但未纳入 10 大核心场景 | 后续可扩展为 12 场景矩阵 |

---

## 5. 关联文档

- **Iron Law 10 立法**: D-196 (decisions.jsonl)
- **ArchUnit 架构守护**: D-199 (`docs/adr/D-199-root-bom-archunit.md`)
- **CI 检查脚本**: `scripts/check-iron-law-10.py`
- **SRS 文档目录**: `docs/requirements/`
- **ADR 文档目录**: `docs/adr/`
- **架构文档目录**: `docs/architecture/`
