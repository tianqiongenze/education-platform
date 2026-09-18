#!/bin/bash
# D-206: Lint all contract-first OpenAPI specs with Spectral.
# Usage: ./scripts/lint-openapi.sh
# Requires: npx @stoplight/spectral-cli
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SPEC_DIR="$PROJECT_ROOT/docs/contracts/openapi"
SPECTRAL_CONFIG="$PROJECT_ROOT/.spectral.yml"

echo "=== Spectral OpenAPI Linting (D-206) ==="
echo "Spec directory: $SPEC_DIR"
echo ""

failures=0
for spec in "$SPEC_DIR"/*.yaml "$SPEC_DIR"/*.yml; do
    [ -f "$spec" ] || continue
    echo "--- Linting: $(basename "$spec") ---"
    if npx -y @stoplight/spectral-cli@latest lint "$spec" --ruleset "$SPECTRAL_CONFIG"; then
        echo "  PASS"
    else
        echo "  FAIL"
        failures=$((failures + 1))
    fi
    echo ""
done

if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures spec(s) failed linting"
    exit 1
fi
echo "All OpenAPI specs passed Spectral linting."
