# D-221: 非交易域业务幂等键补全（棋牌房间 + 长者档案）

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-221 | v1.0 | 2026-09-06 | Done | 平台架构组 | 架构委员会 |

> 关联：D-148（senior-care）、D-149（boardgame）、D-132/D-090（Order/Payment 幂等基线）、风控深度分析 §1.1.3（非交易域缺口）、铁律 8、铁律 9、铁律 10。

## 1. 问题陈述与约束条件

风控深度分析（`docs/architecture/risk-control-and-architecture-deep-analysis.md` §1.1.3）确认：核心交易链路（订单/支付/评分/BFF）具备三层幂等防御，但**非交易域创建类操作依赖状态机幂等性，无业务幂等键**——网络重试/用户重复点击会创建重复记录：

- `GameRoomServiceImpl.createRoom()`：重复提交产生重复房间
- `ElderProfileServiceImpl.createElder()`：重复提交产生重复长者档案（涉民生数据，重复记录危害高于房间）

约束：不得破坏现有 API 兼容性（无 key 的旧调用方行为不变）；离线环境可测试（无 Redis/Pulsar 依赖）；符合模块既有 JPA + probe 模式风格。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Cmd 携带业务幂等键 + 仓储 probe + DB 唯一约束兜底（已选）** | `CreateRoomCmd`/`CreateElderCmd` 增加可选 `idempotencyKey`；创建前 `findByTenantIdAndIdempotencyKey` 探测，命中即返回原记录；`(tenant_id, idempotency_key)` 唯一约束作并发兜底 |
| B | **Redis SETNX 分布式幂等锁** | 复用 grading-service `IdempotencyService.claimOrFetch()` 模式，Redis SETNX 占位 + 24h TTL |
| C | **DB 唯一约束 + 冲突后查询** | 仅靠唯一约束，插入冲突时捕获 `DataIntegrityViolationException` 后反查原记录 |

## 3. 对比维度表

| 维度 | A. probe + 唯一约束（已选） | B. Redis SETNX | C. 纯唯一约束 |
|------|---------------------------|----------------|--------------|
| **依赖** | 零新增依赖（JPA 已有） | 新增 Redis 依赖（boardgame/senior-care 现无 Redis 直连） | 零新增 |
| **离线可测** | ✅ mock 仓储即可 | ❌ 需 Redis 或重度 mock | ✅ |
| **并发窗口** | 探测与插入间存在竞态，由唯一约束兜底 | 无竞态 | 无竞态 |
| **冲突处理复杂度** | 低（探测命中即返回） | 中（TTL/降级逻辑） | 高（解析冲突异常 + 反查，H2/TiDB 异常类型不同） |
| **API 兼容** | ✅ key 可选，旧调用方无感 | ✅ | ✅ |
| **幂等窗口** | 永久（key 随行存储） | 24h TTL 过期后失效 | 永久 |

## 4. 决策

选择 **方案 A**。理由：两个模块均为低 QPS 创建场景（房间/档案），probe 一次索引查询成本可忽略；Redis 依赖（B）对非交易域是过度设计且引入新的可用性耦合；C 的异常解析在 H2（dev-local）/TiDB（prod）间行为不一致，可移植性差。

### 实现要点

- `CreateRoomCmd`/`CreateElderCmd` 增加 `idempotencyKey`（可选字段）
- `GameRoom`/`ElderProfile` 增加 `idempotency_key VARCHAR(64)` 列 + `@UniqueConstraint(tenant_id, idempotency_key)`（仅对新建 schema 生效；存量库需手工执行 `ALTER TABLE ... ADD UNIQUE KEY`，见实体注释）
- 服务层 probe：key 归一化（trim；blank → null），命中即返回原记录并打 INFO 日志；未命中走原创建路径并持久化 key
- **遗留（诚实声明）**：探测与插入的竞态窗口由唯一约束兜底——但唯一约束冲突场景当前未捕获转换（依赖调用方重试），与 C 方案的差距记录为已知限制

## 5. 影响与验证证据（铁律 9）

| 模块 | 变更 | 验证命令 | 结果 |
|------|------|---------|------|
| boardgame-service | Cmd/实体/仓储/服务 4 处 + `GameRoomIdempotencyTest`（4 测试） | `mvn -o test` | **Tests run: 202, Failures: 0 — BUILD SUCCESS**（基线 198 + 4） |
| senior-care | Cmd/实体/仓储/服务 4 处 + `ElderProfileIdempotencyTest`（4 测试） | `mvn -o test` | **Tests run: 70, Failures: 0 — BUILD SUCCESS**（基线 66 + 4） |

测试覆盖：首建持久化 key、同 key 重放返回原记录且不再 insert、不同 key 各自创建、null/blank key 保持旧行为且不探测。
