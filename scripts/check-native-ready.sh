#!/usr/bin/env bash
# D-181: Checks that all Java services with native-maven-plugin have
# corresponding META-INF/native-image config directories.
# Usage: ./scripts/check-native-ready.sh

set -euo pipefail

# Resolve repo root regardless of where the script is invoked from
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVICES_DIR="$REPO_ROOT/services"

MISSING=0
CHECKED=0

for pom in "$SERVICES_DIR"/*/pom.xml; do
  [ -f "$pom" ] || continue
  service=$(basename "$(dirname "$pom")")

  if ! grep -q "native-maven-plugin" "$pom"; then
    continue
  fi

  CHECKED=$((CHECKED + 1))
  meta_dir="$SERVICES_DIR/$service/src/main/resources/META-INF/native-image"
  if [ ! -d "$meta_dir" ]; then
    echo "❌ $service has native-maven-plugin but NO native-image config"
    MISSING=$((MISSING + 1))
  else
    echo "✅ $service has native-image config"
  fi
done

echo ""
echo "Checked $CHECKED native-enabled service(s)."

if [ "$MISSING" -gt 0 ]; then
  echo "Total missing: $MISSING"
  exit 1
else
  echo "All native-enabled services have config."
fi
