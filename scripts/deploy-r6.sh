#!/usr/bin/env bash
# deploy-r6.sh — D-231: render + apply Wave-1 with D-230 data-layer switch.
# Replicas: core-business/portal-bff=2, others=1 (node capacity sizing).
set -uo pipefail
NODE=10.167.2.175

python3 - <<'PYEOF' > /tmp/ep-wave1-r6.yaml
import sys, re, yaml
import subprocess
s = subprocess.run(['kubectl','kustomize','/Users/joelgong/claude-Projects/education-platform/deploy/k8s/overlays/applications-cluster'],
                   capture_output=True, text=True).stdout
for n in ['core-business','grading-service','portal-bff','order-platform-service','payment-platform-service','ws-gateway']:
    s = s.replace(f'education-platform/{n}:1.0.0', f'education-platform/{n}:1.0.0-r6')
docs = [d for d in yaml.safe_load_all(s) if d]
pat = re.compile(r'core-business|grading-service|order-platform|payment-platform|portal-bff|ws-gateway|common-config|dubbo-config|namespace')
out = [d for d in docs if d['kind'] in ('Namespace','ConfigMap') or pat.search((d.get('metadata') or {}).get('name',''))]
out = [d for d in out if d['kind'] not in ('NetworkPolicy','HorizontalPodAutoscaler')]
for d in out:
    if d['kind'] == 'Deployment':
        name = d['metadata']['name']
        d['spec']['replicas'] = 2 if ('core-business' in name or 'portal-bff' in name) else 1
yaml.safe_dump_all(out, sys.stdout)
PYEOF

echo "resources: $(grep -c '^kind:' /tmp/ep-wave1-r6.yaml)"
scp -q /tmp/ep-wave1-r6.yaml root@$NODE:/tmp/ep-wave1.yaml || exit 1
ssh root@$NODE "kubectl apply -f /tmp/ep-wave1.yaml" 2>&1 | grep -vE 'WARNING|post-quantum|decrypt|upgraded' | tail -5
echo "== rollout =="
ssh root@$NODE "for d in edu-core-business edu-grading-service edu-order-platform edu-payment-platform edu-portal-bff edu-ws-gateway; do kubectl -n education-platform rollout status deploy/\$d --timeout=180s 2>&1 | head -1; done" 2>&1 | grep -vE 'WARNING|post-quantum|decrypt|upgraded'
echo "== pods =="
ssh root@$NODE "kubectl -n education-platform get pods" 2>&1 | grep -vE 'WARNING|post-quantum|decrypt|upgraded'
