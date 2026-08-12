# ArgoCD GitOps — Education Platform (D-114)

This directory makes `deploy/k8s` declaratively synced by ArgoCD, replacing
the previous manual `kubectl apply -k deploy/k8s/overlays/cell-hz` workflow.

Decision record: `docs/adr/D-114-gitops-argocd.md`.

## Files

| File | Purpose | Sync wave |
| --- | --- | --- |
| `app-of-apps.yaml` | Root Application — the **only** manifest applied by hand. Manages everything below. | -1 |
| `argocd-project.yaml` | AppProject `education-platform`: allowed repos, destinations, RBAC roles. | -1 |
| `argocd-config.yaml` | argocd-cm customizations: ignoreDifferences, health checks, repo credential template. | -1 |
| `applicationset-base.yaml` | Base infra (namespace, configmap, ingress, TiDB, OTel, Karmada policies). | 0 |
| `applicationset-cells.yaml` | 4 cell overlays: hz / sz / bj / sg. | 1 |
| `applicationset-apps.yaml` | 16 application services (portal-bff, ws-gateway, core-business, order-platform, payment-platform, grading-service, ai-platform, frontend-portal, and more). | 2 |

Sync waves guarantee ordering: base → cells → apps. Within a wave, ArgoCD
reconciles in parallel.

## Prerequisites

1. **ArgoCD installed** in the control-plane cluster, in namespace `argocd`:
   ```bash
   kubectl create namespace argocd
   kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
   ```
2. **ArgoCD CLI** installed locally (`brew install argocd`).
3. **Repo connected.** Create a deploy key / SSH key and a repository secret:
   ```bash
   kubectl create secret generic repo-creds-ssh -n argocd \
     --from-file=sshPrivateKey=~/.ssh/edu_platform_deploy_key \
     --label argocd.argoproj.io/secret-type=repository
   ```
   Replace the placeholder `repoURL` (`https://github.com/tianqiongenze/education-platform.git`)
   in each manifest with your real repo URL if it differs.
4. **Member clusters registered** (multi-cluster topology only). For each cell:
   ```bash
   argocd cluster add cell-hz --label ep.io/cell=cell-hz
   argocd cluster add cell-sz --label ep.io/cell=cell-sz
   argocd cluster add cell-bj --label ep.io/cell=cell-bj
   argocd cluster add cell-sg --label ep.io/cell=cell-sg
   ```
   Replace the placeholder `server:` URLs (`https://hz-cluster-api:6443`, etc.)
   in `argocd-project.yaml` and `applicationset-cells.yaml` with the real
   cluster API URLs returned by `argocd cluster list`.
5. **CRDs pre-installed** in target clusters: TiDB Operator (`pingcap.com`),
   Karmada (`policy.karmada.io`), Prometheus Operator (`monitoring.coreos.com`).
   ArgoCD will not install CRDs that `kustomize` renders if they are missing —
   install operator CRDs out-of-band.

## Bootstrap (one-time)

```bash
kubectl apply -f deploy/gitops/argocd/app-of-apps.yaml
```

The root Application reconciles the entire `deploy/gitops/argocd/` directory:
it creates the AppProject, then the three ApplicationSets, which in turn each
create their child Applications. Watch progress:

```bash
argocd app get ep-root
argocd app list
```

After bootstrap, never run `kubectl apply` against `deploy/k8s` again — all
changes go through Git.

## How auto-sync works

- Every Application has `syncPolicy.automated: { prune: true, selfHeal: true }`.
- ArgoCD polls Git every ~3 min (or receives a webhook push) and re-renders
  kustomize. If the rendered manifest differs from the cluster state, ArgoCD
  applies the diff.
- `selfHeal: true` means an out-of-band `kubectl edit` is reverted back to
  Git on the next reconcile — protecting the declared state.
- `prune: true` means resources deleted from Git are deleted from the cluster.
- `ignoreDifferences` (in `argocd-config.yaml` and each ApplicationSet) tells
  ArgoCD to ignore HPA-managed `spec.replicas` drift and CRD `status` fields,
  so selfHeal does not fight the autoscaler or operators.

## Triggering a deployment

The platform uses image-tag promotion (D-035 rule 6):

1. CI builds an image and pushes it to `harbor.edu.internal/education-platform/<svc>:<tag>`.
2. CI opens a PR updating the matching line under `images:` in
   `deploy/k8s/base/kustomization.yaml`:
   ```yaml
   images:
     - name: harbor.edu.internal/education-platform/portal-bff
       newTag: 1.2.3   # <- updated
   ```
3. PR is reviewed and merged to `main`.
4. ArgoCD detects the change, re-renders kustomize, and syncs the affected
   Application (`ep-app-portal-bff`). The new `image:` triggers a RollingUpdate.

For config-only changes (ConfigMap, Ingress, HPA thresholds), just edit the
YAML in `deploy/k8s/base` or the relevant `overlays/cell-*` and push — ArgoCD
syncs the corresponding Application.

## Rollback

GitOps rollback = Git revert.

```bash
git revert <bad-commit-sha>
git push
```

ArgoCD auto-syncs the reverted state. For faster rollback during an active
incident, disable auto-sync temporarily and use ArgoCD's history:

```bash
argocd app set ep-app-portal-bff --sync-policy none     # stop auto-sync
argocd app rollback ep-app-portal-bff <revision-id>     # manual rollback
# once stable, re-enable auto-sync:
argocd app set ep-app-portal-bff --sync-policy automated --auto-prune --self-heal
```

Re-enabling `selfHeal` will re-converge the cluster to whatever is in Git, so
make sure the Git revert has merged first.

## Multi-cluster setup (Karmada integration)

Two supported topologies — choose one:

**Topology A — Hub-spoke (recommended for the existing D-041 Karmada setup):**
All ApplicationSets target `https://kubernetes.default.svc` (the Karmada host
where ArgoCD runs). The rendered manifests are applied to the host, then
`deploy/k8s/base/karmada-propagation.yaml` (PropagationPolicy +
OverridePolicy) fans them out to member cells with weighted replica scheduling
(hz 40 / sz 30 / bj 20 / sg 10) and per-cell ConfigMap overrides. No per-cell
ArgoCD credentials required.

**Topology B — Direct multi-cluster:**
Each cell Application targets its own cluster URL. ArgoCD needs credentials
for every cell (registered via `argocd cluster add`). Use this if you want
ArgoCD to be the single source of truth and skip Karmada propagation.

`applicationset-cells.yaml` defaults to topology B with placeholder cluster
URLs. To switch to topology A, change every `server:` value to
`https://kubernetes.default.svc` and delete the four cell entries from the
list generator (a single in-cluster entry is enough).

## Disaster recovery

- **ArgoCD control plane lost:** re-run the bootstrap command against a fresh
  ArgoCD install. The `app-of-apps.yaml` reconstructs the entire Application
  tree from Git. No ArgoCD state needs backup beyond Git itself.
- **A cell cluster lost:** Karmada reschedules replicas to surviving cells per
  the PropagationPolicy `spreadConstraints` (min 3 regions). Once the cell is
  rebuilt and re-registered (`argocd cluster add`), ArgoCD re-syncs it.
- **Git repo unavailable:** ArgoCD continues reconciling from its last-known
  state for up to its cache TTL; no auto-rollback. To freeze deploys during a
  Git outage: `argocd app set ep-root --sync-policy none`.
- **Bad manifest merged:** `git revert` + push. ArgoCD auto-syncs the
  reverted state. If auto-sync propagated the bad manifest to all cells, the
  revert rolls back all cells in the same wave-based order.

## Verification

After bootstrap, confirm all Applications are `Healthy` and `Synced`:

```bash
argocd app list -l app.kubernetes.io/part-of=education-platform
```

Expected output: 1 base + 4 cell + 16 app = 21 child Applications, plus the
root `ep-root`. All should show `Synced` / `Healthy`.

Local smoke-test on k3s without ArgoCD is unchanged — use
`deploy/k8s/overlays/local-verify` with `kubectl apply -k`.
