#!/usr/bin/env bash
# D-215a: Build all service images (arm64 native) from CURRENT source using RD docker engine.
# Java: mvn package → docker build (Dockerfile.java-svc)
# Go/Python/Frontend: existing Dockerfiles
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="$REPO_ROOT/deploy/build/Dockerfile.java-svc"
TMPDIR="/tmp/d215-builds"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
export GIT_URL="https://github.com/education-platform/edu-platform"
export GIT_COMMIT="d215-build"

docker context use rd-vm 2>/dev/null || true
mkdir -p "$TMPDIR"

log() { printf '\033[1;34m[d215]\033[0m %s\n' "$*"; }

# Java services: (service_dir, artifact_id)
JAVA_SERVICES=(
  "services/portal-bff:portal-bff"
  "services/core-business:core-business"
  "services/grading-service:grading-service"
  "services/data-platform:data-platform"
  "services/order-platform/order-platform-service:order-platform-service"
  "services/payment-platform/payment-platform-service:payment-platform-service"
  "services/knowledge-platform/retrieval-service:retrieval-service"
  "services/knowledge-platform/knowledge-service:knowledge-service"
  "services/knowledge-platform/knowledge-bff:knowledge-bff"
  "services/knowledge-platform/agent-service:agent-service"
  "services/knowledge-platform/tenant-service:tenant-service"
)

build_java() {
  local svc_dir="$1" artifact_id="$2"
  local image="esp/edu-${artifact_id}:1.0.0"
  log "Building $image"
  
  ( cd "$REPO_ROOT/$svc_dir" && \
    mvn -q package -DskipTests -Djacoco.skip=true 2>&1 ) || { log "❌ mvn package failed: $image"; return 1; }
  
  local jar_path
  jar_path=$(find "$REPO_ROOT/$svc_dir/target" -name "${artifact_id}-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)
  if [ -z "$jar_path" ]; then
    log "❌ jar not found for $artifact_id"
    return 1
  fi
  
  local tmp_jar="$TMPDIR/${artifact_id}.jar"
  cp "$jar_path" "$tmp_jar"
  
  ( cd "$TMPDIR" && docker build -t "$image" \
    --build-arg JAR_FILE="${artifact_id}.jar" \
    -f "$DOCKERFILE" . 2>&1 ) || { log "❌ docker build failed: $image"; return 1; }
  
  local arch
  arch=$(docker inspect "$image" --format '{{.Architecture}}' 2>/dev/null)
  log "✅ $image ($arch)"
  rm -f "$tmp_jar"
}

build_go() {
  log "Building esp/edu-ws-gateway:1.0.0"
  ( cd "$REPO_ROOT" && docker build -t esp/edu-ws-gateway:1.0.0 \
    -f services/ws-gateway/Dockerfile services/ 2>&1 ) || { log "❌ ws-gateway failed"; return 1; }
  log "✅ esp/edu-ws-gateway:1.0.0"
}

build_python() {
  log "Building esp/edu-ai-platform:1.0.0"
  ( cd "$REPO_ROOT" && docker build -t esp/edu-ai-platform:1.0.0 \
    -f services/ai-platform/Dockerfile services/ 2>&1 ) || { log "❌ ai-platform failed"; return 1; }
  log "✅ esp/edu-ai-platform:1.0.0"
}

build_frontend() {
  log "Building esp/edu-frontend-portal:1.0.0"
  ( cd "$REPO_ROOT" && docker build -t esp/edu-frontend-portal:1.0.0 \
    -f frontend/apps/portal-web/Dockerfile frontend/ 2>&1 ) || { log "❌ frontend-portal failed"; return 1; }
  log "✅ esp/edu-frontend-portal:1.0.0"
}

FAILED=0

# Build Java services one by one
for entry in "${JAVA_SERVICES[@]}"; do
  svc_dir="${entry%%:*}"
  artifact_id="${entry##*:}"
  build_java "$svc_dir" "$artifact_id" || FAILED=$((FAILED+1))
done

# Build Go
build_go || FAILED=$((FAILED+1))

# Build Python
build_python || FAILED=$((FAILED+1))

# Build Frontend
build_frontend || FAILED=$((FAILED+1))

log "========================================"
log "Build complete. Failed: $FAILED / 14"
log "========================================"

# Show all built images
docker images --format "{{.Repository}}:{{.Tag}}\t{{.Architecture}}\t{{.Size}}" | grep "esp/edu" | sort

exit $FAILED
