# D-199: Root BOM + data-platform ArchUnit

| 编号 | 版本 | 日期 | 状态 | 作者 | 审阅 |
|------|------|------|------|------|------|
| D-199 | v1.0 | 2026-08-12 | Done | 平台架构组 | 架构委员会 |

> 关联：D-195（Pulsar 4.0 升级就绪，统一 `${pulsar.version}` 属性）、铁律 10（设计-实现可追溯性）、ArchUnit 架构守护。

## 1. 问题陈述与约束条件

铁律 10 立法（D-196）后进行设计-实现可追溯性审计，发现两项基础设施缺口：

1. **Pulsar 版本漂移**：各服务 pom.xml 中 Pulsar 版本分散管理，部分硬编码 `3.3.6`，部分 `3.3.9`（D-195 已统一到 `${pulsar.version}` 属性，但属性值仍分散在各服务 pom 中）。缺乏顶层 BOM 聚合，版本升级需逐一修改。
2. **data-platform 缺少 ArchUnit**：data-platform 是唯一没有 ArchUnit 架构守护测试的 Java 服务。其他 Java 服务（core-business、grading-service、boardgame-service、senior-care、order-platform、payment-platform）均已接入 ArchUnit（D-094 ~ D-196 期间逐步补齐）。

## 2. 候选方案（铁律 8）

| # | 方案 | 一句话定位 |
|---|------|-----------|
| A | **Root BOM 聚合 + data-platform ArchUnit（已选）** | 创建 root pom.xml `dependencyManagement` 聚合版本；data-platform 新增 ArchitectureTest.java |
| B | **仅 Root BOM，data-platform 延后** | 仅统一版本管理，ArchUnit 延后到下个迭代 |
| C | **仅 data-platform ArchUnit，版本管理保持现状** | 仅补 ArchUnit，版本仍分散管理 |

## 3. 对比维度表

| 维度 | A. Root BOM + ArchUnit（已选） | B. 仅 Root BOM | C. 仅 ArchUnit |
|------|------------------------------|----------------|----------------|
| **版本漂移修复** | ✅ 集中 dependencyManagement | ✅ 集中 dependencyManagement | ❌ 仍分散 |
| **架构守护补齐** | ✅ data-platform 接入 ArchUnit | ❌ data-platform 仍无守护 | ✅ data-platform 接入 ArchUnit |
| **铁律 10 合规** | ✅ 全量补齐 | ⚠️ 部分补齐 | ⚠️ 部分补齐 |
| **未来可扩展** | 新服务 import BOM 即可继承版本管理 | 同 A | 无版本管理扩展性 |

## 4. 决策

**选 A：创建 root BOM 聚合 pom.xml + data-platform 新增 ArchUnit。**

理由：

1. **Pulsar 版本漂移**：D-195 已将各服务 Pulsar 版本统一到 `${pulsar.version}` 属性，但属性值仍分散。Root BOM 集中 `dependencyManagement` 后，版本升级改一处即可。
2. **data-platform 是唯一无 ArchUnit 的 Java 服务**：其他 6 个 Java服务均已接入 ArchUnit 架构守护（包依赖、分层、命名规则），data-platform 缺失是审计盲区。
3. **未来可扩展**：新服务 import Root BOM 即可继承版本管理，降低新服务接入成本。

## 5. 实现细节

### 5.1 Root BOM

- 创建 root `pom.xml`，`packaging=pom`，`dependencyManagement` 聚合核心依赖版本：
  - Pulsar: `${pulsar.version}` = 3.3.9
  - Dubbo: `${dubbo.version}` = 3.3.6
  - Seata: `${seata.version}` = 2.0.0
  - Redisson: `${redisson.version}` = 3.51.0
  - 其他共享依赖版本
- 各服务 pom.xml 可 import Root BOM（`<scope>import</scope>`）继承版本管理

### 5.2 data-platform ArchUnit

- 新增 `data-platform/src/test/java/.../ArchitectureTest.java`
- 规则对齐其他 Java 服务：
  - 包依赖规则（controller 不依赖 repository，service 不依赖 controller）
  - 分层规则（每层只能依赖下层）
  - 命名规则（Controller/Service/Repository 后缀）
  - 禁止循环依赖

## 6. Iron Law 合规

| Iron Law | 合规 | 说明 |
|----------|------|------|
| IL10（doc synced） | ✅ | 本 ADR 记录决策 |
| IL10.4（设计合规性审计） | ✅ | ArchUnit 是架构守护的自动化审计手段 |

## 7. 文档同步

- progress.md：D-199 条目已记录
- integration-tests pom.xml：已声明 data-platform 依赖（D-199 注释），CrossServiceContractTest 已补 data-platform 控制器契约验证
