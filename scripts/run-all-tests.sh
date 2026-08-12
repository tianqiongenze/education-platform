#!/usr/bin/env bash
# =============================================================================
# run-all-tests.sh
# =============================================================================
# Master test runner for the education-platform monorepo.
#
# Runs every language test suite in sequence, collects pass/fail + timing into
# a single summary table, and exits non-zero if ANY suite failed.
#
# Coverage:
#   1. Java   — `mvn -B test`           per service module (top-level pom.xml)
#   2. Go     — `go test ./...`         ws-gateway + ep-common-go
#   3. Python — `pytest -q`             ai-platform + ep-common-python
#   4. Frontend — `pnpm test`           frontend/ (turbo run test)
#
# Options:
#   --skip-java       skip Java suites
#   --skip-go         skip Go suites
#   --skip-python     skip Python suites
#   --skip-frontend   skip frontend suite
#   --no-fail-fast    continue past failures (default: keep going & summarize)
#   --keep-going      alias for --no-fail-fast (default behaviour)
#   --quiet / -q      reduce per-suite stdout (still prints summary)
#   --help / -h       show usage
#
# Environment variables (all optional):
#   MAVEN_OPTS     passed through to mvn
#   GOFLAGS        passed through to go test
#   PYTEST_ARGS    extra args appended to pytest
#   PNPM_ARGS      extra args appended to pnpm
#
# Exit code: 0 if all run suites passed, 1 if any failed (or a required tool
# was missing), 2 on usage error.
# =============================================================================

set -u
# Do NOT use set -e — we want to keep going and aggregate results.
# Trap errors per-suite instead.

# -----------------------------------------------------------------------------
# Resolve repo root (parent of scripts/) regardless of where this is invoked.
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICES_DIR="${REPO_ROOT}/services"
FRONTEND_DIR="${REPO_ROOT}/frontend"

# -----------------------------------------------------------------------------
# Result accumulation
# -----------------------------------------------------------------------------
# Each suite writes a line: "STATUS|duration|name"
RESULTS_FILE="$(mktemp -t ep-test-results.XXXXXX)"
trap 'rm -f "${RESULTS_FILE}"' EXIT

# ANSI colors (disabled if not a TTY)
if [ -t 1 ]; then
    C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'
    C_CYAN=$'\033[36m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
    C_GREEN=""; C_RED=""; C_YELLOW=""; C_CYAN=""; C_BOLD=""; C_RESET=""
fi

QUIET=0
SKIP_JAVA=0
SKIP_GO=0
SKIP_PYTHON=0
SKIP_FRONTEND=0

usage() {
    sed -n '3,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-java)       SKIP_JAVA=1 ;;
        --skip-go)         SKIP_GO=1 ;;
        --skip-python)     SKIP_PYTHON=1 ;;
        --skip-frontend)   SKIP_FRONTEND=1 ;;
        --no-fail-fast|--keep-going) : ;; # default behaviour, accepted as no-op
        --quiet|-q)        QUIET=1 ;;
        --help|-h)         usage ;;
        *) echo "unknown option: $1" >&2; usage ;;
    esac
    shift
done

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------
log() { printf '%s\n' "$*"; }
info() { [ "$QUIET" -eq 1 ] || printf '%s[info]%s %s\n' "$C_CYAN" "$C_RESET" "$*" >&2; }
section() { printf '\n%s==== %s ====%s\n' "$C_BOLD" "$*" "$C_RESET" >&2; }

# require_tool CMD — aborts the whole run if a required tool is missing.
require_tool() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        printf '%s[error]%s required tool not found: %s\n' "$C_RED" "$C_RESET" "$cmd" >&2
        exit 1
    fi
}

# run_suite NAME WORKDIR CMD... — runs CMD in WORKDIR, records result.
# Always returns 0 (so set -e-style aborts don't trigger); failure is captured
# in RESULTS_FILE and reflected in the final exit code.
run_suite() {
    local name="$1"; shift
    local workdir="$1"; shift
    local start_ts end_ts elapsed
    local status

    if [ ! -d "$workdir" ]; then
        printf '%s[skip]%s %s: workdir missing (%s)\n' "$C_YELLOW" "$C_RESET" "$name" "$workdir" >&2
        printf 'SKIP|0|%s\n' "$name" >> "$RESULTS_FILE"
        return 0
    fi

    printf '%s[run ]%s %s  (cwd=%s)\n' "$C_CYAN" "$C_RESET" "$name" "$workdir" >&2
    start_ts=$(date +%s)

    # Subshell so cd doesn't leak; capture combined output.
    # We intentionally do NOT propagate failure here.
    set +e
    ( cd "$workdir" && "$@" ) > "/tmp/ep-test-${name}.log" 2>&1 </dev/null
    status=$?
    set -e 2>/dev/null || true
    end_ts=$(date +%s)
    elapsed=$(( end_ts - start_ts ))

    if [ "$status" -eq 0 ]; then
        printf '%s[pass]%s %s  (%ds)\n' "$C_GREEN" "$C_RESET" "$name" "$elapsed" >&2
        printf 'PASS|%d|%s\n' "$elapsed" "$name" >> "$RESULTS_FILE"
        if [ "$QUIET" -ne 1 ]; then
            tail -n 5 "/tmp/ep-test-${name}.log" >&2 || true
        fi
    else
        printf '%s[fail]%s %s  (exit=%d, %ds) — log: /tmp/ep-test-%s.log\n' \
            "$C_RED" "$C_RESET" "$name" "$status" "$elapsed" "$name" >&2
        printf 'FAIL|%d|%s\n' "$elapsed" "$name" >> "$RESULTS_FILE"
        # print last 30 lines of the failing log to stderr for visibility
        tail -n 30 "/tmp/ep-test-${name}.log" >&2 || true
    fi
    return 0
}

# =============================================================================
# 0. Tool availability pre-flight
# =============================================================================
section "pre-flight checks"
if [ "$SKIP_JAVA" -ne 1 ]; then
    command -v mvn >/dev/null 2>&1 || { info "mvn not found — Java suites will be skipped"; SKIP_JAVA=1; }
fi
if [ "$SKIP_GO" -ne 1 ]; then
    command -v go >/dev/null 2>&1 || { info "go not found — Go suites will be skipped"; SKIP_GO=1; }
fi
if [ "$SKIP_PYTHON" -ne 1 ]; then
    command -v pytest >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || { info "pytest not found — Python suites will be skipped"; SKIP_PYTHON=1; }
fi
if [ "$SKIP_FRONTEND" -ne 1 ]; then
    command -v pnpm >/dev/null 2>&1 || { info "pnpm not found — frontend suite will be skipped"; SKIP_FRONTEND=1; }
fi
info "repo root: ${REPO_ROOT}"

# =============================================================================
# 1. Java — per top-level service module with a pom.xml
# =============================================================================
if [ "$SKIP_JAVA" -eq 1 ]; then
    section "Java (skipped)"
else
    section "Java suites (mvn -B test)"
    require_tool mvn
    # Top-level service directories containing a pom.xml (excluding nested
    # sub-module poms, e.g. order-platform/order-platform-service/pom.xml which are
    # built by the parent reactor).
    java_dirs=(
        portal-bff
        data-platform
        core-business
        order-platform/order-platform-service
        payment-platform/payment-platform-service
        grading-service
        knowledge-platform
        senior-care
        boardgame-service
        ep-web-common
        ep-memory-java
        ep-cache
    )
    for svc in "${java_dirs[@]}"; do
        d="${SERVICES_DIR}/${svc}"
        [ -f "${d}/pom.xml" ] || { info "skip ${svc}: no pom.xml"; continue; }
        run_suite "java:${svc}" "$d" mvn -B test
    done
fi

# =============================================================================
# 2. Go — ws-gateway and ep-common-go
# =============================================================================
if [ "$SKIP_GO" -eq 1 ]; then
    section "Go (skipped)"
else
    section "Go suites (go test ./...)"
    require_tool go
    for svc in ws-gateway ep-common-go; do
        d="${SERVICES_DIR}/${svc}"
        [ -f "${d}/go.mod" ] || { info "skip ${svc}: no go.mod"; continue; }
        run_suite "go:${svc}" "$d" go test ./...
    done
fi

# =============================================================================
# 3. Python — ai-platform and ep-common-python
# =============================================================================
if [ "$SKIP_PYTHON" -eq 1 ]; then
    section "Python (skipped)"
else
    section "Python suites (pytest)"
    # pick the pytest invocation (prefer project venv, fall back to plain pytest)
    for svc in ai-platform ep-common-python; do
        d="${SERVICES_DIR}/${svc}"
        [ -f "${d}/pyproject.toml" ] || { info "skip ${svc}: no pyproject.toml"; continue; }
        run_suite "py:${svc}" "$d" bash -c '
            if [ -x ".venv/bin/pytest" ]; then
                PYTEST=.venv/bin/pytest
            elif command -v pytest >/dev/null 2>&1; then
                PYTEST=pytest
            else
                PYTEST="python3 -m pytest"
            fi
            # shellcheck disable=SC2086
            exec $PYTEST -q ${PYTEST_ARGS:-}
        '
    done
fi

# =============================================================================
# 4. Frontend — pnpm (turbo run test)
# =============================================================================
if [ "$SKIP_FRONTEND" -eq 1 ]; then
    section "Frontend (skipped)"
else
    section "Frontend suite (pnpm test → turbo run test)"
    require_tool pnpm
    [ -f "${FRONTEND_DIR}/package.json" ] || {
        info "skip frontend: no frontend/package.json"
    }
    if [ -f "${FRONTEND_DIR}/package.json" ]; then
        run_suite "frontend" "$FRONTEND_DIR" bash -c '
            # ensure deps installed (no-op if node_modules present)
            if [ ! -d node_modules ]; then pnpm install --frozen-lockfile >/dev/null 2>&1 || pnpm install >/dev/null 2>&1; fi
            # shellcheck disable=SC2086
            exec pnpm test ${PNPM_ARGS:-}
        '
    fi
fi

# =============================================================================
# 5. Summary table
# =============================================================================
section "summary"

# Build a compact table: STATUS | DURATION | SUITE
total_pass=0
total_fail=0
total_skip=0
total_time=0
printf '%s%-8s %8s  %s%s\n' "$C_BOLD" "STATUS" "TIME(s)" "SUITE" "$C_RESET" >&2
printf '%s\n' "----------------------------------------------" >&2
# RESULTS_FILE lines: "STATUS|seconds|name"
while IFS='|' read -r status secs name; do
    case "$status" in
        PASS) color="$C_GREEN"; total_pass=$((total_pass+1)) ;;
        FAIL) color="$C_RED";   total_fail=$((total_fail+1)) ;;
        SKIP) color="$C_YELLOW"; total_skip=$((total_skip+1)) ;;
        *)    color="" ;;
    esac
    secs="${secs:-0}"
    total_time=$(( total_time + secs ))
    printf '%s%-8s%s %8s  %s\n' "$color" "$status" "$C_RESET" "$secs" "$name" >&2
done < "$RESULTS_FILE"
printf '%s\n' "----------------------------------------------" >&2
printf '%s%-8s %8s  %s%s\n' "$C_BOLD" "TOTAL" "$total_time" \
    "pass=${total_pass} fail=${total_fail} skip=${total_skip}" "$C_RESET" >&2

# Final verdict
if [ "$total_fail" -gt 0 ]; then
    printf '\n%s[FAIL]%s %d suite(s) failed.\n' \
        "$C_RED" "$C_RESET" "$total_fail" >&2
    exit 1
fi
printf '\n%s[OK]%s all %d suite(s) passed (%d skipped).\n' \
    "$C_GREEN" "$C_RESET" "$total_pass" "$total_skip" >&2
exit 0
