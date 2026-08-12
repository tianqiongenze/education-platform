#!/usr/bin/env python3
"""
D-094: Validate that all OpenAPI specs in schemas/ are well-formed and
cross-reference consistently. Checks:

1. JSON validity (every spec parses).
2. No duplicate operationIds across services (a duplicate would collide
   in the generated TypeScript client, which flattens all operations
   into one namespace).
3. Paths follow the /api/v1/<resource> convention (warns on non-standard
   prefixes; /health and /v3/* are always allowed).
4. Every operation has an operationId (required for clean codegen).

This script is dependency-free (stdlib only) and safe to run even when
no specs are present (it prints a warning and exits 0, so CI doesn't
turn red on a fresh checkout).

Usage:
    python3 scripts/validate-api-contracts.py
"""

import json
import sys
import glob
from pathlib import Path

SCHEMAS_GLOB = "frontend/packages/api-client/schemas/*-openapi.json"
# Path prefixes that are always acceptable (don't need to match /api/...).
ALLOWED_PREFIXES = ("/api/", "/health", "/v3/", "/actuator")
HTTP_METHODS = ("get", "post", "put", "delete", "patch")


def service_name_of(filepath: str) -> str:
    """backend/bff-openapi.json -> backend  (strip the '-openapi' suffix)."""
    return Path(filepath).stem.replace("-openapi", "")


def validate_spec(filepath: str):
    """Return a list of error strings for a single spec file."""
    errors = []
    try:
        with open(filepath, encoding="utf-8") as f:
            spec = json.load(f)
    except json.JSONDecodeError as e:
        return [f"{filepath}: Invalid JSON: {e}"]
    except OSError as e:
        return [f"{filepath}: Cannot read file: {e}"]

    service = service_name_of(filepath)

    for path, methods in spec.get("paths", {}).items():
        # A path item can hold parameters + method children. Iterate the
        # HTTP-method children only.
        for method, operation in methods.items():
            if method not in HTTP_METHODS:
                continue
            if not isinstance(operation, dict):
                errors.append(
                    f"{service} {method.upper()} {path}: operation is not an object"
                )
                continue
            # operationId is required for clean codegen.
            if "operationId" not in operation:
                errors.append(
                    f"{service} {method.upper()} {path}: missing operationId"
                )
            # Path convention (skip health / api-docs / actuator).
            if not path.startswith(ALLOWED_PREFIXES):
                errors.append(
                    f"{service} {method.upper()} {path}: non-standard path prefix "
                    f"(expected one of {ALLOWED_PREFIXES})"
                )

    return errors


def collect_operationids():
    """Walk every spec and return a dict opId -> 'service METHOD path'."""
    seen = {}
    duplicates = []
    for filepath in sorted(glob.glob(SCHEMAS_GLOB)):
        try:
            with open(filepath, encoding="utf-8") as f:
                spec = json.load(f)
        except (json.JSONDecodeError, OSError):
            continue
        service = service_name_of(filepath)
        for path, methods in spec.get("paths", {}).items():
            for method, op in methods.items():
                if method not in HTTP_METHODS or not isinstance(op, dict):
                    continue
                op_id = op.get("operationId")
                if not op_id:
                    continue
                where = f"{service} {method.upper()} {path}"
                if op_id in seen:
                    duplicates.append(
                        f"Duplicate operationId '{op_id}': "
                        f"{seen[op_id]} and {where}"
                    )
                else:
                    seen[op_id] = where
    return duplicates


def main():
    specs = sorted(glob.glob(SCHEMAS_GLOB))

    if not specs:
        print(
            "⚠ No OpenAPI specs found in schemas/. "
            "Run ./scripts/generate-openapi-specs.sh first."
        )
        # Exit 0: a fresh checkout legitimately has no specs.
        return 0

    print(f"Validating {len(specs)} OpenAPI spec(s)...")
    all_errors = []
    for spec in specs:
        errors = validate_spec(spec)
        if errors:
            all_errors.extend(errors)
        else:
            print(f"  ✓ {Path(spec).name}")

    dupes = collect_operationids()
    if dupes:
        print("\nDuplicate operationIds:")
        for d in dupes:
            print(f"  ⚠ {d}")
        # Treat duplicate operationIds as a hard error: they collide in
        # the generated TS client.
        all_errors.extend(dupes)

    if all_errors:
        print(f"\n❌ {len(all_errors)} validation error(s):")
        for e in all_errors:
            print(f"  ✗ {e}")
        return 1

    print("\n✅ All specs valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
