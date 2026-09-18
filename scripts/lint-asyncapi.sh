#!/bin/bash
# D-207: Validate all AsyncAPI specs.
# Usage: ./scripts/lint-asyncapi.sh
# Requires: npx @asyncapi/cli
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SPEC_DIR="$PROJECT_ROOT/docs/contracts/asyncapi"

echo "=== AsyncAPI Spec Validation (D-207) ==="
echo "Spec directory: $SPEC_DIR"
echo ""

failures=0
for spec in "$SPEC_DIR"/*.yaml "$SPEC_DIR"/*.yml; do
    [ -f "$spec" ] || continue
    echo "--- Validating: $(basename "$spec") ---"
    if npx -y @asyncapi/cli@latest validate "$spec"; then
        echo "  ✓ VALID"
    else
        echo "  ✗ INVALID"
        failures=$((failures + 1))
    fi
    echo ""
done

if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures spec(s) failed validation"
    exit 1
fi

echo "All AsyncAPI specs validated successfully. ✓"
