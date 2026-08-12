#!/usr/bin/env bash
# D-108: End-to-end smoke test — boots each Java service with the dev-local profile
# and checks its health endpoint. Zero external infrastructure required
# (no TiDB/Redis/Nacos/Pulsar/PG/Minio).
#
# Usage:
#   scripts/e2e-smoke-test.sh            # test all services
#   scripts/e2e-smoke-test.sh order-platform # test a single service (by name column)
#   TIMEOUT_SEC=120 scripts/e2e-smoke-test.sh
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
TIMEOUT_SEC="${TIMEOUT_SEC:-90}"   # max seconds to wait for a service to come UP
HEALTH_POLL_SEC="${HEALTH_POLL_SEC:-2}"

# Colors
if [[ -t 1 ]]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  YELLOW='\033[1;33m'
  CYAN='\033[0;36m'
  BOLD='\033[1m'
  NC='\033[0m'
else
  GREEN='' RED='' YELLOW='' CYAN='' BOLD='' NC=''
fi

# Services to test: name | module-dir (relative to project root) | health-port | health-path
# Notes:
#   - order-platform already uses H2 in its base application.yml (profile-agnostic).
#   - payment-platform has no DB and registry.address=N/A in base config — boots infra-free.
#   - knowledge-platform (sp07-*) services are omitted from the default list because several depend on
#     PG-specific JPA entities / Milvus / ES that may not start cleanly under H2.
#     Add them via EXTRA_SERVICES env or edit the list to extend coverage.
SERVICES=(
  "core-business|services/core-business|8083|/actuator/health"
  "order-platform|services/order-platform/order-platform-service|8084|/actuator/health"
  "payment-platform|services/payment-platform/payment-platform-service|8087|/actuator/health"
  "grading-service|services/grading-service|8085|/actuator/health"
  "portal-bff|services/portal-bff|8090|/actuator/health"
)

# Optional extra services via env (space-separated, same pipe format).
if [[ -n "${EXTRA_SERVICES:-}" ]]; then
  # shellcheck disable=SC2206
  SERVICES+=(${EXTRA_SERVICES})
fi

# Allow filtering to a single service by name.
FILTER="${1:-}"

PASS=0
FAIL=0
SKIP=0

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
log()  { printf '%b\n' "$*"; }
ok()   { printf '%b%s%b\n' "${GREEN}" "$*" "${NC}"; }
err()  { printf '%b%s%b\n' "${RED}" "$*" "${NC} }" >&2; }
warn() { printf '%b%s%b\n' "${YELLOW}" "$*" "${NC}"; }

# Wait for http://127.0.0.1:PORT/PATH to return HTTP 200 with status UP.
# Returns 0 on success, 1 on timeout/error.
wait_for_health() {
  local port="$1"
  local path="${2#/}"   # strip leading slash
  local url="http://127.0.0.1:${port}/${path}"
  local deadline=$(( $(date +%s) + TIMEOUT_SEC ))
  local now code body

  while true; do
    now=$(date +%s)
    if (( now >= deadline )); then
      return 1
    fi
    # -s silent, -S show errors, -m max-time per attempt, --fail treat 5xx/4xx as failure.
    body="$(curl -sS -m "${HEALTH_POLL_SEC}" --fail "$url" 2>/dev/null || true)"
    code=$?
    if (( code == 0 )); then
      # Some actuators return 200 even when status=DOWN; check the payload.
      if grep -q '"status":"UP"' <<<"$body" 2>/dev/null || \
         grep -q '"status": "UP"' <<<"$body" 2>/dev/null; then
        return 0
      fi
    fi
    sleep "${HEALTH_POLL_SEC}"
  done
}

# Kill a backgrounded mvn spring-boot:run and any java child it spawned.
cleanup_pid() {
  local mvn_pid="$1"
  if [[ -z "${mvn_pid}" ]] || ! kill -0 "${mvn_pid}" 2>/dev/null; then
    return
  fi
  # Send SIGTERM to the process group first (mvn + its java child).
  kill -TERM "${mvn_pid}" 2>/dev/null || true
  # Give it a moment, then SIGKILL if still alive.
  for _ in 1 2 3 4 5; do
    kill -0 "${mvn_pid}" 2>/dev/null || break
    sleep 0.5
  done
  kill -KILL "${mvn_pid}" 2>/dev/null || true
}

test_service() {
  local name="$1"
  local module="$2"
  local port="$3"
  local path="${4:-/actuator/health}"

  log ""
  printf '%b--- %s (%s) -> 127.0.0.1:%s%s%b\n' "${BOLD}${CYAN}" "${name}" "${module}" "${port}" "${path}" "${NC}"

  if [[ ! -d "${PROJECT_ROOT}/${module}" ]]; then
    warn "  [SKIP] module dir not found: ${module}"
    SKIP=$((SKIP + 1))
    return
  fi

  log "  starting mvn spring-boot:run -Dspring-boot.run.profiles=dev-local ..."

  # Run from the module dir; capture stderr+stdout to a per-service log file.
  local log_file="${PROJECT_ROOT}/scripts/.e2e-${name}.log"
  : > "${log_file}"

  # Background the build. setsid (if available) creates a new process group so
  # we can reliably tear down the java child alongside mvn.
  (
    cd "${PROJECT_ROOT}/${module}"
    if command -v setsid >/dev/null 2>&1; then
      exec setsid mvn -q spring-boot:run -Dspring-boot.run.profiles=dev-local
    else
      exec mvn -q spring-boot:run -Dspring-boot.run.profiles=dev-local
    fi
  ) >>"${log_file}" 2>&1 &
  local mvn_pid=$!

  if wait_for_health "${port}" "${path}"; then
    ok "  [PASS] ${name} is UP (health returned 200/UP within ${TIMEOUT_SEC}s)"
    PASS=$((PASS + 1))
  else
    err "  [FAIL] ${name} did not become healthy within ${TIMEOUT_SEC}s"
    err "  tail of log (${log_file}):"
    tail -n 30 "${log_file}" | sed 's/^/    /' >&2 || true
    FAIL=$((FAIL + 1))
  fi

  cleanup_pid "${mvn_pid}"
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
echo "=========================================="
echo "  E2E Smoke Test (dev-local profiles)"
echo "=========================================="
echo "  timeout per service : ${TIMEOUT_SEC}s"
echo "  health poll interval: ${HEALTH_POLL_SEC}s"
echo "  project root        : ${PROJECT_ROOT}"
if [[ -n "${FILTER}" ]]; then
  echo "  filter              : ${FILTER}"
fi
echo "=========================================="

for entry in "${SERVICES[@]}"; do
  # Split on '|' into name|module|port|path.
  IFS='|' read -r name module port path <<<"${entry}"
  [[ -z "${path}" ]] && path="/actuator/health"

  if [[ -n "${FILTER}" && "${name}" != "${FILTER}" ]]; then
    continue
  fi

  test_service "${name}" "${module}" "${port}" "${path}"
done

echo ""
echo "=========================================="
printf '  %bPASS%b: %d   %bFAIL%b: %d   %bSKIP%b: %d\n' \
  "${GREEN}"  "${NC}" "${PASS}" \
  "${RED}"    "${NC}" "${FAIL}" \
  "${YELLOW}" "${NC}" "${SKIP}"
echo "=========================================="

if (( FAIL > 0 )); then
  exit 1
fi
exit 0
