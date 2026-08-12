#!/usr/bin/env bash
# D-195: Pulsar 4.0 upgrade readiness checker.
# Verifies that all service poms use the unified ${pulsar.version} property
# (no hardcoded 3.3.9), checks the PulsarVersionAdapter exists, and prints
# a readiness summary.
# Usage: ./scripts/check-pulsar-4-ready.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVICES_DIR="$REPO_ROOT/services"

echo "D-195 Pulsar 4.0 Upgrade Readiness Check"
echo "=========================================="
echo ""

# --- 1. Check all service poms use ${pulsar.version} ---
echo "[1/4] Checking pulsar.version unification across service poms..."
HARDCODED=0
UNIFIED=0
HARDCODED_FILES=""

for pom in "$SERVICES_DIR"/*/pom.xml "$SERVICES_DIR"/*/*/pom.xml; do
  [ -f "$pom" ] || continue
  if ! grep -q "pulsar-client" "$pom"; then
    continue
  fi
  service=$(echo "$pom" | sed "s|$SERVICES_DIR/||" | sed 's|/pom.xml||')

  if grep -q 'pulsar.version' "$pom" && grep -q '\${pulsar.version}' "$pom"; then
    echo "  ✅ $service — uses \${pulsar.version} property"
    UNIFIED=$((UNIFIED + 1))
  else
    echo "  ❌ $service — has hardcoded pulsar-client version"
    HARDCODED=$((HARDCODED + 1))
    HARDCODED_FILES="$HARDCODED_FILES $service"
  fi
done

echo ""
echo "  Summary: $UNIFIED unified, $HARDCODED hardcoded"

# --- 2. Check PulsarVersionAdapter exists ---
echo ""
echo "[2/4] Checking PulsarVersionAdapter..."
ADAPTER="$SERVICES_DIR/ep-cache/src/main/java/com/ep/cache/pulsar/PulsarVersionAdapter.java"
if [ -f "$ADAPTER" ]; then
  VERSION=$(grep 'CLIENT_VERSION =' "$ADAPTER" | head -1 | sed 's/.*"\(.*\)".*/\1/')
  TARGET=$(grep 'TARGET_VERSION =' "$ADAPTER" | head -1 | sed 's/.*"\(.*\)".*/\1/')
  echo "  ✅ PulsarVersionAdapter found"
  echo "     Current client version: $VERSION"
  echo "     Target version:         $TARGET"
else
  echo "  ❌ PulsarVersionAdapter not found at expected path"
fi

# --- 3. Check ADR exists ---
echo ""
echo "[3/4] Checking readiness ADR..."
ADR="$REPO_ROOT/docs/adr/D-195-pulsar-4-upgrade-readiness.md"
if [ -f "$ADR" ]; then
  echo "  ✅ ADR found: docs/adr/D-195-pulsar-4-upgrade-readiness.md"
else
  echo "  ❌ ADR not found"
fi

# --- 4. Summary ---
echo ""
echo "[4/4] Readiness Summary"
echo " -----------------------------"
if [ "$HARDCODED" -eq 0 ]; then
  echo "  ✅ All $UNIFIED Pulsar-using services use unified \${pulsar.version}"
else
  echo "  ❌ $HARDCODED service(s) still have hardcoded versions:$HARDCODED_FILES"
fi

if [ -f "$ADAPTER" ] && [ -f "$ADR" ] && [ "$HARDCODED" -eq 0 ]; then
  echo ""
  echo "  ✅ Pulsar 4.0 upgrade readiness: PASS"
  echo "  To upgrade: change pulsar.version in each service pom + update"
  echo "  PulsarVersionAdapter.CLIENT_VERSION, then run integration tests."
  exit 0
else
  echo ""
  echo "  ❌ Pulsar 4.0 upgrade readiness: NOT READY"
  exit 1
fi
