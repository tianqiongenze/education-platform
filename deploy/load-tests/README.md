# k6 Load Tests — Education Platform (SP-09 Scale Validation)

This directory contains production-quality [k6](https://k6.io/) load testing scripts
used to validate the education-platform's path to **1 million concurrent users**
and **500K QPS peak** under the SP-09 scale target (3 billion users total).

The platform uses a **cell-based architecture**: each cell handles ~100K concurrent
users, and 10 cells compose the 1M concurrent target. Several scripts below are
designed for **distributed k6 execution** so you can fan out across multiple
machines / cells.

## Directory Layout

```
deploy/load-tests/
├── README.md                       (this file)
└── k6/
    ├── config/
    │   └── common.js               shared utilities, URLs, login, custom metrics
    ├── bff-smoke.js                smoke test — 10 VUs, 30s
    ├── bff-ramp.js                 ramp to 10K concurrent VUs
    ├── business-stress.js          stress test to 20K VUs, find ceiling
    ├── ai-gateway-load.js          500 VUs, 5min, chat/embeddings mix
    ├── itbackend-flow.js           2000 VUs, homework assignment → grading flow
    ├── seckill-spike.js            50K VU flash-sale spike
    ├── websocket-connect.js        5000 concurrent WS connections
    └── million-user-simulation.js  distributed 1M concurrent simulation
```

## Installing k6

### macOS (Homebrew)
```bash
brew install k6
```

### Linux (apt)
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt update
sudo apt install k6
```

### Docker
```bash
docker pull grafana/k6:latest
docker run --rm -i grafana/k6 run - < k6/bff-smoke.js
```

### From source (for xk6 extensions like 50K+ VU seckill)
```bash
go install go.k6.io/xk6/cmd/xk6@latest
xk6 build --with github.com/grafana/xk6-...
```

Verify:
```bash
k6 version
```

## Running the Tests

All commands assume `cwd = deploy/load-tests/k6/`.

### Environment Variables
| Variable | Default | Description |
|---|---|---|
| `K6_BASE_URL` | `http://localhost:8080` | BFF base URL |
| `K6_BUSINESS_URL` | `http://localhost:8083` | Business service URL |
| `K6_ITBACKEND_URL` | `http://localhost:8085` | IT Backend URL |
| `K6_AIGW_URL` | `http://localhost:8086` | AI Gateway URL |
| `K6_WS_URL` | `ws://localhost:8081` | WS Gateway URL |
| `K6_TEST_USER` | `loadtest@education.com` | login username |
| `K6_TEST_PASSWORD` | `LoadTest#2026` | login password |
| `SECKILL_COURSE_ID` | `9999` | flash-sale course id |

### 1. Smoke Test
```bash
k6 run bff-smoke.js
```

### 2. BFF Ramp (10K concurrent)
```bash
k6 run bff-ramp.js
```

### 3. Business Stress (to 20K VUs)
```bash
k6 run business-stress.js
```

### 4. AI Gateway Load
```bash
k6 run ai-gateway-load.js
```

### 5. IT Backend Flow
```bash
k6 run itbackend-flow.js
```

### 6. Seckill Spike (50K VUs in 10s)
> Requires a beefy runner. Set `K6_GOMAXPROCS` and tune ulimits first.
```bash
ulimit -n 1048576
K6_GOMAXPROCS=16 k6 run seckill-spike.js
```

### 7. WebSocket Connect (5000 connections)
```bash
k6 run websocket-connect.js
```

### 8. Million-User Simulation (distributed)

The `million-user-simulation.js` script targets **100K VUs per instance**.
Run 10 instances in parallel to reach **1M concurrent**.

#### 3-node cluster example (each node ~33K VUs of the 100K-per-instance slice)
```bash
# Sequence: 0, 1/3, 2/3, 1
# Node 1
k6 run --execution-segment '0:1/3' \
       --execution-segment-sequence '0,1/3,2/3,1' \
       million-user-simulation.js

# Node 2
k6 run --execution-segment '1/3:2/3' \
       --execution-segment-sequence '0,1/3,2/3,1' \
       million-user-simulation.js

# Node 3
k6 run --execution-segment '2/3:1' \
       --execution-segment-sequence '0,1/3,2/3,1' \
       million-user-simulation.js
```

#### 10-node cluster (1M concurrent — full SP-09 target)
```bash
SEQ='0,1/10,2/10,3/10,4/10,5/10,6/10,7/10,8/10,9/10,1'
for i in 0 1 2 3 4 5 6 7 8 9; do
  k6 run --execution-segment "${i}/10:$((i+1))/10" \
         --execution-segment-sequence "$SEQ" \
         million-user-simulation.js > "node-${i}.log" 2>&1 &
done
wait
```

Each node should sit in (or proxy to) a different **cell** of the platform so
traffic is distributed across the cell-based architecture.

## Thresholds Explained

Each script declares `thresholds` that k6 evaluates at the end of the run.
A threshold failure marks the run as failed (non-zero exit code), making it
CI-friendly.

| Script | Key Thresholds |
|---|---|
| `bff-smoke.js` | `http_req_failed < 1%`, `p(95) < 500ms` |
| `bff-ramp.js` | `p(95) < 500ms`, `p(99) < 2000ms`, error `< 1%` |
| `business-stress.js` | `p(99) < 3000ms`, error `< 5%` |
| `ai-gateway-load.js` | `p(95) < 2000ms`, error `< 1%` |
| `itbackend-flow.js` | `p(95) < 1000ms`, `p(99) < 3000ms`, error `< 2%` |
| `seckill-spike.js` | error `< 10%` (seckill allows degradation), `p(99) < 5000ms` |
| `websocket-connect.js` | `ws_connect_failures < 100`, ping `p(95) < 500ms` |
| `million-user-simulation.js` | Browse `p(95) < 200ms` / `p(99) < 500ms`; Interact `p(95) < 500ms` / `p(99) < 2000ms`; error `< 0.5%` |

The million-user thresholds mirror the **SP-09 SLO** directly.

## Custom Metrics

Reusable metrics are defined in `config/common.js` and surfaced in each script:

- `orders_per_sec`, `orders_created_total`
- `idempotency_dedup_rate`
- `homework_submitted_per_sec`
- `grading_pipeline_latency`
- `spike_qps`
- `ws_connect_failures`, `ws_msg_per_sec`
- `login_latency`, `request_latency`, `error_rate`

The million-user script adds per-scenario dashboards: `browse_rps`,
`interact_rps`, `video_ws_rps`, `admin_rps`, plus error breakdown counters
(`error_breakdown_browse`, etc.) for a latency heatmap and error breakdown
view in Grafana.

## Grafana Dashboard Integration

k6 can stream metrics to Grafana Cloud / Prometheus via:

### Option A — k6 → Prometheus (remote write)
```bash
K6_PROMETHEUS_REMOTE_URL=http://<prometheus>:9090/api/v1/write \
k6 run --out experimental-prometheus-rw bff-ramp.js
```

### Option B — k6 → InfluxDB → Grafana
```bash
# Start InfluxDB + Grafana via docker-compose (see grafana/ dir if present)
k6 run --out influxdb=http://localhost:8086/k6 bff-ramp.js
```

Import the official **k6 Grafana dashboard** (ID `2587`) and tag-filter by
`scenario` to isolate each test type.

### Latency Heatmap
Use the `*_latency` Trend metrics with a heatmap panel (Prometheus
`histogram_quantile`) to visualize per-scenario latency distribution over the
ramp — the canonical "load curve" for capacity planning.

## k6 Cloud Execution

For runs beyond local capacity (e.g., the full 1M concurrent test), use
[Grafana Cloud k6](https://grafana.com/products/cloud/k6/):

```bash
# One-time auth
k6 login cloud --token <YOUR_K6_CLOUD_TOKEN>

# Run in the cloud (load zones distributed across regions)
k6 cloud \
  --env K6_BASE_URL=https://bff.example.com \
  --env K6_BUSINESS_URL=https://business.example.com \
  -e K6_TEST_USER=cloud@example.com \
  million-user-simulation.js
```

For the 1M test, define load zones matching your cell topology:

```bash
k6 cloud \
  --load-zones 'us-east-1:40000users,us-west-2:30000users,ap-southeast-1:30000users' \
  million-user-simulation.js
```

The distributed `--execution-segment` approach (above) is preferred when you
need deterministic per-cell traffic and have your own runners.

## CI Integration

All scripts exit non-zero on threshold breach, so they drop into CI cleanly:

```yaml
# .github/workflows/load-test.yml
- name: BFF smoke
  run: k6 run deploy/load-tests/k6/bff-smoke.js
```

Use the smoke test as a gate on every PR, and schedule the ramp/stress/million
tests nightly against staging.

## Troubleshooting

- **`dial tcp: connection refused`** — services aren't up; verify ports 8080/8083/8085/8086/8081.
- **`socket: too many open files`** — raise ulimits: `ulimit -n 1048576`.
- **50K VU seckill crashes k6** — use xk6 build and bump `K6_GOMAXPROCS`.
- **Login returns null token** — check `K6_TEST_USER` / `K6_TEST_PASSWORD` and that the BFF `/api/v1/auth/login` mock is enabled.
- **WS connections drop** — gateway-go may have a per-IP conn limit; raise it for the test source IPs.
