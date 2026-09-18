#!/bin/bash
#
# NOTE (D-206): This script is now used for DRIFT DETECTION, not contract generation.
# The contract-first specs live in docs/contracts/openapi/*.yaml and are the
# source-of-truth. This script fetches runtime specs from running services so
# they can be diffed against the contract specs to detect implementation drift.
# Contract linting: ./scripts/lint-openapi.sh
#
# D-094: Fetch OpenAPI specs from all running services and save to schemas/.
#
# Usage: ./scripts/generate-openapi-specs.sh [base-url]
# Default base-url: http://localhost
#
# Each running service exposes springdoc OpenAPI JSON at /v3/api-docs.
# This script pulls those specs into frontend/packages/api-client/schemas/
# so the TypeScript client (pnpm gen:api) and the contract validator
# (scripts/validate-api-contracts.py) can consume them.
#
# Graceful degradation: services that aren't running are skipped (no
# partial/corrupt file is written), and the script always exits 0 so it
# is safe to wire into CI / pre-commit even when the stack is down.
#
# Port source of truth: docs/api/API-INDEX.md (verified against each
# service's src/main/resources/application.yml at D-094).
#
# NOTE: this script intentionally avoids bash 4 associative arrays so it
# runs on the macOS system bash (3.2). Service names and ports are kept
# in two parallel indexed arrays.

set -uo pipefail
# set -e is intentionally OFF: a failed curl for one service must not
# abort the whole run — we want to fetch as many services as are up.

BASE_URL="${1:-http://localhost}"
OUTPUT_DIR="frontend/packages/api-client/schemas"

# Service → port mapping (from docs/api/API-INDEX.md). Keep the two
# arrays in the same order — names[i] pairs with ports[i].
#
# NOTE: portal-bff and mcp-server both default to 8090 in their
# application.yml — they cannot both run at once on the same host. The
# default assumes portal-bff owns 8090; override with BFF_PORT=<port> when
# mcp-server occupies 8090 instead.
NAMES=(bff core-business grading-service knowledge-platform knowledge-bff retrieval-service agent-service tenant-service data-platform)
PORTS=(8090 8083 8085 8071 8070 8072 8073 8074 8082)

# Optional override for the bff port.
#   BFF_PORT=8091 ./scripts/generate-openapi-specs.sh
if [ -n "${BFF_PORT:-}" ]; then
    PORTS[0]="$BFF_PORT"
fi

mkdir -p "$OUTPUT_DIR"

fetched=0
skipped=0

# Iterate by index so names[i] and ports[i] stay aligned.
for i in "${!NAMES[@]}"; do
    service="${NAMES[$i]}"
    port="${PORTS[$i]}"
    url="${BASE_URL}:${port}/v3/api-docs"
    output="${OUTPUT_DIR}/${service}-openapi.json"
    echo "Fetching $service from $url ..."
    if curl -sf -o "$output" "$url" 2>/dev/null; then
        echo "  ✓ $service → $output ($(wc -c < "$output" | tr -d ' ') bytes)"
        fetched=$((fetched + 1))
    else
        echo "  ✗ $service (service not running on :$port, skipping)"
        rm -f "$output"
        skipped=$((skipped + 1))
    fi
done

echo ""
echo "Done. Fetched=$fetched Skipped=$skipped Specs dir=$OUTPUT_DIR/"
echo "Next: python3 scripts/validate-api-contracts.py"
echo "Then: cd frontend/packages/api-client && pnpm gen:api"

# Always exit 0 — a down service is not a build failure (CI-safe).
exit 0
