#!/usr/bin/env bash
# port-forward-infra.sh
# Gap 1: 为 Java live integration tests 启动 K8s 基础设施 port-forward。
#
# 用法:
#   ./scripts/port-forward-infra.sh start   # 启动 (后台), PID 写入 /tmp/ep-port-forward-pids.txt
#   ./scripts/port-forward-infra.sh stop    # 停止所有由本脚本启动的 port-forward
#
# 覆盖基础设施:
#   - TiDB         4000  (MySQL 协议)  + 10080 (Status API)
#   - Redis        6379  (redis-cluster-new)
#   - Nacos        8848  (esp-prod)
#   - Seata TC     7091  (esp-prod)
#   - Milvus       19530 (gRPC 向量库, D-140 新增)
#   - Pulsar       6650  (消息 broker, D-140 新增)
#
# 环境变量:
#   EP_NAMESPACE_INFRA    共享集群基础设施命名空间 (默认 ekb-m2-aws-us-east-1)
#   EP_NAMESPACE_PLATFORM 平台中间件命名空间 (默认 esp-prod)
#
# 安全: 仅做只读 port-forward, 不修改/重启集群资源。
set -euo pipefail

PID_FILE="/tmp/ep-port-forward-pids.txt"
NAMESPACE_INFRA="${EP_NAMESPACE_INFRA:-ekb-m2-aws-us-east-1}"
NAMESPACE_PLATFORM="${EP_NAMESPACE_PLATFORM:-esp-prod}"

# 待 port-forward 的列表: local_port:cluster_port:namespace:service
PORT_FORWARDS=(
  "4000:4000:${NAMESPACE_INFRA}:tidb-cluster-tidb"
  "10080:10080:${NAMESPACE_INFRA}:tidb-cluster-tidb"
  "6379:6379:${NAMESPACE_INFRA}:redis-cluster-new"
  "8848:8848:${NAMESPACE_PLATFORM}:nacos"
  "7091:7091:${NAMESPACE_PLATFORM}:seata-tc-server"
  "19530:19530:${NAMESPACE_INFRA}:milvus"
  "6650:6650:${NAMESPACE_INFRA}:pulsar-broker"
)

port_in_use() {
  local port="$1"
  lsof -iTCP:"${port}" -sTCP:LISTEN -P -n >/dev/null 2>&1
}

start() {
  : > "${PID_FILE}"
  for entry in "${PORT_FORWARDS[@]}"; do
    IFS=':' read -r local_port cluster_port ns svc <<< "${entry}"
    if port_in_use "${local_port}"; then
      echo "[skip] 端口 ${local_port} 已被占用 (svc/${svc})"
      continue
    fi
    echo "[start] kubectl port-forward -n ${ns} svc/${svc} ${local_port}:${cluster_port}"
    kubectl port-forward -n "${ns}" "svc/${svc}" "${local_port}:${cluster_port}" \
        >/tmp/ep-pf-${svc}-${local_port}.log 2>&1 &
    echo $! >> "${PID_FILE}"
    # 给 port-forward 一点点时间建立隧道
    sleep 1
  done
  echo "[done] 已启动 port-forward, PID 列表见 ${PID_FILE}"
  echo "[hint] 运行 './scripts/port-forward-infra.sh stop' 停止"
}

stop() {
  if [[ ! -s "${PID_FILE}" ]]; then
    echo "[info] 无 PID 记录 (${PID_FILE} 为空/不存在), 尝试按端口清理"
  else
    while read -r pid; do
      [[ -z "${pid}" ]] && continue
      if kill -0 "${pid}" >/dev/null 2>&1; then
        echo "[stop] kill PID ${pid}"
        kill "${pid}" >/dev/null 2>&1 || true
      fi
    done < "${PID_FILE}"
    : > "${PID_FILE}"
  fi
  # 兜底: 按端口杀掉残留的 kubectl port-forward
  for entry in "${PORT_FORWARDS[@]}"; do
    IFS=':' read -r local_port _ _ _ <<< "${entry}"
    pids=$(lsof -iTCP:"${local_port}" -sTCP:LISTEN -P -n -t 2>/dev/null || true)
    for pid in ${pids}; do
      echo "[stop-cleanup] kill PID ${pid} (端口 ${local_port})"
      kill "${pid}" >/dev/null 2>&1 || true
    done
  done
  echo "[done] port-forward 已停止"
}

trap 'stop' EXIT

case "${1:-}" in
  start) start ;;
  stop)  stop; trap - EXIT ;;
  *) echo "用法: $0 {start|stop}"; exit 1 ;;
esac
