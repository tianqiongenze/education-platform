# D-198: TenantContext 提取至 ep-web-common 共享模块

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-198 | v1.0 | 2026-08-12 | Done | 平台架构组 | 架构委员会 |

> 关联：D-094（ep-web-common 共享 JAR 消灭 RoleChecker ×6 拷贝）、D-161（TenantContextFilter ×4 测试）、铁律 10.3（共享 SDK 强制使用）、铁律 10（设计-实现可追溯性）。

## 1. 问题陈述与约束条件

铁律 10 立法（D-196）后进行设计-实现可追溯性审计，发现 `TenantContext` / `TenantContextFilter` 在 8 个 Java 服务中重复实现，违反铁律 10.3（共享 SDK 强制使用）：

- core-business、grading-service、boardgame-service、senior-care、order-platform、payment-platform、portal-bff、knowledge 各有一份 `TenantContext` + `TenantContextFilter`。
- 8 份代码逻辑相同（ThreadLocal 存储 + `X-Tenant-Id` header 解析），仅包名不同。
- 维护负担：任一修改需同步 8 处，易遗漏导致行为不一致。
- 所有 8 个服务已依赖 `ep-web-common` 共享模块（D-094），具备提取前提。

约束：data-platform 保留自有 `TenantContext`（区域路由语义不同，需额外字段），不参与本次提取。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **提取至 ep-web-common 共享 tenant 包（已选）** | 8 服务删除重复，统一引用 ep-web-common 的 `com.ep.web.tenant` 包 |
| B | **保持现状 + 代码生成** | 用代码生成器同步 8 份拷贝，不提取共享模块 |
| C | **提取至独立 ep-tenant-context JAR** | 新建独立模块，仅含 tenant 上下文 |

## 3. 对比维度表

| 维度 | A. 提取至 ep-web-common（已选） | B. 代码生成同步 | C. 独立 ep-tenant-context JAR |
|------|-------------------------------|----------------|------------------------------|
| **DRY 合规** | ✅ 单一实现源 | ❌ 仍是 8 份拷贝 | ✅ 单一实现源 |
| **依赖复杂度** | 8 服务已依赖 ep-web-common，零新增依赖 | 无新增依赖 | 8 服务新增 ep-tenant-context 依赖 |
| **铁律 10.3 合规** | ✅ 共享 SDK 强制使用 | ❌ 未使用共享 SDK | ✅ 但新增模块过重 |
| **维护成本** | 修改一处，8 服务自动生效 | 修改生成模板 + 重新生成 8 服务 | 修改一处，8 服务自动生效 |
| **模块边界** | tenant 上下文属 web 层公共能力，归 ep-web-common 合理 | N/A | tenant 上下文独立模块，过度拆分 |

## 4. 决策

**选 A：提取至 ep-web-common 共享 `com.ep.web.tenant` 包，8 服务删除重复，data-platform 保留自有。**

理由：

1. **DRY 原则**：8 份重复代码是维护负担，任一修改需同步 8 处，易遗漏。
2. **铁律 10.3（共享 SDK 强制使用）**：存在 ep-web-common 共享模块时，禁止手写等价功能。
3. **依赖前提已满足**：8 服务已依赖 ep-web-common（D-094），提取零新增依赖成本。
4. **data-platform 例外**：data-platform 的 `TenantContext` 含区域路由语义（额外字段），保留自有实现，不强制统一。

## 5. 实现细节

- **ep-web-common 新增**：`com.ep.web.tenant.TenantContext`（ThreadLocal 存储）+ `TenantContextFilter`（`X-Tenant-Id` header 解析）+ `TenantContextHolder`（静态访问工具）
- **8 服务删除重复**：core-business、grading-service、boardgame-service、senior-care、order-platform、payment-platform、portal-bff、knowledge 删除各自的 `TenantContext` / `TenantContextFilter`，import 改为 `com.ep.web.tenant.*`
- **data-platform 保留**：自有 `TenantContext`（区域路由语义），不参与提取
- **TenantIsolationE2ETest**：引用 ep-web-common 的 `TenantContext` / `TenantContextFilter`（pom.xml 已声明 ep-web-common 依赖）

## 6. Iron Law 合规

| Iron Law | 合规 | 说明 |
|----------|------|------|
| IL10.3（共享 SDK 强制使用） | ✅ | 8 服务统一使用 ep-web-common tenant 包 |
| IL10（doc synced） | ✅ | 本 ADR 记录决策 |

## 7. 文档同步

- SDD：ep-web-common 共享模块章节标注 TenantContext 提取
- SAD：共享 SDK 强制使用条款引用本 ADR
- progress.md：D-198 条目已记录
