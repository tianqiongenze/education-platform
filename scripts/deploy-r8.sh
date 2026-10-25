#!/usr/bin/env bash
# deploy-r8.sh — D-232: ONE-SHOT idempotent restore of the education-platform
# namespace on applications-cluster. Survives the external namespace+image
# reaper: recreates ns, jwt-keys secret (SPKI/PKCS8), configmaps, deployments
# and re-loads node images from the local jib tars (no rebuild needed).
#
# Preconditions (all verified present):
#   - local tars: <svc>/target/jib-image.tar        (r7-level code)
#   - /tmp/ws-gateway                              (linux/amd64 static binary)
#   - local kubectl + kustomize, ssh key to both nodes
set -uo pipefail
NODE=10.167.2.175; WORKER=10.167.2.176
ROOT=/Users/joelgong/claude-Projects/education-platform
NS=education-platform
R=1.0.0-r8

log(){ echo "[$(date +%H:%M:%S)] $*"; }
sshr(){ ssh root@"$1" "$2" 2>&1 | grep -vE 'WARNING|post-quantum|decrypt|upgraded'; }

# ---- 1. render manifests (r8 tag, replica sizing) ----
log "render manifests"
python3 - <<'PYEOF' > /tmp/ep-all-r8.yaml
import sys, re, yaml, subprocess
s = subprocess.run(['kubectl','kustomize','/Users/joelgong/claude-Projects/education-platform/deploy/k8s/overlays/applications-cluster'],
                   capture_output=True, text=True).stdout
for n in ['core-business','grading-service','portal-bff','order-platform-service','payment-platform-service','ws-gateway']:
    s = s.replace(f'education-platform/{n}:1.0.0', f'education-platform/{n}:1.0.0-r8')
docs = [d for d in yaml.safe_load_all(s) if d]
pat = re.compile(r'core-business|grading-service|order-platform|payment-platform|portal-bff|ws-gateway|common-config|dubbo-config|namespace')
out = [d for d in docs if d['kind'] in ('Namespace','ConfigMap') or pat.search((d.get('metadata') or {}).get('name',''))]
out = [d for d in out if d['kind'] not in ('NetworkPolicy','HorizontalPodAutoscaler')]
for d in out:
    if d['kind'] == 'Deployment':
        n = d['metadata']['name']
        d['spec']['replicas'] = 2 if ('core-business' in n or 'portal-bff' in n) else 1
yaml.safe_dump_all(out, sys.stdout)
PYEOF
[ -s /tmp/ep-all-r8.yaml ] || { echo "render failed"; exit 1; }
log "rendered: $(grep -c '^kind:' /tmp/ep-all-r8.yaml) resources"

# ---- 2. namespace + secret (regenerate keys ON MASTER) ----
log "ensure ns + jwt-keys secret"
sshr "$NODE" "kubectl get ns $NS >/dev/null 2>&1 || kubectl create ns $NS
kubectl -n $NS get secret jwt-keys >/dev/null 2>&1 || {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/k.pem 2>/dev/null
  openssl rsa -in /tmp/k.pem -pubout -out /tmp/p.pem 2>/dev/null
  kubectl -n $NS create secret generic jwt-keys \
    --from-file=private.pem=/tmp/k.pem --from-file=public.pem=/tmp/p.pem \
    --from-file=jwt-private.pem=/tmp/k.pem --from-file=jwt-public.pem=/tmp/p.pem >/dev/null
  rm -f /tmp/k.pem /tmp/p.pem; echo secret-created; }"

# ---- 3. apply manifests ----
log "apply manifests"
scp -q /tmp/ep-all-r8.yaml root@$NODE:/tmp/ep-all.yaml
sshr "$NODE" "kubectl apply -f /tmp/ep-all.yaml | tail -3"

# ---- 4. load images on both nodes (from local tars, via Mac relay) ----
log "load images on both nodes"
declare -A TARS=(
  [core-business]=$ROOT/services/core-business/target/jib-image.tar
  [grading-service]=$ROOT/services/grading-service/target/jib-image.tar
  [portal-bff]=$ROOT/services/portal-bff/target/jib-image.tar
  [order-platform-service]=$ROOT/services/order-platform/order-platform-service/target/jib-image.tar
  [payment-platform-service]=$ROOT/services/payment-platform/payment-platform-service/target/jib-image.tar
)
for n in "${!TARS[@]}"; do
  t=${TARS[$n]}
  [ -f "$t" ] || { log "WARN missing tar $t"; continue; }
  scp -q "$t" root@$NODE:/tmp/ep-image.tar
  sshr "$NODE" "docker load -q -i /tmp/ep-image.tar && docker tag harbor.edu.internal/education-platform/$n:1.0.0 harbor.edu.internal/education-platform/$n:$R && rm /tmp/ep-image.tar"
  ssh root@$NODE "docker save harbor.edu.internal/education-platform/$n:$R" 2>/dev/null | ssh root@$WORKER "docker load" >/dev/null 2>&1
  log "loaded $n:$R (both nodes)"
  sleep 5
done
# ws-gateway: scratch build from static binary (on worker first — it schedules there)
if [ -f /tmp/ws-gateway ]; then
  for h in $NODE $WORKER; do
    scp -q /tmp/ws-gateway root@$h:/tmp/ws-gateway
    sshr "$h" "printf 'FROM scratch\nCOPY ws-gateway /ws-gateway\nEXPOSE 8081\nENTRYPOINT [\"/ws-gateway\"]\n' > /tmp/Dockerfile.ws && cd /tmp && docker build -q -f Dockerfile.ws -t harbor.edu.internal/education-platform/ws-gateway:$R . >/dev/null && echo gw-$h-ok"
  done
fi

# ---- 5. restart + status ----
sshr "$NODE" "kubectl -n $NS rollout restart deploy >/dev/null 2>&1; echo restarted"
log "waiting 170s for first boots..."; sleep 170
sshr "$NODE" "kubectl -n $NS get pods -o wide | grep -vE 'Completed'"
