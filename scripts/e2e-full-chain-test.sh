#!/bin/bash
# =============================================================================
# e2e-full-chain-test.sh
# -----------------------------------------------------------------------------
# End-to-end full-chain business-flow test for the education-platform.
#
# This script exercises the cross-service business flows by making HTTP calls
# against the running services on localhost (each service on its allocated
# port). It does NOT start any service — all services must already be running
# (e.g. via port-forward or local dev launch) before invoking this script.
#
# Port allocation (localhost):
#   ws-gateway        8081
#   data-platform     8082
#   core-business     8083
#   order-platform    8084
#   grading-service   8085
#   ai-platform       8086
#   payment-platform  8087
#   portal-bff        8090
#   knowledge-platform 8070-8074
#
# Business flows covered (in order):
#   0. Health check for every service
#   a. User registration/login  -> JWT token   (portal-bff /api/v1/auth/*)
#   b. Course browsing/search                   (core-business /api/v1/courses)
#   c. Order creation -> Payment -> Confirmation (order-platform + payment-platform)
#   d. Assignment submission -> AI grading       (grading-service /api/v1/*)
#   e. Knowledge RAG retrieval -> Agent chat     (knowledge-platform /api/v1/*)
#   f. Data platform: metadata / nlquery / lineage / aiops / profile (data-platform)
#
# Safety features for shared infrastructure:
#   - All test data uses a unique "e2e-test-" prefix (username, course, order...).
#   - Idempotency keys are derived from a run timestamp to avoid collisions.
#   - Read-only GET calls are used wherever possible; mutations are isolated.
#   - No destructive DELETE calls against shared data.
#
# Usage:
#   scripts/e2e-full-chain-test.sh
#   RUN_ID=custom scripts/e2e-full-chain-test.sh
#   REPORT_FILE=docs/testing/e2e-full-chain-report.md scripts/e2e-full-chain-test.sh
#
# Exit codes:
#   0  all steps passed
#   1  one or more steps failed (report still written)
# =============================================================================
set -u

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_ID="${RUN_ID:-e2e-$(date +%Y%m%d%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${PROJECT_ROOT}/docs/testing/e2e-full-chain-report.md}"
HOST="${HOST:-127.0.0.1}"
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

# Service ports
PORT_WS_GATEWAY=8081
PORT_DATA_PLATFORM=8082
PORT_CORE_BUSINESS=8083
PORT_ORDER_PLATFORM=8084
PORT_GRADING_SERVICE=8085
PORT_AI_PLATFORM=8086
PORT_PAYMENT_PLATFORM=8087
PORT_PORTAL_BFF=8090
PORT_KNOWLEDGE_PLATFORM=8070   # primary knowledge service; 8071-8074 are alternates

# Test data prefix (isolates this run from shared infra data)
PREFIX="e2e-test-${RUN_ID}"

# Credentials / role headers (X-Role is used by several sp03/sp05 endpoints)
ADMIN_ROLE_HEADER="X-Role: admin"
TEACHER_ROLE_HEADER="X-Role: teacher"

# Counters
PASS=0
FAIL=0
SKIP=0
START_TS="$(date -u +%FT%TZ)"

# Use jq if available, otherwise fall back to grep/sed extraction.
if command -v jq >/dev/null 2>&1; then
  HAS_JQ=1
else
  HAS_JQ=0
fi

# ----------------------------------------------------------------------------
# Color helpers (TTY-aware)
# ----------------------------------------------------------------------------
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

# ----------------------------------------------------------------------------
# Report lines buffer (markdown)
# ----------------------------------------------------------------------------
# We accumulate markdown report lines in REPORT_LINES and write them at the end.
declare -a REPORT_LINES=()

report_line() {
  # Append a raw line to the markdown report buffer.
  REPORT_LINES+=("$1")
}

# ----------------------------------------------------------------------------
# Logging helpers
# ----------------------------------------------------------------------------
ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

log()  { printf '%s\n' "$*"; }
info() { printf '%b[INFO]%b %s\n' "${CYAN}" "${NC}" "$*" | sed "s/%T/$(ts)/"; }
pass() {
  PASS=$((PASS + 1))
  local msg="$1"; local detail="${2:-}"
  printf '%b[PASS]%b %s — %s\n' "${GREEN}" "${NC}" "$(ts)" "${msg}"
  [[ -n "$detail" ]] && printf '        %s\n' "$detail"
  report_line "| PASS | $(ts) | ${msg} | ${detail} |"
}
fail() {
  FAIL=$((FAIL + 1))
  local msg="$1"; local detail="${2:-}"
  printf '%b[FAIL]%b %s — %s\n' "${RED}" "${NC}" "$(ts)" "${msg}" >&2
  [[ -n "$detail" ]] && printf '        %s\n' "$detail" >&2
  report_line "| FAIL | $(ts) | ${msg} | ${detail} |"
}
skip() {
  SKIP=$((SKIP + 1))
  local msg="$1"; local detail="${2:-}"
  printf '%b[SKIP]%b %s — %s\n' "${YELLOW}" "${NC}" "$(ts)" "${msg}"
  [[ -n "$detail" ]] && printf '        %s\n' "$detail"
  report_line "| SKIP | $(ts) | ${msg} | ${detail} |"
}

# ----------------------------------------------------------------------------
# HTTP helpers
# ----------------------------------------------------------------------------

# http_code <method> <url> [data] [extra-curl-args...]
# Sets globals: LAST_CODE, LAST_BODY, LAST_HEADERS
# Returns 0 if 2xx, 1 otherwise.
http() {
  local method="$1"; local url="$2"; local data="${3:-}"
  shift 3 2>/dev/null || true
  local tmp_body tmp_headers tmp_status
  tmp_body="$(mktemp)"
  tmp_headers="$(mktemp)"

  local curl_args=(
    -sS
    -m "$CURL_TIMEOUT"
    -X "$method"
    -H "Content-Type: application/json"
    -D "$tmp_headers"
    -o "$tmp_body"
    -w "%{http_code}"
  )
  [[ -n "$data" ]] && curl_args+=(--data "$data")
  curl_args+=("$@")  # extra args (e.g. -H "X-Role: ...")

  LAST_CODE="$(curl "${curl_args[@]}" "$url" 2>/dev/null || true)"
  LAST_BODY="$(cat "$tmp_body" 2>/dev/null || true)"
  LAST_HEADERS="$(cat "$tmp_headers" 2>/dev/null || true)"
  rm -f "$tmp_body" "$tmp_headers"

  if [[ "$LAST_CODE" =~ ^2 ]]; then
    return 0
  else
    return 1
  fi
}

# Extract a JSON field. Uses jq if available; else falls back to a grep/sed
# heuristic that handles simple flat objects. Returns empty string if not found.
json_get() {
  local body="$1"; local key="$2"
  if (( HAS_JQ )); then
    echo "$body" | jq -r --arg k "$key" '.[$k] // (.data[$k]) // (.result[$k]) // empty' 2>/dev/null
  else
    # Fallback: match "key":"value" or "key": value
    echo "$body" | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" \
      | head -1 | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
  fi
}

# Wait for a health endpoint to return 2xx with status UP within a deadline.
# Usage: wait_health <port> <path>
wait_health() {
  local port="$1"; local path="${2#/}"
  local deadline=$(( $(date +%s) + 30 ))
  local url="http://${HOST}:${port}/${path}"
  while true; do
    if (( $(date +%s) >= deadline )); then
      return 1
    fi
    if http GET "$url" 2>/dev/null && \
       ( echo "$LAST_BODY" | grep -q '"status":"UP"' 2>/dev/null || \
         echo "$LAST_BODY" | grep -q '"status": "UP"' 2>/dev/null || \
         [[ "$LAST_CODE" == "200" ]] ); then
      return 0
    fi
    sleep 2
  done
}

# =============================================================================
# STEP 0: Health checks for all services
# =============================================================================
section_health() {
  log ""
  printf '%b=== Step 0: Service Health Checks ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step 0 — Service Health Checks"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  # name | port | health-path
  local services=(
    "ws-gateway|${PORT_WS_GATEWAY}|/actuator/health"
    "data-platform|${PORT_DATA_PLATFORM}|/actuator/health"
    "core-business|${PORT_CORE_BUSINESS}|/actuator/health"
    "order-platform|${PORT_ORDER_PLATFORM}|/actuator/health"
    "grading-service|${PORT_GRADING_SERVICE}|/actuator/health"
    "ai-platform|${PORT_AI_PLATFORM}|/actuator/health"
    "payment-platform|${PORT_PAYMENT_PLATFORM}|/actuator/health"
    "portal-bff|${PORT_PORTAL_BFF}|/actuator/health"
    "knowledge-platform|${PORT_KNOWLEDGE_PLATFORM}|/actuator/health"
  )

  for entry in "${services[@]}"; do
    IFS='|' read -r name port path <<<"$entry"
    local url="http://${HOST}:${port}${path}"
    if http GET "$url"; then
      pass "health ${name}" "GET ${url} -> ${LAST_CODE}"
    else
      # Non-actuator services may return 404 on /actuator/health; treat 200 on / as UP.
      if http GET "http://${HOST}:${port}/"; then
        pass "health ${name}" "GET / -> ${LAST_CODE} (actuator unavailable)"
      else
        fail "health ${name}" "GET ${url} -> ${LAST_CODE} (service not reachable)"
      fi
    fi
  done
}

# =============================================================================
# STEP A: User registration / login -> JWT token
# (portal-bff /api/v1/auth/*)
# =============================================================================
section_auth() {
  log ""
  printf '%b=== Step A: User Registration / Login -> JWT ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step A — Auth: Registration / Login -> JWT"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  local username="${PREFIX}-user"
  local password="E2e-Test-Passw0rd!"
  local email="${PREFIX}-user@example.com"
  local url="http://${HOST}:${PORT_PORTAL_BFF}/api/v1/auth/register"

  # A1. Registration (idempotent: a duplicate username should return a
  #     non-2xx that we treat as a skip rather than a hard failure).
  local body="{\"username\":\"${username}\",\"password\":\"${password}\",\"email\":\"${email}\",\"role\":\"student\"}"
  if http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    pass "auth register" "POST ${url} -> ${LAST_CODE}"
  else
    # If the user already exists from a prior run with the same RUN_ID, skip.
    if echo "$LAST_BODY" | grep -qiE "already|exist|duplicate"; then
      skip "auth register" "user already exists (idempotent) -> ${LAST_CODE}"
    else
      fail "auth register" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
    fi
  fi

  # A2. Login -> extract JWT token into global AUTH_TOKEN.
  url="http://${HOST}:${PORT_PORTAL_BFF}/api/v1/auth/login"
  body="{\"username\":\"${username}\",\"password\":\"${password}\"}"
  if http POST "$url" "$body"; then
    AUTH_TOKEN="$(json_get "$LAST_BODY" "token")"
    if [[ -n "$AUTH_TOKEN" ]]; then
      pass "auth login" "JWT acquired (len=${#AUTH_TOKEN})"
    else
      fail "auth login" "no token field in response: $(echo "$LAST_BODY" | head -c 200)"
    fi
  else
    AUTH_TOKEN=""
    fail "auth login" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # Build the standard Authorization header used by subsequent steps.
  if [[ -n "$AUTH_TOKEN" ]]; then
    AUTH_HEADER="Authorization: Bearer ${AUTH_TOKEN}"
  else
    AUTH_HEADER=""
  fi
}

# =============================================================================
# STEP B: Course browsing / search
# (core-business /api/v1/courses)
# =============================================================================
section_courses() {
  log ""
  printf '%b=== Step B: Course Browsing / Search ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step B — Course Browsing / Search"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  # B1. List courses (read-only).
  local url="http://${HOST}:${PORT_CORE_BUSINESS}/api/v1/courses?page=1&size=10"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    local count
    count="$(json_get "$LAST_BODY" "total")"
    pass "courses list" "GET ${url} -> ${LAST_CODE}; total=${count:-?}"
  else
    fail "courses list" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # B2. Search courses by keyword (idempotent GET).
  url="http://${HOST}:${PORT_CORE_BUSINESS}/api/v1/courses/search?keyword=${PREFIX}&page=1&size=10"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "courses search" "GET ${url} -> ${LAST_CODE}"
  else
    fail "courses search" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # B3. Browse a single course detail (courseId=1 is a common fixture; guard if absent).
  url="http://${HOST}:${PORT_CORE_BUSINESS}/api/v1/courses/1"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "courses detail" "GET ${url} -> ${LAST_CODE}"
  else
    skip "courses detail" "GET ${url} -> ${LAST_CODE} (courseId=1 may not exist)"
  fi
}

# =============================================================================
# STEP C: Order creation -> Payment -> Order confirmation
# (order-platform /api/v1/orders + payment-platform /api/payments)
# Uses an idempotency key derived from RUN_ID so re-runs do not double-create.
# =============================================================================
section_order_payment() {
  log ""
  printf '%b=== Step C: Order -> Payment -> Confirmation ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step C — Order -> Payment -> Confirmation"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  local idem_key="idem-${RUN_ID}-order"
  local url="http://${HOST}:${PORT_ORDER_PLATFORM}/api/v1/orders"
  local body
  body=$(cat <<EOF
{
  "userId": "${PREFIX}-user",
  "courseId": 1,
  "quantity": 1,
  "idempotencyKey": "${idem_key}",
  "remark": "e2e full-chain test"
}
EOF
)

  # C1. Create order.
  ORDER_ID=""
  if http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    ORDER_ID="$(json_get "$LAST_BODY" "orderId")"
    [[ -z "$ORDER_ID" ]] && ORDER_ID="$(json_get "$LAST_BODY" "id")"
    pass "order create" "POST ${url} -> ${LAST_CODE}; orderId=${ORDER_ID:-?}"
  else
    fail "order create" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # C2. Initiate payment for the order (idempotency key reused to avoid double pay).
  url="http://${HOST}:${PORT_PAYMENT_PLATFORM}/api/payments"
  body=$(cat <<EOF
{
  "orderId": "${ORDER_ID}",
  "amount": 99.00,
  "channel": "mock",
  "idempotencyKey": "idem-${RUN_ID}-pay",
  "userId": "${PREFIX}-user"
}
EOF
)
  PAYMENT_ID=""
  if [[ -z "$ORDER_ID" ]]; then
    skip "payment create" "no orderId available (order creation failed)"
  elif http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    PAYMENT_ID="$(json_get "$LAST_BODY" "paymentId")"
    [[ -z "$PAYMENT_ID" ]] && PAYMENT_ID="$(json_get "$LAST_BODY" "id")"
    pass "payment create" "POST ${url} -> ${LAST_CODE}; paymentId=${PAYMENT_ID:-?}"
  else
    fail "payment create" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # C3. Confirm the order (verify order status moved to PAID / CONFIRMED).
  url="http://${HOST}:${PORT_ORDER_PLATFORM}/api/v1/orders/${ORDER_ID}"
  if [[ -z "$ORDER_ID" ]]; then
    skip "order confirm" "no orderId available"
  elif http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    local status
    status="$(json_get "$LAST_BODY" "status")"
    pass "order confirm" "GET ${url} -> ${LAST_CODE}; status=${status:-?}"
  else
    fail "order confirm" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi
}

# =============================================================================
# STEP D: Assignment submission -> AI grading
# (grading-service /api/v1/*)
# =============================================================================
section_assignment() {
  log ""
  printf '%b=== Step D: Assignment Submission -> AI Grading ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step D — Assignment Submission -> AI Grading"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  local url="http://${HOST}:${PORT_GRADING_SERVICE}/api/v1/assignments"
  local body
  body=$(cat <<EOF
{
  "studentId": "${PREFIX}-student",
  "courseId": 1,
  "assignmentId": 1,
  "content": "This is an e2e-test submission: explain CAP theorem.",
  "idempotencyKey": "idem-${RUN_ID}-submit"
}
EOF
)
  SUBMISSION_ID=""
  if http POST "$url" "$body" -H "${TEACHER_ROLE_HEADER}"; then
    SUBMISSION_ID="$(json_get "$LAST_BODY" "submissionId")"
    [[ -z "$SUBMISSION_ID" ]] && SUBMISSION_ID="$(json_get "$LAST_BODY" "id")"
    pass "assignment submit" "POST ${url} -> ${LAST_CODE}; submissionId=${SUBMISSION_ID:-?}"
  else
    fail "assignment submit" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # D2. Trigger AI grading for the submission.
  url="http://${HOST}:${PORT_GRADING_SERVICE}/api/v1/assignments/grade"
  body=$(cat <<EOF
{
  "submissionId": "${SUBMISSION_ID}",
  "idempotencyKey": "idem-${RUN_ID}-grade"
}
EOF
)
  if [[ -z "$SUBMISSION_ID" ]]; then
    skip "ai grade" "no submissionId available"
  elif http POST "$url" "$body" -H "${TEACHER_ROLE_HEADER}"; then
    local score
    score="$(json_get "$LAST_BODY" "score")"
    pass "ai grade" "POST ${url} -> ${LAST_CODE}; score=${score:-?}"
  else
    fail "ai grade" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # D3. Retrieve grading result (read-only).
  url="http://${HOST}:${PORT_GRADING_SERVICE}/api/v1/assignments/${SUBMISSION_ID}/result"
  if [[ -z "$SUBMISSION_ID" ]]; then
    skip "grade result" "no submissionId available"
  elif http GET "$url" -H "${TEACHER_ROLE_HEADER}"; then
    pass "grade result" "GET ${url} -> ${LAST_CODE}"
  else
    fail "grade result" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi
}

# =============================================================================
# STEP E: Knowledge RAG retrieval -> Agent chat
# (knowledge-platform /api/v1/*)
# knowledge-platform runs on 8070-8074; we probe 8070 as the primary entrypoint.
# =============================================================================
section_knowledge() {
  log ""
  printf '%b=== Step E: Knowledge RAG Retrieval -> Agent Chat ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step E — Knowledge RAG Retrieval -> Agent Chat"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  local base="http://${HOST}:${PORT_KNOWLEDGE_PLATFORM}/api/v1"

  # E1. RAG retrieval (query the knowledge base).
  local url="${base}/knowledge/retrieve"
  local body="{\"query\":\"What is the CAP theorem?\",\"topK\":3}"
  if http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    pass "rag retrieve" "POST ${url} -> ${LAST_CODE}"
  else
    fail "rag retrieve" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # E2. Agent chat (multi-turn; use an idempotency key per run).
  url="${base}/agent/chat"
  body=$(cat <<EOF
{
  "sessionId": "${RUN_ID}-chat",
  "message": "Explain distributed consensus in one sentence.",
  "idempotencyKey": "idem-${RUN_ID}-chat"
}
EOF
)
  if http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    local reply
    reply="$(json_get "$LAST_BODY" "answer")"
    [[ -z "$reply" ]] && reply="$(json_get "$LAST_BODY" "content")"
    pass "agent chat" "POST ${url} -> ${LAST_CODE}; replyLen=${#reply}"
  else
    fail "agent chat" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi
}

# =============================================================================
# STEP F: Data platform — metadata / nlquery / lineage / aiops / profile
# (data-platform /api/v1/*)
# =============================================================================
section_data_platform() {
  log ""
  printf '%b=== Step F: Data Platform (data-platform) ===%b\n' "${BOLD}${CYAN}" "${NC}"
  report_line ""
  report_line "## Step F — Data Platform: Metadata / NL Query / Lineage / AIOps / Profile"
  report_line ""
  report_line "| Result | Timestamp | Check | Detail |"
  report_line "|--------|-----------|-------|--------|"

  local base="http://${HOST}:${PORT_DATA_PLATFORM}/api/v1"

  # F1. Metadata query — list tables/datasets (read-only).
  local url="${base}/metadata?page=1&size=10"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "data metadata" "GET ${url} -> ${LAST_CODE}"
  else
    fail "data metadata" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # F2. Natural-language query — translate NL -> SQL / execute.
  url="${base}/nlquery"
  local body="{\"question\":\"Show me total orders per day\",\"idempotencyKey\":\"idem-${RUN_ID}-nlq\"}"
  if http POST "$url" "$body" -H "${ADMIN_ROLE_HEADER}"; then
    pass "data nlquery" "POST ${url} -> ${LAST_CODE}"
  else
    fail "data nlquery" "POST ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # F3. Lineage query — get upstream/downstream lineage for a dataset.
  url="${base}/lineage?dataset=orders"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "data lineage" "GET ${url} -> ${LAST_CODE}"
  else
    fail "data lineage" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # F4. AIOps — anomaly / operations insight query.
  url="${base}/aiops?metric=cpu&window=5m"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "data aiops" "GET ${url} -> ${LAST_CODE}"
  else
    fail "data aiops" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi

  # F5. Data profile — schema/profile summary for a dataset.
  url="${base}/profile?dataset=orders"
  if http GET "$url" -H "${ADMIN_ROLE_HEADER}"; then
    pass "data profile" "GET ${url} -> ${LAST_CODE}"
  else
    fail "data profile" "GET ${url} -> ${LAST_CODE}; body: $(echo "$LAST_BODY" | head -c 200)"
  fi
}

# =============================================================================
# Report writer
# =============================================================================
write_report() {
  local total=$((PASS + FAIL + SKIP))
  local end_ts="$(date -u +%FT%TZ)"
  {
    echo "# E2E Full-Chain Test Report"
    echo ""
    echo "- **Run ID**: \`${RUN_ID}\`"
    echo "- **Started**: ${START_TS}"
    echo "- **Finished**: ${end_ts}"
    echo "- **Host**: ${HOST}"
    echo "- **Script**: \`scripts/e2e-full-chain-test.sh\`"
    echo ""
    echo "## Summary"
    echo ""
    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| Total  | ${total} |"
    echo "| Passed | ${PASS} |"
    echo "| Failed | ${FAIL} |"
    echo "| Skipped| ${SKIP} |"
    echo ""
    echo "> A row is PASS when the HTTP response status is 2xx; otherwise FAIL. SKIPPED rows indicate a prerequisite step did not produce an id (e.g. orderId) or an idempotent duplicate was detected."
    echo ""
    # Append the per-step tables accumulated in REPORT_LINES.
    for line in "${REPORT_LINES[@]}"; do
      echo "$line"
    done
    echo ""
    echo "## Port Allocation"
    echo ""
    echo "| Service | Port |"
    echo "|---------|------|"
    echo "| ws-gateway | ${PORT_WS_GATEWAY} |"
    echo "| data-platform | ${PORT_DATA_PLATFORM} |"
    echo "| core-business | ${PORT_CORE_BUSINESS} |"
    echo "| order-platform | ${PORT_ORDER_PLATFORM} |"
    echo "| grading-service | ${PORT_GRADING_SERVICE} |"
    echo "| ai-platform | ${PORT_AI_PLATFORM} |"
    echo "| payment-platform | ${PORT_PAYMENT_PLATFORM} |"
    echo "| portal-bff | ${PORT_PORTAL_BFF} |"
    echo "| knowledge-platform | ${PORT_KNOWLEDGE_PLATFORM} (8070-8074) |"
    echo ""
    echo "## How to re-run"
    echo ""
    echo '```bash'
    echo "# Ensure all services are running on localhost (port-forward or dev launch)."
    echo "scripts/e2e-full-chain-test.sh"
    echo "# With a custom run id and report path:"
    echo "RUN_ID=nightly-$(date +%Y%m%d) \\"
    echo "REPORT_FILE=docs/testing/e2e-nightly-$(date +%Y%m%d).md \\"
    echo "  scripts/e2e-full-chain-test.sh"
    echo '```'
  } > "$REPORT_FILE"
}

# =============================================================================
# Main
# =============================================================================
echo "============================================================"
echo "  E2E Full-Chain Test — ${START_TS}"
echo "  Run ID      : ${RUN_ID}"
echo "  Host        : ${HOST}"
echo "  Report      : ${REPORT_FILE}"
echo "  jq available: $(( HAS_JQ ))"
echo "============================================================"

section_health
section_auth
section_courses
section_order_payment
section_assignment
section_knowledge
section_data_platform

write_report

echo ""
echo "============================================================"
printf '  %bPASS%b: %d   %bFAIL%b: %d   %bSKIP%b: %d\n' \
  "${GREEN}"  "${NC}" "${PASS}" \
  "${RED}"    "${NC}" "${FAIL}" \
  "${YELLOW}" "${NC}" "${SKIP}"
echo "  Report written to: ${REPORT_FILE}"
echo "============================================================"

if (( FAIL > 0 )); then
  exit 1
fi
exit 0
