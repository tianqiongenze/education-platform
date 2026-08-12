#!/usr/bin/env bash
# D-194: CockroachDB PoC readiness + verification script.
# Checks cluster health, runs the PoC verification items, and prints a summary.
# Usage: ./scripts/cockroachdb-poc.sh [--health-only]
#
# Environment:
#   CRDB_HOST   CockroachDB SQL host (default: cockroachdb-public)
#   CRDB_PORT   CockroachDB SQL port (default: 26257)
#   CRDB_USER   CockroachDB SQL user (default: root)
#   CRDB_DB     CockroachDB database (default: defaultdb)
#   CRDB_REGION Region to tag test rows (default: ap-southeast-1)
#   EP_CRDB_CDC_PULSAR_URI  Pulsar sink URI for changefeed test

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CRDB_HOST="${CRDB_HOST:-cockroachdb-public}"
CRDB_PORT="${CRDB_PORT:-26257}"
CRDB_USER="${CRDB_USER:-root}"
CRDB_DB="${CRDB_DB:-defaultdb}"
CRDB_REGION="${CRDB_REGION:-ap-southeast-1}"
HEALTH_ONLY=false

if [ "${1:-}" = "--health-only" ]; then
  HEALTH_ONLY=true
fi

# CockroachDB uses the PostgreSQL wire protocol.
PSQL_ARGS="-h ${CRDB_HOST} -p ${CRDB_PORT} -U ${CRDB_USER} -d ${CRDB_DB} --set=ON_ERROR_STOP=1"

echo "D-194 CockroachDB PoC"
echo "====================="
echo "Target: ${CRDB_HOST}:${CRDB_PORT}/${CRDB_DB} (region=${CRDB_REGION})"
echo ""

# --- 1. Prerequisite checks ---
if ! command -v psql >/dev/null 2>&1; then
  echo "❌ psql not found. Install PostgreSQL client (CockroachDB speaks the pg wire protocol)."
  exit 2
fi

# Check K8s CRD if kubectl is available
if command -v kubectl >/dev/null 2>&1; then
  echo "[1/5] Checking CockroachDB CRD..."
  if kubectl get crd crdbclusters.crdb.cockroachlabs.com >/dev/null 2>&1; then
    echo "  ✅ crdbclusters.crdb.cockroachlabs.com CRD installed"
  else
    echo "  ⚠️  CRD not found — apply deploy/k8s/base/cockroachdb-cluster.yaml first"
  fi
else
  echo "[1/5] kubectl not found, skipping CRD check"
fi

# --- 2. Cluster health ---
echo "[2/5] Checking CockroachDB connectivity..."
if psql "${PSQL_ARGS}" -c "SELECT version();" >/dev/null 2>&1; then
  VERSION=$(psql "${PSQL_ARGS}" -tAc "SELECT version();" 2>/dev/null | head -1)
  echo "  ✅ Connected: ${VERSION}"
else
  echo "  ❌ Cannot connect to CockroachDB at ${CRDB_HOST}:${CRDB_PORT}"
  echo "     Port-forward: kubectl port-forward svc/cockroachdb-public 26257:26257"
  exit 1
fi

if [ "${HEALTH_ONLY}" = true ]; then
  echo ""
  echo "Health-only mode: skipping PoC verification."
  exit 0
fi

# --- 3. REGIONAL BY ROW ---
echo "[3/5] Verifying REGIONAL BY ROW..."
psql "${PSQL_ARGS}" <<'SQL'
DROP TABLE IF EXISTS crdb_poc_regional;
CREATE TABLE crdb_poc_regional (
    id INT PRIMARY KEY,
    region crdb_internal_region NOT NULL,
    payload STRING
) LOCALITY REGIONAL BY ROW;
SQL

# Insert test rows
psql "${PSQL_ARGS}" -c "INSERT INTO crdb_poc_regional (id, region, payload) VALUES (1, 'ap-southeast-1', 'sg-row');" >/dev/null
psql "${PSQL_ARGS}" -c "INSERT INTO crdb_poc_regional (id, region, payload) VALUES (2, 'europe-west1', 'fra-row');" >/dev/null

SG_COUNT=$(psql "${PSQL_ARGS}" -tAc "SELECT count(*) FROM crdb_poc_regional WHERE region = 'ap-southeast-1';")
if [ "${SG_COUNT}" = "1" ]; then
  echo "  ✅ REGIONAL BY ROW: 1 row tagged ap-southeast-1 found"
else
  echo "  ❌ REGIONAL BY ROW: expected 1 sg row, got ${SG_COUNT}"
fi

# --- 4. Cross-region latency ---
echo "[4/5] Measuring cross-region write latency (20 samples)..."
TOTAL_MS=0
MAX_MS=0
for i in $(seq 1 20); do
  START=$(python3 -c 'import time; print(int(time.time()*1000))')
  psql "${PSQL_ARGS}" -c "INSERT INTO crdb_poc_regional (id, region, payload) VALUES ($((1000 + i)), 'europe-west1', 'probe-${i}');" >/dev/null
  END=$(python3 -c 'import time; print(int(time.time()*1000))')
  ELAPSED=$((END - START))
  TOTAL_MS=$((TOTAL_MS + ELAPSED))
  if [ "${ELAPSED}" -gt "${MAX_MS}" ]; then MAX_MS=${ELAPSED}; fi
done
echo "  Cross-region write: avg=$((TOTAL_MS / 20))ms, max(${MAX_MS}ms)≈P99"

# --- 5. Changefeeds + 1M connections (informational) ---
echo "[5/5] Checking changefeed + connection pool..."
PULSAR_URI="${EP_CRDB_CDC_PULSAR_URI:-pulsar://localhost:6650/persistent/public/default/crdb-cdc}"
if psql "${PSQL_ARGS}" -c "CREATE CHANGEFEED FOR TABLE crdb_poc_regional WITH format = json, sink = '${PULSAR_URI}';" >/dev/null 2>&1; then
  echo "  ✅ Changefeed created to ${PULSAR_URI}"
else
  echo "  ⚠️  Changefeed to ${PULSAR_URI} failed (sink adapter may not be configured)"
fi

POOL_SIZE=$(psql "${PSQL_ARGS}" -tAc "SHOW CLUSTER SETTING sql.pool.max_size;" 2>/dev/null | xargs)
if [ -n "${POOL_SIZE}" ]; then
  if [ "${POOL_SIZE}" -ge 1000 ]; then
    echo "  ✅ sql.pool.max_size=${POOL_SIZE} (>= 1000 baseline)"
  else
    echo "  ⚠️  sql.pool.max_size=${POOL_SIZE} (< 1000 baseline — tune for 1M-conn stress)"
  fi
else
  echo "  ⚠️  Could not read sql.pool.max_size"
fi

# Cleanup
psql "${PSQL_ARGS}" -c "DROP TABLE IF EXISTS crdb_poc_regional;" >/dev/null 2>&1 || true

echo ""
echo "D-194 CockroachDB PoC complete."
echo "See docs/adr/D-194-cockroachdb-poc-results.md for full analysis."
