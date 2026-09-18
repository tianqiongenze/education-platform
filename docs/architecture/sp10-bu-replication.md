# SP-10 事业群复制方案 —— 软件架构设计文档(SAD)

> **D-200 Naming Note:** This document's filename retains the legacy code `sp10` for reference continuity. Per D-137, sp10 → gateway. The subproject has been renamed in all source code, configs, and directory structure.

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP10-SAD-001 | v1.0 | 2026-08-04 | Draft(待评审) | ZCode(解决方案架构师) | EP-TOGAF-001 全文 / SP01-SAD-001 / SP05-SRS / 决策 D-001/D-032/D-012 |

> 本文是 SP-10 事业群复制方案(BU Replication)的**架构设计基线**，定义如何将四大中台(技术/数据/业务/AI)共享能力快速复制到新事业群(BU)。面向架构师/平台 SRE/BU 团队，提供能力清单、DDD 边界上下文、EDA 集成、数据隔离、路由隔离、六类文档模板、CI/CD 模板、监控运营、上线检查清单与扩展规划。所有新 BU 接入必须以本文为蓝本，铁律(D-032 技术栈 / DDD 限界上下文 / EDA-Pulsar / Cell-based)不可违背。

---

## 目录

1. [事业群复制总览](#1-事业群复制总览)
2. [中台能力清单](#2-中台能力清单)
3. [BU 快速启动流程](#3-bu-快速启动流程)
4. [领域驱动设计(DDD)边界上下文](#4-领域驱动设计ddd边界上下文)
5. [事件驱动集成](#5-事件驱动集成)
6. [数据隔离与共享](#6-数据隔离与共享)
7. [路由与流量隔离](#7-路由与流量隔离)
8. [六类文档模板](#8-六类文档模板)
9. [CI/CD 流水线模板](#9-cicd-流水线模板)
10. [监控与运营](#10-监控与运营)
11. [BU 复制检查清单](#11-bu-复制检查清单)
12. [扩展规划](#12-扩展规划)

---

## 1. 事业群复制总览

### 1.1 复制目标与原则

事业群复制(BU Replication)的目标是：在**不重造轮子**的前提下，将平台四大中台的共享能力快速装配到一个新事业群，使其在 4 周内完成从注册到上线的端到端打通。

复制遵循五条不可违背原则(铁律延伸)：

| 原则 | 含义 | 对应铁律 |
|------|------|----------|
| 中台优先(BU-Inherits-Platform) | BU 只写"BU 特有领域"，通用能力一律调中台 | D-001 四大中台 |
| 契约先行(Contract-First) | BU 接入前先定 API 契约 + 事件 schema，再写代码 | EDA-Pulsar |
| 边界清晰(Bounded-Context) | BU 自有领域与中台领域通过 ACL 隔离，禁止跨边界直接 ORM 引用 | DDD |
| 单元化继承(Cell-by-Default) | BU 服务默认接入 CellRouter，按 user_id 分片，不自己造路由 | Cell-based |
| 技术栈统一(Stack-Lock) | BU 服务必须用 D-032 规定栈，禁止引入新语言/框架 | D-032 |

### 1.2 BU 生命周期

```
        ┌──────────────────────────────────────────────────────────┐
        │                  BU 生命周期(BU Lifecycle)                │
        └──────────────────────────────────────────────────────────┘

  [申请]   [评审]    [装配]      [联调]     [灰度]     [全量]    [运营/演进]
   │       │         │           │          │          │          │
   ▼       ▼         ▼           ▼          ▼          ▼          ▼
 BU立项  架构评审  中台能力挂载  端到端联调  5%→25%→50%  100%流量  季度演进
 BU-ID  DDD边界   IaC provision EDA契约    监控验证    上线检查   能力反哺
        复用矩阵   CI/CD流水线   测试报告   SLO达标     Go-Live   新能力沉淀
```

| 阶段 | 产出物 | 责任方 | 时长(SLA) |
|------|--------|--------|-----------|
| 申请 | BU 立项书 + BU-ID 分配 | BU 负责人 + 平台 PMO | D+1 |
| 评审 | DDD 边界图 + 复用矩阵 + ADR | 架构委员会 | D+3 |
| 装配 | IaC 模板实例化 + 中台挂载 + CI/CD | 平台 SRE + BU Dev | D+7 |
| 联调 | 契约测试报告 + E2E 用例 | BU QA + 中台 QA | D+14 |
| 灰度 | 灰度路由配置 + 监控大盘 | 平台 SRE | D+18 |
| 全量 | Go-Live 检查清单签字 | 架构委员会 | D+21 |
| 演进 | 季度能力反哺提案 | BU + 中台 | 每季 |

### 1.3 复用 vs 自建矩阵(Reuse vs Build Matrix)

新 BU 的每项能力按以下矩阵决策：**复用中台 / 扩展中台 / BU 自建**。

```
                高复用度(通用)
                    ▲
        复用中台    │   扩展中台
        (Reuse)    │   (Extend)
                    │
  通用 ◄────────────┼────────────► 专用
                    │
        BU 自建     │   BU 自建
        (Build)     │   (Build)
                    ▼
                低复用度(定制)
```

| 能力域 | IT后端(MVP) | K12 | 考公考研 | 企业培训 | 国际教育 |
|--------|------------|-----|---------|---------|---------|
| 注册登录/会话(UCM) | 复用 | 复用 | 复用 | 复用(扩展 SSO) | 复用(扩展 GDPR) |
| 选课购课(OPM) | 复用 | 扩展(家长代付) | 复用 | 扩展(企业账期) | 复用(多币种) |
| 学习记录(LMS) | 复用 | 复用 | 复用 | 复用 | 复用 |
| 课程内容(CMP) | 复用 | 扩展(教材合规) | 复用 | 扩展(企业定制) | 扩展(本地化) |
| 直播平台 | 复用 | 复用 | 复用 | 复用 | 复用 |
| 在线编程沙箱 | 自建(IT专属) | 自建(少儿Scratch) | 不需要 | 扩展(企业测评) | 自建 |
| AI 答疑 | 复用 | 扩展(家长视角) | 扩展(考纲对齐) | 扩展(企业知识库) | 复用 |
| 推荐引擎 | 复用 | 扩展(家长推荐) | 复用 | 扩展(岗位推荐) | 复用 |
| 数据看板(BI) | 复用 | 扩展(学情+家长) | 复用 | 扩展(企业 ROI) | 复用 |

> **决策规则**：能力域在 3 个以上 BU 通用 → 中台沉淀；仅 1 个 BU 用 → BU 自建；2 个 BU 用但需扩展 → 扩展中台(策略模式/插件化)。

---

## 2. 中台能力清单

四大中台对外暴露的可复用能力总览。每个能力标注：能力 ID、提供方、消费方契约、SLA、是否支持扩展点。

### 2.1 技术中台(SP-01)

| 能力 ID | 能力名 | 描述 | 契约/接口 | SLA | 扩展点 |
|--------|--------|------|----------|-----|--------|
| T-GW-01 | API 网关 | JWT RS256 鉴权 + 路由 + 限流 + 灰度 | OpenAPI 3.0 / `/gw/*` | 99.95% | 路由插件 |
| T-BFF-02 | BFF 聚合层 | 面向前端的多端聚合，三端共 API | GraphQL + REST | 99.9% | 聚合 resolver |
| T-WS-03 | WebSocket 网关 | 直播/互动长连接，Go 实现 | WS/WSS | 99.9% | 房间回调 |
| T-DIS-04 | Disruptor Ingest | 高吞吐学习事件环形队列 | Java SDK | 99.95% | EventHandler |
| T-CACHE-05 | 多级缓存 | Caffeine L1 + Redis L2 | Cache SPI | 99.95% | CacheLoader |
| T-RW-06 | 读写分离 | 主写 TiDB + 读 MySQL 只读 + ES | R/W SDK | 99.9% | 路由策略 |
| T-CELL-07 | CellRouter | 单元化路由(user_id 哈希) | Nacos 路由表 | 99.99% | 分片策略 |
| T-OBS-08 | 可观测栈 | Metrics(Prom)+Trace(Tempo)+Log(Loki) | OTLP | 99.9% | 指标自定义 |
| T-EDA-09 | 事件总线 | Pulsar topic 管理 + Schema Registry | Pulsar Producer/Consumer | 99.95% | topic 命名空间 |

### 2.2 数据中台(SP-02)

| 能力 ID | 能力名 | 描述 | 契约/接口 | SLA | 扩展点 |
|--------|--------|------|----------|-----|--------|
| D-CDC-01 | CDC Pipeline | TiDB → Kafka → Flink → Iceberg 实时入湖 | Debezium connector | 延迟<5min | 表白名单 |
| D-DW-02 | 数据仓库 | Iceberg + Trino 湖仓，ODS/DWD/DWS/ADS | SQL 查询接口 | 99.9% | 业务主题模型 |
| D-BI-03 | BI 看板 | Superset 嵌入式看板，多租户 | iframe + 行级权限 | 99.9% | 看板模板 |
| D-GOV-04 | 数据治理 | 元数据 + 血缘 + 质量 + 分级分类 | DataHub API | 99.9% | 分类规则 |
| D-REAL-05 | 实时计算 | Flink SQL 流式聚合 | SQL Submit | 99.95% | 作业模板 |

### 2.3 业务中台(SP-03)

| 能力 ID | 能力名 | 领域 | 描述 | 契约 | 扩展点 |
|--------|--------|------|------|------|--------|
| B-UCM-01 | 用户中心 | User | 注册/登录/会话/画像/权限 | REST + 事件 | 画像属性扩展 |
| B-OPM-02 | 订单中心 | Order | 下单/支付/退款/对账 | REST + 事件 | 支付渠道插件 |
| B-LMS-03 | 学习管理 | Learning | 进度/计划/证书/学情 | REST + 事件 | 学习事件订阅 |
| B-CMP-04 | 课程中心 | Course | 课程/章节/资源/上架审核 | REST + 事件 | 内容类型扩展 |
| B-NOT-05 | 通知中心 | Notification | 站内信/短信/推送/邮件 | REST + 事件 | 渠道适配器 |

### 2.4 AI 中台(SP-04)

| 能力 ID | 能力名 | 描述 | 契约 | SLA | 扩展点 |
|--------|--------|------|------|-----|--------|
| A-REC-01 | 推荐引擎 | 课程/内容/学习路径推荐 | REST + 异步事件 | P95<3s | 召回策略 |
| A-QA-02 | 智能答疑 | LLM 答疑 bot + 多轮对话 | REST(流式) | P95<3s | 知识库挂载 |
| A-MOD-03 | 内容审核 | 文本/图片/视频合规审核 | REST + 异步回调 | P95<2s | 审核规则 |
| A-RUB-04 | 评分辅助 | LLM rubric 评分 + human-in-loop | REST + 事件 | P95<5s | 量规模板 |
| A-AGENT-05 | 学习 Agent | 规划/辅导/数字人(M2) | REST + WS | P95<3s | 工具集 |

### 2.5 能力消费契约示例

新 BU 在装配阶段，通过统一中台门户(BU Portal)订阅能力，生成消费契约：

```yaml
# bu-portal/contracts/it-backend-mvp.yaml
bu_id: BU-IT-BACKEND
bu_name: IT后端MVP
phase: MVP
subscriptions:
  - capability: T-GW-01
    quota: { rps: 5000, burst: 10000 }
  - capability: T-CELL-07
    config: { shard_key: user_id, cell_count: 4 }
  - capability: B-UCM-01
    extensions: [student_profile_attrs]
  - capability: A-QA-02
    config: { kb_namespace: it-backend }
events:
  subscribe: [user.registered, order.paid, course.published]
  publish:   [assignment.submitted, live.room.created]
```

---

## 3. BU 快速启动流程

### 3.1 七步启动法

```
┌─────────────────────────────────────────────────────────────────┐
│                    BU 快速启动七步法(7-Step)                      │
└─────────────────────────────────────────────────────────────────┘

 Step1            Step2           Step3            Step4
 BU 注册          中台订阅        IaC 装配         配置注入
 ─────────        ─────────       ─────────        ─────────
 BU-ID 分配       选能力+配额      Terraform 实例化  Nacos 配置
 命名空间          生成消费契约    K8s namespace    密钥/路由表

 Step5            Step6           Step7
 CI/CD 接入       联调验证        灰度上线
 ─────────        ─────────       ─────────
 流水线模板        契约+E2E        5%→25%→100%
 镜像仓库          测试报告         Go-Live 签字
```

### 3.2 Step1: BU 注册

平台 PMO 在 BU Portal 登记新 BU，分配全局唯一 BU-ID 与命名空间。

```yaml
# bu-portal/registry/k12.yaml
bu_id: BU-K12
bu_name: K12中小学
bu_code: k12
owner: k12-team@edu.com
phase: MVP
go_live_target: 2026-11-30
domains:
  - name: student-learning
    bounded_contexts: [k12-curriculum, parent-portal, compliance-audit]
  - name: parent-payment
    bounded_contexts: [family-account, guardian-consent]
```

### 3.3 Step2: 中台订阅

BU 团队在 BU Portal 勾选订阅的中台能力，系统自动生成消费契约(见 §2.5)与配额。

### 3.4 Step3: IaC 装配

提供 Terraform 模块 `bu-bootstrap`，一键拉起 BU 所需基础设施：

```hcl
# iac/modules/bu-bootstrap/main.tf
module "bu_namespace" {
  source      = "../k8s-namespace"
  bu_id       = var.bu_id
  bu_code     = var.bu_code
  cell_count  = var.cell_count
}

module "bu_db" {
  source       = "../tidb-database"
  bu_code      = var.bu_code
  db_name      = "${var.bu_code}_db"
  tier         = var.db_tier          # mvp: small, prod: large
  backup_retention_days = 30
}

module "bu_pulsar_topics" {
  source   = "../pulsar-namespace"
  bu_code  = var.bu_code
  topics   = var.event_topics
}

module "bu_nacos_config" {
  source    = "../nacos-namespace"
  bu_code   = var.bu_code
  configs   = var.nacos_configs
}

module "bu_monitoring" {
  source   = "../monitoring-stack"
  bu_code  = var.bu_code
  grafana_folder = var.bu_code
  alert_contact  = var.oncall_email
}
```

```hcl
# iac/envs/k12.tfvars
bu_id      = "BU-K12"
bu_code    = "k12"
cell_count = 4
db_tier    = "small"
event_topics = [
  "k12.assignment.submitted",
  "k12.parent.consented",
  "k12.curriculum.published"
]
oncall_email = "k12-oncall@edu.com"
```

### 3.5 Step4: 配置注入

Nacos 自动注入 BU 配置到服务，密钥走 Vault：

```yaml
# nacos/k12/gateway-route.yaml
route:
  bu_id: BU-K12
  path_prefix: /k12
  upstream:
    type: cell-router
    shard_key: user_id
    cells: [k12-cell-0, k12-cell-1, k12-cell-2, k12-cell-3]
  rate_limit: { rps: 5000 }
  auth: { jwt_issuer: ucm, roles: [student, parent, teacher] }
```

### 3.6 Step5: CI/CD 接入

见 §9 详细模板。BU 服务接入共享构建工具链(Maven + Docker + ArgoCD)。

### 3.7 Step6: 联调验证

BU QA 执行契约测试 + E2E 测试，中台 QA 验证事件 schema 与路由：

```bash
# scripts/bu-verify.sh
#!/usr/bin/env bash
set -euo pipefail
BU_CODE=${1:?usage: bu-verify.sh <bu_code>}

echo "[1/4] 契约测试..."
contract-cli test --bu "$BU_CODE" --contracts ./contracts/

echo "[2/4] E2E 冒烟..."
newman run ./postman/${BU_CODE}-smoke.postman_collection.json

echo "[3/4] 事件 schema 校验..."
pulsarctl schema verify --namespace "$BU_CODE" --all

echo "[4/4] 路由可达性..."
curl -fsS "https://api.edu.com/${BU_CODE}/health" | jq .status

echo "✅ BU $BU_CODE 联调通过"
```

### 3.8 Step7: 灰度上线

通过 CellRouter 灰度路由，按 user_id 段逐步放量 5% → 25% → 50% → 100%：

```json
// nacos/cell-router/k12-canary.json
{
  "bu_code": "k12",
  "canary": {
    "strategy": "user_id_range",
    "stages": [
      { "percent": 5,  "duration_min": 60 },
      { "percent": 25, "duration_min": 120 },
      { "percent": 50, "duration_min": 240 },
      { "percent": 100 }
    ],
    "rollback_on": { "error_rate_gt": 0.01, "p99_gt_ms": 800 }
  }
}
```

---

## 4. 领域驱动设计(DDD)边界上下文

### 4.1 边界上下文划分原则

每个 BU 接入时，必须先绘制**限界上下文图(Bounded Context Map)**，明确：

- BU 自有上下文(BU-Owned)：BU 独占领域，自己建模、自己持久化
- 中台共享上下文(Platform-Shared)：复用中台，BU 只消费不持有
- 防腐层(ACL)：BU 与中台之间翻译，避免领域模型污染

划分依据：**语言边界(统一语言)** + **变更频率** + **团队归属**。

### 4.2 BU × 中台 上下文映射图(以 K12 为例)

```
┌───────────────────────────── K12 事业群(BU-K12) ─────────────────────────────┐
│                                                                              │
│   ┌──────────────┐   ┌──────────────┐   ┌────────────────┐                  │
│   │ K12 课程合规 │   │ 家长门户     │   │ 监护人授权     │                  │
│   │ (Curriculum) │   │ (Parent)     │   │ (Consent)      │                  │
│   │  BU-Owned    │   │  BU-Owned    │   │  BU-Owned      │                  │
│   └──────┬───────┘   └──────┬───────┘   └────────┬───────┘                  │
│          │ ACL                │ ACL                │ ACL                       │
└──────────┼────────────────────┼────────────────────┼──────────────────────────┘
           │ U/C (上游/客户)     │ U/C                │ U/C
           ▼                    ▼                    ▼
   ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
   │ 业务中台-CMP   │   │ 业务中台-UCM   │   │ 业务中台-OPM   │
   │ 课程中心(共享) │   │ 用户中心(共享) │   │ 订单中心(共享) │
   └────────────────┘   └────────────────┘   └────────────────┘
           │                    │                    │
           │ 事件                │ 事件                │ 事件
           ▼                    ▼                    ▼
        ┌─────────────────────────────────────────────┐
        │          Pulsar 事件总线(T-EDA-09)          │
        └─────────────────────────────────────────────┘
```

### 4.3 上下文关系模式

BU 与中台之间采用以下 DDD 上下文映射模式：

| 模式 | 符号 | 适用场景 | 示例 |
|------|------|---------|------|
| 上游/下游(Upstream/Downstream) | U/D | BU 被动消费中台能力 | BU → UCM(用户) |
| 防腐层(Anti-Corruption Layer) | ACL | 中台模型与 BU 模型差异大，需翻译 | K12 课程 ↔ CMP 课程 |
| 共享内核(Shared Kernel) | SK | 极少用，仅限值对象共享 | user_id 全局唯一 |
| 客户/供应商(Customer/Supplier) | C/S | BU 反向驱动中台演进 | BU 提需求 → 中台扩展 |
| 遵奉者(Conformist) | CF | BU 完全接受中台模型 | BU 直接用 OPM 订单模型 |
| 各行其道(Separate Ways) | X | BU 完全独立，不集成 | IT后端 沙箱(无对应中台) |

### 4.4 防腐层(ACL)实现规范

BU 服务调用中台时，必须经过 ACL 翻译层，禁止中台 DTO 直接渗入 BU 领域：

```java
// bu-k12/acl/curriculum-acl/CurriculumACL.java
@Component
@RequiredArgsConstructor
public class CurriculumACL {
    private final CmpCourseClient cmpClient;  // 中台 CMP Feign 客户端

    /**
     * 将中台 CMP 课程翻译为 K12 合规课程领域模型。
     * 隔离点：中台 CourseDO 不外泄到 K12 领域层。
     */
    public K12Course toK12Course(Long courseId) {
        CmpCourseDTO dto = cmpClient.getCourse(courseId);
        return K12Course.builder()
            .courseId(dto.getId())
            .title(dto.getTitle())
            .gradeLevel(mapGrade(dto.getTags()))     // K12 特有：年级映射
            .complianceStatus(ComplianceStatus.PENDING) // K12 特有：合规状态
            .build();
    }

    private GradeLevel mapGrade(List<String> tags) {
        // 翻译中台标签 → K12 年级枚举
        ...
    }
}
```

### 4.5 BU 自有上下文清单模板

新 BU 必须在 SAD 文档中声明自有上下文：

| 上下文名 | 归属 BU | 聚合根 | 主要实体 | 持久化 | 对外事件 |
|---------|--------|--------|---------|--------|---------|
| k12-curriculum | BU-K12 | K12Course | Chapter, Resource | TiDB | k12.curriculum.published |
| parent-portal | BU-K12 | Family | Parent, Student | TiDB | k12.parent.consented |
| compliance-audit | BU-K12 | AuditCase | Rule, Result | TiDB+ES | k12.audit.completed |

---

## 5. 事件驱动集成

### 5.1 Pulsar Topic 命名规范

铁律：所有跨服务通信走 Pulsar 事件(EDA)，禁止跨 cell 直接 RPC。Topic 命名遵循：

```
persistent://<tenant>/<namespace>/<topic>
                │         │          │
                │         │          └── <bu_code>.<domain>.<action>[.v<ver>]
                │         └────────────── <bu_code> 或 platform(中台)
                └──────────────────────── edu(平台级 tenant，固定)
```

| 来源 | namespace | 示例 topic |
|------|-----------|-----------|
| 业务中台 | platform | `platform.user.registered.v1` |
| 业务中台 | platform | `platform.order.paid.v1` |
| IT后端 BU | it-backend | `it-backend.assignment.submitted.v1` |
| K12 BU | k12 | `k12.parent.consented.v1` |
| 考公 BU | civil | `civil.exam.scored.v1` |

### 5.2 事件 Schema Registry

所有事件必须注册 Avro schema，启用 schema 兼容检查(BACKWARD_TRANSITIVE)：

```json
// schema-registry/it-backend/assignment.submitted.v1.avsc
{
  "type": "record",
  "name": "AssignmentSubmitted",
  "namespace": "edu.platform.itbackend",
  "fields": [
    { "name": "eventId", "type": "string", "doc": "UUID" },
    { "name": "occurredAt", "type": "long", "doc": "epoch millis" },
    { "name": "buId", "type": "string" },
    { "name": "studentId", "type": "long" },
    { "name": "assignmentId", "type": "long" },
    { "name": "submissionRef", "type": "string", "doc": "OSS 路径" },
    { "name": "cellId", "type": "int", "doc": "单元号，路由用" },
    { "name": "extensions", "type": "map<string,string>", "default": {} }
  ]
}
```

### 5.3 事件版本演进规则

| 演进类型 | 兼容策略 | 示例 |
|---------|---------|------|
| 新增可选字段 | BACKWARD，老消费者可读 | + `extensions` |
| 删除字段 | FORWARD，需双发过渡 | 弃用 `oldTag` |
| 字段类型变更 | 不兼容，必须新版本 topic | `string→long` 升级 |
| 语义变更 | 不兼容，新 topic + 双写 | 业务重构 |

版本号写在 topic 后缀：`.v1` / `.v2`。新版本上线后老版本保留 90 天再下线。

### 5.4 事件回放(Replay)

BU 装配阶段需要回灌历史事件(如新订阅 `user.registered`)。通过 Pulsar 的 Reader API 指定时间戳回放：

```java
// bu-bootstrap/replay/EventReplayer.java
Reader<byte[]> reader = pulsarClient.newReader()
    .topic("persistent://edu/platform/platform.user.registered.v1")
    .startMessageId(MessageId.earliest)
    .readerName("bu-k12-bootstrap-replay")
    .create();

while (reader.hasMessageAvailable()) {
    Message<byte[]> msg = reader.readNext();
    UserRegistered event = avroDecode(msg.getValue());
    // 投递到 K12 ACL 翻译并落到本地投影
    k12ProjectionService.project(event);
    // 回放限速，避免压垮中台
    RateLimiter.acquire(1000);  // 1000 ev/s
}
```

### 5.5 事件契约矩阵(BU × 中台)

| 事件 | 产生方 | 消费方(BU) | 用途 |
|------|--------|-----------|------|
| user.registered.v1 | UCM 中台 | 所有 BU | 同步用户投影 |
| order.paid.v1 | OPM 中台 | 所有 BU | 解锁学习内容 |
| course.published.v1 | CMP 中台 | 所有 BU | 更新课程缓存 |
| assignment.submitted.v1 | IT后端 BU | AI中台(评分) | 触发 LLM 评分 |
| live.room.created.v1 | IT后端 BU | 技术中台(WS) | 预约 WS 房间 |
| k12.parent.consented.v1 | K12 BU | UCM 中台 | 更新监护人关系 |

---

## 6. 数据隔离与共享

### 6.1 数据库隔离原则

铁律：**Database per BU service**。每个 BU 服务拥有独立逻辑库，禁止跨 BU 跨服务直接 JOIN。

```
┌─────────────────── TiDB 集群(物理共享) ───────────────────┐
│                                                            │
│  业务中台库         IT后端库          K12库                │
│  platform_db        it_backend_db     k12_db               │
│  ├── ucm_*          ├── assignment_*  ├── k12_course_*     │
│  ├── opm_*          ├── grading_*     ├── family_*         │
│  ├── lms_*          ├── sandbox_*     ├── consent_*        │
│  └── cmp_*          └── live_*        └── audit_*          │
│                                                            │
│  (逻辑库隔离，物理共享 TiDB，按 bu_code 前缀)              │
└────────────────────────────────────────────────────────────┘
```

| 数据类别 | 隔离方式 | 示例 |
|---------|---------|------|
| BU 业务数据 | 逻辑库 per BU | `k12_db.k12_course_*` |
| 共享参考数据 | 中台库 + 只读副本 | `platform_db.dict_*`(年级/学科字典) |
| 用户身份 | 中台 UCM 库，BU 投影只读 | `platform_db.ucm_user` → BU 本地 `*_user_view` |
| 事件流 | Pulsar namespace per BU | `k12.*` topic 独占 |
| 缓存 | Redis key 前缀 per BU | `k12:session:*` / `it:cache:*` |

### 6.2 数据共享模式

BU 需要中台数据时，采用以下三种模式之一，禁止直接读中台库：

| 模式 | 适用 | 实现 | 一致性 |
|------|------|------|--------|
| API 调用(同步) | 实时强一致需求 | 中台 REST API + ACL | 强 |
| 事件投影(CQRS) | 读多写少，可容忍最终一致 | 订阅事件，落到本地投影表 | 最终(秒级) |
| 共享只读副本 | 大量参考数据查询 | 中台开放只读 MySQL/ES 副本 | 最终(分钟级) |

### 6.3 数据治理与分级

每个 BU 的数据必须按数据中台 D-GOV-04 治理规范分级：

| 等级 | 含义 | 示例 | 加密 | 脱敏 | 出境 |
|------|------|------|------|------|------|
| L1 公开 | 公开数据 | 课程标题 | 否 | 否 | 允许 |
| L2 内部 | 内部业务 | 学习进度 | 传输加密 | 日志脱敏 | 评估后 |
| L3 敏感 | 个人识别 | 手机号/邮箱 | 全程加密 | 展示脱敏 | 禁止 |
| L4 高敏 | 未成年人/支付 | K12学生身份、支付凭证 | 全程加密+字段级 | 严格脱敏 | 禁止 |

### 6.4 GDPR / 数据主权(按 BU)

| BU 区域 | 合规框架 | 关键约束 | 实现 |
|---------|---------|---------|------|
| 国内所有 BU | PIPL | 个人信息不出境 | 数据物理境内 |
| 国际 BU(欧美) | GDPR | 被遗忘权/可携带权 | 独立海外集群 + 删除 API |
| K12 BU | 未成年人保护法 | 监护人同意 + 最小化采集 | Consent 上下文 + 字段白名单 |
| 企业培训 BU | 合同数据保密 | 企业数据隔离 | 租户级加密 + 独立密钥 |

### 6.5 被遗忘权实现(国际 BU)

```java
// bu-international/privacy/GdprErasureService.java
public class GdprErasureService {
    /**
     * GDPR 被遗忘权：级联删除用户在 BU + 中台的全部个人数据。
     * 流程：BU 本地删除 → 发 erasure.requested 事件 → 中台订阅删除 → 审计留痕。
     */
    @Transactional
    public void requestErasure(Long userId, String reason) {
        // 1. BU 本地软删除
        buUserRepo.softDelete(userId);
        eventProjectionRepo.anonymize(userId);
        // 2. 发事件让中台删除
        eventBus.publish("platform.user.erasure-requested.v1",
            ErasureRequested.builder().userId(userId).reason(reason).build());
        // 3. 审计(不可删)
        auditLog.record("GDPR_ERASURE", userId, reason, Instant.now());
    }
}
```

---

## 7. 路由与流量隔离

### 7.1 域名与路径策略

```
                        ┌─────────────────────┐
                        │   edu.com(主域)     │
                        │   *.edu.com(泛域名) │
                        └──────────┬──────────┘
                                   │
        ┌──────────────┬───────────┼────────────┬──────────────┐
        ▼              ▼           ▼            ▼              ▼
  www.edu.com    it.edu.com   k12.edu.com  civil.edu.com  corp.edu.com
  (门户首页)    (IT后端BU)   (K12 BU)     (考公BU)       (企业培训BU)
        │              │           │            │              │
        └──────────────┴───────────┴────────────┴──────────────┘
                                   │
                                   ▼
                      api.edu.com/<bu_code>/<resource>
                      (统一 API 网关，路径前缀隔离)
```

| 类型 | 策略 | 示例 |
|------|------|------|
| 前端域名 | 子域 per BU | `k12.edu.com` |
| API 路径 | 前缀 per BU | `/k12/course/...` |
| 中台 API | 共享路径 | `/platform/user/...`(无前缀) |
| WS 长连接 | 子域 per BU | `ws.k12.edu.com` |
| 后台管理 | 统一 + RBAC | `admin.edu.com`(按 BU 角色隔离) |

### 7.2 CellRouter 按 BU 路由

铁律：Cell-based 部署。每个 BU 独立 cell 集合，按 user_id 哈希分片，cell 故障互不影响。

```json
// nacos/cell-router/routing-table.json
{
  "bu_routes": {
    "BU-IT-BACKEND": {
      "shard_key": "user_id",
      "shard_algo": "modulo",
      "cells": ["it-cell-0","it-cell-1","it-cell-2","it-cell-3"],
      "failover": "neighbor"
    },
    "BU-K12": {
      "shard_key": "user_id",
      "shard_algo": "modulo",
      "cells": ["k12-cell-0","k12-cell-1","k12-cell-2","k12-cell-3"],
      "failover": "neighbor"
    }
  },
  "platform_routes": {
    "UCM": { "cells": ["ucm-cell-0","ucm-cell-1"] },
    "OPM": { "cells": ["opm-cell-0","opm-cell-1"] }
  }
}
```

### 7.3 流量隔离与限流

| 维度 | 策略 |
|------|------|
| BU 间隔离 | 网关按 path 前缀分流，独立限流配额 |
| BU 内 cell 隔离 | 故障 cell 流量自动切邻居 cell(failover < 30s) |
| 中台保护 | 中台 API 按 BU 配额限流，防止单 BU 打垮中台 |
| 突发流量 | Sentinel 热点参数限流 + 排队 |
| 灰度 | 按 user_id 段灰度，见 §3.8 |

```yaml
# gateway/limits/k12.yaml
limits:
  - path: /k12/*
    bu: BU-K12
    rps: 5000
    burst: 10000
    cell_failover: { enabled: true, max_cells_down: 1 }
  - path: /platform/user/*
    bu: BU-K12
    rps: 2000       # K12 对中台 UCM 的消费配额
    burst: 4000
```

---

## 8. 六类文档模板

每个 BU 必须交付六类文档，对应项目群铁律。下面给出每类文档的**模板骨架 + K12 BU 示例片段**。

### 8.1 需求文档(SRS)模板

```markdown
# <BU-NAME> 软件需求规格说明书(SRS)

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| <BU>-SRS | v1.0 | YYYY-MM-DD | Draft | <作者> | EP-TOGAF-001 / SP10-SAD-001 |

## 1. 引言
1.1 目的 / 1.2 范围(In/Out Scope) / 1.3 术语 / 1.4 依据
## 2. 总体描述
2.1 系统定位 / 2.2 用户角色画像 / 2.3 约束 / 2.4 假设与依赖
## 3. 功能需求(按 BU 自有上下文组织)
3.1 <上下文A> 用例 + 用户故事 + 验收标准
3.2 <上下文B> ...
## 4. 非功能需求(对齐 SLO G-xx)
## 5. 中台复用清单(引用 SP10 §2)
## 6. 验收标准(MVP 门槛)
## 7. 追溯矩阵(需求 ←→ ADR)
```

**K12 SRS 示例片段**：

> FR-K12-001 监护人授权：未满 14 岁学生注册须由监护人完成同意流程，同意记录不可撤销并审计留痕(依据《未成年人保护法》)。
> 验收标准：注册流程在监护人未同意时阻断，并在 `k12.audit` 留有 ConsentRecord。

### 8.2 架构设计文档(SAD)模板

```markdown
# <BU-NAME> 软件架构设计文档(SAD)

## 1. TOGAF 四域对齐(业务/数据/应用/技术)
## 2. 组件架构图(含中台依赖)
## 3. DDD 限界上下文图(引用 SP10 §4)
## 4. 关键 ADR(<BU>-A01..An)
## 5. 接口契约(REST + 事件)
## 6. 部署拓扑(cell 化)
## 7. 安全设计
## 8. 可观测架构
## 9. 追溯矩阵
```

### 8.3 详细设计文档(SDD)模板

```markdown
# <BU-NAME> 软件详细设计文档(SDD)

## 1. 模块划分(按上下文 × 聚合)
## 2. 类图/时序图(关键用例)
## 3. 数据模型(表结构 + 索引 + 分片键)
## 4. 接口详细设计(OpenAPI + 事件 schema)
## 5. ACL 翻译规则(引用 SP10 §4.4)
## 6. 异常与补偿设计
## 7. 性能与容量估算
```

### 8.4 原型文档(Prototype)模板

```markdown
# <BU-NAME> 原型与交互设计

## 1. 信息架构(IA)
## 2. 三端(web/h5/小程序)原型链接(Figma)
## 3. 关键流程线框图(注册/购课/学习/直播)
## 4. 设计系统复用(引用平台 Design System)
## 5. 无障碍与多端一致性说明
```

### 8.5 测试文档(Test)模板

```markdown
# <BU-NAME> 测试方案

## 1. 测试策略(单元/集成/契约/E2E/压测/安全)
## 2. 测试用例矩阵(需求 ←→ 用例)
## 3. 契约测试(中台契约 + 事件 schema)
## 4. 性能测试目标(对齐 SLO)
## 5. 灰度/混沌测试计划
## 6. 缺陷管理流程
```

### 8.6 部署文档(Deploy)模板

```markdown
# <BU-NAME> 部署与运维文档

## 1. 部署拓扑(K8s + cell + 多区域)
## 2. IaC 清单(Terraform 模块引用 SP10 §3.4)
## 3. 配置管理(Nacos + Vault)
## 4. CI/CD 流水线(引用 SP10 §9)
## 5. 灰度与回滚 Runbook
## 6. 监控大盘与告警(引用 SP10 §10)
## 7. 应急预案(故障切换/数据恢复)
```

---

## 9. CI/CD 流水线模板

### 9.1 共享构建工具链

| 阶段 | 工具 | 版本锁定 | 说明 |
|------|------|---------|------|
| 编译 | Maven | 3.9.x | D-032 Java 栈统一 |
| 镜像 | Docker BuildKit | 24.x | 多阶段构建 |
| 扫描 | Trivy + Aikido | latest | 镜像 + SAST + 密钥 |
| 制品 | Harbor | 2.x | 按 BU 项目隔离 |
| 部署 | ArgoCD | 2.x | GitOps，按 BU App 隔离 |
| 灰度 | Argo Rollouts | latest | Canary + cell 灰度 |

### 9.2 BU 流水线模板(GitHub Actions / ArgoCD)

```yaml
# .github/workflows/bu-ci.yml (共享模板，各 BU 引用)
name: BU-CI
on:
  push: { branches: [main, release/*] }
  pull_request: { branches: [main] }

env:
  BU_CODE: ${{ vars.BU_CODE }}      # 由 BU 仓库注入
  JAVA_VERSION: '21'

jobs:
  build:
    uses: edu-platform/shared-workflows/.github/workflows/maven-build.yml@v3
    with: { bu_code: ${{ vars.BU_CODE }} }
    secrets: inherit

  scan:
    needs: build
    uses: edu-platform/shared-workflows/.github/workflows/security-scan.yml@v3
    with:
      bu_code: ${{ vars.BU_CODE }}
      image_tag: ${{ needs.build.outputs.image_tag }}
    secrets: inherit

  contract-test:
    needs: build
    uses: edu-platform/shared-workflows/.github/workflows/contract-test.yml@v3
    with:
      bu_code: ${{ vars.BU_CODE }}
      contracts_path: ./contracts

  deploy-staging:
    needs: [scan, contract-test]
    if: github.ref == 'refs/heads/main'
    uses: edu-platform/shared-workflows/.github/workflows/argocd-deploy.yml@v3
    with:
      bu_code: ${{ vars.BU_CODE }}
      env: staging
      image_tag: ${{ needs.build.outputs.image_tag }}
    secrets: inherit
```

### 9.3 ArgoCD Application(BU 隔离)

```yaml
# argocd/apps/k12.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: k12-services
  namespace: argocd
spec:
  project: bu-k12                       # ArgoCD 项目按 BU 隔离
  source:
    repoURL: https://git.edu.com/bu/k12-manifests.git
    targetRevision: main
    path: overlays/staging
  destination:
    server: https://k8s.edu.com
    namespace: k12
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=true]
```

### 9.4 灰度发布(Argo Rollouts)

```yaml
# manifests/k12/rollout.yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata: { name: k12-course-svc }
spec:
  strategy:
    canary:
      canaryService: k12-course-canary
      stableService: k12-course-stable
      trafficRouting:
        istio:
          virtualService: { name: k12-course-vs }
      steps:
        - setWeight: 5
        - pause: { duration: 10m }
        - analysis:
            templates: [{ templateName: bu-slo-check }]
        - setWeight: 25
        - pause: { duration: 20m }
        - setWeight: 50
        - pause: { duration: 30m }
        - setWeight: 100
```

### 9.5 自动回滚分析模板

```yaml
# argocd/analysis-templates/bu-slo-check.yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata: { name: bu-slo-check }
spec:
  args:
  - name: bu-code
  metrics:
  - name: error-rate
    interval: 1m
    successCondition: result[0] < 0.01
    failureLimit: 2
    provider:
      prometheus:
        address: http://prom.edu.com:9090
        query: |
          sum(rate(http_requests_total{bu="{{args.bu-code}}",code=~"5.."}[2m]))
          / sum(rate(http_requests_total{bu="{{args.bu-code}}"}[2m]))
```

---

## 10. 监控与运营

### 10.1 可观测三层架构(继承技术中台 T-OBS-08)

| 层 | 工具 | BU 隔离方式 |
|----|------|------------|
| Metrics | Prometheus + Grafana | Grafana Folder per BU，标签 `bu=<code>` |
| Trace | Tempo + OTLP | trace 标签 `bu=<code>`，按 BU 过滤 |
| Log | Loki | log 标签 `bu=<code>`，多租户隔离 |

### 10.2 每 BU 监控大盘

每个 BU 默认提供 4 个标准大盘，由 `bu-bootstrap` 模板自动生成：

1. **业务大盘**：注册/活跃/订单/学习时长/完成率
2. **SLO 大盘**：核心 API P95/P99/可用性/错误率(对齐 G-03/G-04/G-07)
3. **资源大盘**：CPU/内存/DB 连接/缓存命中率/Pulsar 积压
4. **事件大盘**：事件吞吐/延迟/schema 校验失败/回放进度

```
┌─────────────── Grafana: BU-K12 大盘目录 ───────────────────┐
│  k12-business    │  k12-slo    │  k12-resource │  k12-events│
│  (业务指标)      │  (SLO 达成) │  (资源水位)   │  (EDA 健康)│
└──────────────────┴─────────────┴───────────────┴───────────┘
```

### 10.3 告警规则模板

```yaml
# monitoring/rules/k12-alerts.yaml
groups:
- name: k12-slo
  rules:
  - alert: K12HighErrorRate
    expr: |
      sum(rate(http_requests_total{bu="k12",code=~"5.."}[5m]))
      / sum(rate(http_requests_total{bu="k12"}[5m])) > 0.01
    for: 5m
    labels: { severity: page, bu: k12 }
    annotations:
      summary: "K12 BU 错误率 >1% 持续 5 分钟"
  - alert: K12P99High
    expr: histogram_quantile(0.99, rate(http_request_duration_bucket{bu="k12"}[5m])) > 0.5
    for: 10m
    labels: { severity: ticket, bu: k12 }
    annotations:
      summary: "K12 P99 >500ms 持续 10 分钟"
  - alert: K12PulsarBacklog
    expr: pulsar_backlog_size{namespace="k12"} > 100000
    for: 15m
    labels: { severity: page, bu: k12 }
    annotations:
      summary: "K12 Pulsar 积压 >10w 持续 15 分钟"
```

### 10.4 值班与 SLA

| BU | 值班轮值 | 响应 SLA(P1) | 修复 SLA(P1) | 升级路径 |
|----|---------|-------------|-------------|---------|
| IT后端 | IT BU 团队 | 5min | 1h | BU Lead → 平台 SRE |
| K12 | K12 团队 | 5min | 1h | BU Lead → 平台 SRE |
| 考公 | 考公团队 | 10min | 2h | BU Lead → 平台 SRE |
| 中台(共享) | 平台 SRE 7×24 | 5min | 30min | SRE Lead → 架构委员会 |

### 10.5 运营 Runbook 要点

- **cell 故障切换**：CellRouter 自动 failover <30s(G-08)，值班确认数据一致性
- **事件积压**：先扩 Pulsar consumer，再查消费者异常
- **中台故障**：BU 启用降级(本地缓存/默认值)，中台恢复后事件回放补数据
- **限流触发**：网关限流告警 → 评估是否临时提配额 → 排查异常流量源

---

## 11. BU 复制检查清单

Go-Live 前必须逐项签字。任一未通过则阻断上线。

### 11.1 架构合规检查

| # | 检查项 | 标准 | 责任方 | ✓ |
|---|--------|------|--------|---|
| A1 | DDD 限界上下文图已绘制并评审 | 含 BU 自有 + 中台共享 + ACL | 架构委员会 | ☐ |
| A2 | 复用 vs 自建矩阵已签字 | 见 SP10 §1.3 | 架构委员会 | ☐ |
| A3 | 技术栈符合 D-032 | 无未经批准的新语言/框架 | 架构委员会 | ☐ |
| A4 | Cell-based 部署已验证 | user_id 分片 + failover | 平台 SRE | ☐ |
| A5 | EDA 集成无直连 RPC | 跨服务全走 Pulsar | 架构委员会 | ☐ |

### 11.2 安全审查

| # | 检查项 | 标准 | 责任方 | ✓ |
|---|--------|------|--------|---|
| S1 | JWT RS256 + JWKS 接入 | 使用 UCM 统一签发 | 安全团队 | ☐ |
| S2 | 密钥管理走 Vault | 无硬编码密钥 | 安全团队 | ☐ |
| S3 | 数据分级标注完成 | L1-L4 标注 | 数据治理 | ☐ |
| S4 | GDPR/PIPL 合规(按 BU 区域) | 见 SP10 §6.4 | 合规团队 | ☐ |
| S5 | 镜像 + SAST 扫描通过 | Trivy + Aikido 0 高危 | 安全团队 | ☐ |
| S6 | 未成年人合规(K12 专属) | 监护人同意 + 最小化采集 | 合规团队 | ☐ |

### 11.3 容量与性能审查

| # | 检查项 | 标准 | 责任方 | ✓ |
|---|--------|------|--------|---|
| C1 | 压测通过 MVP SLO | G-01=1万CCU / G-03 P95<200ms | 性能团队 | ☐ |
| C2 | DB 容量规划 | 30 天增量 + 水位告警 | 平台 DBA | ☐ |
| C3 | 缓存容量与命中率 | 命中率 >90% / 内存 <70% | 平台 SRE | ☐ |
| C4 | Pulsar 配额 | 吞吐 + 积压在阈值内 | 平台 SRE | ☐ |
| C5 | 限流配额已配置 | 网关 + 中台消费配额 | 平台 SRE | ☐ |

### 11.4 文档与流程审查

| # | 检查项 | 标准 | 责任方 | ✓ |
|---|--------|------|--------|---|
| D1 | 六类文档齐备 | SRS/SAD/SDD/原型/测试/部署 | BU Lead | ☐ |
| D2 | CI/CD 流水线就绪 | 构建→扫描→部署→灰度全通 | 平台 SRE | ☐ |
| D3 | 监控大盘 + 告警就绪 | 4 大盘 + 告警规则 | 平台 SRE | ☐ |
| D4 | Runbook + 值班排班 | 含故障切换/回滚 | BU Lead | ☐ |
| D5 | 追溯矩阵完整 | 需求↔ADR↔测试 | 架构委员会 | ☐ |

### 11.5 Go-Live 签字栏

| 角色 | 姓名 | 签字 | 日期 |
|------|------|------|------|
| BU 负责人 | | | |
| 平台架构师 | | | |
| 平台 SRE Lead | | | |
| 安全合规负责人 | | | |
| 架构委员会主席 | | | |

---

## 12. 扩展规划

### 12.1 新 BU 路线图

```
2026 Q3                Q4                  2027 Q1              Q2
  │                    │                    │                    │
  ▼                    ▼                    ▼                    ▼
 IT后端MVP           K12                  考公考研             企业培训
 (BU-IT-BACKEND)     (BU-K12)             (BU-CIVIL)           (BU-CORP)
 ───────────         ───────────          ───────────          ───────────
 端到端验证           中台复制验证          高并发考试场景       多租户企业场景
 D+21 上线           D+21 上线            D+21 上线            D+21 上线
                                          │
                                          └─→ 国际教育(BU-INTL, GDPR) 2027 Q3
```

| BU | BU-ID | 目标上线 | 关键场景 | 中台扩展点 |
|----|-------|---------|---------|-----------|
| IT后端 MVP | BU-IT-BACKEND | 2026-09 | 端到端架构打通 | 沙箱/评分/直播 |
| K12 | BU-K12 | 2026-11 | 监护人/合规/家长门户 | UCM 画像扩展、CMP 教材合规 |
| 考公考研 | BU-CIVIL | 2027-01 | 高并发考试/题库 | OPM 批量报名、AI 题库对齐 |
| 企业培训 | BU-CORP | 2027-03 | 企业多租户/账期/ROI | OPM 企业账期、BI 企业看板 |
| 国际教育 | BU-INTL | 2027-06 | GDPR/多语言/多币种 | UCM GDPR、OPM 多币种 |

### 12.2 中台能力反哺机制

BU 运营中沉淀的通用能力，按季度评审反哺中台：

```
   BU 自建能力 ──(≥3 BU 复用)──> 提案中台化 ──> 架构委员会评审
        │                                           │
        │                                           ├─ 通过：中台 v2 沉淀，BU 切换消费
        │                                           └─ 驳回：保持 BU 自建
        ▼
   季度能力反哺提案表
```

### 12.3 复制效率演进目标

| 阶段 | BU 上线周期 | 自动化率 | 关键改进 |
|------|-----------|---------|---------|
| M1(IT后端) | 8 周 | 40% | 首个 BU，模板尚不成熟 |
| M2(K12) | 4 周 | 70% | IaC + 文档模板固化 |
| M3(考公) | 3 周 | 85% | BU Portal 自助订阅 |
| M4(企业培训) | 2 周 | 90% | 全自动装配 + 一键联调 |

### 12.4 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 中台能力不足 BU 强行自建 | 重复造轮子 | 复用矩阵评审 + 反哺机制 |
| BU 模型污染中台 | 架构腐化 | ACL 强制隔离 + 架构门禁 |
| 多 BU 共享中台打垮 | 中台故障 | 按 BU 配额限流 + 熔断 |
| 事件 schema 演进冲突 | 消费中断 | Schema Registry + BACKWARD 策略 |
| 跨区域数据合规违规 | 法律风险 | 按 BU 区域独立集群 + 出境审计 |

---

## 附录 A：与顶层架构追溯矩阵

| SP-10 章节 | 上游依据 | 关键铁律 |
|-----------|---------|---------|
| §1 复制总览 | EP-TOGAF-001 §8/§9 | D-001 四大中台 |
| §2 能力清单 | SP01/02/03/04-SAD | D-032 技术栈 |
| §3 启动流程 | EP-TOGAF-001 §10 | Cell-based |
| §4 DDD 边界 | EP-TOGAF-001 §5 | DDD 限界上下文 |
| §5 事件集成 | SP01-SAD §4 | EDA-Pulsar |
| §6 数据隔离 | EP-TOGAF-001 §4 | D-008/D-012 |
| §7 路由隔离 | SP01-SAD §3 ADR-T03 | Cell-based |
| §8 文档模板 | EP-TOGAF-001 §10 | 六类文档 |
| §9 CI/CD | EP-TOGAF-001 §10 | D-032 |
| §10 监控 | SP01-SAD §7 | G-03/G-04/G-07 |
| §11 检查清单 | EP-TOGAF-001 §7 | 架构治理 |
| §12 扩展规划 | EP-TOGAF-001 §9 | 迁移规划 |

## 附录 B：ADR 索引(本卷新增)

| ADR ID | 标题 | 决策 |
|--------|------|------|
| ADR-BU01 | 中台优先原则 | BU 通用能力一律调中台，禁止自建 |
| ADR-BU02 | ACL 强制隔离 | BU 与中台间必须经防腐层翻译 |
| ADR-BU03 | Topic 命名规范 | `persistent://edu/<bu_code>/<bu_code>.<domain>.<action>.v<n>` |
| ADR-BU04 | Database per BU | 逻辑库隔离，禁止跨 BU JOIN |
| ADR-BU05 | 灰度按 user_id 段 | 5→25→50→100，自动回滚基于 SLO |
| ADR-BU06 | 能力反哺阈值 | ≥3 BU 复用 → 中台化提案 |

---

> 文档结束。本卷为活文档，随 BU 复制实践持续演进，变更通过 ADR 驱动(Phase H)。
