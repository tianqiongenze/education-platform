#!/usr/bin/env bash
# D-215a: Build all service images from CURRENT source code using RD docker engine.
# Java: JIB dockerBuild → loads into local Docker daemon
# Go/Python/Frontend: Dockerfile-based docker build
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
export GIT_URL="https://github.com/education-platform/edu-platform"
export GIT_COMMIT="d215-build"
export DOCKER_CONTEXT="rd-vm"

# Switch to RD docker context
docker context use rd-vm 2>/dev/null || true

log() { printf '\033[1;34m[build-d215]\033[0m %s\n' "$*"; }

# ---- Java services (JIB dockerBuild) ------------------------------------
JAVA_SERVICES=(
  "services/portal-bff:portal-bff"
  "services/core-business:core-business"
  "services/data-platform:data-platform"
  "services/grading-service:grading-service"
  "services/order-platform/order-platform-service:order-platform"
  "services/payment-platform/payment-platform-service:payment-platform"
  "services/knowledge-platform/retrieval-service:retrieval-service"
  "services/knowledge-platform/knowledge-service:knowledge-service"
  "services/knowledge-platform/knowledge-bff:knowledge-bff"
  "services/knowledge-platform/agent-service:agent-service"
  "services/knowledge-platform/tenant-service:tenant-service"
)

build_jib() {
  local svc_dir="$1"
  local name="$2"
  local image="esp/edu-${name}:1.0.0"
  log "JIB dockerBuild: $svc_dir → $image"
  ( cd "$REPO_ROOT/$svc_dir" && \
    mvn -q compile jib:dockerBuild -Pjib-build \
      -Djib.to.image="$image" \
      -Djib.from.image=eclipse-temurin:21-jre-alpine \
      2>&1 )
  if [ $? -eq 0 ]; then
    log "✅ $image built"
  else
    log "❌ $image FAILED"
    return 1
  fi
}

# ---- Go service (Dockerfile) --------------------------------------------
build_ws_gateway() {
  log "docker build: ws-gateway"
  docker build -t esp/edu-ws-gateway:1.0.0 \
    -f services/ws-gateway/Dockerfile services/ 2>&1
  log "✅ esp/edu-ws-gateway:1.0.0 built"
}

# ---- Python service (Dockerfile) -----------------------------------------
build_ai_platform() {
  log "docker build: ai-platform"
  docker build -t esp/edu-ai-platform:1.0.0 \
    -f services/ai-platform/Dockerfile services/ 2>&1
  log "✅ esp/edu-ai-platform:1.0.0 built"
}

# ---- Frontend (Dockerfile) -----------------------------------------------
build_portal_web() {
  log "docker build: frontend-portal"
  docker build -t esp/edu-frontend-portal:1.0.0 \
    -f frontend/apps/portal-web/Dockerfile frontend/ 2>&1
  log "✅ esp/edu-frontend-portal:1.0.0 built"
}

# ---- Main ----------------------------------------------------------------
log "Starting D-215a image build with RD engine"

FAILED=0

# Build Java services
for entry in "${JAVA_SERVICES[@]}"; do
  svc_dir="${entry%%:*}"
  name="${entry##*:}"
  build_jib "$svc_dir" "$name" || FAILED=$((FAILED+1))
done

# Build Go
build_ws_gateway || FAILED=$((FAILED+1))

# Build Python
build_ai_platform || FAILED=$((FAILED+1))

# Build Frontend
build_portal_web || FAILED=$((FAILED+1))

log "Build complete. Failed: $FAILED"
exit $FAILED
