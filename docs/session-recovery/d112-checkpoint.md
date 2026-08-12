# D-112 会话恢复检查点 (Session Recovery Checkpoint)

> 生成时间: 2026-08-08 (D-111/D-112 完成后, progress.md 冷热分离后)
> 会话ID: sess_2f097796-388c-4e37-af6c-0bf518c2842e
> synced_to_redis: true (Redis Cluster 7 keys synced, 2026-08-08)
> 决策计数: 112 (decisions.jsonl D-060~D-112 = 48条 + progress.md 决策登记簿 D-027~D-112 = 85条)
> 测试总数: ~849 (Java 730 + Python 134/20skip + Frontend 189 + Go 22 + ep-python-common 45), 100% pass (1 skipped: D-102 springdoc 兼容)
> 审计问题: 21/21 闭环 (D-048) + 8/8 扫描发现闭环 (D-077)
> D-111状态: ✅ COMPLETE — 统一前端门户 portal-web
> D-112状态: ✅ COMPLETE — 全项目命名规范重构

> ⚠️ 旧检查点 `d048-checkpoint.md` 已过时 (51决策/301测试), 仅供历史参考。

---

## 1. 当前任务状态: D-111/D-112 全部完成 ✅

### 1.1 D-111: 统一前端门户 portal-web

**目标**: 创建公司统一门户入口, 集成官网展示 + 公开课目录 + 事业群入口 + 统一登录。

**技术栈**: Vite + React 19 + Ant Design 5 + TypeScript 5.x (port 3004)

**交付物**:
| # | 页面/组件 | 路由 | 功能 |
|---|----------|------|------|
| 1 | HomePage | `/` | 公司官网首页 (hero + 特色课程 + 事业群概览) |
| 2 | CoursesPage | `/courses` | 公开课目录 (课程卡片网格 + 分类筛选) |
| 3 | CourseDetailPage | `/courses/:id` | 课程详情 (大纲 + 讲师 + 报名入口) |
| 4 | BusinessUnitsPage | `/business-units` | 事业群入口 (5个BU卡片导航) |
| 5 | LoginPage | `/login` | 统一登录 (SSO入口, 跳转各子平台) |
| 6 | AboutPage | `/about` | 关于我们 |
| 7 | PortalLayout | — | 全局布局 (Header导航 + Footer + Outlet) |

**K8s 部署**: nginx:1.27-alpine, `www.edu.com` Ingress, ConfigMap 挂载 dist/
**测试**: 6 个测试全绿 (PortalLayout 组件测试)

**遇到的问题与解决方案**:
| 问题 | 解决方案 |
|------|---------|
| PortalLayout.test.tsx 中 `getByRole('link')` 找到多个匹配 (footer 链接与导航链接重叠) | 改用 `getAllByRole('link')` + `.some()` 匹配断言 |
| exactOptionalPropertyTypes 启用后可选属性不接受 undefined | 组件 props 中可选字段显式标注类型, 避免传 undefined |

### 1.2 D-112: 全项目命名规范重构

**目标**: 统一全项目命名规范, 消除 11 项命名不一致。

**11 项修复**:
| # | 修复项 | 范围 | 修复前 → 修复后 |
|---|--------|------|-----------------|
| 1 | Java Entity 后缀 | sp03 | OrderEntity → OrderModel |
| 2 | BFF API 前缀 | bff-java | 缺前缀 → /api/v1/ 统一前缀 |
| 3 | REST 路径风格 | bff-java | camelCase → kebab-case (如 /by-user-course) |
| 4 | DTO 后缀统一 | sp04-ai | 混用 → 统一 AgentDto 后缀 |
| 5 | Cmd 后缀 | sp05 | GradingRequest → StartGradingCmd |
| 6 | Go module path | gateway-go | 非标准路径 → github.com/education-platform/gateway-go |
| 7 | MySQL 保留字 | DDL | api_key (保留字) → api_token |
| 8 | 包路径消重 | sp04-ai | 重复包路径 → 消重 |
| 9 | 测试名单数 | 全项目 | 复数测试类名 → 单数 (如 OrdersTest → OrderTest) |
| 10 | coding-standards.md | docs | 新建 ~180 行命名规范参考文档 |
| 11 | API-INDEX.md | docs | 新增前端门户 section + portal-web 端口表 |

**回归验证**:
- Java 730 测试 0 fail (`-Djacoco.skip=true` 跳过 JaCoCo 0.8.12/JDK21 不兼容)
- Frontend 22 tasks 全绿 (lint + typecheck + build + test)
- Go `go vet` 通过

**Iron Law 8 合规**: D-111 和 D-112 在 decisions.jsonl 中均含 ≥3 alternatives。

---

## 2. 关键文件索引 (恢复时优先读取)

### 2.1 治理文件
| 文件 | 用途 |
|------|------|
| `docs/progress.md` | 总进度看板 (热区 ≤300行, §5 决策登记簿 D-027~D-112) |
| `docs/archive/progress-history.md` | 冷区归档 (§6~§14, D-039~D-112 详细执行记录) |
| `~/.zcode/sessions/education-platform/decisions.jsonl` | 48 条决策记录 (D-060~D-112, 均含 alternatives 字段) |
| `docs/coding-standards.md` | 全项目命名规范参考 (~180 行) |
| `docs/session-recovery/d112-checkpoint.md` | 本文件 (Redis 主恢复源) |
| `docs/session-recovery/sync-to-redis.py` | Redis 同步脚本 (D-112 版) |

### 2.2 D-111/D-112 代码文件
| 文件 | 内容 |
|------|------|
| `frontend/apps/portal-web/` | 统一门户 (Vite+React19+AntD5) |
| `frontend/apps/portal-web/src/layouts/PortalLayout.tsx` | 全局布局 |
| `frontend/apps/portal-web/src/pages/*.tsx` | 7 个页面组件 |
| `frontend/apps/portal-web/src/layouts/PortalLayout.test.tsx` | 布局测试 |
| `frontend/apps/portal-web/k8s/` | K8s 部署 (nginx + Ingress) |
| `docs/coding-standards.md` | 命名规范文档 |
| `docs/api/API-INDEX.md` | API 索引 (含 portal-web) |

### 2.3 架构文档
| 文件 | 用途 |
|------|------|
| `docs/architecture/adr-inf-{01..06}-*.md` | 6 项基础设施 ADR |
| `docs/architecture/sp04-sad.md` | SP-04 架构 (D-046/D-047) |
| `docs/architecture/sp07-sad.md` | SP-07 架构 |
| `docs/design/sp04-sdd.md` | SP-04 详细设计 |
| `docs/togaf/00-top-level-enterprise-architecture.md` | TOGAF 企业架构 |

---

## 3. 后续待办 (优先级排序)

| 优先级 | 待办 | 关联 | 前置条件 |
|--------|------|------|---------|
| HIGH | M3 生产环境部署验证 (4 cell K8s + Karmada) | D-058/D-079 | 真实多集群环境 |
| HIGH | 100万并发压测执行 (17 场景 k6) | D-079/D-080 | 预发/生产环境 |
| MEDIUM | 异地多活故障切换演练 (chaos engineering) | D-039~D-041 | 4 cell 真实部署 |
| MEDIUM | Data Mesh 4 原则落地 (当前全 PROPOSED) | M4 | 数据治理体系 |
| LOW | 历史决策 D-027~D-059 alternatives 质量回填 | P2-4 | 逐条核查 |
| LOW | JaCoCo 升级 (0.8.12 → 兼容 JDK21 版本) | 技术债 | 社区发布兼容版 |

---

## 4. Redis Cluster 同步状态 (2026-08-08 最新同步)

**集群拓扑**: `ekb-m2-aws-us-east-1` namespace, 6 节点 (3 主 3 从), `redis-cluster-new-{0..5}`
**集群状态**: `cluster_state:ok`, `cluster_slots_assigned:16384`, `cluster_size:3`
**Redis 版本**: 7.2.13, 无认证

**同步方式**: `kubectl exec` 在 pod 内执行 `redis-cli -c` (cluster mode, 自动重定向)
**同步时间**: 2026-08-08 (D-112 完成后重新同步, decisions 51→112, tests 301→~849)

| # | Key | TTL | STRLEN | 验证 |
|---|-----|-----|--------|------|
| 1 | `zcode:session:education-platform:d112-checkpoint` | 604800s (7d) | 8959 | ✅ 跨节点读取 OK |
| 2 | `audit:decisions:count` | -1 (永久) | 3 | ✅ 值=112 |
| 3 | `audit:d112:results` | 86400s (24h) | 547 | ✅ JSON 含 D-112 审计结果 |
| 4 | `zcode:session:education-platform:active` | 604800s (7d) | 661 | ✅ 跨节点读取 OK |
| 5 | `zcode:session:education-platform:decisions` | -1 (永久) | 78986 | ✅ 48 条决策 |
| 6 | `zcode:session:education-platform:pitfalls` | -1 (永久) | 52 | ✅ 占位标记 |
| 7 | `zcode:session:education-platform:active-json` | -1 (永久) | 118 | ✅ 占位标记 |

**跨节点验证**: 从 `redis-cluster-new-5` 读取 Key 1 STRLEN=8959, 与 node-0 一致 (集群自动重定向 ✅)。
**安全验证**: `esp:*` 应用缓存 key 未触碰, DBSIZE=3 (仅新增了本项目的 key)。

**安全保证**: 只操作 `zcode:session:*` 和 `audit:*` 前缀的 key, 绝不触碰 `esp:*` (应用缓存) 或其他前缀。

**恢复指令** (上下文溢出后):
```bash
# 1. 从 Redis Cluster 恢复检查点全文
kubectl exec -n ekb-m2-aws-us-east-1 redis-cluster-new-0 -- redis-cli -c \
  GET zcode:session:education-platform:d112-checkpoint

# 2. 恢复决策记录
kubectl exec -n ekb-m2-aws-us-east-1 redis-cluster-new-0 -- redis-cli -c \
  GET zcode:session:education-platform:decisions

# 3. 恢复活跃状态
kubectl exec -n ekb-m2-aws-us-east-1 redis-cluster-new-0 -- redis-cli -c \
  GET zcode:session:education-platform:active

# 4. 跨节点验证 (从 redis-cluster-new-3 读取, 集群自动重定向)
kubectl exec -n ekb-m2-aws-us-east-1 redis-cluster-new-3 -- redis-cli -c \
  GET zcode:session:education-platform:d112-checkpoint

# 5. 本地文件兜底 (铁律0.2, 集群不可用时)
cat docs/session-recovery/d112-checkpoint.md
cat docs/progress.md
```

---

## 5. 版本矩阵 (恢复时必须遵守)

| 组件 | 版本 | 铁律/ADR |
|------|------|---------|
| Spring Boot | 4.0.7 | 铁律7 |
| Spring Cloud | 2025.1.2 | — |
| Spring Cloud Alibaba | 2025.1.0.0 | 铁律6 |
| Dubbo | 3.3.6 | 铁律6 |
| Nacos | 3.1.1 | 铁律6 |
| Seata | 2.0.0 | D-048 |
| Java | 21 (虚拟线程) | 铁律7 |
| Python | 3.12 | — |
| LangGraph | 1.0 | ADR-INF-05 |
| Spring AI | 2.0.0 | D-047 |
| MCP Java SDK | 2.0.0 | D-047 |
| Milvus | 3.0 | D-052/D-053 |
| NebulaGraph | (替代 Neo4j) | D-052/D-053 |
| TiDB | 8.5 LTS | D-027 |
| Pulsar | 3.x | 铁律4/ADR-INF-03 |
| Redis | 7.2.13 (Cluster) | — |
| GraalVM | Native Image | 铁律7 |
| React | 19 | D-111 |
| Vite | 7.x | D-111 |
| Ant Design | 5.x | D-111 |
| Go | 1.23+ | D-109 |

**注意**: JaCoCo 0.8.12 不兼容 JDK 21+, 所有 Java 测试运行时加 `-Djacoco.skip=true`。
**注意**: tsconfig exactOptionalPropertyTypes 已启用, 可选属性不接受 undefined。
