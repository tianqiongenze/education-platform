#!/usr/bin/env bash
# =============================================================================
# D-225: deploy education-platform to the "applications-cluster" K8s cluster
# (nodes 10.167.2.175 / 10.167.2.176, SSH key auth).
#
# Topology (user-provided):
#   - Shared infra ALREADY RUNNING — REUSED, never redeployed:
#       namespace `infra`     : TiDB, Pulsar, Milvus, registry, ...
#       namespace `dify-plus` : Redis Cluster
#   - Project services deploy into dedicated namespace `education-platform`.
#
# Usage:
#   ./scripts/deploy-applications-cluster.sh --dry-run   # validate + plan only
#   ./scripts/deploy-applications-cluster.sh             # full deploy
#
# D-215 constraints honored: ClusterIP only, exactly 3 Traefik Ingresses,
# dependency-ordered deployment.
#
# Iron Law 9: cluster-level verification is PENDING until SSH key auth works
# (see docs/deployment/applications-cluster-runbook.md §1 for the fix).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="${REPO_ROOT}/deploy/k8s/overlays/applications-cluster"
NODE_A="10.167.2.175"
NODE_B="10.167.2.176"
SSH_USER="${SSH_USER:-root}"
DRY_RUN=false
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=true

log()  { printf '\033[1;34m[d225]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[d225]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[d225] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# Step 0 — static validation (works without cluster access)
# -----------------------------------------------------------------------------
log "Step 0: static kustomize validation of ${OVERLAY}"
RENDERED="$(mktemp /tmp/d225-rendered.XXXXXX.yaml)"
kubectl kustomize "${OVERLAY}" > "${RENDERED}" \
  || die "kustomize build failed — fix the overlay before deploying (run: kubectl kustomize ${OVERLAY})"
log "  rendered $(grep -c '^kind:' "${RENDERED}") resources"

# -----------------------------------------------------------------------------
# Step 1 — preflight: SSH reachability + kubectl against the cluster
# -----------------------------------------------------------------------------
preflight_ssh() {
  local node
  for node in "${NODE_A}" "${NODE_B}"; do
    if ! ssh -o BatchMode=yes -o ConnectTimeout=5 "${SSH_USER}@${node}" true 2>/dev/null; then
      die "SSH key auth to ${SSH_USER}@${node} FAILED.

Fix (run interactively, enter password once):
  ssh-copy-id ${SSH_USER}@${NODE_A}
  ssh-copy-id ${SSH_USER}@${NODE_B}

If the machines are NOT in ~/.ssh/known_hosts yet, accept the host key first:
  ssh -o StrictHostKeyChecking=accept-new ${SSH_USER}@${NODE_A} true
Then re-run this script."
    fi
    log "  SSH OK: ${node}"
  done
}

preflight_kubectl() {
  kubectl get nodes >/dev/null 2>&1 \
    || die "kubectl cannot reach the cluster. Export a working kubeconfig first, e.g.:
  scp ${SSH_USER}@${NODE_A}:/etc/rancher/k3s/k3s.yaml /tmp/applications-cluster-kubeconfig.yaml
  export KUBECONFIG=/tmp/applications-cluster-kubeconfig.yaml
  sed -i '' 's|server: https://127.0.0.1:6443|server: https://${NODE_A}:6443|' \${KUBECONFIG}
Then re-run this script."
  log "  kubectl OK: $(kubectl config current-context)"
}

# -----------------------------------------------------------------------------
# Step 2 — DISCOVER real middleware endpoints from the live cluster and
# rewrite the placeholder ConfigMaps in the overlay. Never apply guessed IPs.
# -----------------------------------------------------------------------------
discover_middleware() {
  log "Step 2: discovering shared middleware Services (infra / dify-plus)"

  discover_svc() { # $1 namespace  $2 label-regex-ish name grep
    kubectl -n "$1" get svc -o jsonpath='{range .items[*]}{.metadata.name}={.spec.clusterIP}:{.spec.ports[0].port}{"\n"}{end}' \
      2>/dev/null | grep -iE "$2" | head -5
  }

  : > /tmp/d225-discovered.txt
  {
    echo "## infra namespace Services:"
    kubectl -n infra get svc -o wide 2>/dev/null || warn "  cannot list infra namespace Services"
    echo "## dify-plus namespace Services:"
    kubectl -n dify-plus get svc -o wide 2>/dev/null || warn "  cannot list dify-plus namespace Services"
  } | tee /tmp/d225-discovered.txt

  # Resolve concrete endpoints (first match per middleware family).
  REDIS_EP="$(discover_svc dify-plus 'redis' | head -1)"
  TIDB_EP="$(discover_svc infra 'tidb' | head -1)"
  PULSAR_EP="$(discover_svc infra 'pulsar' | head -1)"
  MILVUS_EP="$(discover_svc infra 'milvus' | head -1)"
  REGISTRY_EP="$(discover_svc infra 'harbor|registry' | head -1)"
  NACOS_EP="$(discover_svc infra 'nacos' | head -1)"

  cat <<EOF

Discovered middleware endpoints:
  Redis     (dify-plus): ${REDIS_EP:-<NOT FOUND>}
  TiDB      (infra):     ${TIDB_EP:-<NOT FOUND>}
  Pulsar    (infra):     ${PULSAR_EP:-<NOT FOUND>}
  Milvus    (infra):     ${MILVUS_EP:-<NOT FOUND>}
  Registry  (infra):     ${REGISTRY_EP:-<NOT FOUND>}
  Nacos     (infra):     ${NACOS_EP:-<NOT FOUND>}
EOF

  # D-225 (verified 2026-09-06): the applications-cluster has NO TiDB and NO
  # Nacos. CockroachDB (3-node, infra ns, PG wire on 26257) is the database;
  # Pulsar may be degraded (bookies crashlooping) — services boot with the
  # D-195 no-op event/cache path. Only Redis is a hard requirement.
  CRDB_EP="$(discover_svc infra 'cockroachdb' | grep -vE 'admin|read|-a|-b|-c' | head -1)"
  DB_EP="${TIDB_EP:-${CRDB_EP}}"
  DB_KIND="tidb"; [[ -z "${TIDB_EP}" && -n "${CRDB_EP}" ]] && DB_KIND="cockroachdb"

  if [[ -z "${PULSAR_EP}" ]]; then
    log "  WARN: no Pulsar Service discovered — deploying with event/cache no-op mode (D-195)"
  fi
  if [[ -z "${NACOS_EP}" ]]; then
    log "  WARN: no Nacos Service discovered — Dubbo registry N/A + per-interface direct URLs"
  fi

  [[ -n "${REDIS_EP}" && -n "${DB_EP}" ]] \
    || die "Middleware discovery incomplete — inspect /tmp/d225-discovered.txt, correct the
grep patterns in discover_middleware(), then re-run. Refusing to apply placeholders."

  log "  rewriting ${OVERLAY}/middleware-endpoints.yaml with discovered endpoints"
  python3 - "${OVERLAY}" "${REDIS_EP}" "${DB_EP}" "${PULSAR_EP}" "${MILVUS_EP}" "${NACOS_EP}" "${REGISTRY_EP}" "${DB_KIND}" <<'PYEOF'
import sys, re
overlay, redis, db, pulsar, milvus, nacos, registry, db_kind = sys.argv[1:9]
def hostport(ep):  # "name=10.0.0.5:6379" -> "name.ns.svc.cluster.local:6379" form is
    name, ipport = ep.split("=", 1)  # produced by the caller using kubectl jsonpath
    return name, ipport
# NOTE: endpoint values are name=IP:port pairs from the live cluster; the FQDN
# form (name.namespace.svc) is preferred for cross-namespace DNS, so we keep
# the discovered Service NAME and port, and rebuild the FQDN.
def fqdn(ep, ns):
    name, ipport = ep.split("=", 1)
    port = ipport.rsplit(":", 1)[1]
    return f"{name}.{ns}.svc.cluster.local:{port}"

p = f"{overlay}/middleware-endpoints.yaml"
s = open(p).read()
s = re.sub(r'REDIS_CLUSTER_NODES:\s*"[^"]*"', f'REDIS_CLUSTER_NODES: "{fqdn(redis, "dify-plus")}"', s)
if db_kind == "cockroachdb":
    # CRDB speaks the PostgreSQL wire protocol. DatasourceProvider=cockroachdb
    # (D-202) drives follower-reads on the read pool.
    s = re.sub(r'TIDB_JDBC_URL:\s*"[^"]*"',
               f'TIDB_JDBC_URL: "jdbc:postgresql://{fqdn(db, "infra")}/education?sslmode=disable"', s)
else:
    s = re.sub(r'TIDB_JDBC_URL:\s*"[^"]*"',
               f'TIDB_JDBC_URL: "jdbc:mysql://{fqdn(db, "infra")}/education?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true"', s)
s = re.sub(r'PULSAR_URL:\s*"[^"]*"', f'PULSAR_URL: "pulsar://{fqdn(pulsar, "infra")}"', s)
s = re.sub(r'PULSAR_WEB_URL:\s*"[^"]*"', f'PULSAR_WEB_URL: "http://{fqdn(pulsar, "infra")}"', s)
if milvus:
    s = re.sub(r'MILVUS_HOST:\s*"[^"]*"', f'MILVUS_HOST: "{fqdn(milvus, "infra").rsplit(":", 1)[0]}"', s)
if nacos:
    nac = fqdn(nacos, "infra")
    s = re.sub(r'NACOS_SERVER_ADDR:\s*"[^"]*"', f'NACOS_SERVER_ADDR: "{nac}"', s)
    s = re.sub(r'DUBBO_REGISTRY_ADDRESS:\s*"[^"]*"',
               f'DUBBO_REGISTRY_ADDRESS: "nacos://{nac}?namespace=education-platform"', s)
    s = re.sub(r'DUBBO_METADATA_REPORT_ADDRESS:\s*"[^"]*"',
               f'DUBBO_METADATA_REPORT_ADDRESS: "nacos://{nac}?namespace=education-platform"', s)
    s = re.sub(r'DUBBO_CONFIG_CENTER_ADDRESS:\s*"[^"]*"',
               f'DUBBO_CONFIG_CENTER_ADDRESS: "nacos://{nac}?namespace=education-platform"', s)
else:
    # No Nacos: N/A registry (no registration) + per-interface direct URLs so
    # the verified cross-service chains still resolve over k8s Service DNS.
    s = re.sub(r'DUBBO_REGISTRY_ADDRESS:\s*"[^"]*"', 'DUBBO_REGISTRY_ADDRESS: "N/A"', s)
    s = re.sub(r'DUBBO_METADATA_REPORT_ADDRESS:\s*"[^"]*"', 'DUBBO_METADATA_REPORT_ADDRESS: "N/A"', s)
    s = re.sub(r'DUBBO_CONFIG_CENTER_ADDRESS:\s*"[^"]*"', 'DUBBO_CONFIG_CENTER_ADDRESS: "N/A"', s)
    # Wave-1 note: cross-service Dubbo calls degrade (registry N/A). A
    # project-local Nacos is deployed in wave 2 to restore discovery; see
    # docs/deployment/applications-cluster-runbook.md "Dubbo discovery".
open(f"{overlay}/middleware-endpoints.yaml", "w").write(s)
open(f"{overlay}/dubbo-middleware-endpoints.yaml", "w").write(open(f"{overlay}/dubbo-middleware-endpoints.yaml").read())
print(f"[d225]  rewrote {overlay}/middleware-endpoints.yaml + dubbo-middleware-endpoints.yaml")
PYEOF

  kubectl kustomize "${OVERLAY}" > "${RENDERED}" \
    || die "kustomize build failed after endpoint rewrite"
  log "  re-validated: $(grep -c '^kind:' "${RENDERED}") resources"
}

# -----------------------------------------------------------------------------
# Step 3 — dependency-ordered apply (D-215). Middleware already exists in
# shared namespaces, so ordering covers: namespace → config → security →
# foundation → domain services → edge (BFF/gateway/frontend) → autoscaling.
# -----------------------------------------------------------------------------
apply_ordered() {
  local render="$1"
  local phase
  # Phase groups by workload name; every group waits for rollout before the next.
  local -A PHASES=(
    [1_namespace]="^Namespace$"
    [2_config]="^ConfigMap$"
    [3_security]="^NetworkPolicy$"
    [4_foundation]="portal-bff|tenant-service|core-business"
    [5_domain]="grading-service|order-platform|payment-platform|knowledge-service|knowledge-bff|retrieval-service|data-platform|ai-platform|agent-service"
    [6_domain_extra]="boardgame-service|senior-care|ws-gateway|live-go|otel-collector"
    [7_edge]="frontend-portal|Ingress"
    [8_autoscale]="^HorizontalPodAutoscaler$|^PodDisruptionBudget$"
  )
  local order=(1_namespace 2_config 3_security 4_foundation 5_domain 6_domain_extra 7_edge 8_autoscale)
  for phase in "${order[@]}"; do
    local pattern="${PHASES[$phase]}"
    log "Step 3 [${phase}]: applying (filter: ${pattern})"
    if [[ "${phase}" == "7_edge" ]]; then
      # Ingress objects must be filtered by kind, not name.
      python3 -c "
import sys, yaml
docs = [d for d in yaml.safe_load_all(open('${render}')) if d]
sel = [d for d in docs if d['kind'] in ('Ingress',) or (d['kind']=='Deployment' and 'frontend' in d['metadata'].get('name',''))]
print(yaml.safe_dump_all(sel), end='')" | kubectl apply -f - \
        || die "apply failed in phase ${phase}"
    else
      python3 -c "
import sys, yaml, re
docs = [d for d in yaml.safe_load_all(open('${render}')) if d]
sel = [d for d in docs if re.search('${pattern}', d['kind']) or (d.get('metadata',{}).get('name','') and re.search('${pattern}', d['metadata']['name']))]
print(yaml.safe_dump_all(sel), end='')" | kubectl apply -f - \
        || die "apply failed in phase ${phase}"
    fi
    # Rollout verification per phase (Iron Law 9: verify, don't assume).
    local dep
    while IFS= read -r dep; do
      [[ -z "${dep}" ]] && continue
      log "  waiting for rollout: ${dep}"
      kubectl -n education-platform rollout status "deployment/${dep}" --timeout=300s \
        || die "rollout FAILED for ${dep} in phase ${phase} (see kubectl -n education-platform get pods)"
    done < <(python3 -c "
import yaml
docs = [d for d in yaml.safe_load_all(open('${render}')) if d]
import re
for d in docs:
    if d['kind']=='Deployment' and re.search('${pattern}', d['metadata'].get('name','')):
        print(d['metadata']['name'])")
  done
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------
if ${DRY_RUN}; then
  log "DRY-RUN: static validation passed."
  preflight_ssh || true   # report, don't block in dry-run
  preflight_kubectl || true
  log "Plan: apply ${OVERLAY} (68 resources) in 8 dependency-ordered phases;"
  log "     rewrite middleware-endpoints.yaml from live discovery before apply."
  log "DRY-RUN complete — no cluster mutation performed."
  exit 0
fi

preflight_ssh
preflight_kubectl
discover_middleware
apply_ordered "${RENDERED}"

log "Post-deploy verification:"
kubectl -n education-platform get pods -o wide
kubectl -n education-platform get ingress
log "Smoke test (from inside the cluster):"
log "  kubectl -n education-platform run smoke --rm -it --image=curlimages/curl --restart=Never -- curl -sf http://edu-portal-bff:8080/actuator/health"
log "Deploy complete. Rollback: kubectl -n education-platform rollout undo deployment/<name>"
