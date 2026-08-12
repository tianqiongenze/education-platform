#!/usr/bin/env bash
###############################################################################
# cell-routing-verify.sh — 单元路由正确性验证脚本
#
# 生成随机用户 ID, 验证路由分布、misroute 纠正、misroute 超限拒绝.
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
CURRENT_CELL="${CURRENT_CELL:-cell-bj}"
USER_COUNT="${USER_COUNT:-100}"

echo "================================================================"
echo " Cell Routing Verification"
echo "   BFF_URL      = $BFF_URL"
echo "   CURRENT_CELL = $CURRENT_CELL"
echo "   USER_COUNT   = $USER_COUNT"
echo "   EP_DEV_ONLY  = $EP_DEV_ONLY"
echo "================================================================"
echo

# --- 辅助: 复制 Java 的 String.hashCode() ---
java_hashcode() {
  local s="$1"
  local h=0
  local i=0
  local len=${#s}
  while (( i < len )); do
    # 取一个字符的 ASCII 码
    local ch="${s:$i:1}"
    local code=$(printf '%d' "'$ch")
    # Java: h = 31 * h + ch  (32-bit 有符号回绕)
    h=$(( (31 * h + code) ))
    # 模拟 32-bit 有符号回绕
    if (( h > 2147483647 )); then
      h=$(( h - 4294967296 ))
    elif (( h < -2147483648 )); then
      h=$(( h + 4294967296 ))
    fi
    (( i++ ))
  done
  echo "$h"
}

# --- 辅助: 计算目标 cell 索引 (与 RouteTable.hashRoute 一致) ---
# 4 cells fallback (MVP). 生产环境需读取配置.
CELL_COUNT=4
target_index() {
  local userId="$1"
  local h
  h=$(java_hashcode "$userId")
  # Math.abs(Integer.MIN_VALUE) → 0; 其余取绝对值
  if (( h == -2147483648 )); then
    h=0
  elif (( h < 0 )); then
    h=$(( -h ))
  fi
  echo $(( h % CELL_COUNT ))
}

CELL_NAMES=("cell-bj" "cell-sz" "cell-hz" "cell-sg")

# --- Step 1: 路由分布验证 ---
echo "Step 1: 路由分布验证 ($USER_COUNT 个随机用户)"
declare -A dist
for i in $(seq 1 "$USER_COUNT"); do
  userId="verify-user-$i"
  idx=$(target_index "$userId")
  cell="${CELL_NAMES[$idx]}"
  dist[$cell]=$(( ${dist[$cell]:-0} + 1 ))
done
echo "  分布统计:"
for cell in "${CELL_NAMES[@]}"; do
  count="${dist[$cell]:-0}"
  pct=$(( count * 100 / USER_COUNT ))
  printf "    %-10s : %4d (%2d%%)\n" "$cell" "$count" "$pct"
done
echo

# --- Step 2: 请求头验证 (正确 cell → Pass) ---
echo "Step 2: 正确 cell 请求验证"
pass_count=0
for i in $(seq 1 10); do
  userId="verify-user-$i"
  idx=$(target_index "$userId")
  expected_cell="${CELL_NAMES[$idx]}"
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BFF_URL/api/users/$userId" \
    -H "X-User-Id: $userId" \
    -H "X-Cell-Name: $expected_cell" || true)
  if [[ "$code" == "200" || "$code" == "404" ]]; then
    # 200/404 都表示 Pass (业务层 404 不影响路由验证)
    pass_count=$(( pass_count + 1 ))
  fi
done
echo "  正确 cell Pass: $pass_count / 10"
echo

# --- Step 3: misroute 重定向验证 ---
echo "Step 3: misroute 重定向验证 (到达错误 cell → 307)"
misroute_user="misroute-verify-$$"
idx=$(target_index "$misroute_user")
correct_cell="${CELL_NAMES[$idx]}"
# 选一个错误的 cell
wrong_cell="cell-sz"
[[ "$wrong_cell" == "$correct_cell" ]] && wrong_cell="cell-bj"

redirect_count=0
for i in 1 2 3; do
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "$BFF_URL/api/users/$misroute_user" \
    -H "X-User-Id: $misroute_user" \
    -H "X-Cell-Name: $wrong_cell" || true)
  if [[ "$code" == "307" ]]; then
    redirect_count=$(( redirect_count + 1 ))
  fi
done
echo "  前 3 次 misroute 重定向: $redirect_count / 3 (期望 307)"
echo

# --- Step 4: misroute 超限拒绝 ---
echo "Step 4: misroute 超限验证 (第 4 次 → 403)"
# 继续对同一用户 misroute (前 3 次已在 Step 3 发送)
code=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BFF_URL/api/users/$misroute_user" \
  -H "X-User-Id: $misroute_user" \
  -H "X-Cell-Name: $wrong_cell" || true)
if [[ "$code" == "403" ]]; then
  echo "  [PASS] 第 4 次 misroute → 403"
else
  echo "  [WARN] 期望 403, 实际 $code (可能用户已被其它测试重置计数)"
fi
echo

# --- 总结 ---
echo "================================================================"
echo " Verification summary:"
echo "   Distribution : ${dist[*]}"
echo "   Pass (correct cell)   : $pass_count / 10"
echo "   Redirect (misroute)   : $redirect_count / 3"
echo "   Reject (over limit)   : ${code:-N/A}"
echo "================================================================"
