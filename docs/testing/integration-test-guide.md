# Integration Test Execution Guide

## Overview

This guide covers real integration tests that connect to the K8s cluster infrastructure
(TiDB, Redis, Pulsar, Milvus, Seata, Nacos) via `kubectl port-forward`.

## Safety Constraints

> ⚠️ **CRITICAL**: The K8s cluster is shared with other projects. All integration tests
> MUST follow these safety rules:
> 1. Use isolated test data (separate schema `ep_test_integration`, Redis DB 15)
> 2. Never run destructive operations on shared infrastructure
> 3. All live tests default-skip via `@Tag("live")` + `EP_LIVE=1` double gating
> 4. Clean up all test objects after each run

## Prerequisites

### 1. K8s Cluster Access
Ensure kubectl is configured and can access the cluster:
```bash
kubectl get ns ekb-m2-aws-us-east-1   # TiDB, Redis, Pulsar, Milvus
kubectl get ns esp-prod               # Nacos, Seata
```

### 2. Port-Forward Infrastructure
Use the helper script:
```bash
./scripts/port-forward-infra.sh start
# This forwards: TiDB (4000, 10080), Redis (6379), Nacos (8848), Seata (7091)
./scripts/port-forward-infra.sh stop  # Clean up
```

Or manually:
```bash
# TiDB
kubectl port-forward -n ekb-m2-aws-us-east-1 svc/tidb-cluster-tidb 4000:4000 &
kubectl port-forward -n ekb-m2-aws-us-east-1 svc/tidb-cluster-tidb 10080:10080 &
# Redis
kubectl port-forward -n ekb-m2-aws-us-east-1 svc/redis-cluster-new 6379:6379 &
# Nacos
kubectl port-forward -n esp-prod svc/nacos 8848:8848 &
# Seata TC
kubectl port-forward -n esp-prod svc/seata-server 7091:7091 &
```

### 3. Start Backend Services (for CrossServiceFlowIT)
```bash
cd services/sp03-business && mvn spring-boot:run &  # port 8083
cd services/sp05-itbackend && mvn spring-boot:run & # port 8085
```

## Running Live Integration Tests

### Java (integration-tests module)
```bash
# Run ALL live integration tests
cd services/integration-tests
EP_LIVE=1 mvn verify -Dgroups=live

# Run specific test
EP_LIVE=1 mvn verify -Dgroups=live -Dit.test=TiDBIntegrationIT

# Run with custom connection URLs
EP_LIVE=1 \
  SP03_BASE_URL=http://localhost:8083 \
  SP05_BASE_URL=http://localhost:8085 \
  mvn verify -Dgroups=live
```

### Python (sp04-ai)
```bash
cd services/sp04-ai
EP_LIVE=1 poetry run pytest -m live
EP_LIVE=1 poetry run pytest -m live tests/test_full_chain.py
```

### Default (Non-Live) Tests
Live tests are automatically excluded:
```bash
# Java — surefire excludes @Tag("live")
mvn test

# Python — pytest excludes @pytest.mark.live
poetry run pytest -m "not live"
```

## Test Data Isolation Strategy

| Infrastructure | Isolation Method | Cleanup |
|----------------|-----------------|---------|
| TiDB | Schema `ep_test_integration` + unique table names | DROP TABLE per test |
| Redis | DB index 15 (production uses DB 0) | FLUSHDB in @AfterAll |
| Pulsar | Read-only connectivity check (no writes) | N/A |
| Milvus | Separate collection with `ep_test_` prefix | Drop collection after test |
| Seata TC | HTTP API query only (no real transactions) | N/A |

## Test Inventory

### Java Live Integration Tests (`services/integration-tests`)
| Test Class | Target | Purpose |
|-----------|--------|---------|
| TiDBIntegrationIT | TiDB (JDBC 4000) | CRUD + recursive CTE verification |
| RedisIntegrationIT | Redis Cluster (6379, DB 15) | Ping, SET/GET with TTL, cluster info |
| CrossServiceFlowIT | sp03 + sp05 (HTTP) | Full-chain: register→course→order→assignment→grading |
| SeataLiveIT | Seata TC (7091) | TC connectivity, global transaction commit/rollback |
| LiveTiDBTest | TiDB Status API (10080) | Basic TiDB reachability (existing) |
| LiveRedisTest | Redis Cluster (kubectl exec) | Basic Redis reachability (existing) |

### Python Live Tests (`services/sp04-ai`)
| Test File | Target | Purpose |
|-----------|--------|---------|
| test_full_chain.py | AI Gateway (8086) | AI grading pipeline + memory-assisted learning |
| test_live_milvus.py | Milvus (19530) | Vector DB CRUD |
| test_live_redis.py | Redis (6379) | Redis connectivity |
| test_procedural_tidb_live.py | TiDB (4000) | Recursive CTE procedural memory |
| test_nebula_live.py | NebulaGraph | Graph DB connectivity |

## Frontend E2E Tests

### Playwright (admin-web)
```bash
cd frontend/apps/admin-web
pnpm e2e           # Run all E2E (headless)
pnpm e2e:ui        # Run with UI debugger

# With running backends
./scripts/port-forward-infra.sh start
cd services/sp03-business && mvn spring-boot:run &
cd services/sp05-itbackend && mvn spring-boot:run &
pnpm e2e
```

### E2E Test Inventory
| Spec File | Coverage |
|-----------|----------|
| user-management.spec.ts | Create → list → freeze user |
| course-management.spec.ts | Create → publish → search → archive |
| order-flow.spec.ts | Create → pay → refund |
| assignment-grading.spec.ts | Create assignment → submit → grade |
| full-chain.spec.ts | Full E2E chain: register→course→order→assignment→grade |

## CI Integration

### PR Gates (Fast)
- `java-test.yml` — Java unit tests (matrix, exclude live)
- `python-test.yml` — Python tests (exclude live, coverage ≥70%)
- `frontend-test.yml` — Typecheck + lint + vitest + Playwright (chromium)
- `security-semgrep.yml` — SAST scan
- `security-trivy.yml` — Dependency + filesystem scan
- `security-aikido.yml` — SAST + secrets scan
- `load-test-smoke.yml` — k6 script validation (inspect)

### Nightly Gates (Comprehensive)
- `coverage-gate.yml` — Aggregate coverage across all modules
- `load-test-smoke.yml` — Full k6 script inspection + ramp test

## Troubleshooting

### "EP_LIVE 未启用, 跳过 live 验证"
This is expected when EP_LIVE is not set. Live tests are designed to skip silently.
Set `EP_LIVE=1` to enable them.

### Port already in use
```bash
lsof -i :4000   # Find process using port 4000
kill -9 <PID>   # Kill it
```
Or use `./scripts/port-forward-infra.sh stop` to clean up all port-forwards.

### TiDB connection refused
Ensure port-forward is active:
```bash
kubectl port-forward -n ekb-m2-aws-us-east-1 svc/tidb-cluster-tidb 4000:4000 &
```

### Redis CLUSTER state not ok
Check Redis cluster health:
```bash
kubectl exec -n ekb-m2-aws-us-east-1 redis-cluster-new-0 -- redis-cli -c CLUSTER INFO
```
