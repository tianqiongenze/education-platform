# Live 集成连接验证报告 (D-048 §11.4 诚实声明闭环)

- **日期**: 2026-08-05
- **集群**: Rancher Desktop (lima-rancher-desktop, k3s v1.34.6+k3s1), 运行 120 天
- **执行脚本**: `scripts/live-integration-check.sh` (可重复执行, 仅读操作)
- **连接方式**: 全部通过 `kubectl exec` (集群内直连), 不依赖 localhost port-forward

## 1. 基础设施实测连接结果

| # | 基础设施 | Namespace | 连接方式 | 结果 | 命令输出摘要 |
|---|----------|-----------|----------|------|--------------|
| 1 | Redis Cluster | ekb-m2-aws-us-east-1 | kubectl exec → redis-cli | **PASS** | `PONG`; `cluster_state:ok`, `cluster_slots_ok:16384`, `cluster_size:3` |
| 2 | TiDB (版本) | ekb-m2-aws-us-east-1 | kubectl exec → `/tidb-server -V` | **PASS** | `Release Version: v8.5.5` (Edition: Community) |
| 3 | TiDB (TCP 4000) | ekb-m2-aws-us-east-1 | 临时 busybox pod → `nc -z` | **PASS** | `TCP_OPEN_4000` (MySQL 协议端口开放) |
| 4 | TiDB (Status 10080) | ekb-m2-aws-us-east-1 | 临时 busybox pod → `wget` | **PASS** | `{"connections":69,"version":"8.0.11-TiDB-v8.5.5",...,"init_stats_percentage":100}` |
| 5 | Pulsar brokers | ekb-m2-aws-us-east-1 | kubectl exec → curl :8080 | **PASS** | 3 个 broker 节点 (`pulsar-broker-{0,1,2}`) |
| 6 | Pulsar tenants | ekb-m2-aws-us-east-1 | kubectl exec → curl :8080 | **PASS** | `["esp"]` |
| 7 | Milvus (19530) | ekb-m2-aws-us-east-1 | — | **FAIL** | deployment 副本数=0 (缩容), Service `milvus` 无 endpoints, 19530 不可达 |
| 8 | Neo4j (7474/7687) | ekb-m2-aws-us-east-1 | — | **FAIL** | deployment 副本数=0 (缩容), Service `neo4j` 无 endpoints, 7474/7687 不可达 |
| 9 | Nacos (8848) | esp-prod | kubectl exec → curl :8848 | **PASS** | `{"status":"UP"}`; 已注册服务 `["bff-java"]` (count=1) |
| 10 | Seata TC (7091) | esp-prod | kubectl exec → curl :7091 | **PASS** | Seata 控制台 HTML (store.mode=file, registry.type=nacos) |

**汇总**: PASS=9, FAIL=2 (Milvus / Neo4j 因副本缩容为 0 不可达)

### 关键发现

- **TiDB Pod 内无 mysql CLI**: `tidb-cluster-tidb-...` 容器 PATH 中无 `mysql`,
  但 `/tidb-server` 二进制可直接 `-V` 取版本, 10080 Status API 提供 JSON 状态。
  MySQL 协议端口 4000 经 TCP 探测确认开放, 后续可用 JDBC 验证。
- **Pulsar 控制面正常**: HTTP admin API (8080) 返回 3 broker + 1 tenant (`esp`),
  bin/pulsar-admin CLI 因 JVM 启动慢会超时 (exit 137/143), HTTP 路径更稳定。
- **Milvus / Neo4j 被缩容**: 两个 deployment 的 `spec.replicas=0`,
  Service 存在但 `endpoints=<none>`。这是诚实声明 #4 (Python ai_rag)
  无法在本轮完成 live 验证的根本原因。需手动扩容后才能验证:
  ```bash
  kubectl scale deploy milvus -n ekb-m2-aws-us-east-1 --replicas=1
  kubectl scale deploy neo4j  -n ekb-m2-aws-us-east-1 --replicas=1
  ```
- **Seata store.mode=file**: 当前 Seata TC 使用本地文件存储事务日志 (非 DB 模式),
  功能可用但非生产推荐配置; 诚实声明 #2 的全局事务回滚端到端验证仍需业务服务接入。

## 2. D-048 §11.4 四条诚实声明验证状态表

| # | 诚实声明 | 原状态 | 本轮后新状态 | 证据 / 缺口 |
|---|----------|--------|--------------|-------------|
| 1 | Java测试全Mockito, 无live Seata/Redis/Pulsar/Nacos验证 | 未验证 | **部分验证** | Redis/Nacos/Seata/Pulsar/TiDB 控制面已实测可达 (PASS=9); Java live 测试骨架已创建 (`LiveRedisTest` / `LiveTiDBTest`, `@Tag("live")` 默认 skip) |
| 2 | Redisson+Seata注解未验证实际全局事务回滚 | 未验证 | **仍待验证** | Seata TC 已确认 Running; 跨服务全局事务回滚需业务服务接入并触发 @GlobalTransactional, 本轮未覆盖 |
| 3 | gradeSubmission生产profile抛异常 (路线B) | 未验证 | **仍待验证 (路线B)** | 不在本轮范围, 依赖 sp05 GradingService Dubbo API 发布 |
| 4 | Python ai_rag无live Milvus/PG连接验证 | 未验证 | **仍待验证 (基础设施不可达)** | Milvus deployment 副本数=0, 19530 不可达; `test_live_milvus.py` / `test_live_redis.py` 已创建 (`@pytest.mark.live`, 默认 skip), 待扩容后启用 |

## 3. 已创建文件清单

| 文件 | 用途 |
|------|------|
| `scripts/live-integration-check.sh` | 可重复执行的 K8s 集群连通性探测脚本 (10 项, PASS/FAIL 汇总) |
| `services/sp04-ai/tests/test_live_milvus.py` | Python live 测试: Milvus 真实连接/插入/检索 (`@pytest.mark.live`) |
| `services/sp04-ai/tests/test_live_redis.py` | Python live 测试: Redis Cluster PING/SET/GET (`@pytest.mark.live`) |
| `services/sp04-ai/pyproject.toml` | 新增 `live` pytest marker 声明 |
| `services/integration-tests/src/test/java/com/ep/live/LiveRedisTest.java` | Java live 测试骨架: Redis PING + CLUSTER INFO (`@Tag("live")`) |
| `services/integration-tests/src/test/java/com/ep/live/LiveTiDBTest.java` | Java live 测试骨架: TiDB Status API (`@Tag("live")`) |
| `docs/testing/live-integration-report.md` | 本报告 |

## 4. 剩余技术债清单

| 优先级 | 技术债 | 关联声明 | 处置建议 |
|--------|--------|----------|----------|
| P1 | Milvus / Neo4j deployment 副本数=0, live 验证无法完成 | #4 | 扩容后运行 `EP_LIVE=1 pytest -m live tests/test_live_milvus.py`; 考虑资源占用是否长期保活 |
| P1 | Redisson + Seata 全局事务回滚端到端未验证 | #2 | 在 sp03-business 写一个 @GlobalTransactional 的集成场景, 触发子事务失败观察主事务回滚 |
| P2 | Java live 测试默认 skip, 未进入 CI | #1 | 在 CI 增加 `live` profile (需 K8s 集群凭据), 或在 nightly job 中运行 |
| P2 | Seata store.mode=file 非生产配置 | #2 | 切换为 DB 模式 (TiDB) 后重新验证 |
| P3 | gradeSubmission 路线B | #3 | 待 sp05 GradingService Dubbo API 发布 |
| P3 | TiDB 无 JDBC 端到端验证 (仅 Status API) | #1 | 业务服务接入后用真实 SQL 覆盖 |
