#!/usr/bin/env bash
#
# gen-rs256-keys.sh - Generate an RS256 (RSA 2048-bit) key pair for JWT signing.
#
# -----------------------------------------------------------------------------
# ADR-A02 compliance: JWT tokens are signed with RS256 (asymmetric RSA).
#   - Minimum key length: 2048 bits (this script enforces it).
#   - Private key is PKCS#8 PEM  ("-----BEGIN PRIVATE KEY-----").
#   - Public  key is X.509  PEM  ("-----BEGIN PUBLIC KEY-----").
#
# Output formats:
#   - jwt-rs256-private.pem : raw private key (PKCS#8, unencrypted)
#   - jwt-rs256-public.pem  : raw public key (X.509 SubjectPublicKeyInfo)
#   - jwt-rs256.env         : env-file with single-line escaped PEM values:
#       JWT_RSA_PRIVATE_KEY_PEM=-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----\n
#       JWT_RSA_PUBLIC_KEY_PEM=-----BEGIN PUBLIC KEY-----\nMIIB...\n-----END PUBLIC KEY-----\n
#
# CI / production:
#   - Run this script ONCE to generate the key pair.
#   - Store the resulting PEM material as Kubernetes Secrets (never commit to git).
#   - The .env file is for LOCAL DEVELOPMENT only. Production injects the same
#     env vars (JWT_RSA_PRIVATE_KEY_PEM / JWT_RSA_PUBLIC_KEY_PEM) from K8s Secrets.
#
# Fixes audit item A-22: "RS256 key generation script missing".
# -----------------------------------------------------------------------------
set -euo pipefail

KEY_BITS="${KEY_BITS:-2048}"
FORCE=0
OUTDIR="."

usage() {
  cat <<EOF
Usage: $0 [--force] [--key-bits N] [--out-dir DIR]

Options:
  --force          Overwrite existing key files if present.
  --key-bits N     RSA key size in bits (default: 2048; minimum 2048).
  --out-dir DIR    Directory to write files into (default: current dir).
  -h, --help       Show this help.

Outputs (written to --out-dir):
  jwt-rs256-private.pem   raw PKCS#8 private key
  jwt-rs256-public.pem    raw X.509 public key
  jwt-rs256.env           env file with single-line escaped PEM values
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --key-bits) KEY_BITS="$2"; shift 2 ;;
    --out-dir) OUTDIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# --- Validate key size ------------------------------------------------------
if ! [[ "$KEY_BITS" =~ ^[0-9]+$ ]]; then
  echo "ERROR: --key-bits must be a number, got '$KEY_BITS'" >&2
  exit 2
fi
if (( KEY_BITS < 2048 )); then
  echo "WARNING: key size ${KEY_BITS} bits is below the 2048-bit minimum required by ADR-A02." >&2
  echo "         Bumping key size to 2048 bits." >&2
  KEY_BITS=2048
fi
echo ">> RSA key size: ${KEY_BITS} bits"

# --- Tool check -------------------------------------------------------------
if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl not found in PATH. Install openssl (macOS: brew install openssl)." >&2
  exit 1
fi
OPENSSL_VER="$(openssl version 2>/dev/null || echo 'unknown')"
echo ">> Using ${OPENSSL_VER}"

# --- Output paths -----------------------------------------------------------
mkdir -p "$OUTDIR"
PRIV_PEM="${OUTDIR}/jwt-rs256-private.pem"
PUB_PEM="${OUTDIR}/jwt-rs256-public.pem"
ENV_FILE="${OUTDIR}/jwt-rs256.env"

# --- Overwrite protection ---------------------------------------------------
existing=()
for f in "$PRIV_PEM" "$PUB_PEM" "$ENV_FILE"; do
  if [[ -e "$f" && "$FORCE" -eq 0 ]]; then
    existing+=("$f")
  fi
done
if (( ${#existing[@]} > 0 )); then
  echo "ERROR: existing key file(s) found:" >&2
  printf '   - %s\n' "${existing[@]}" >&2
  echo "       Pass --force to overwrite." >&2
  exit 1
fi

# --- Generate key pair ------------------------------------------------------
# Private key: PKCS#8, unencrypted PEM ("-----BEGIN PRIVATE KEY-----").
echo ">> Generating ${KEY_BITS}-bit RSA private key (PKCS#8)..."
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:"${KEY_BITS}" -out "$PRIV_PEM"
chmod 600 "$PRIV_PEM"

# Public key: X.509 SubjectPublicKeyInfo PEM ("-----BEGIN PUBLIC KEY-----").
echo ">> Deriving public key (X.509)..."
openssl rsa -in "$PRIV_PEM" -pubout -out "$PUB_PEM" 2>/dev/null
chmod 644 "$PUB_PEM"

# --- Verify key length ------------------------------------------------------
ACTUAL_BITS="$(openssl rsa -in "$PRIV_PEM" -text -noout 2>/dev/null | head -1 | grep -oE '[0-9]+ bit' | grep -oE '[0-9]+')"
if [[ -z "${ACTUAL_BITS:-}" ]]; then
  echo "WARNING: could not determine actual key size; manual verification recommended." >&2
elif (( ACTUAL_BITS < 2048 )); then
  echo "WARNING: generated key is only ${ACTUAL_BITS} bits (< 2048). Regenerate with a larger size." >&2
else
  echo ">> Verified key length: ${ACTUAL_BITS} bits"
fi

# --- Sanity-check the private key ------------------------------------------
if ! openssl rsa -in "$PRIV_PEM" -check -noout >/dev/null 2>&1; then
  echo "ERROR: private key failed openssl consistency check." >&2
  exit 1
fi

# --- Build .env file (single-line, escaped \n) -----------------------------
# Convert multi-line PEM into a single line where newlines become literal "\n".
to_escaped_oneline() {
  # Reads PEM from stdin, emits: <header>\n<body>\n<footer>\n  (literal backslash-n)
  awk 'BEGIN{ORS=""} {if(NR>1) printf "\\n"; printf "%s",$0} END{printf "\\n"}'
}

PRIV_ESCAPED="$(to_escaped_oneline < "$PRIV_PEM")"
PUB_ESCAPED="$(to_escaped_oneline < "$PUB_PEM")"

{
  echo "# Auto-generated by scripts/gen-rs256-keys.sh"
  echo "# ADR-A02: RS256 (RSA ${KEY_BITS}-bit) JWT signing keys."
  echo "# LOCAL DEV ONLY - production uses Kubernetes Secrets. Do NOT commit."
  echo "# Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "JWT_RSA_PRIVATE_KEY_PEM=${PRIV_ESCAPED}"
  echo "JWT_RSA_PUBLIC_KEY_PEM=${PUB_ESCAPED}"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

# --- Summary ----------------------------------------------------------------
echo ""
echo ">> Done. Generated files in ${OUTDIR}:"
echo "   - ${PRIV_PEM}  (private key, PKCS#8)"
echo "   - ${PUB_PEM}   (public key, X.509)"
echo "   - ${ENV_FILE}  (env file, escaped single-line)"
echo ""
echo ">> For local dev, source the env file or export the vars:"
echo "   set -a; . ${ENV_FILE}; set +a"
echo ">> For production, store PEM content as K8s Secrets (never commit to git)."
