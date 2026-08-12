# k6 Load Test Execution Runbook

Operational runbook for executing the k6 load-test suite against the Education
Platform multi-cell Kubernetes deployment. This document covers environment
requirements, the test matrix, step-by-step execution, expected results,
rollback criteria, cost estimates, and safety notes.

---

## 1. Environment Requirements

- **10-cell K8s cluster** (~300 nodes total): each cell hosts a full stack
  (sp03-business, sp05-itbackend, bff-java, sp04-ai) plus shared infra.
- **10 k6 load generator machines**: bare-metal or large VMs, each capable of
  sustaining 10K+ VUs (recommended: 16 vCPU / 64 GB RAM / 10 Gbps NIC).
- **Grafana / Prometheus monitoring**: per-cell dashboards for RPS, P95/P99,
  error rate, CPU/Memory, TiDB connections, Redis pool, Pulsar backlog.
- **k6 installed** on all load generator machines (v0.50+).
- **Network**: load generators must reach each cell's ingress / NodePort;
  inter-cell latency < 2 ms within a region.

---

## 2. Prerequisites

Before any load test run, confirm all of the following:

1. All services deployed to **each cell** and passing readiness probes:
   sp03-business, sp05-itbackend, bff-java, sp04-ai.
2. `scripts/port-forward-infra.sh` (or `scripts/live-integration-check.sh`)
   reports **all PASS** for every cell — TiDB, Redis, Pulsar, Milvus, Nacos,
   Seata reachable.
3. k6 installed and verified (`k6 version`) on all 10 load generator machines.
4. Test data seeded: users (≥1.2M), courses (≥10K), homework templates,
   enrollment rows. See `scripts/seed-loadtest-data.sh` (if present).
5. Grafana dashboards imported and snapshot retention configured.
6. On-call SRE notified of the load-test window.

---

## 3. Test Matrix

| Script                        | VUs / Connections    | Duration  | Target Service | Threshold                                         | Purpose                                  |
|-------------------------------|----------------------|-----------|----------------|---------------------------------------------------|------------------------------------------|
| `bff-smoke.js`                | 10 VU                | 30s       | BFF (8080)     | P95<500ms, err<1%                                 | Smoke validation per cell                |
| `bff-ramp.js`                 | ramp → 10K           | ~22min    | BFF (8080)     | P95<500ms, P99<2000ms, err<0.5%                   | Ramp ceiling / connection pool sizing    |
| `business-stress.js`          | ramp → 20K           | ~20min    | sp03 (8083)    | P99<3000ms                                        | Find breaking point of business service  |
| `ai-gateway-load.js`          | 500 VU               | 5min      | sp04-ai (8086) | P95<2000ms                                        | AI Gateway throughput / LLM cost ceiling |
| `itbackend-flow.js`           | 2000 VU              | 10min     | sp05 (8085)    | P95<1000ms, P99<3000ms                            | Homework→grading end-to-end flow         |
| `seckill-spike.js`            | ramp → 50K           | ~50s      | sp03 (8083)    | P99<5000ms                                        | Flash-sale spike survival                |
| `websocket-connect.js`        | 5000 WS              | 5min      | WS gateway     | connection stability                              | Long-lived WS connection stability       |
| `million-user-simulation.js`  | 100K × 10 nodes      | ~30min    | full platform  | 1M concurrent connections stable                 | 1M concurrent target (distributed)       |

All scripts live under `deploy/load-tests/k6/` and share configuration via
`deploy/load-tests/k6/config/common.js`.

---

## 4. Execution Steps

### 4.1 Per-cell smoke validation

Run `bff-smoke.js` against each cell to confirm basic connectivity before any
large-scale test:

```bash
# Per cell (repeat for cells 0-9, adjusting K6_BASE_URL)
K6_BASE_URL=http://cell0-bff:8080 k6 run deploy/load-tests/k6/bff-smoke.js
```

A failed smoke test on any cell blocks the distributed run — fix the cell or
exclude it from the test matrix.

### 4.2 Configure k6 distributed cluster (10 nodes)

Each of the 10 load generator nodes runs a segment of the total load using k6's
`--execution-segment` flag. Segments are expressed as `start/end` of the unit
interval `[0,1)`.

### 4.3 Distributed million-user-simulation

Target: 1,000,000 concurrent connections across 10 nodes (100K each).

```bash
# On node 0:
k6 run --execution-segment '0/10,1/10' deploy/load-tests/k6/million-user-simulation.js

# On node 1:
k6 run --execution-segment '1/10,2/10' deploy/load-tests/k6/million-user-simulation.js

# ... through node 9:
k6 run --execution-segment '9/10,10/10' deploy/load-tests/k6/million-user-simulation.js
```

Start all 10 nodes within a 30-second window (use `pdsh` or a parallel SSH
runner) so the ramp-up is synchronized.

### 4.4 Collect metrics

During and after the run, collect from Grafana/Prometheus:

- P95 and P99 latency per service
- Error rate (5xx + 4xx-as-errors)
- Achieved RPS
- CPU / Memory utilization per service pod
- TiDB connection pool utilization
- Redis connection pool + cluster state
- Pulsar consumer backlog
- Milvus query latency (for AI flows)

### 4.5 Save results

Persist run artifacts under a dated folder:

```
deploy/load-tests/results/YYYY-MM-DD/
  ├── summary.json          # k6 machine-readable summary
  ├── grafana-snapshot.png  # dashboard screenshot
  ├── metrics.csv           # exported Prometheus series
  └── runbook-checklist.md  # signed-off checklist
```

---

## 5. Expected Results

| Metric                          | Target           |
|---------------------------------|------------------|
| Browse page P95 latency         | < 200 ms         |
| Interact (submit/query) P95     | < 500 ms         |
| AI grading P95                  | < 2 s            |
| Overall error rate              | < 0.5%           |
| 1M concurrent connections       | Stable ≥ 10 min  |
| No service OOM / crash          | Yes              |
| TiDB connection pool            | < 80% utilized   |
| Redis cluster state             | `ok` throughout  |

---

## 6. Rollback Criteria

**Abort the load test immediately** if **any** of the following occur:

- Error rate > 1% sustained for > 60 s
- P99 latency > 5 s on any critical path
- Any service pod OOM-killed or crash-looping
- TiDB connection pool exhausted (available connections = 0)
- Redis cluster state degrades to `fail`
- Pulsar backlog grows unbounded (> 100K unacked messages)
- Shared infrastructure cluster shows degradation affecting other tenants
- On-call SRE declares an incident

To abort: `Ctrl-C` k6 on all load generator nodes (or `pkill -TERM k6`), then
verify service recovery via `scripts/live-integration-check.sh`.

---

## 7. Cost Estimate

Full 10-cell cluster + 10 load generator machines, running 24/7:

| Component                          | Estimated Monthly Cost (USD) |
|------------------------------------|------------------------------|
| 10-cell K8s cluster (~300 nodes)   | $60K – $110K                 |
| 10 k6 load generator machines      | $8K – $15K                   |
| Shared infra (TiDB/Redis/Pulsar/…) | $10K – $20K                  |
| Egress / cross-AZ traffic          | $2K – $5K                    |
| **Total**                          | **~$80K – $150K / month**    |

For non-production validation, a single-cell subset (~$8K–$15K/month) is
sufficient for `bff-smoke`, `bff-ramp`, and `ai-gateway-load`.

---

## 8. Safety Notes

- **Tenant isolation**: use a dedicated test tenant (`tenant_id=999`) to
  isolate load-test data from production traffic. Never run load tests against
  a tenant that serves real users.
- **Redis**: use DB 14–15 for load-test data. **Never** use DB 0 (production
  cache) or DB 1–3 (sessions). Flush DB 14–15 between runs:
  `redis-cli -n 14 FLUSHDB && redis-cli -n 15 FLUSHDB`.
- **TiDB**: use the `ep_loadtest` schema only. Clean up after each run:
  `DROP SCHEMA ep_loadtest CASCADE;` then re-seed.
- **Milvus**: use a dedicated collection prefix (`loadtest_*`); drop collections
  after the run to reclaim object storage.
- **Pulsar**: use the `persistent://loadtest/...` namespace; set a TTL of 1h so
  unconsumed messages expire automatically.
- **Monitor infra health continuously** during the test; abort if the shared
  cluster shows degradation (CPU > 90%, disk > 85%, etc.).
- **Rate-limit fallback**: if the platform's rate limiter trips during a spike
  test, that is expected behavior — record it as a passed spike-survival
  scenario, not a failure.
- **No production data writes**: all load-test payloads must use synthetic IDs
  (`loadtest-*`); never reuse production user IDs or course IDs.
