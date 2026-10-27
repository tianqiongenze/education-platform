# D-240: AI 支付授权 Envelope——KYA/授权四要素/决策模式/标记化/意图留痕

> **日期**: 2026-10-27（r28 E2E 终局 2026-10-28）
> **类型**: 支付安全架构（AI/智能体支付能力，B 包）
> **状态**: ✅ 代码+114 测试全绿；r28 双节点部署 BVV 通过；**集群 E2E T1~T10 全部 PASS**（CRDB 实证：3 笔 SUCCESS 带 agent 溯源、意图留痕 3 条、token CONSUMED）
> **关联**: D-235（状态机）/ D-237（路由）/ D-238（账务底座）/ D-239（对账五段式）/ D-222（Saga 补偿）/ D-221（幂等）
> **参考**: 《3万字，224张图，一文讲透「AI支付」原理与全链路落地》

---

## 1. 背景与问题

AI Agent 代表用户发起支付（订阅续费、内容采购、工具调用计费）时，传统支付的三要素（商户/订单/金额）不足以界定责任：**付费的是用户，下单的不是用户**。缺三样东西：
1. **身份**：这个 agent 是谁、受谁委托（KYA）；
2. **授权**：它被允许花多少钱、花在哪、花到什么时候（授权四要素）；
3. **留痕**：每笔支付背后用户的原始指令是什么（争议仲裁依据）。

零信任前提：**被授权的 AI 视为潜在恶意内部人员**——模型给建议，规则做闸门。

## 2. 方案（已实现）

### 2.1 三字段扩展（支付域最小侵入）
`Payment`/`ep_payment` 增加 `agent_id` + `authorization_id`（人工支付为 null）+ 委托人经授权记录关联 —— 人工路径零改动，agent 路径全溯源。

### 2.2 KYA 注册（`ep_agent_registration`）
5 要素：agentId/principalUserId/displayName/modelProvider+modelId/identityKey。
**幂等语义**：同 principal 重复注册返回已存在记录；不同 principal 抢注已有 agentId 抛 `AGENT_TAKEN`（fail-closed，无 `requireRegistered` 直付 `KYA_UNREGISTERED`）。

### 2.3 授权四要素（`ep_agent_authorization`）
范围 scopeJson / 限额 perTxLimit+dailyLimit / 期限 effectiveFrom~effectiveTo / 权利义务 termsVersion。
- `verifyForPayment` fail-closed：AUTH_INACTIVE / AUTH_WINDOW / LIMIT_PER_TX / LIMIT_DAILY，任何失败不放行；
- `reserveDaily` 与 verify 同一 clock instant 防跨日 re-normalize 清零竞态；
- 撤销仅委托人可发起（NOT_PRINCIPAL 拒绝），幂等。

### 2.4 三种决策模式
| 模式 | 语义 | 闸门 |
|---|---|---|
| CONFIRM_REQUIRED | 笔笔确认 | **必须携带一次性 paymentToken，否则 `TOKEN_REQUIRED` 403** |
| PRE_AUTHORIZED | 额度内免确认 | 仍走完整 verifyAndReserve（额度+窗口+绑定） |
| AUTONOMOUS | 自主（回退机制） | 同 PRE_AUTHORIZED，回退链由上层 agent 决策栈负责 |

### 2.5 支付标记化（`ep_payment_token`）
确认时委托人铸造一次性 token（TTL 600s、maxAmount 上限、绑定授权）。消费用 **DB 级条件 UPDATE 原子单次消费**：
```sql
UPDATE ep_payment_token SET status='CONSUMED', payment_id=:pid
 WHERE token_id=:tid AND status='ISSUED'   -- 返回 1 行=赢，0 行=TOKEN_REPLAYED
```
并发重放只有一个赢家；codes: TOKEN_INVALID / TOKEN_EXPIRED / TOKEN_CEILING / TOKEN_REPLAYED。

### 2.6 意图留痕（五段式第 5 段，`ep_agent_intent_log`）
存 **SHA-256 指令摘要**而非原文（PIPL 最小化）：paymentId/agentId/principalUserId/authorizationId/digest。logIntent 失败不回滚支付（留痕尽力而为，支付是主链）。

### 2.7 REST 面（AI 钱包控制台）
`/api/agents/register|authorizations(POST/GET)|authorizations/{id}/revoke|usage|confirm|intents` + `POST /api/payments/agent`。稳定业务错误码统一 403/400（`AgentApiExceptionHandler`）。

## 3. 关键取舍

| 决策 | 取舍 | 理由 |
|---|---|---|
| token 原子消费用条件 UPDATE 而非 Redis 锁 | DB 单点真相，与支付同库同事务语义 | 重放窗口=0，无需引入分布式锁 |
| 意图存摘要不存原文 | 合规（PIPL） vs 可审计 | SHA-256 摘要可验证存在性，原文留在 agent 侧会话 |
| envelope 以 ObjectProvider 注入而非硬依赖 | 人工支付路径永不因 agent 子系统故障受阻 | ENVELOPE_ABSENT 时 agent 支付拒绝、人工支付照常 |
| 预铸 paymentId（token 绑定需要） | JPA detached entity 带 preset id → merge 陷阱 | toEntity 无条件置 lockVersion=null（r28 根治，见 §4 验证与 runbook §11.4） |

## 4. 验证

- **单测 23t + 契约 2t**：AgentAuthorizationServiceTest 15t + AgentPaymentFlowTest 8t + `PaymentRepositoryNewRowTest`（纯 Hibernate+H2：好形状 persist INSERT / 坏形状 merge 抛 OptimisticLock 双向钉死），模块合计 **114t 全绿**。
- **集群 E2E（r28）**：T1~T10 全 PASS —— 注册/签发/额度内支付/超日限/AGENT_MISMATCH/AUTH_INACTIVE/TOKEN_REQUIRED/token 支付/TOKEN_REPLAYED/意图留痕。
- **CRDB 实证**：`ep_payment` 3 行 SUCCESS 全带 agent_id+authorization_id；`ep_agent_intent_log`=3；`ep_payment_token` CONSUMED（原子单次消费）。
- **修复链实录（runbook §11.4）**：r26 saveAndFlush→r27 透传 version→r28 根因（Payment 构造器 lockVersion=0 致 isNew()=false→merge）→ toEntity 置 null。

## 5. 后续（DEFERRED，非阻塞）

- 授权审批流（委托人移动端 push 确认）→ 需要 BFF 通知通道；
- scopeJson 目前 `{}` 通配 → 细化品类/商户白名单校验；
- 意图摘要的原文存证（加密托管 + 司法鉴定接口）按监管要求再启。
