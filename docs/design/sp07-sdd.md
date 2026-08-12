# SP-07 知识中台 软件详细设计（SDD）

| 编号 | 版本 | 日期 | 状态 | 编制 | 依据 |
|------|------|------|------|------|------|
| SP-07-SDD | v1.0 | 2026-08-05 | Draft(待评审) | 知识中台架构组 | SP-07-SAD v1.0 + SRS v1.0 + D-033/D-039/D-040/D-041 |

> 本 SDD 兑现深度：各服务模块边界与核心流程、文档处理管道时序、混合检索与 RAG Pipeline、Agent 编排引擎、多租户 SaaS 隔离/配额/计费、Iceberg 表 DDL、Dubbo/Pulsar 接口契约、作业清单与设计追溯。承接 SAD ADR-D33..D-041，可直接用于编码与部署。

---

## 目录

1. [设计概述](#1-设计概述)
2. [模块结构](#2-模块结构)
3. [知识库服务详细设计](#3-知识库服务详细设计)
4. [检索服务详细设计](#4-检索服务详细设计)
5. [智能体服务详细设计](#5-智能体服务详细设计)
6. [多租户 SaaS 详细设计](#6-多租户-saas-详细设计)
7. [社区服务详细设计](#7-社区服务详细设计)
8. [学习服务详细设计](#8-学习服务详细设计)
9. [接口设计](#9-接口设计)
10. [作业清单](#10-作业清单flinkspark定时任务)
11. [设计追溯](#11-设计追溯)

---

## 1. 设计概述

### 1.1 设计目标

| 目标 | 描述 | 量化指标 |
|------|------|----------|
| 知识统一治理 | 跨租户/跨BU的文档、分块、向量、知识图谱统一管理 | 文档入池延迟 P95 < 30s |
| 高精度检索 | 混合检索 + Reranker，支撑 RAG 引用溯源 | Top-5 命中率 ≥ 92% |
| 智能体编排 | 路由/流水线/辩论多模式 Agent，支撑业务问答与作业批改 | 端到端 P95 < 4s |
| 多租户 SaaS | 行级/Schema/独立 DB 三级隔离，开放 API + 计量计费 | 1000+ 租户，10w QPS |
| 弹性扩展 | 多区域多活（D-041），虚拟线程高并发（D-039） | RTO < 60s，RPO < 5s |
| 低成本运行 | GraalVM 原生镜像（D-040），Redis 缓存（D-033） | 启动 < 1s，缓存命中率 ≥ 80% |

### 1.2 设计约束

| 约束 ID | 内容 | 来源 |
|---------|------|------|
| D-033 | Redis 缓存：热门问答/Embedding/会话上下文；双写一致性 via Pub/Sub | 铁律 |
| D-039 | 虚拟线程：IO 密集型召回/生成全程 `Thread.ofVirtual()`，禁用 `synchronized` 改用 `ReentrantLock` | 铁律 |
| D-040 | GraalVM Native Image：sp07-bff / sp07-api 强制 native；service 模块 GraalVM 兼容 | 铁律 |
| D-041 | 多区域多活：三中心（北京/上海/深圳）单元化部署，租户级路由 + 异步复制 | 铁律 |
| 版本对齐 | Spring Boot 4.0.7、Spring Cloud Alibaba 2025.1.0.0、Dubbo 3.3.6、JDK 21 | 全局基线 |
| SP-04 调用 | LLM/Embedding/Reranker 一律通过 Dubbo RPC 调用 SP-04，禁止直连模型 | 接口铁律 |
| Milvus 版本 | 2.5+，collection 命名 `kb_{tenant_id}_*` | 对齐 SP-04 |
| 资源上限 | 单租户向量集合上限 100w chunks，超出需独立 Schema | 容量规划 |

### 1.3 技术栈

| 层 | 技术 | 版本 | 用途 |
|----|------|------|------|
| BFF | Spring Boot + Spring Cloud Gateway | 4.0.7 / 2025.1.0.0 | Web/H5/MP 聚合网关 |
| RPC | Dubbo | 3.3.6 | 与 SP-04 LLM/Embedding 调用 |
| 持久层 | MyBatis-Plus + TiDB | 3.5.x / 8.x | 业务库（行级隔离） |
| 向量库 | Milvus | 2.5+ | 文档向量召回 |
| 全文检索 | Elasticsearch | 8.x | BM25 关键词召回 |
| 图谱 | Neo4j | 5.x | 知识图谱存储 |
| 对象存储 | MinIO / S3 | - | 文档原文件 |
| 解析 | Apache Tika + Unstructured | 2.x / 0.15+ | PDF/Word/HTML/Markdown |
| 缓存 | Redis Cluster | 7.x | 问答/Embedding/会话 |
| 流处理 | Flink | 1.20 | 异步解析/实体抽取 |
| 批处理 | Spark | 3.5 | 向量批量索引/统计聚合 |
| 消息 | Pulsar | 3.3 | 事件驱动 |
| 数据湖 | Iceberg + TiDB Catalog | 1.6+ | 计量/审计/统计表 |
| Native | GraalVM | 21 | BFF/API 原生镜像 |

---

## 2. 模块结构

```
sp07-knowledge/
├── sp07-bff/              # BFF层(Web/H5/MP聚合)，GraalVM Native
├── sp07-knowledge-service/  # 知识库服务(文档/分块/索引)
├── sp07-retrieval-service/  # 检索服务(RAG/语义搜索)
├── sp07-agent-service/      # 智能体服务(Agent编排/会话)
├── sp07-community-service/  # 社区服务(问答/评审)
├── sp07-tenant-service/     # 租户服务(多租户/配额/SaaS)
├── sp07-learning-service/   # 学习服务(路径/进度/评估)
└── sp07-api/                # 对外API(SaaS开放平台)，GraalVM Native
```

| 模块 | 职责 | 关键依赖 | 对外协议 |
|------|------|---------|---------|
| sp07-bff | 三端聚合、鉴权透传、SSR | Redis、Nacos | HTTPS REST |
| sp07-knowledge-service | 文档生命周期、分块、索引、版本、图谱 | Tika、Milvus、Neo4j、Flink | Dubbo + REST |
| sp07-retrieval-service | 混合检索、RAG、缓存 | Milvus、ES、Redis、SP-04 | Dubbo + REST |
| sp07-agent-service | Agent 编排、会话、Function Calling | Redis、SP-04 | Dubbo + REST |
| sp07-community-service | 问答、评审、贡献度 | TiDB、Redis | REST |
| sp07-tenant-service | 租户/隔离/配额/计费/开放 API | TiDB、Kafka、MyBatis 拦截器 | REST |
| sp07-learning-service | 学习路径、进度、评估 | Neo4j、SP-04 | REST |
| sp07-api | SaaS 开放平台（API Key + JWT） | sp07-* 内部 Dubbo | HTTPS REST |

模块间调用默认走 Dubbo（内部），对外开放走 sp07-api 的 REST。BFF 不直接访问 service 的 DB，一律通过 Dubbo RPC。

---

## 3. 知识库服务详细设计

### 3.1 文档处理管道

#### 3.1.1 管道阶段

```
Upload → Parse → Chunk → Embed → Index → Notify
  │        │        │        │        │       │
  │MinIO   │Tika/   │语义+   │SP-04   │Milvus │Pulsar
  │原文件  │Unstruc │滑动+   │Embed   │+ES    │kb.doc.
  │        │tured   │递归    │RPC     │       │indexed
```

| 阶段 | 实现 | 输出 | 失败策略 |
|------|------|------|---------|
| Upload | BFF 直传 MinIO 预签名 URL | `document_id`、`object_key` | 客户端重试 |
| Parse | Apache Tika（PDF/Word/Excel/PPT）+ Unstructured（HTML/Markdown） | 纯文本 + 结构化段落 | 降级 OCR（Tesseract），仍失败标记 `parse_failed` |
| Chunk | 语义分块（句子边界）+ 滑动窗口（overlap 10%）+ 递归分块（大文档） | `chunk[]`（含 offset/section） | 跳过空段，记 warning |
| Embed | Dubbo 调用 SP-04 `EmbeddingService.embed(bge-large-zh-v1.5, 1024维)` | `float[1024]` | 降级本地 bge-small，告警 |
| Index | Milvus insert（IVF_FLAT, nlist=1024）+ ES upsert（BM25 文本） | `chunk_id` 列表 | 双写失败进入死信 topic，补偿 Job 重试 |
| Notify | 发布 Pulsar `kb.document.indexed` | - | 至少一次，幂等消费 |

#### 3.1.2 时序图

```
用户     BFF    knowledge-svc    MinIO    Flink(Parse)   SP-04(Embed)   Milvus    ES      Pulsar
 │        │          │             │           │              │            │       │         │
 │─选文件─►│          │             │           │              │            │       │         │
 │        │─预签名URL──────────────►│           │              │            │       │         │
 │        │◄─url───────────────────│           │              │            │       │         │
 │─PUT────│───────────────────────►│           │              │            │       │         │
 │        │          │             │           │              │            │       │         │
 │        │─POST /docs (metadata)─►│           │              │            │       │         │
 │        │          │─写 kb_document(status=uploaded)        │            │       │         │
 │        │          │─发 Pulsar kb.document.uploaded────────►│            │       │         │
 │        │◄─doc_id──│             │           │              │            │       │         │
 │◄─202───│          │             │           │              │            │       │         │
 │        │          │             │           │              │            │       │         │
 │        │          │             │     ┌─────▼──────────────┘            │       │         │
 │        │          │             │     │ 消费 uploaded                    │       │         │
 │        │          │             │     │─拉文件──────────────────────────►│       │         │
 │        │          │             │     │◄─bytes──────────────────────────│       │         │
 │        │          │             │     │─Tika/Unstructured 解析           │       │         │
 │        │          │             │     │─语义分块                          │       │         │
 │        │          │             │     │─Dubbo embed(text[])──────────────►│       │         │
 │        │          │             │     │◄─vectors[][]────────────────────│       │         │
 │        │          │             │     │─Milvus insert────────────────────────────►│       │
 │        │          │             │     │─ES upsert─────────────────────────────────►│       │
 │        │          │             │     │─更新 kb_document(status=indexed)          │       │
 │        │          │             │     │─发 kb.document.indexed─────────────────────────────►│
 │        │          │             │     │                                                              │
 │ (异步) │          │             │     │                                                              │
 │        │          │             │     │                                                              │
 │─GET /docs/{id}──►│             │     │                                                              │
 │        │─Dubbo getDoc──────────►│     │                                                              │
 │        │◄─status=indexed────────│     │                                                              │
 │◄─200───│          │             │     │                                                              │
```

#### 3.1.3 分块策略详述

- 语义分块：基于句子边界（中文用 HanLP 分句，英文用 NLTK），目标 chunk 长度 256~512 token。
- 滑动窗口：相邻 chunk overlap 10%（约 25~50 token），避免句子边界切断语义。
- 递归分块：单文档 > 5w token 时，先按章节（h1/h2）切分，再对每个章节走语义分块。
- 元数据保留：每个 chunk 携带 `doc_id / section_path / page_no / offset_start / offset_end`，用于引用溯源高亮。

### 3.2 知识分类体系

- 多级目录：`kb_category` 表存树形结构（`parent_id` 自关联），深度无限制，默认 3 级。
- 标签：`kb_tag` 多对多关联文档，支持租户自定义标签字典。
- Taxonomy 自定义：租户可在 `tenant_config.taxonomy` 配置目录模板，新建子目录时按模板克隆。
- 权限：目录级 ACL（`category_acl` 表），支持 `read/write/admin` 三级，通过 MyBatis 拦截器注入。

### 3.3 文档版本管理

- Git-like 版本树：`kb_version` 表记录 `doc_id / version_no / parent_version / commit_msg / diff_hash / object_key`。
- diff 对比：基于文本 diff（JGit Library），段落级粒度，前端渲染增删行。
- 版本操作：`commit`（新版本）/ `branch`（分支）/ `merge`（合并）/ `rollback`（回滚到指定版本）。
- 主版本：`kb_document.head_version` 指向当前线上版本，检索/Agent 默认读主版本。

### 3.4 知识图谱构建流程

```
文档分块 ──► LLM 实体抽取(SP-04) ──► 实体消歧 ──► Neo4j 存储 ──► 图查询
              (Prompt: 抽取实体+关系)   (向量相似度>0.9合并)   (Entity/Relation)   (Cypher/最短路径)
```

- 实体抽取：Flink Job（J-KB-03）消费 `kb.document.indexed`，调用 SP-04 LLM（Prompt 模板见 §9.2）。
- 实体类型：人物/概念/课程/技能/工具（可租户扩展）。
- 关系类型：属于/前置/相关/等同/创建。
- 消歧：同名实体用 Embedding 相似度 > 0.9 合并；< 0.9 视为新实体。
- 存储：Neo4j 节点带 `tenant_id` 标签，关系带 `source_chunk_id` 用于溯源。
- 查询：学习服务调用图查询（最短路径、邻居子图）推荐学习路径。

### 3.5 Iceberg 表 DDL

```sql
-- 文档元数据
CREATE TABLE iceberg_jdbc.kb.kb_document (
  tenant_id BIGINT, doc_id BIGINT, title STRING, category_id BIGINT,
  object_key STRING, mime_type STRING, size_bytes BIGINT,
  status STRING,  -- uploaded/parsing/parsed/indexed/failed
  head_version BIGINT, created_by BIGINT,
  created_at TIMESTAMP(6), updated_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, doc_id) NOT ENFORCED
) PARTITIONED BY (tenant_id, days(created_at))
WITH ('write.parquet.compression-codec'='zstd', 'write.target-file-size-bytes'='268435456');

-- 文档分块
CREATE TABLE iceberg_jdbc.kb.kb_chunk (
  tenant_id BIGINT, chunk_id BIGINT, doc_id BIGINT, version_no BIGINT,
  section_path STRING, page_no INT, offset_start INT, offset_end INT,
  content STRING, content_hash STRING, embed_model STRING,
  milvus_id STRING, es_id STRING,
  created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, chunk_id) NOT ENFORCED
) PARTITIONED BY (tenant_id, days(created_at))
WITH ('write.parquet.compression-codec'='zstd');

-- 分类目录
CREATE TABLE iceberg_jdbc.kb.kb_category (
  tenant_id BIGINT, category_id BIGINT, parent_id BIGINT,
  name STRING, path STRING, sort_order INT, acl JSON,
  created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, category_id) NOT ENFORCED
) PARTITIONED BY (tenant_id);

-- 标签
CREATE TABLE iceberg_jdbc.kb.kb_tag (
  tenant_id BIGINT, tag_id BIGINT, name STRING, color STRING,
  created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, tag_id) NOT ENFORCED
) PARTITIONED BY (tenant_id);

-- 文档-标签关联
CREATE TABLE iceberg_jdbc.kb.kb_doc_tag (
  tenant_id BIGINT, doc_id BIGINT, tag_id BIGINT,
  PRIMARY KEY(tenant_id, doc_id, tag_id) NOT ENFORCED
) PARTITIONED BY (tenant_id);

-- 文档版本树
CREATE TABLE iceberg_jdbc.kb.kb_version (
  tenant_id BIGINT, doc_id BIGINT, version_no BIGINT,
  parent_version BIGINT, commit_msg STRING, diff_hash STRING,
  object_key STRING, author BIGINT, created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, doc_id, version_no) NOT ENFORCED
) PARTITIONED BY (tenant_id, days(created_at));

-- 知识图谱实体
CREATE TABLE iceberg_jdbc.kb.kg_entity (
  tenant_id BIGINT, entity_id BIGINT, name STRING, type STRING,
  description STRING, mention_count INT, first_seen_doc BIGINT,
  created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, entity_id) NOT ENFORCED
) PARTITIONED BY (tenant_id);

-- 知识图谱关系
CREATE TABLE iceberg_jdbc.kb.kg_relation (
  tenant_id BIGINT, relation_id BIGINT, src_entity BIGINT, dst_entity BIGINT,
  relation_type STRING, source_chunk_id BIGINT, confidence DOUBLE,
  created_at TIMESTAMP(6),
  PRIMARY KEY(tenant_id, relation_id) NOT ENFORCED
) PARTITIONED BY (tenant_id);
```

---

### 3.6 PostgreSQL 业务表 DDL（D-072/D-075）

> 决策 D-072：sp07 业务表 DDL 采用版本化 SQL 文件（`src/main/resources/sql/ddl.sql`），经 DBA review 后手动执行，不引入 Flyway/Liquibase 自动迁移，规避共享 PostgreSQL 集群运行期 DDL 风险。

涉及四张业务表，DDL 文件分别存放于各服务的 `src/main/resources/sql/ddl.sql`：

| 表名 | 所属服务 | 用途 |
|------|---------|------|
| `tenant_api_key` | sp07-retrieval / agent-service | 租户 API Key 管理 |
| `kb_document` | sp07-retrieval | 知识库文档元数据 |
| `kb_category` | sp07-retrieval | 知识库分类体系 |
| `agent_session` | agent-service | Agent 会话持久化 |

```sql
-- tenant_api_key
CREATE TABLE IF NOT EXISTS tenant_api_key (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  api_key VARCHAR(128) NOT NULL,
  status TINYINT DEFAULT 1,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_api_key (api_key)
);

-- kb_document
CREATE TABLE IF NOT EXISTS kb_document (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  category_id BIGINT,
  status TINYINT DEFAULT 1,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP
);

-- kb_category
CREATE TABLE IF NOT EXISTS kb_category (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  parent_id BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP
);

-- agent_session
CREATE TABLE IF NOT EXISTS agent_session (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  session_state JSON,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP
);
```

> 决策 D-075：`agent_session` 持久化层补齐。新增 `AgentSessionMapper`（继承 MyBatis-Plus `BaseMapper`），在 `AgentServiceApplication` 添加 `@MapperScan`，配置共享 knowledge PG 库 datasource 与 postgresql 驱动依赖，测试中排除 `DataSourceAutoConfiguration` / `MybatisPlusAutoConfiguration`。会话不再随重启丢失，支持水平扩展。

---

## 4. 检索服务详细设计

### 4.1 混合检索架构

```
                  ┌─────────────────┐
   Query ────────►│  Query 理解      │
                  │ (意图/改写/拼上下文)│
                  └────────┬────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
   ┌──────────▼──────────┐  ┌───────────▼─────────┐
   │ 向量检索(Milvus)    │  │ 关键词检索(ES BM25) │
   │ topK=20             │  │ topK=20             │
   │ IVF_FLAT, nprobe=64 │  │ 分词: ik_smart       │
   └──────────┬──────────┘  └───────────┬─────────┘
              │                         │
              └────────────┬────────────┘
                           │
                  ┌────────▼────────┐
                  │ RRF 融合排序     │
                  │ k=60, topK=10   │
                  └────────┬────────┘
                           │
                  ┌────────▼────────┐
                  │ Reranker 精排    │
                  │ bge-reranker-large│
                  │ topK=5           │
                  └────────┬────────┘
                           │
                  ┌────────▼────────┐
                  │  返回结果+溯源   │
                  └─────────────────┘
```

| 召回通道 | 参数 | 说明 |
|---------|------|------|
| 向量检索 | Milvus collection=`kb_{tenant}_chunk`，index=IVF_FLAT，nlist=1024，nprobe=64，topK=20 | cosine 距离 |
| 关键词检索 | ES index=`kb_{tenant}_chunk`，BM25，topK=20 | ik_smart 分词 |
| RRF 融合 | `score = Σ 1/(k + rank_i)`，k=60 | 取 topK=10 |
| Reranker | SP-04 `RerankerService.rerank(bge-reranker-large)` | 取 topK=5 |

> 召回阶段使用虚拟线程（D-039）并发执行向量与关键词检索，`StructuredTaskScope.allSuccessful(...)` 并行触发，任一失败降级为单通道。

### 4.2 RAG 问答 Pipeline

#### 4.2.1 阶段

1. **Query 理解**
   - 意图识别：LLM 分类（FAQ / 文档问答 / 闲聊 / 工具调用）。
   - Query 改写：多轮对话场景下，用历史 3 轮改写当前 query（指代消解）。
   - 上下文拼接：取 Redis 会话窗口最近 10 轮，拼接为 `history`。
2. **检索**：调用 §4.1 混合检索，返回 topK=5 chunks。
3. **生成**：组装 Prompt（系统 + 知识上下文 + 历史对话 + 问题），Dubbo 调 SP-04 LLM（默认 GLM-4 级别，可租户配置）。
4. **引用溯源**：标注答案中每个论据的 `[doc_title, section, page]`，前端支持点击跳转原文高亮。

#### 4.2.2 Prompt 模板

```
[System]
你是 {tenant_name} 的知识助手，必须基于下方知识上下文回答；若上下文不足以回答，请回复"知识库未覆盖该问题"。

[Knowledge Context]
{for chunk in topk_chunks}
[#{i}] 《{chunk.doc_title}》§{chunk.section_path} p{chunk.page_no}
{chunk.content}
{endfor}

[History]
{recent_10_turns}

[Question]
{user_query}

[Answer with citations]
```

#### 4.2.3 时序图

```
用户   BFF   retrieval-svc   Redis(问答缓存)   Milvus   ES   SP-04(LLM/Rerank)   Pulsar
  │      │         │              │              │       │         │              │
  │─Q──►│         │              │              │       │         │              │
  │      │─Dubbo rag(query,session)►│            │       │         │              │
  │      │         │─Embedding(query)───────────────────────────►│              │
  │      │         │◄─query_vec─────────────────────────────────│              │
  │      │         │─GET cache(q_hash)►│                          │              │
  │      │         │◄─hit?─│           │                          │              │
  │      │         │       │            │                          │              │
  │      │         │  [hit]─直接返回缓存答案                                      │
  │      │         │       │            │                          │              │
  │      │         │  [miss]            │                          │              │
  │      │         │─向量检索(top20)────────────►│                  │              │
  │      │         │─关键词检索(top20)────────────────►│            │              │
  │      │         │◄─vec_hits─│       │           │                │              │
  │      │         │◄─es_hits───│      │           │                │              │
  │      │         │─RRF融合→top10                                               │
  │      │         │─Reranker RPC(top10)──────────────────────────►│              │
  │      │         │◄─top5─────────────────────────────────────────│              │
  │      │         │─LLM 生成(prompt)──────────────────────────────►│              │
  │      │         │◄─answer+citations─────────────────────────────│              │
  │      │         │─SET cache(q_hash, answer, TTL=1h)►│           │              │
  │      │         │─发 kb.rag.answered────────────────────────────────────────►│
  │      │◄─answer─│                                                              │
  │◄─A──│                                                                        │
```

### 4.3 缓存策略（D-033）

| 缓存对象 | Key | Value | TTL | 命中条件 |
|---------|-----|-------|-----|---------|
| 热门问答 | `qa:{tenant}:{q_hash}` | 答案 + citations | 1h | query Embedding 与缓存 query 相似度 > 0.95 |
| Embedding | `emb:{model}:{text_hash}` | float[1024] | 7d | 文本 SHA256 完全一致 |
| 会话上下文 | `sess:{tenant}:{session_id}` | JSON(最近 10 轮) | 24h | session_id 精确匹配 |
| 检索结果 | `ret:{tenant}:{q_hash}` | topK chunks | 10min | query 相似度 > 0.98 |

- 热门问答命中：检索前先计算 query Embedding，与 Redis 中近 1h 的 query 集合做近似最近邻（Redis 7.4 VECTOR SET），相似度 > 0.95 直接返回缓存答案。
- 双写一致性：缓存写入同时 Pulsar 发布 `cache.invalidate`，多区域订阅清本地 Caffeine L1（D-041 多活）。

### 4.4 性能设计

- 并发召回：`Thread.ofVirtual()` 并发触发向量 + 关键词检索，期望 P95 召回 < 200ms。
- Reranker 批量化：topK=10 一次性送 SP-04，避免多次 RPC。
- LLM 流式：LLM 生成走 SSE 流式，首 token < 800ms，整体 P95 < 4s。
- Milvus 分区：collection 按 `tenant_id` 做 partition，避免跨租户扫描。
- ES 索引分片：`kb_{tenant}_chunk` 单租户独立 index，分片数 = max(1, chunks/50w)。

---

## 5. 智能体服务详细设计

### 5.1 Agent 模型定义

```yaml
# Agent 定义 DSL
agent_id: agent_course_assistant
tenant_id: 1001
name: 课程助教
persona: |
  你是 {course_name} 的助教，语气温和、善于引导。
system_prompt: |
  {persona}
  你只能基于知识库 {kb_ids} 回答，禁止编造。
kb_ids: [2001, 2002]
tools:
  - tool_id: search_kb
    enabled: true
  - tool_id: query_progress
    enabled: true
model: glm-4-plus
temperature: 0.3
max_turns: 8
fallback_agent: agent_general_qa
```

- Agent 实体存储 `agent` 表：`agent_id / tenant_id / name / persona / system_prompt / kb_ids / tools / model / temperature / max_turns / fallback_agent`。
- 人设（persona）：租户可自定义角色设定，渲染时注入 system_prompt。
- 知识库绑定：`kb_ids` 数组，Agent 仅检索这些知识库。
- 工具配置：引用工具注册中心（§5.4）的工具，按 `enabled` 开关。

### 5.2 Agent 编排引擎

#### 5.2.1 三种编排模式

```
(a) 路由模式                 (b) 流水线模式              (c) 辩论模式
     ┌─────────┐              ┌────┐ ┌────┐ ┌────┐        ┌────┐ ┌────┐ ┌────┐
Query│ 分类器  │           Q  │ A  │►│ B  │►│ C  │     Q  │ A  │ │ B  │ │ C  │
────►│ 选最佳  │──────────►  └────┘ └────┘ └────┘   ────►└─┬──┘ └─┬──┘ └─┬──┘
     │ Agent   │              输出                            └──┬───┘      │
     └────┬────┘                                              ┌─▼──────────▼─┐
          │                                                   │  共识聚合器    │
       ┌──▼──┐ ┌───┐ ┌───┐                                    │ (LLM 投票/汇总)│
       │ QA  │ │码评│ │批改│                                   └──────┬───────┘
       └──┬──┘ └─┬─┘ └─┬─┘                                          │
          └─────┴─────┘                                            输出
```

| 模式 | 适用场景 | 实现 |
|------|---------|------|
| 路由 | 多领域问答，分类器（LLM 0-shot）选 Agent | 单跳，fallback 兜底 |
| 流水线 | 作业批改：先批改→再点评→最后给建议 | 串行 DAG，前序输出注入后序 prompt |
| 辩论 | 复杂决策，多 Agent 独立作答→共识聚合器汇总 | 并行调用，`StructuredTaskScope` 并发 |

#### 5.2.2 编排引擎实现

- 引擎类 `AgentOrchestrator`，输入 `(mode, agent_ids, query, session)`，输出最终答案 + trace。
- 路由分类器：LLM few-shot，输出 `agent_id`；置信度 < 0.6 走 `fallback_agent`。
- 流水线：DAG 拓扑排序，每节点 Agent 调用结果存入 `context`，下一节点可读取。
- 辩论：并发调用所有 Agent，共识聚合器用 LLM 汇总（Prompt：综合以下多个观点给出最终答案）。
- trace：每步记录 `agent_id / input / output / latency / tokens`，用于审计与优化。

### 5.3 会话管理

| 项 | 设计 |
|----|------|
| 存储 | Redis `sess:{tenant}:{session_id}`，JSON 数组 |
| TTL | 24h，每次访问续期 |
| 窗口 | 最近 10 轮（user+assistant），超出截断 |
| 多轮拼接 | 改写 query 时注入最近 3 轮，生成时注入全部窗口 |
| 会话恢复 | 客户端带 `session_id`，服务端按 key 恢复；无则新建 |
| 会话结束 | 用户主动结束或 TTL 过期 → 发 `agent.session.ended` 事件 |

### 5.4 Function Calling

- 工具注册中心：`tool_registry` 表，字段 `tool_id / name / description / json_schema / endpoint / auth_type`。
- 注册方式：平台预置（search_kb / query_progress / submit_assignment）+ 租户自定义（HTTP webhook）。
- 调用流程：LLM 返回 `tool_calls` → 引擎按 `tool_id` 查注册中心 → JSON Schema 校验参数 → HTTP/Dubbo 调用 → 结果回填 LLM。
- 参数校验：使用 `networknt/json-schema-validator`，校验失败返回错误给 LLM 重试（最多 3 次）。
- 安全：工具调用必须带 `tenant_id` 上下文，租户只能调用本租户启用 + 平台允许的工具。

---

## 6. 多租户 SaaS 详细设计

### 6.1 租户模型

```sql
-- TiDB: tenant 表
CREATE TABLE tenant (
  tenant_id BIGINT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  plan VARCHAR(32) NOT NULL,        -- starter/pro/enterprise/flagship
  quota_json JSON,                  -- 配额快照
  brand_json JSON,                  -- 品牌配置(logo/color/domain)
  custom_domain VARCHAR(255),
  region VARCHAR(32),               -- 主区域(D-041)
  isolation_level VARCHAR(16),      -- row/schema/db
  schema_name VARCHAR(64),          -- schema 隔离时专用 schema
  db_instance_id VARCHAR(64),       -- 独立 DB 时实例 ID
  status VARCHAR(16),
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
```

- 套餐（plan）：starter（行级）/ pro（行级+更高配额）/ enterprise（Schema 隔离）/ flagship（独立 DB）。
- 品牌配置：logo URL、主题色、自定义域名（CNAME 到 SaaS 平台）。
- 区域：D-041 三中心，租户主区域固定，跨区域只读副本异步复制。

### 6.2 数据隔离策略

| 级别 | 适用 | 实现 |
|------|------|------|
| 行级（默认） | starter/pro | 每张业务表加 `tenant_id`，MyBatis 拦截器自动注入 `WHERE tenant_id=?` |
| Schema 隔离 | enterprise | 动态数据源路由（`DynamicDataSourceRouter`），按 `tenant_id` 路由到独立 schema |
| 独立 DB | flagship | 独立 TiDB 实例，独立部署，数据物理隔离 |

- MyBatis 拦截器：`TenantInterceptor implements InnerInterceptor`，拦截 SELECT/UPDATE/DELETE 自动加 `tenant_id` 条件；INSERT 自动填充 `tenant_id`。
- 升级流程：租户在控制台发起升级 → 后台异步迁移数据到新 schema/DB → 切换 `tenant.isolation_level` → 旧数据保留 7d 后清理。
- Milvus/ES：collection/index 名带 `tenant_id`，天然隔离。
- Neo4j：节点/关系带 `tenant_id` label，Cypher 查询自动加 label 过滤。

### 6.3 配额管理

| 资源 | 默认配额（starter） | 升级选项 |
|------|-------------------|---------|
| API 调用 | 100 QPS | 500/2000/不限 |
| 存储 | 10 GB | 100GB/1TB/不限 |
| Agent 数 | 5 | 20/100/不限 |
| 用户数 | 50 | 500/5000/不限 |
| 文档数 | 1w | 10w/100w/不限 |

- 限流：令牌桶（Redis + Lua），key=`quota:{tenant}:{resource}`，超限返回 429。
- 存储/文档数：定时 Job 统计，超限拒绝新写入并通知租户管理员。
- 升级即时生效：控制台改套餐 → 写 `tenant.plan` + 刷新 `quota_json` → 限流器读取新配额。

### 6.4 计量计费

```
使用事件 ──► Kafka(metering.usage) ──► SP-02 数据中台 ──► 聚合 ──► 账单生成
   │                                                              │
   │  (API/存储/Agent/用户 实时采集)                                │
```

- 采集点：BFF/API 网关（API 调用）、knowledge-svc（文档/存储）、agent-svc（Agent 调用/LLM tokens）、tenant-svc（用户数）。
- 事件 schema：`{tenant_id, resource, quantity, unit, ts}`，发 Kafka `metering.usage`。
- 聚合：SP-02 Spark Job（J-KB-06）按租户/资源/天聚合，写 Iceberg `metering.daily_usage`。
- 账单：月初定时 Job 读取上月聚合，按套餐计价规则生成账单，推送租户管理员。

### 6.5 开放 API 设计

- Base URL：`https://api.{saas-domain}/api/v1`
- 资源示例：
  - `POST /api/v1/kb/{tenantId}/documents` 上传文档
  - `GET /api/v1/kb/{tenantId}/documents/{docId}` 查询文档
  - `POST /api/v1/agent/{tenantId}/chat` Agent 对话（SSE 流式）
  - `GET /api/v1/agent/{tenantId}/sessions/{sessionId}` 查会话
- 认证：`x-api-key`（开放平台颁发，租户级）+ JWT（用户级）双重；API Key 走 `tenant_api_key` 表校验 + HMAC 签名。
- 限流：每租户 100 QPS 默认，可升级；超限 429 + `Retry-After`。
- 版本：URL 内嵌 `/v1/`，破坏性变更升 `/v2/`，旧版本保留 12 个月。
- 文档：OpenAPI 3.1 自动生成，挂 `/api/v1/openapi.json` + Swagger UI。

---

## 7. 社区服务详细设计

### 7.1 问答模型

- 实体：`Question`（提问）/ `Answer`（回答）/ `Comment`（评论）/ `Acceptance`（采纳）。
- 关系：Question 1:N Answer；Answer 1:N Comment；Question 1:1 Acceptance（提问者采纳一个 Answer）。
- 状态机：Question `open → answered → accepted/closed`；Answer `draft → published → accepted/rejected`。
- 富文本：Markdown + 代码高亮，图片走对象存储。

### 7.2 评审工作流

```
草稿(draft) ──提交──► 待审(pending) ──审核──► 发布(published)
                          │                       │
                          │                     驳回(rejected)
                          │                       │
                          └──修改回草稿────────────┘
```

- 角色：作者（创建/提交/修改）/ 审核员（通过/驳回）/ 读者（浏览/点赞/评论）。
- 审核规则：自动审核（敏感词 + LLM 内容安全）+ 人工审核（高曝光内容强制人工）。
- 版本：审核通过后修改需重新走流程，保留历史版本。

### 7.3 贡献度量化

| 行为 | 积分 | 说明 |
|------|------|------|
| 发帖（Question） | +10 | 每日上限 100 |
| 回答（Answer） | +20 | 每日上限 200 |
| 被采纳 | +50 | 无上限 |
| 被点赞 | +5 | 每日上限 200 |
| 审核通过 | +15 | 每日上限 150 |
| 内容被举报且成立 | -30 | 扣分 |

- 排行榜：Redis ZSET `rank:{tenant}:score` 实时更新；月榜每月 1 日归档到 TiDB。
- 等级：积分阈值映射等级（L1..L7），等级解锁权限（如审核豁免、专属徽章）。

---

## 8. 学习服务详细设计

### 8.1 学习路径生成

```
用户画像 + 知识图谱 ──► LLM 路径生成 ──► 路径节点序列 ──► 持久化 ──► 推送
   │                    (SP-04 RPC)       (课程/文档/练习)
   │
   ├─ 用户画像: 等级/历史课程/薄弱知识点/学习目标
   └─ 知识图谱: Neo4j 子图(目标节点的前置依赖链)
```

- 输入：用户画像（来自 SP-02 Feature Store）+ 目标知识点 + Neo4j 前置依赖子图。
- LLM 生成：Prompt 提供依赖链 + 候选课程/文档列表，输出有序学习节点 + 预估时长。
- 持久化：`learning_path` / `learning_path_node` 表，节点类型 `course/document/exercise/assessment`。
- 动态调整：用户完成节点后，LLM 重新评估剩余路径（弱项补强）。

### 8.2 进度跟踪

- 模块完成度：`learning_progress` 表记录每个节点的 `status(in_progress/completed/skipped) / progress_pct / time_spent`。
- 时间投入：前端心跳上报学习时长，聚合到 `learning_daily_stat`。
- 知识点掌握：基于完成的练习/评估，更新 `knowledge_mastery` 表（知识点 → 掌握度 0~1）。

### 8.3 掌握度评估

- AI 出题：LLM 基于知识点 + 难度等级生成题目（选择/填空/简答），Dubbo 调 SP-04。
- 自动评分：客观题规则判分；简答题 LLM 评分（rubric + few-shot）+ 抽样人工复核。
- 薄弱点推荐：错题对应知识点 → 知识图谱找相关前置知识点 → 推荐补强资源。
- 评估报告：生成 PDF 报告（掌握度雷达图 + 薄弱点 + 推荐路径）。

---

## 9. 接口设计

### 9.1 REST API 列表

| 服务 | 方法 | 路径 | 描述 |
|------|------|------|------|
| knowledge | POST | `/kb/{tenant}/documents` | 上传文档（预签名 URL） |
| knowledge | GET | `/kb/{tenant}/documents/{id}` | 查询文档详情 |
| knowledge | DELETE | `/kb/{tenant}/documents/{id}` | 删除文档 |
| knowledge | POST | `/kb/{tenant}/categories` | 创建分类目录 |
| knowledge | GET | `/kb/{tenant}/versions/{docId}` | 查版本树 |
| knowledge | POST | `/kb/{tenant}/versions/{docId}/rollback` | 回滚版本 |
| retrieval | POST | `/retrieval/{tenant}/search` | 混合检索 |
| retrieval | POST | `/retrieval/{tenant}/rag` | RAG 问答（SSE） |
| agent | POST | `/agent/{tenant}/chat` | Agent 对话（SSE） |
| agent | POST | `/agent/{tenant}/agents` | 创建 Agent |
| agent | GET | `/agent/{tenant}/sessions/{id}` | 查会话 |
| community | POST | `/community/{tenant}/questions` | 提问 |
| community | POST | `/community/{tenant}/answers` | 回答 |
| community | POST | `/community/{tenant}/reviews` | 提交评审 |
| tenant | POST | `/tenant/tenants` | 创建租户 |
| tenant | GET | `/tenant/tenants/{id}/quota` | 查配额 |
| tenant | POST | `/tenant/apikeys` | 生成 API Key |
| learning | POST | `/learning/{tenant}/paths/generate` | 生成学习路径 |
| learning | GET | `/learning/{tenant}/progress/{userId}` | 查进度 |
| learning | POST | `/learning/{tenant}/assessments` | 创建评估 |

### 9.2 Dubbo RPC 接口

| 接口 | 提供方 | 消费方 | 方法 |
|------|-------|-------|------|
| `EmbeddingService` | SP-04 | sp07-retrieval / knowledge | `embed(model, text[])` |
| `LlmService` | SP-04 | sp07-retrieval / agent / learning | `chat(model, prompt)` / `streamChat(...)` |
| `RerankerService` | SP-04 | sp07-retrieval | `rerank(model, query, docs[])` |
| `KbQueryService` | sp07-knowledge | sp07-retrieval / agent | `getChunks(tenant, chunkIds)` |
| `AgentInvokeService` | sp07-agent | sp07-bff / sp07-api | `invoke(tenant, agentId, query, session)` |
| `TenantQuotaService` | sp07-tenant | 全部 sp07-* | `checkQuota(tenant, resource, qty)` |

> Dubbo 注册中心 Nacos；triple 协议（HTTP/2）；超时 5s（LLM 调用 30s）；重试 2 次（幂等接口）。

### 9.3 事件驱动（Pulsar Topic）

| Topic | 生产者 | 消费者 | Payload |
|-------|-------|-------|---------|
| `kb.document.uploaded` | knowledge-svc | Flink J-KB-01 | `{tenantId, docId, objectKey}` |
| `kb.document.indexed` | Flink J-KB-01 | J-KB-03 / retrieval-svc | `{tenantId, docId, chunkCount}` |
| `kb.rag.answered` | retrieval-svc | J-KB-04(预热) / SP-02 | `{tenantId, qHash, answer, ts}` |
| `agent.session.ended` | agent-svc | SP-02 / 审计 | `{tenantId, sessionId, turns, tokens}` |
| `community.post.created` | community-svc | 通知 / 排行榜 | `{tenantId, postId, type, authorId}` |
| `metering.usage` | 全部 sp07-* | SP-02 | `{tenantId, resource, quantity, ts}` |
| `cache.invalidate` | 任意 sp07-* | 全部 sp07-* | `{key, scope}` |

> Topic 命名空间 `persistent://public/sp07/*`；分区数按租户量动态调整（默认 8）；Avro schema 走 Pulsar 内置 Registry，兼容性 `BACKWARD_TRANSITIVE`。

---

## 10. 作业清单（Flink/Spark/定时任务）

| Job ID | 类型 | 功能 | 并行度 | checkpoint | 调度 |
|--------|------|------|--------|-----------|------|
| J-KB-01 | Flink | 文档异步解析与分块（消费 uploaded → Tika/Unstructured → Chunk） | 16 | 1min | 持续 |
| J-KB-02 | Spark | 向量批量索引构建（历史文档补 Embedding → Milvus + ES） | - | 批 | 每日 01:00 |
| J-KB-03 | Flink | 知识图谱实体抽取（消费 indexed → LLM 抽取 → Neo4j） | 8 | 2min | 持续 |
| J-KB-04 | 定时 | 热门问答缓存预热（统计近 24h 高频 query → 预生成答案） | - | - | 每 2h |
| J-KB-05 | 定时 | 租户配额重置（月度配额清零 + 账单生成） | - | - | 每月 1 日 00:00 |
| J-KB-06 | Spark | 使用量统计聚合（消费 metering.usage → Iceberg daily_usage） | - | 批 | 每日 03:00 |

### 10.1 J-KB-01 文档异步解析与分块（Flink）

| 项 | 设计 |
|----|------|
| Source | Pulsar `persistent://public/sp07/kb.document.uploaded`（Avro） |
| 算子 | ① 拉取 MinIO 文件 ② Tika/Unstructured 解析 ③ 语义分块 ④ Dubbo 调 SP-04 Embedding ⑤ Milvus insert + ES upsert ⑥ 更新 kb_document ⑦ 发 `kb.document.indexed` |
| 并行度 | 16（CPU 密集，Tika 占 CPU） |
| checkpoint | 1min，EXACTLY_ONCE，RocksDB |
| 容错 | Milvus/ES 写失败进死信 topic，补偿 Job 重试；幂等：chunk_id 唯一 |

### 10.2 J-KB-03 知识图谱实体抽取（Flink）

| 项 | 设计 |
|----|------|
| Source | Pulsar `persistent://public/sp07/kb.document.indexed` |
| 算子 | ① 按 chunk 取文本 ② Dubbo LLM 抽取实体+关系 ③ Embedding 相似度消歧 ④ 写 Neo4j + Iceberg kg_entity/kg_relation |
| 并行度 | 8（LLM 调用 IO 密集，配合虚拟线程） |
| checkpoint | 2min |
| LLM Prompt | `从以下文本抽取实体(人物/概念/课程/技能/工具)及其关系，输出 JSON: {entities:[{name,type}], relations:[{src,dst,type}]}\n文本: {chunk}` |

### 10.3 J-KB-04 热门问答缓存预热（定时）

> 决策 D-073：实现由原设计的 XXL-Job 改为 Spring `@Scheduled(fixedDelay = 2h)` + ep-cache `CacheWarmer` SPI。ep-cache 新增 `CacheWarmer` 接口与 `CacheWarmupRunner`（虚拟线程，D-039）；sp07-retrieval 新增 `HotQueryCacheWarmer` 实现并集成 ep-cache 依赖。无需额外调度中心。

- 调度：`@Scheduled(fixedDelay = 2h)` 触发（原设计 XXL-Job，已由 D-073 替换）。
- 流程：查询近 24h 高频 query（聚合 `kb.rag.answered`）→ 取 Top 100 → 虚拟线程异步重新生成答案 → 写 Redis `qa:{tenant}:{q_hash}` TTL=1h。
- 目的：上班高峰前预热，降低首问延迟。

### 10.4 J-KB-06 使用量统计聚合（Spark）

```sql
INSERT INTO iceberg_jdbc.metering.daily_usage
SELECT tenant_id, resource, SUM(quantity) AS qty, day(ts) AS d
FROM pulsar_source.metering_usage  -- Pulsar Spark Connector
WHERE day(ts) = current_date - 1
GROUP BY tenant_id, resource, day(ts);
```

---

## 11. 设计追溯

| SRS 需求 | SDD 章节 | 关联 ADR |
|---------|---------|---------|
| FR-KB-01 文档上传与解析 | §3.1 文档处理管道 + §10.1 J-KB-01 | D-039 |
| FR-KB-02 分块与索引 | §3.1.3 分块策略 + §3.5 DDL | D-033 |
| FR-KB-03 分类与版本 | §3.2 分类体系 + §3.3 版本管理 | - |
| FR-KB-04 知识图谱 | §3.4 图谱构建 + §10.2 J-KB-03 | - |
| FR-RT-01 混合检索 | §4.1 混合检索架构 | D-039 |
| FR-RT-02 RAG 问答 | §4.2 RAG Pipeline | D-033 |
| FR-RT-03 缓存 | §4.3 缓存策略 | D-033 |
| FR-AG-01 Agent 模型 | §5.1 Agent 定义 | - |
| FR-AG-02 Agent 编排 | §5.2 编排引擎 | - |
| FR-AG-03 会话管理 | §5.3 会话管理 | D-033 |
| FR-AG-04 Function Calling | §5.4 工具调用 | - |
| FR-TN-01 租户模型 | §6.1 租户模型 | - |
| FR-TN-02 数据隔离 | §6.2 隔离策略 | D-041 |
| FR-TN-03 配额管理 | §6.3 配额 | - |
| FR-TN-04 计量计费 | §6.4 计费 + §10.4 J-KB-06 | - |
| FR-TN-05 开放 API | §6.5 开放 API | D-040 |
| FR-CM-01 问答社区 | §7.1 问答模型 | - |
| FR-CM-02 评审工作流 | §7.2 评审 | - |
| FR-CM-03 贡献度 | §7.3 贡献度 | - |
| FR-LN-01 学习路径 | §8.1 路径生成 | - |
| FR-LN-02 进度跟踪 | §8.2 进度 | - |
| FR-LN-03 掌握度评估 | §8.3 评估 | - |
| NFR-性能 | §4.4 性能设计 + §3.1 管道 | D-039 |
| NFR-多活 | §6.1 区域 + §6.2 隔离 + §4.3 多活一致性 | D-041 |
| NFR-原生镜像 | §1.2 GraalVM 约束 | D-040 |
| NFR-缓存 | §4.3 + §5.3 + §10.3 | D-033 |

---

## 12. D-047: MCP Server 重构设计

> 2026-08-05 追加。对应决策 D-047 与 ADR-A07：采用官方 MCP Java SDK + Spring AI MCP 注解替换手写 JSON-RPC dispatcher。

### 12.1 设计目标

将 SP-07 MCP Server 实现从 D-046 的手写 JSON-RPC dispatcher 迁移到基于官方 MCP Java SDK（modelcontextprotocol/java-sdk v2.0.0）与 Spring AI MCP 注解的声明式实现，满足：

- 通过官方 MCP conformance tests（40/40）。
- 支持 MCP 2.0 规范的 Streamable HTTP 传输与协议版本协商。
- 与 Spring Boot 4.0.x 基线及 Spring AI 2.0.0 BOM 对齐。
- 工具注册收敛到注解声明，消除手写 schema 与注册中心。

### 12.2 新增依赖

`sp07-mcp-server/pom.xml`：

```xml
<dependencies>
  <!-- Spring AI MCP Server（WebMVC + Streamable HTTP） -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
  </dependency>
  <!-- @McpTool / @McpToolParam 注解 -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-annotations</artifactId>
  </dependency>
</dependencies>
```

父 pom 通过 `spring-ai-bom 2.0.0` 统一版本：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>2.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 12.3 @McpTool 注解式工具注册（KnowledgeTools.java）

工具暴露收敛到单一 Bean，由 Spring 容器扫描 `@McpTool` 方法自动注册到 MCP Server。`tenant_id` 通过 Spring AI 的 `ToolContext` 透传，禁止在方法签名外拼接。

```java
@Component
public class KnowledgeTools {

    private final KnowledgeQueryService queryService;     // Dubbo → sp07-retrieval-service
    private final SubmissionGradeService gradeService;    // Dubbo → sp07-agent-service
    private final QuizGenerateService quizService;        // Dubbo → sp07-agent-service

    public KnowledgeTools(KnowledgeQueryService queryService,
                          SubmissionGradeService gradeService,
                          QuizGenerateService quizService) {
        this.queryService = queryService;
        this.gradeService = gradeService;
        this.quizService = quizService;
    }

    @McpTool(name = "knowledge.search",
             description = "在租户知识库中进行混合检索（向量+BM25，RRF 融合）")
    public SearchHit[] search(
            @McpToolParam(description = "查询语句") String query,
            @McpToolParam(description = "知识库 ID") String kbId,
            @McpToolParam(description = "Top-K", required = false) Integer topK,
            ToolContext ctx) {
        String tenantId = ctx.getContext().get("tenant_id");
        return queryService.hybridSearch(tenantId, kbId, query, topK == null ? 5 : topK);
    }

    @McpTool(name = "submission.grade",
             description = "对学生提交进行 AI 批改并返回评分与评语")
    public GradeResult grade(
            @McpToolParam(description = "提交 ID") String submissionId,
            ToolContext ctx) {
        String tenantId = ctx.getContext().get("tenant_id");
        return gradeService.grade(tenantId, submissionId);
    }

    @McpTool(name = "quiz.generate",
             description = "基于知识点与难度生成测验题")
    public QuizQuestion[] generate(
            @McpToolParam(description = "知识点列表") List<String> topics,
            @McpToolParam(description = "难度 1-5") int difficulty,
            @McpToolParam(description = "题目数量") int count,
            ToolContext ctx) {
        String tenantId = ctx.getContext().get("tenant_id");
        return quizService.generate(tenantId, topics, difficulty, count);
    }
}
```

工具调用仍受 §6.2 隔离策略约束：所有 Dubbo 下游服务沿用 MyBatis `TenantInterceptor` 自动注入 `tenant_id`，与 §5.4 Function Calling 共用 `tool_registry` 元数据（仅元数据保留，schema 由注解生成）。

### 12.4 Streamable HTTP 传输（/mcp/v1）

- 端点：`POST /mcp/v1`，由 `spring-ai-starter-mcp-server-webmvc` 自动注册的 `McpServerWebMvcAutoConfiguration` 提供。
- 传输：Streamable HTTP（MCP 2.0 spec），单连接支持流式响应（SSE 下行）。
- 协议版本协商：官方 SDK 内置 `MCP-Protocol-Version` header 协商，默认 `2.0`，向下兼容 `1.0` initialize 握手。
- 取消：客户端发送 `notifications/cancelled`，SDK 自动取消服务端 `McpTool` 执行的虚拟线程（对齐 D-039 虚拟线程模型）。
- 鉴权：复用 §6.5 开放 API 的 `x-api-key` + JWT 双重鉴权，由 BFF 在 `/mcp/v1` 前置拦截器注入 `tenant_id` 到 `ToolContext`。

### 12.5 删除的手写实现

以下 D-046 中手写 JSON-RPC 的类整体删除，不再保留：

| 删除文件 | 原职责 | 替代 |
|----------|--------|------|
| `McpController.java` | 接收 `/api/v1/mcp` JSON-RPC 请求并分发 | `spring-ai-starter-mcp-server-webmvc` 自动注册的 `/mcp/v1` 端点 |
| `McpRequest.java` | JSON-RPC 请求体 POJO | 官方 SDK 的 `McpSchema` 类型 |
| `McpResponse.java` | JSON-RPC 响应体 POJO | 官方 SDK 的 `McpSchema` 类型 |
| `ToolSchema.java` | 手写 JSON Schema 描述工具参数 | `@McpTool` / `@McpToolParam` 注解自动生成 |
| `ToolRegistry.java` | 内存工具注册表 + 手写路由 | Spring 容器扫描 `@McpTool` Bean 自动注册 |

### 12.6 application.yml 配置

```yaml
spring:
  ai:
    mcp:
      server:
        name: sp07-mcp-server
        version: 1.0.0
        type: WEBMVC                # WebMVC + Streamable HTTP
        sse-message-endpoint: /mcp/v1
        capabilities:
          tool: true
          resource: false           # 资源暂不暴露，后续迭代
          prompt: false
        # 协议版本由 SDK 内置协商，不在此硬编码
```

### 12.7 与其他模块的关系

- **sp07-retrieval-service / sp07-agent-service**：通过 Dubbo 被调用，无任何代码变更；`KnowledgeTools` 仅作 MCP 适配层。
- **sp07-bff / sp07-api**：`/mcp/v1` 路由由 BFF 透传至 sp07-mcp-server；鉴权在 BFF 完成。
- **SP-04（Python 侧）**：继续通过 MCP/A2A 协议（Streamable HTTP）调用 sp07-mcp-server 暴露的工具，协议契约不变。
- **D-040（GraalVM）**：sp07-mcp-server 作为 service 模块仅做 GraalVM 兼容（不强制 native），BFF/API 仍强制 native。

### 12.8 设计追溯

| SRS 需求 | SDD 章节 | 关联 ADR |
|---------|---------|---------|
| FR-MCP-04 MCP 协议合规 | §12 D-047 MCP Server 重构 | ADR-A07 |

---

**文档结束**。本 SDD 为编码与部署的直接输入，变更需同步 SAD ADR（D-033/D-039/D-040/D-041/D-047）与 SRS 追溯矩阵。
