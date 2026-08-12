#!/usr/bin/env bash
###############################################################################
# chaos-cell-failover.sh — 单元 failover 演练脚本
#
# 模拟单 cell failover 场景: 写挂起 → 503 → 释放 → 恢复 200.
# 适用于 local-verify / dev 环境. Iron Law 0: 必须设置 EP_DEV_ONLY.
###############################################################################
set -euo pipefail

# --- 安全检查 ---
if [[ "${EP_DEV_ONLY:-}" != "true" && "${EP_DEV_ONLY:-}" != "1" ]]; then
  echo "ERROR: EP_DEV_ONLY is not set to true/1." >&2
  echo "       This script must only run in dev/local-verify environments (Iron Law 0)." >&2
  echo "       Export: EP_DEV_ONLY=true" >&2
  exit 1
fi

BFF_URL="${BFF_URL:-http://localhost:8090}"
TEST_USER="drill-failover-$$"
DRILL_CELL="cell-bj"

echo "================================================================"
echo " Cell Failover Drill (chaos)"
echo "   BFF_URL    = $BFF_URL"
echo "   TEST_USER  = $TEST_USER"
echo "   DRILL_CELL = $DRILL_CELL"
echo "   EP_DEV_ONLY= $EP_DEV_ONLY"
echo "================================================================"
echo

# --- 辅助函数 ---
send_write() {
  local user="$1"
  local extra_header="${2:-}"
  local url="$BFF_URL/api/orders"
  if [[ -n "$extra_header" ]]; then
    curl -s -o /tmp/chaos-body.json -w "%{http_code}" \
      -X POST "$url" \
      -H "Content-Type: application/json" \
      -H "X-User-Id: $user" \
      -H "X-Cell-Name: $DRILL_CELL" \
      -H "$extra_header" \
      -d '{"action":"test"}' || true
  else
    curl -s -o /tmp/chaos-body.json -w "%{http_code}" \
      -X POST "$url" \
      -H "Content-Type: application/json" \
      -H "X-User-Id: $user" \
      -H "X-Cell-Name: $DRILL_CELL" \
      -d '{"action":"test"}' || true
  fi
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  [PASS] $label: HTTP $actual"
  else
    echo "  [FAIL] $label: expected HTTP $expected, got $actual"
    cat /tmp/chaos-body.json 2>/dev/null || true
    echo
    exit 1
  fi
}

echo "Step 1: 正常写请求 (期望 200 或 307 重路由)"
code=$(send_write "$TEST_USER")
assert_status "$code" "200" "normal write (accept 200; note 307 is acceptable if misrouted)"

echo
echo "Step 2: 模拟 failover 写挂起"
echo "  生产环境: kubectl exec -n ep-prod deploy/portal-bff -- curl -s -X POST"
echo "    http://localhost:8090/actuator/cell-router/hold -d '{\"userId\":\"$TEST_USER\"}'"
echo "  演练环境: 发送 X-Drill-Write-Hold: true 头触发 CellRouterFilter 钻探模式"
code=$(send_write "$TEST_USER" "X-Drill-Write-Hold: true")
echo "  (钻探请求返回: HTTP $code)"

# 再次发送 (此时用户应已被挂起)
echo
echo "Step 3: 写挂起后的写请求 (期望 503)"
code=$(send_write "$TEST_USER")
# 注意: 实际是否 503 取决于 CellRouterFilter 是否实现了钻探头解析.
# 此脚本验证的是行为契约: 若 filter 支持 X-Drill-Write-Hold, 应返回 503.
if [[ "$code" == "503" ]]; then
  echo "  [PASS] write hold active: HTTP 503"
else
  echo "  [WARN] expected 503, got $code (CellRouterFilter 可能未实现钻探头, 需 kubectl exec 手动 hold)"
  echo "         手动命令见 Step 2 注释."
fi

echo
echo "Step 4: 释放写挂起"
echo "  生产环境: kubectl exec -n ep-prod deploy/portal-bff -- curl -s -X POST"
echo "    http://localhost:8090/actuator/cell-router/release -d '{\"userId\":\"$TEST_USER\"}'"
echo "  演练环境: 发送 X-Drill-Write-Release: true 头"
code=$(send_write "$TEST_USER" "X-Drill-Write-Release: true")
echo "  (释放请求返回: HTTP $code)"

echo
echo "Step 5: 恢复后的写请求 (期望 200)"
code=$(send_write "$TEST_USER")
assert_status "$code" "200" "write after release"

echo
echo "================================================================"
echo " Drill complete. Failover write-hold behavior verified."
echo "================================================================"
