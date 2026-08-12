#!/usr/bin/env bash
# 本地 k3s 部署验证脚本
# 用途: 在 Rancher Desktop k3s 上验证 K8s YAML 可部署性
#
# 此脚本用于 local-verify overlay 的端到端验证:
#   1. kustomize 渲染
#   2. 应用 (CRD 缺失的 ServiceMonitor/PropagationPolicy 错误会被忽略)
#   3. 等待 Pod 就绪
#   4. 输出部署结果
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OVERLAY="$PROJECT_ROOT/deploy/k8s/overlays/local-verify"
NAMESPACE="education-platform"

echo "=== 0. 前置检查 ==="
kubectl config current-context || { echo "kubectl 不可用或未配置上下文"; exit 1; }
kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || echo "(namespace 尚未创建, 稍后由 overlay 创建)"

echo
echo "=== 1. kustomize 渲染验证 ==="
if kubectl kustomize "$OVERLAY" >/dev/null 2>&1; then
  echo "OK: kustomize 渲染成功"
else
  echo "FAIL: kustomize 渲染失败"
  kubectl kustomize "$OVERLAY" >/dev/null
  exit 1
fi

echo
echo "=== 2. 应用 overlay (CRD 缺失错误将被忽略) ==="
# ServiceMonitor (monitoring.coreos.com/v1), PropagationPolicy/OverridePolicy
# (policy.karmada.io/v1alpha1) 的 CRD 在 k3s 默认未安装,
# kubectl apply 会对此类资源报错, 但不影响 Deployment/Service/ConfigMap 等.
APPLY_LOG="$(mktemp)"
kubectl apply -k "$OVERLAY" >"$APPLY_LOG" 2>&1 || true
cat "$APPLY_LOG"
echo
echo "--- CRD 缺失类错误 (预期, 可忽略) ---"
grep -E "no matches for kind|cannot be recognized" "$APPLY_LOG" || echo "(无)"

echo
echo "=== 3. 等待 Pod 就绪 (最多 90s) ==="
# 由于 namePrefix=edu-, labelSelector 用 part-of
if kubectl wait --for=condition=Ready pod \
    -l app.kubernetes.io/part-of=education-platform \
    -n "$NAMESPACE" --timeout=90s 2>&1; then
  echo "OK: 所有 Pod 就绪"
else
  echo "WARN: 部分 Pod 未就绪"
fi

echo
echo "=== 4. 部署验证结果 ==="
kubectl get all -n "$NAMESPACE" 2>&1 || true

echo
echo "=== 5. Pod 详细状态 (含镜像和事件) ==="
kubectl get pods -n "$NAMESPACE" -o wide 2>&1 || true

echo
echo "=== 6. 未就绪 Pod 的事件 (如有) ==="
NOT_READY="$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null \
  | awk '{if ($3!="Running" && $3!="Completed") print $1}')"
if [ -n "$NOT_READY" ]; then
  for p in $NOT_READY; do
    echo "--- $p ---"
    kubectl describe pod -n "$NAMESPACE" "$p" 2>&1 | tail -25
  done
else
  echo "(所有 Pod 均 Running/Completed)"
fi

echo
echo "=== 7. 清理 (可选) ==="
echo "如需清理: kubectl delete -k $OVERLAY"
