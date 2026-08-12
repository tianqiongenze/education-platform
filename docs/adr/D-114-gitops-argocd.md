# D-114: GitOps with ArgoCD (replacing manual kubectl apply)

- **Status:** Accepted
- **Date:** 2026-08-09
- **Decider:** Platform SRE + Architecture
- **Supersedes:** Manual `kubectl apply -k` deployment practice (implicit, undocumented)
- **Related:** D-035 (ConfigMaps via GitOps), D-041 (Karmada multi-cell scheduling), D-079/D-080 (TiDB distributed cluster)

## Context

The platform's K8s manifests already declare
`app.kubernetes.io/managed-by: argocd` in
`deploy/k8s/base/kustomization.yaml` `commonLabels`, and D-035 rule 6 states
"ConfigMaps are managed via GitOps (ArgoCD/Flux)". In practice, however:

- There are **zero** ArgoCD `Application` / `ApplicationSet` / `AppProject`
  manifests anywhere in the repo.
- Deployment is entirely manual: `kubectl apply -k deploy/k8s/overlays/cell-hz`.
- There is no auto-sync, no drift detection, no self-heal. An out-of-band
  `kubectl edit` is invisible and survives indefinitely.
- Multi-cell rollout (4 cells: hz/sz/bj/sg, per D-041) requires running the
  apply command four times against four cluster contexts — error-prone and
  not reproducible.
- Image promotion (D-035 rule 6) updates `images:` tags in
  `base/kustomization.yaml`, but nothing applies the change automatically.
- Rollback is manual `kubectl rollout undo`, decoupled from Git history.

The label `managed-by: argocd` is therefore aspirational, not factual. This
decision makes it factual.

The user explicitly requested: "要做好全面支持GitOps:引入 ArgoCD/Flux,
deploy/k8s 入 git 后自动同步, 替代手动 kubectl apply".

## Decision

Adopt **ArgoCD** with an **app-of-apps + ApplicationSet** topology as the
single GitOps engine for the education platform. Concretely:

1. All deployment state lives in `deploy/k8s/**` (unchanged structure).
2. ArgoCD manifests live in `deploy/gitops/argocd/`:
   - `app-of-apps.yaml` — root Application, the only hand-applied manifest.
   - `argocd-project.yaml` — AppProject scoping repos/clusters/RBAC.
   - `argocd-config.yaml` — `argocd-cm` customizations (ignoreDifferences for
     HPA/TiDB/Karmada, health checks, repo credential template).
   - `applicationset-base.yaml` (sync wave 0) — base infra.
   - `applicationset-cells.yaml` (sync wave 1) — 4 cell overlays.
   - `applicationset-apps.yaml` (sync wave 2) — 8 application services.
3. Sync policy: `automated.prune + selfHeal` on every Application. Git is the
   sole source of truth; out-of-band edits are reverted.
4. `ignoreDifferences` exempts HPA-managed `spec.replicas` and CRD `status`
   fields so selfHeal does not fight the autoscaler or operators.
5. Multi-cluster fan-out reuses the existing D-041 Karmada
   `PropagationPolicy` (hub-spoke topology). A direct multi-cluster topology
   is documented as a fallback in the README.
6. Image promotion (D-035 rule 6): CI opens a PR updating `images:` in
   `base/kustomization.yaml`; merge triggers ArgoCD auto-sync of the affected
   Application.
7. Rollback = `git revert` + push. ArgoCD auto-syncs the reverted state.

The `managed-by: argocd` label becomes truthful: every resource ArgoCD
applies carries it, and `selfHeal` enforces it.

## Alternatives considered (Iron Law 8: ≥3)

### (a) ArgoCD — CHOSEN
- **Pros:** Declarative `Application`/`ApplicationSet` CRDs fit the
  K8s-native model; app-of-apps pattern gives one-command bootstrap; UI +
  CLI for observability; mature multi-cluster support via cluster registry;
  integrates cleanly with the existing Karmada PropagationPolicy (manifests
  applied to host, Karmada fans out). `ignoreDifferences` cleanly handles
  HPA/operator-owned fields. RBAC via AppProject maps to the existing SRE
  vs developer split.
- **Cons:** Heavier control plane (multiple controllers); `ApplicationSet`
  templating is less flexible than pure Helm; sync-wave semantics require
  discipline.

### (b) FluxCD
- **Pros:** Lighter footprint (single controller per source); first-class
  HelmRepository / ImageRepository / ImagePolicy automation for tag
  promotion; `Kustomization` CRD is a near-perfect fit for the existing
  `deploy/k8s/base` + `overlays/cell-*` layout; TLS-based notification
  webhooks are simpler than ArgoCD's.
- **Cons:** No first-class UI (only Grafana dashboards / CLI); multi-cluster
  requires either Flux-per-cluster or a separate multi-cluster controller,
  which would duplicate or conflict with the D-041 Karmada propagation
  already in place; no `ignoreDifferences` equivalent — HPA drift would need
  `# flux:ignore` annotations on every managed resource; smaller pool of
  operators familiar with it on the team. Picking Flux would mean maintaining
  two multi-cluster fan-out mechanisms (Flux + Karmada).

### (c) Rancher Fleet
- **Pros:** Designed for large multi-cluster fleets; `GitRepo` + `Bundle`
  model with target cluster selectors maps naturally to the 4-cell layout;
  opinionated overlay pattern (`fleet.yaml`) overlaps with the existing
  kustomize overlays; integrates with Rancher if that is the cluster manager.
- **Cons:** Tightly coupled to the Rancher ecosystem — adopting it without
  Rancher as the cluster manager gives little benefit and adds a dependency;
  smaller community than ArgoCD/Flux; less mature `kustomize` passthrough
  (Fleet prefers its own overlay syntax), which would force restructuring the
  existing `deploy/k8s/overlays/cell-*` layout; no equivalent of
  `ignoreDifferences` for HPA, risking the same drift fights.

### Selection rationale
ArgoCD won because (1) the repo already labels resources
`managed-by: argocd` and D-035 rule 6 names ArgoCD first, so adopting it
honors existing intent; (2) `ApplicationSet` + app-of-apps gives a single
bootstrap command and a clean sync-wave ordering that the others approximate
less cleanly; (3) `ignoreDifferences` directly solves the HPA/TiDB drift
problem that Flux and Fleet handle more awkwardly; (4) it composes with the
existing Karmada propagation rather than competing with it.

## Consequences

**Positive:**
- One-command bootstrap (`kubectl apply -f app-of-apps.yaml`).
- Drift is auto-corrected (`selfHeal`); out-of-band `kubectl edit` is
  reverted on the next reconcile, closing a real audit gap.
- Rollback becomes `git revert` — auditable, reversible, reviewable.
- Image promotion end-to-end (D-035 rule 6) is now automated: tag bump in
  `base/kustomization.yaml` → ArgoCD sync → RollingUpdate.
- Multi-cell rollout is one Git push, not four `kubectl apply` commands.
- The `managed-by: argocd` label is now factual, not aspirational.

**Negative / risks:**
- ArgoCD control plane is a new critical dependency. Mitigation: app-of-apps
  makes it fully reconstructable from Git; no ArgoCD-internal state needs
  backup.
- `selfHeal: true` will revert legitimate emergency `kubectl` edits. Runbook
  must teach the break-glass: `argocd app set <app> --sync-policy none` first.
- `ignoreDifferences` on `spec.replicas` means a malicious/accidental manual
  scale-down is *not* reverted by ArgoCD (HPA is expected to own this).
  Mitigation: HPA minReplicas bounds the floor; alerts on HPA status.
- Sync waves add ordering complexity — a wave-0 failure blocks waves 1/2 by
  design. Acceptable: base infra must exist before apps.
- Adds 6 new manifests to maintain; all are heavily commented.

**Neutral:**
- `deploy/k8s/**` structure is unchanged; only `deploy/gitops/argocd/` is new.
- Local k3s verification flow (`overlays/local-verify`) is unaffected.

## Implementation notes

- All cluster URLs are placeholders (`https://hz-cluster-api:6443`, etc.) and
  must be replaced with real cluster API URLs after `argocd cluster add`.
- The git `repoURL` is a placeholder (`https://github.com/edu-platform/education-platform.git`).
- CRDs (TiDB Operator, Karmada, Prometheus Operator) must be pre-installed;
  ArgoCD does not install operator CRDs rendered by kustomize.
- `argocd-config.yaml` is applied via server-side apply so it only manages
  the keys it owns in `argocd-cm`.
