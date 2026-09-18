#!/usr/bin/env python3
"""
Iron Law 10: Design-Implementation Traceability Enforcement
============================================================
D-210: CI static check that every ADR has implementation references.

铁律 10 (D-196): 设计→实现可追溯性强制律。本脚本静态检查设计文档与代码实现的
双向可追溯性。

Checks:
  L10-A: Every ADR file in docs/adr/ must contain a "## Implementation" or
         "## 实现" section (or equivalent) with at least one file path
         reference (e.g. services/..., src/...).
         → WARNING (过渡期: 标记缺失, 不阻断 CI)
  L10-B: Every major service directory under services/ must have at least
         one test file (*Test.java, *_test.go, test_*.py, *.test.tsx).
         → CRITICAL
  L10-C: The traceability matrix
         (docs/architecture/requirements-traceability-matrix.md) must exist
         and reference all 10 core scenarios.
         → CRITICAL
  L10-D: ADR files that contain file path references — verify at least one
         referenced path actually exists in the repo (spot check).
         → WARNING

Usage: python3 scripts/check-iron-law-10.py
Exit 0 = pass (only warnings allowed), Exit 1 = fail (critical violations)
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(os.environ.get("REPO_ROOT", Path(__file__).resolve().parents[1]))
ADR_DIR = REPO_ROOT / "docs" / "adr"
SERVICES_DIR = REPO_ROOT / "services"
MATRIX_PATH = REPO_ROOT / "docs" / "architecture" / "requirements-traceability-matrix.md"

# Major service directories that must have tests.
# Excludes shared libraries (ep-web-common, ep-cache, ep-disruptor, ep-memory-java)
# and api-only modules which are covered by their service counterparts.
MAJOR_SERVICES = [
    "core-business",
    "grading-service",
    "order-platform",
    "payment-platform",
    "knowledge-platform",
    "boardgame-service",
    "senior-care",
    "ai-platform",
    "live-go",
    "ws-gateway",
    "portal-bff",
    "data-platform",
    "ep-cache",
    "ep-web-common",
    "ep-common-go",
    "ep-common-python",
]

# Number of core scenarios the traceability matrix must reference.
REQUIRED_SCENARIO_COUNT = 10

violations: list[dict[str, Any]] = []  # CRITICAL
warnings: list[dict[str, Any]] = []    # WARNING


def add_violation(rule: str, location: str, message: str) -> None:
    violations.append({"rule": rule, "severity": "CRITICAL", "location": location, "message": message})


def add_warning(rule: str, location: str, message: str) -> None:
    warnings.append({"rule": rule, "severity": "WARNING", "location": location, "message": message})


# ---------------------------------------------------------------------------
# L10-A: Every ADR must have an implementation section with file path refs
# ---------------------------------------------------------------------------
# Regex for file path references inside an ADR.
# Matches: services/..., src/..., frontend/..., deploy/..., scripts/...
PATH_REF_RE = re.compile(r"(?:services|src|frontend|deploy|scripts)/[\w/.-]+\.\w+")
# Also accept bare paths like `com/ep/...` package refs (lenient).
IMPL_SECTION_RE = re.compile(
    r"^##\s+(Implementation|实现|实现要点|实现细节|实现清单|Implementation notes)",
    re.MULTILINE,
)


def check_adr_implementation_sections() -> None:
    """L10-A: Each ADR file should reference at least one implementation file path."""
    if not ADR_DIR.is_dir():
        add_violation("L10-A", str(ADR_DIR), "docs/adr/ directory does not exist")
        return

    adr_files = sorted(ADR_DIR.glob("*.md"))
    if not adr_files:
        add_violation("L10-A", str(ADR_DIR), "No ADR files found in docs/adr/")
        return

    for adr_file in adr_files:
        try:
            content = adr_file.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            add_warning("L10-A", str(adr_file), f"Cannot read file: {exc}")
            continue

        path_refs = PATH_REF_RE.findall(content)
        has_impl_section = bool(IMPL_SECTION_RE.search(content))

        if not path_refs and not has_impl_section:
            add_warning(
                "L10-A",
                adr_file.name,
                "ADR has no '## Implementation/实现' section and no file path references "
                "(services/..., src/...). Iron Law 10 requires design→implementation traceability.",
            )


# ---------------------------------------------------------------------------
# L10-B: Every major service directory must have at least one test file
# ---------------------------------------------------------------------------
TEST_PATTERNS = [
    "*Test.java",
    "*IT.java",
    "*_test.go",
    "test_*.py",
    "*_test.py",
    "*.test.ts",
    "*.test.tsx",
]


def check_service_test_coverage() -> None:
    """L10-B: Each major service directory must have at least one test file."""
    for svc in MAJOR_SERVICES:
        svc_path = SERVICES_DIR / svc
        if not svc_path.is_dir():
            add_violation("L10-B", svc, f"Major service directory missing: services/{svc}/")
            continue

        found_test = False
        for root, _dirs, files in os.walk(svc_path):
            # Skip target/ build output and node_modules / .venv
            if "/target/" in root or "/node_modules/" in root or "/.venv/" in root:
                continue
            for fname in files:
                for pattern in TEST_PATTERNS:
                    from fnmatch import fnmatch

                    if fnmatch(fname, pattern):
                        found_test = True
                        break
                if found_test:
                    break
            if found_test:
                break

        if not found_test:
            add_violation(
                "L10-B",
                svc,
                f"services/{svc}/ has NO test files. Iron Law 10 requires every service to have "
                "test verification.",
            )


# ---------------------------------------------------------------------------
# L10-C: Traceability matrix must exist and reference 10 core scenarios
# ---------------------------------------------------------------------------
SCENARIO_ROW_RE = re.compile(r"^\|\s*\d+\s*\|", re.MULTILINE)


def check_traceability_matrix() -> None:
    """L10-C: The traceability matrix must exist and reference 10 core scenarios."""
    if not MATRIX_PATH.is_file():
        add_violation(
            "L10-C",
            str(MATRIX_PATH),
            "Traceability matrix missing: docs/architecture/requirements-traceability-matrix.md",
        )
        return

    try:
        content = MATRIX_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        add_violation("L10-C", str(MATRIX_PATH), f"Cannot read matrix: {exc}")
        return

    # Count scenario rows (rows starting with | <number> |)
    scenario_rows = SCENARIO_ROW_RE.findall(content)
    # Subtract header rows that might match (e.g., "| # |" — but # is not a digit)
    # The regex requires a digit after the opening pipe, so header "| # |" won't match.

    scenario_count = len(scenario_rows)

    if scenario_count < REQUIRED_SCENARIO_COUNT:
        add_violation(
            "L10-C",
            str(MATRIX_PATH),
            f"Traceability matrix references {scenario_count} scenarios, "
            f"but {REQUIRED_SCENARIO_COUNT} are required.",
        )

    # Verify the matrix references SRS, ADR, Code, and Test for each scenario
    required_sections = ["SRS", "ADR", "Code", "Test"]
    missing_sections = [s for s in required_sections if s not in content and s.lower() not in content.lower()]
    if missing_sections:
        add_violation(
            "L10-C",
            str(MATRIX_PATH),
            f"Traceability matrix missing traceability columns: {missing_sections}",
        )


# ---------------------------------------------------------------------------
# L10-D: Spot-check that ADR-referenced file paths actually exist
# ---------------------------------------------------------------------------
def check_adr_path_existence() -> None:
    """L10-D: For ADRs with file path references, spot-check that paths exist."""
    if not ADR_DIR.is_dir():
        return

    adr_files = sorted(ADR_DIR.glob("*.md"))
    checked = 0
    max_check = 5  # Spot-check first 5 ADRs with refs to keep CI fast

    for adr_file in adr_files:
        if checked >= max_check:
            break
        try:
            content = adr_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        path_refs = PATH_REF_RE.findall(content)
        if not path_refs:
            continue

        checked += 1
        missing_paths = []
        for ref in path_refs[:10]:  # Check up to 10 refs per ADR
            # Normalize: some refs are relative to repo root
            candidate = REPO_ROOT / ref
            if not candidate.exists():
                missing_paths.append(ref)

        if missing_paths:
            add_warning(
                "L10-D",
                adr_file.name,
                f"Referenced implementation paths not found: {missing_paths[:3]}",
            )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    print("=" * 70)
    print("Iron Law 10: Design-Implementation Traceability Enforcement (D-210)")
    print("=" * 70)
    print(f"Repo root: {REPO_ROOT}")
    print()

    check_adr_implementation_sections()
    check_service_test_coverage()
    check_traceability_matrix()
    check_adr_path_existence()

    # Report warnings
    if warnings:
        print(f"⚠️  Warnings ({len(warnings)}):")
        for w in warnings:
            print(f"  [{w['rule']}] {w['location']}: {w['message']}")
        print()

    # Report violations
    if violations:
        print(f"❌ Critical violations ({len(violations)}):")
        for v in violations:
            print(f"  [{v['rule']}] {v['location']}: {v['message']}")
        print()
        print(f"RESULT: FAIL ({len(violations)} critical violations, {len(warnings)} warnings)")
        return 1

    print(f"RESULT: PASS (0 critical violations, {len(warnings)} warnings)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
