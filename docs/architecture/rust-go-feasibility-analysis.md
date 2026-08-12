# Rust / Go 在新事业群实施中的可行性验证分析

> **编号**: D-144 | **日期**: 2026-08-10 | **Iron Law 8 合规**: ≥3 方案对比
> **结论**: Go 适合 WS 网关扩展和棋牌规则引擎；Rust 因 D-032 技术栈锁定否决；Java 仍为核心业务域首选

---

## 1. 分析背景

用户要求"验证探索 Rust 或者 Go 在新事业群实施中的可能性"。新事业群包括：
- **康养养老事业群 (BU-ELDER)**: 老人档案/健康记录/护理计划（CRUD 密集型）
- **棋牌教育+对弈事业群 (BU-BOARDGAME)**: 房间管理/对局/规则引擎/实时走子（计算密集 + 实时通信）

分析维度：性能、生态、团队学习成本、与现有中台集成、D-032 技术栈合规、Iron Law 8 合规。

---

## 2. 三方对比矩阵

### 2.1 棋牌规则引擎（计算密集型）

| 维度 | Java | Go | Rust |
|------|------|-----|------|
| **性能** | 够用（JIT 优化后接近 native） | 优秀（编译型，GC 暂停可控） | 最优（零成本抽象，无 GC） |
| **生态** | 丰富（JVM 库） | 良好（标准库强大） | 棋类库少（需自建规则引擎） |
| **团队学习成本** | 零（现有团队主语言） | 低（语法简单） | 高（所有权/借用/生命周期） |
| **与现有中台集成** | 原生（Dubbo/JPA/Spring） | 需 HTTP 桥 | 需 FFI/HTTP 桥 |
| **D-032 合规** | ✅ 核心语言 | ✅ 辅助语言（gateway-go 已用） | ❌ 未在 D-032 技术栈中 |
| **Iron Law 8 合规** | ✅ 默认选择 | ✅ 需三方对比（本文档） | ❌ 违反 D-032 |

**决策**: **Java 实现规则引擎**（XiangqiRuleEngine）。
理由：规则引擎的计算量在棋类教育场景（非职业竞技）完全在 Java 性能区间内；与 GameMatch 聚合根同进程调用避免跨语言 IPC；Rust 性能优势在业余棋类教育场景不产生业务价值。

### 2.2 WebSocket 网关扩展（实时通信）

| 维度 | Java | Go | Rust |
|------|------|-----|------|
| **WS 生态** | Spring WebSocket/Netty | coder/websocket(优秀) | tokio-tungstenite(优秀) |
| **并发模型** | 虚拟线程(JEP 444) | goroutine(原生轻量) | async(tokio, 零成本) |
| **现有底座** | 无（BFF 是 HTTP 代理） | ✅ hub.go 已有骨架 | 无（全重建） |
| **Redis Pub/Sub** | Lettuce/Jedis | go-redis(已有) | redis-rs(需引入) |
| **改造工作量** | 高（重写 gateway-go 为 Java） | 低（补 handler/registry/router） | 极高（全量重写） |

**决策**: **Go 扩展现有 gateway-go**。
理由：gateway-go 已有 Hub（Redis 分片广播）+ Frame 编解码 + JWT 验证骨架；Go 的 goroutine 天然适配 WS 长连接；改造工作量最低（补 4 个文件 vs Rust 全量重建 vs Java 丢弃现有 Go 代码）。

### 2.3 康养域 CRUD（业务逻辑密集型）

| 维度 | Java | Go | Rust |
|------|------|-----|------|
| **ORM/JPA** | Spring Data JPA(成熟) | GORM/ent(可用) | Diesel/SQLx(可用) |
| **DDD 充血模型** | 原生支持(现有标杆) | 需手写（Go 无继承） | 需手写（trait + struct） |
| **多租户拦截器** | MyBatis-Plus(现有) | 需手写 middleware | 需手写 |
| **Dubbo RPC** | 原生(@DubboService) | 需 dubbo-go(不成熟) | 无 Dubbo SDK |

**决策**: **Java 实现康养域**。
理由：康养域是标准 CRUD + DDD 充血模型，Java 的 JPA + Dubbo + 多租户拦截器是现成基础设施；Go/Rust 在这里没有任何优势，反而需重建整套中间件集成。

---

## 3. 综合决策

### 3.1 技术栈分配

```
┌──────────────────────────────────────────────────────────┐
│                   新事业群技术栈分配                       │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Java (核心业务域):                                       │
│  ├── sp08-elder (康养: ElderProfile/HealthRecord/CarePlan)│
│  ├── sp09-boardgame (棋牌: GameRoom/GameMatch/RuleEngine) │
│  └── 原因: DDD充血模型+JPA+Dubbo+多租户=Java 最强区        │
│                                                          │
│  Go (实时通信底座):                                       │
│  ├── gateway-go WS handler 扩展 (对弈走子/房间广播)        │
│  └── 原因: 已有 hub.go 骨架 + goroutine 适配长连接         │
│                                                          │
│  Rust: ❌ 否决                                            │
│  └── 原因: D-032 技术栈未包含 Rust; 学习成本高;            │
│      现有中台无 Rust 集成; 性能优势在教育场景无业务价值     │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 3.2 D-032 技术栈合规性

D-032（CLAUDE.md §3）锁定语言为"Java 主 + Go/Python 辅助"：
- **Java**: 核心业务域（sp08-elder, sp09-boardgame）✅
- **Go**: 实时通信网关（gateway-go WS 扩展）✅
- **Python**: AI 中台（sp04-ai industry 配置）✅
- **Rust**: ❌ 未在 D-032 中，引入需新 ADR + Iron Law 8 三方对比 + D-032 修订

### 3.3 Rust 未来潜在场景（记录但不立即采用）

如果未来有以下场景，可考虑引入 Rust（需新 ADR）：
- **高频交易级实时对弈**（职业竞技棋牌，微秒级延迟要求）
- **大规模 AI 推理引擎**（模型 Serving，GPU 内存管理）
- **安全沙箱**（用户上传代码执行隔离，替代 Firecracker microVM）

当前教育平台场景下，这些都不产生业务价值。

---

## 4. Iron Law 8 合规的三方对比总结

| 决策点 | 方案A(Java) | 方案B(Go) | 方案C(Rust) | 选定 | 理由 |
|--------|------------|-----------|-------------|------|------|
| 规则引擎 | ✅ 同进程调用 | 独立微服务 | 独立微服务 | **Java** | 避免 IPC 开销 + DDD 一致性 |
| WS 网关 | 重写为 Java | 扩展现有 Go | 全量 Rust 重建 | **Go** | 已有骨架 + 改造量最低 |
| 康养 CRUD | ✅ JPA+Dubbo | 需重建中间件 | 需重建中间件 | **Java** | 现有基础设施完整复用 |
| 全栈统一 | 全 Java | 全 Go | 全 Rust | **混合** | 各语言用在最强领域 |

---

## 5. 结论

**Rust 在当前教育平台新事业群中不可行**（D-032 不合规 + 学习成本高 + 无业务价值）。

**Go 在 WS 网关扩展中是最优选择**（已有骨架 + goroutine 适配长连接 + 改造量最低）。

**Java 仍是核心业务域首选**（DDD + JPA + Dubbo + 多租户的完整生态优势）。

最终技术栈：**Java（核心域）+ Go（WS 网关）+ Python（AI 配置）**，严格遵守 D-032 技术栈锁定。
