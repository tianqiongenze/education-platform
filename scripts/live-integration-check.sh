#!/usr/bin/env bash
# live-integration-check.sh
# Check whether live infrastructure is reachable for live integration tests.
#
# Verifies:
#   1. kubectl is installed and authenticated
#   2. Each infra service pod is Running: TiDB, Redis, Pulsar, Milvus, Nacos, Seata
#   3. (optional) Port-forward connectivity to localhost ports
#
# Usage:
#   bash scripts/live-integration-check.sh            # pod-state checks only
#   bash scripts/live-integration-check.sh --port-forward  # also probe localhost ports
#
# Exit codes:
#   0  all checks PASS
#   1  one or more checks FAIL
#
# Output: colored (green PASS / red FAIL). Falls back to plain text if not a TTY.

set -u

# ---- color helpers ---------------------------------------------------------
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  YELLOW='\033[0;33m'
  BOLD='\033[1m'
  NC='\033[0m' # no color
else
  GREEN='' RED='' YELLOW='' BOLD='' NC=''
fi

NS_INFRA="${EP_NS_INFRA:-ekb-m2-aws-us-east-1}"
NS_APP="${EP_NS_APP:-esp-prod}"

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0
CHECK_PORT_FORWARD=0

for arg in "$@"; do
  case "$arg" in
    --port-forward) CHECK_PORT_FORWARD=1 ;;
    -h|--help)
      echo "Usage: $0 [--port-forward]"
      echo "  --port-forward  also probe localhost port-forward connectivity"
      exit 0
      ;;
  esac
done

# ---- helpers ---------------------------------------------------------------
pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf "${GREEN}[PASS]${NC} %s — %s\n" "$1" "$2"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  printf "${RED}[FAIL]${NC} %s — %s\n" "$1" "$2"
}

warn() {
  WARN_COUNT=$((WARN_COUNT + 1))
  printf "${YELLOW}[WARN]${NC} %s — %s\n" "$1" "$2"
}

info() {
  printf "${BOLD}[INFO]${NC} %s\n" "$1"
}

# Check that a deployment has >=1 ready replica in a namespace.
# Usage: check_deploy <label> <namespace> <deployment-name-or-selector>
check_deploy() {
  local label="$1" ns="$2" selector="$3"
  local ready
  ready=$(kubectl get deploy -n "$ns" "$selector" \
            -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
  if [ -z "$ready" ] || [ "$ready" = "0" ]; then
    fail "$label" "no ready replicas (ns=$ns, deploy=$selector)"
    return 1
  fi
  pass "$label" "readyReplicas=$ready (ns=$ns)"
  return 0
}

# Check that at least one pod matching a label selector is Running.
# Usage: check_pod <label> <namespace> <label-selector>
check_pod() {
  local label="$1" ns="$2" selector="$3"
  local count
  count=$(kubectl get pods -n "$ns" -l "$selector" \
            -o jsonpath='{range .items[*]}{.status.phase}{end}' 2>/dev/null \
            | tr -d ' ' | grep -c Running || true)
  if [ "$count" -lt 1 ]; then
    fail "$label" "no Running pods (ns=$ns, selector=$selector)"
    return 1
  fi
  pass "$label" "runningPods=$count (ns=$ns)"
  return 0
}

# Probe a localhost TCP port (for port-forward verification).
# Usage: check_port <label> <host> <port>
check_port() {
  local label="$1" host="$2" port="$3"
  if command -v nc >/dev/null 2>&1; then
    if nc -z -w3 "$host" "$port" 2>/dev/null; then
      pass "$label" "$host:$port open"
    else
      fail "$label" "$host:$port closed"
    fi
  elif command -v curl >/dev/null 2>&1; then
    if curl -s -o /dev/null --connect-timeout 3 "http://$host:$port/" 2>/dev/null; then
      pass "$label" "$host:$port responds"
    else
      fail "$label" "$host:$port unreachable"
    fi
  else
    warn "$label" "no nc/curl available to probe $host:$port"
  fi
}

# ---- banner ----------------------------------------------------------------
echo "============================================================"
echo " Live Integration Connectivity Check — $(date -u +%FT%TZ)"
echo " Infra namespace: $NS_INFRA"
echo " App namespace:   $NS_APP"
echo " Port-forward:    $([ "$CHECK_PORT_FORWARD" = "1" ] && echo yes || echo no)"
echo "============================================================"

# ---- 1. kubectl availability ----------------------------------------------
echo
info "## 1. kubectl"
if ! command -v kubectl >/dev/null 2>&1; then
  fail "kubectl installed" "kubectl binary not found in PATH"
  echo
  echo "============================================================"
  echo " Summary: PASS=$PASS_COUNT  FAIL=$FAIL_COUNT  WARN=$WARN_COUNT"
  echo "============================================================"
  exit 1
fi
pass "kubectl installed" "$(kubectl version --client --short 2>/dev/null || kubectl version --client 2>/dev/null | head -1)"

if ! kubectl get ns >/dev/null 2>&1; then
  fail "kubectl authenticated" "cannot list namespaces; check kubeconfig/context"
  echo
  echo "============================================================"
  echo " Summary: PASS=$PASS_COUNT  FAIL=$FAIL_COUNT  WARN=$WARN_COUNT"
  echo "============================================================"
  exit 1
fi
pass "kubectl authenticated" "namespace list OK"
info "Cluster nodes: $(kubectl get nodes -o jsonpath='{.items[*].status.nodeInfo.kubeletVersion}' 2>/dev/null)"

# ---- 2. Infra service pods -------------------------------------------------
echo
info "## 2. Infrastructure service pods (Running check)"

# TiDB — typically a StatefulSet or Deployment under tidb-cluster
TIDB_READY=0
if kubectl get pods -n "$NS_INFRA" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | grep -q "tidb"; then
  TIDB_RUNNING=$(kubectl get pods -n "$NS_INFRA" \
    -o jsonpath='{range .items[?(@.metadata.name~"^tidb")]}{.status.phase} {end}' 2>/dev/null \
    | tr -d ' ' | grep -c Running || true)
  if [ "$TIDB_RUNNING" -ge 1 ]; then
    pass "TiDB" "$TIDB_RUNNING Running pod(s) (ns=$NS_INFRA)"
    TIDB_READY=1
  else
    fail "TiDB" "no Running pods matching ^tidb (ns=$NS_INFRA)"
  fi
else
  fail "TiDB" "no pods matching ^tidb found (ns=$NS_INFRA)"
fi

# Redis
REDIS_READY=0
REDIS_SELECTOR="app.kubernetes.io/name=redis-cluster-new"
if kubectl get pods -n "$NS_INFRA" -l "$REDIS_SELECTOR" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null | grep -q .; then
  if check_pod "Redis" "$NS_INFRA" "$REDIS_SELECTOR"; then
    REDIS_READY=1
  fi
else
  # Fallback: match by name prefix
  REDIS_RUNNING=$(kubectl get pods -n "$NS_INFRA" \
    -o jsonpath='{range .items[?(@.metadata.name~"^redis")]}{.status.phase} {end}' 2>/dev/null \
    | tr -d ' ' | grep -c Running || true)
  if [ "$REDIS_RUNNING" -ge 1 ]; then
    pass "Redis" "$REDIS_RUNNING Running pod(s) (ns=$NS_INFRA)"
    REDIS_READY=1
  else
    fail "Redis" "no Running pods matching ^redis (ns=$NS_INFRA)"
  fi
fi

# Pulsar
PULSAR_READY=0
PULSAR_RUNNING=$(kubectl get pods -n "$NS_INFRA" \
  -o jsonpath='{range .items[?(@.metadata.name~"^pulsar-broker")]}{.status.phase} {end}' 2>/dev/null \
  | tr -d ' ' | grep -c Running || true)
if [ "$PULSAR_RUNNING" -ge 1 ]; then
  pass "Pulsar" "$PULSAR_RUNNING Running broker pod(s) (ns=$NS_INFRA)"
  PULSAR_READY=1
else
  fail "Pulsar" "no Running pods matching ^pulsar-broker (ns=$NS_INFRA)"
fi

# Milvus
MILVUS_READY=0
MILVUS_REPLICAS=$(kubectl get deploy milvus -n "$NS_INFRA" \
                    -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
if [ -z "$MILVUS_REPLICAS" ]; then
  MILVUS_REPLICAS=0
fi
if [ "$MILVUS_REPLICAS" -ge 1 ]; then
  pass "Milvus" "readyReplicas=$MILVUS_REPLICAS (ns=$NS_INFRA)"
  MILVUS_READY=1
else
  fail "Milvus" "deployment scaled to 0 / no ready replicas (ns=$NS_INFRA)"
fi

# Nacos (lives in app namespace)
NACOS_READY=0
NACOS_RUNNING=$(kubectl get pods -n "$NS_APP" \
  -o jsonpath='{range .items[?(@.metadata.name~"^nacos")]}{.status.phase} {end}' 2>/dev/null \
  | tr -d ' ' | grep -c Running || true)
if [ "$NACOS_RUNNING" -ge 1 ]; then
  pass "Nacos" "$NACOS_RUNNING Running pod(s) (ns=$NS_APP)"
  NACOS_READY=1
else
  fail "Nacos" "no Running pods matching ^nacos (ns=$NS_APP)"
fi

# Seata (lives in app namespace)
SEATA_READY=0
SEATA_RUNNING=$(kubectl get pods -n "$NS_APP" \
  -o jsonpath='{range .items[?(@.metadata.name~"^seata")]}{.status.phase} {end}' 2>/dev/null \
  | tr -d ' ' | grep -c Running || true)
if [ "$SEATA_RUNNING" -ge 1 ]; then
  pass "Seata" "$SEATA_RUNNING Running pod(s) (ns=$NS_APP)"
  SEATA_READY=1
else
  fail "Seata" "no Running pods matching ^seata (ns=$NS_APP)"
fi

# ---- 3. (optional) Port-forward connectivity ------------------------------
if [ "$CHECK_PORT_FORWARD" = "1" ]; then
  echo
  info "## 3. Port-forward connectivity (localhost)"

  # Each port should be opened via `kubectl port-forward` in a separate terminal.
  check_port "TiDB 4000"      localhost 4000
  check_port "Redis 6379"     localhost 6379
  check_port "Pulsar 6650"    localhost 6650
  check_port "Milvus 19530"   localhost 19530
  check_port "Nacos 8848"     localhost 8848
  check_port "Seata 7091"     localhost 7091
  check_port "ai-platform 8086"   localhost 8086
  check_port "BFF 8080"       localhost 8080
else
  echo
  info "## 3. Port-forward connectivity — skipped (pass --port-forward to enable)"
fi

# ---- summary ---------------------------------------------------------------
echo
echo "============================================================"
printf " Summary: ${GREEN}PASS=$PASS_COUNT${NC}  ${RED}FAIL=$FAIL_COUNT${NC}  ${YELLOW}WARN=$WARN_COUNT${NC}\n"
echo "============================================================"
echo
echo " Infra readiness:"
echo "   TiDB=$TIDB_READY  Redis=$REDIS_READY  Pulsar=$PULSAR_READY  Milvus=$MILVUS_READY  Nacos=$NACOS_READY  Seata=$SEATA_READY"
echo

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
