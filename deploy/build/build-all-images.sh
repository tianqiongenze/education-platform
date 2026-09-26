#!/usr/bin/env bash
# D-115: Build all container images for the education-platform.
#
# Usage:
#   deploy/build/build-all-images.sh [VERSION] [SERVICE...]
#
#   VERSION  - image tag (default: 1.0.0). Also exported as project.version
#              override for Maven via -Drevision is NOT applied; the JIB plugin
#              tags images as ${project.version} which is 1.0.0 in every pom.
#              Pass VERSION only for the Dockerfile-based (Go/Python/Frontend)
#              images; Java images always use the pom version.
#
#   SERVICE... - optional subset to build. Recognized names:
#       java                all 11 Java services (JIB)
#       portal-bff
#       core-business
#       data-platform
#       grading-service
#       order-platform-service
#       payment-platform-service
#       retrieval-service
#       knowledge-service
#       knowledge-bff
#       agent-service
#       tenant-service
#       ws-gateway
#       ai-platform
#       portal-web
#       all                 (default)
#
# Prerequisites:
#   - docker (with BuildKit enabled) for Go / Python / Frontend
#   - mvn 3.9+, JDK 21, and `mvn install` of ep-* / *-api dependencies
#     for the Java JIB builds
#   - access to harbor.edu.internal (push credentials configured via
#     `docker login` and Maven settings.xml for JIB)
#
# Environment:
#   GIT_URL    - repo URL for org.opencontainers.image.source label
#   GIT_COMMIT - commit SHA for org.opencontainers.image.revision label
#   SKIP_PUSH  - set to 1 to build Docker images without pushing (JIB always
#                pushes; use `mvn jib:buildTar` instead to skip push).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REGISTRY="${REGISTRY:-harbor.edu.internal/education-platform}"
VERSION="${1:-1.0.0}"; shift || true
SERVICES=("$@"); [ "${#SERVICES[@]}" -eq 0 ] && SERVICES=("all")

# OCI label env defaults.
export GIT_URL="${GIT_URL:-unknown}"
export GIT_COMMIT="${GIT_COMMIT:-unknown}"

log() { printf '\033[1;34m[build-all]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[build-all:err]\033[0m %s\n' "$*" >&2; }

# ---- Java services (JIB) -------------------------------------------------
JAVA_SERVICES=(
  "services/portal-bff"
  "services/core-business"
  "services/data-platform"
  "services/grading-service"
  "services/order-platform/order-platform-service"
  "services/payment-platform/payment-platform-service"
  "services/knowledge-platform/retrieval-service"
  # D-141: 补齐 5 个知识中台服务 (mcp-server 无 JIB 配置, 暂跳过)
  "services/knowledge-platform/knowledge-service"
  "services/knowledge-platform/knowledge-bff"
  "services/knowledge-platform/agent-service"
  "services/knowledge-platform/tenant-service"
)

build_jib() {
  local svc_dir="$1"
  log "JIB build: $svc_dir"
  ( cd "$REPO_ROOT/$svc_dir" && mvn -q -DskipTests compile jib:build -Pjib-build )
}

# ---- Go service (Dockerfile) ---------------------------------------------
build_ws_gateway() {
  log "docker build: ws-gateway:$VERSION"
  # Context is services/ so the go.mod replace directive for ep-common-go
  # (-> ../libs-go/ep-common-go) resolves inside the build context.
  docker build -t "${REGISTRY}/ws-gateway:${VERSION}" \
      -f services/ws-gateway/Dockerfile services/
  [ "${SKIP_PUSH:-0}" = "1" ] || docker push "${REGISTRY}/ws-gateway:${VERSION}"
}

# ---- Python service (Dockerfile) -----------------------------------------
build_ai_platform() {
  log "docker build: ai-platform:$VERSION"
  # Context is services/ so pyproject.toml's local path dep
  # ep-common -> ../libs-python/ep-common-python resolves.
  docker build -t "${REGISTRY}/ai-platform:${VERSION}" \
      -f services/ai-platform/Dockerfile services/
  [ "${SKIP_PUSH:-0}" = "1" ] || docker push "${REGISTRY}/ai-platform:${VERSION}"
}

# ---- Frontend (Dockerfile) -----------------------------------------------
build_portal_web() {
  log "docker build: frontend-portal:$VERSION"
  # Context is frontend/ (pnpm workspace root) so @ep/* workspace packages
  # resolve during `pnpm install --frozen-lockfile`.
  docker build -t "${REGISTRY}/frontend-portal:${VERSION}" \
      -f frontend/apps/portal-web/Dockerfile frontend/
  [ "${SKIP_PUSH:-0}" = "1" ] || docker push "${REGISTRY}/frontend-portal:${VERSION}"
}

# ---- dispatch ------------------------------------------------------------
want() { local s; for s in "${SERVICES[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

if want "all"; then
  for d in "${JAVA_SERVICES[@]}"; do build_jib "$d"; done
  build_ws_gateway
  build_ai_platform
  build_portal_web
  exit 0
fi

if want "java"; then
  for d in "${JAVA_SERVICES[@]}"; do build_jib "$d"; done
fi
want "portal-bff"             && build_jib "services/portal-bff"
want "core-business"          && build_jib "services/core-business"
want "data-platform"          && build_jib "services/data-platform"
want "grading-service"        && build_jib "services/grading-service"
want "order-platform-service" && build_jib "services/order-platform/order-platform-service"
want "payment-platform-service"&& build_jib "services/payment-platform/payment-platform-service"
want "retrieval-service"      && build_jib "services/knowledge-platform/retrieval-service"
want "knowledge-service"      && build_jib "services/knowledge-platform/knowledge-service"
want "knowledge-bff"          && build_jib "services/knowledge-platform/knowledge-bff"
want "agent-service"          && build_jib "services/knowledge-platform/agent-service"
want "tenant-service"         && build_jib "services/knowledge-platform/tenant-service"
want "ws-gateway"             && build_ws_gateway
want "ai-platform"            && build_ai_platform
want "portal-web"             && build_portal_web

log "done."
