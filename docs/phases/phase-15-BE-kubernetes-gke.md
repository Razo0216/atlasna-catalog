# Phase 15 (Platform) — Kubernetes on GKE & Scaling

| | |
|---|---|
| **Repo** | `atlasna-catalog` + `infra/` (Terraform) + new `deploy/` (Helm charts) |
| **Prerequisite** | Phase 11 (Terraform, WIF, Cloud Run staging) and Phase 13 (assistant) done |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §3, §6, §8, Phase 11 ADRs |
| **Estimate** | 4 weeks |

> ### Be honest about why
> Cloud Run is the right production home for a one-store workload: cheaper, simpler, scales to zero. **This phase moves staging to GKE to learn Kubernetes properly** (the most requested infrastructure skill in cloud roles). Keep the Cloud Run deployment working and documented; ADR-0041 records the trade-offs. Everything you build here also runs on any Kubernetes (including an Algerian host — gate G1).

## 1. Goal
All services run on a **GKE Autopilot** (or Standard — decide in the ADR) cluster provisioned by Terraform, packaged as **Helm charts**, exposed through a Google Cloud load balancer via the **Gateway API**, autoscaling on load, with **zero-downtime rollouts and rollbacks**, pods authenticating to GCP through **Workload Identity**, and policies that default to deny.

## 2. Scope
**In:** GKE cluster (Terraform) · namespaces & RBAC · Helm charts per service · Deployments/Services/Gateway-HTTPRoute · config & secrets (Secret Manager CSI/External Secrets) · probes · resource requests/limits · HPA (CPU + custom/Kafka-lag via KEDA optional) · PodDisruptionBudgets · NetworkPolicies · Workload Identity · migration Jobs · CronJobs (replace Quartz-style batch where sensible) · progressive delivery · cost & autoscaling tests · CI/CD to GKE.
**Out:** service mesh (record as a decision, don't build unless time), multi-cluster, GitOps controller (optional stretch: Argo CD/Config Sync).

## 3. Steps

### Step 0 — Decisions & preflight
Branch `phase-15-gke`. ADR-0041: why GKE, **Autopilot vs Standard** (Autopilot: less ops, pay per pod request — recommended for learning/cost; Standard: node control), region/zone, cluster version & **release channel**, private nodes. Check current pricing/free-tier of the cluster management fee and Autopilot; update `docs/cost.md`; plan `make cluster-down`.

### Step 1 — Cluster as code
Terraform module `gke_cluster`: **private nodes**, authorised networks for the control plane, VPC-native (alias IPs), release channel, **Workload Identity enabled**, Shielded nodes, **Binary Authorization** (audit mode) noted, logging/monitoring (Managed Prometheus) on, **network policy enabled**, maintenance window, labels, deletion protection for prod. Dedicated node service account (**not** the default compute SA) with minimal roles. Namespaces per environment/component with **ResourceQuota/LimitRange**.

### Step 2 — Packaging: one Helm chart per service (+ a shared library chart)
Templates: `Deployment` (strategy `RollingUpdate`, `maxUnavailable: 0`, `maxSurge: 25%`, `terminationGracePeriodSeconds` ≥ app shutdown timeout, `preStop` sleep so the load balancer drains), `Service`, `HTTPRoute`, `ServiceAccount` (annotated for Workload Identity), `ConfigMap`, `PodDisruptionBudget`, `HorizontalPodAutoscaler`, `NetworkPolicy`, optional `CronJob`/`Job`. Values per environment; **images referenced by digest or immutable tag**, never `latest`.
Pod hygiene (enforced by policy tests): `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, drop all capabilities, seccomp `RuntimeDefault`, no host namespaces, **resource requests and limits on every container**, probes defined (startup + readiness + liveness — startup probe because Spring Boot starts slowly), JVM memory flags aligned with limits (`MaxRAMPercentage`).
Lint/validate in CI: `helm lint`, `helm template | kubeconform`, **policy checks** (Kyverno/Conftest/`kube-linter`/`checkov`) failing the build on the hygiene rules above.

### Step 3 — Networking & exposure
**Gateway API** (`GatewayClass` for the external Application Load Balancer, `Gateway`, `HTTPRoute`) with managed certificate; `/api/*` → gateway service, `/` → the static frontend (bucket backend or the `Dockerfile.web` pod — pick and document), health checks via `HealthCheckPolicy`/`BackendConfig` equivalents; **Cloud Armor** policy attached through the supported policy resource; HTTP→HTTPS redirect. Internal traffic via ClusterIP services; only the gateway is externally reachable.
**NetworkPolicies:** default-deny in every namespace; explicit allows (gateway → services, services → their databases/Redis/Kafka egress, DNS, metrics scrape). Test that a pod in another namespace **cannot** reach a service it shouldn't.

### Step 4 — Configuration, secrets, identity
- Config via ConfigMaps/Helm values; **secrets never in Helm values or git** — use the **Secret Manager CSI driver** or **External Secrets Operator** to materialise them; rotate a secret and show pods picking it up.
- **Workload Identity:** each Kubernetes ServiceAccount maps to its own GCP service account (same least-privilege matrix as Phase 11); prove isolation: from the `orders` pod, accessing the `catalog` secret is **denied**.
- Cloud SQL access via the **Cloud SQL Auth Proxy sidecar** (or private IP direct) with IAM database auth where practical.

### Step 5 — Scaling & resilience
- **HPA** on CPU and memory for stateless services; for Kafka consumers scale on **consumer lag** (KEDA or custom metrics) — optional but recommended learning. Tune min/max replicas (cost caps), stabilisation windows.
- **PodDisruptionBudgets** so node upgrades never take a service to zero; **topology spread constraints** across zones; Autopilot/cluster autoscaling behaviour observed under load.
- Load test (reuse Phase 9 k6) while watching HPA scale up/down; record behaviour and any request errors during scale events.
- **Chaos drills** (manual, documented): delete a pod mid-traffic (no errors beyond retries), drain a node, kill the Redis pod (degrades gracefully per Phase 5), break a dependency (circuit breaker opens).

### Step 6 — Delivery
- **Migrations:** a Helm **pre-upgrade hook Job** (or separate pipeline step) runs Flyway with the migrator DB role before new pods roll; failure blocks the release.
- **CI/CD** (`deploy-gke.yml`): build → scan → push to Artifact Registry → `helm upgrade --install --atomic --timeout` with the new image tag → smoke test → automatic **rollback** on failure (`--atomic` / `helm rollback`). Authenticate with **WIF** (no keys) and a deployer identity limited to the namespace. Production requires manual approval.
- **Progressive delivery (pick one, record in an ADR):** Gateway API **weighted routing** canary (10 % → 50 % → 100 %) driven by the pipeline with automated metric checks (error rate/latency from Prometheus) — or Argo Rollouts/Flagger as a stretch.
- Optional stretch: **GitOps** with Argo CD/Config Sync (repo as the source of truth; drift detection).

### Step 7 — Observability on the cluster
Managed Prometheus scraping `/actuator/prometheus` via `PodMonitoring`; the Phase 9 dashboards recreated; kube-state and node metrics; log-based alerts for `CrashLoopBackOff`/`OOMKilled`/pending pods/HPA at max; trace pipeline from Phase 9 carried over; **alert → runbook** links.

## 4. Required verification (record in the PR)
- [ ] `terraform apply` creates the cluster; `helm install` of the whole stack from scratch works; a second `helm upgrade` is a no-op.
- [ ] **Zero-downtime rollout:** run k6 at steady load during a deployment of each service → **0 failed requests** (or only those the client retries) — attach the k6 summary.
- [ ] Failed deployment (bad image / failing readiness) is **rolled back automatically** and traffic never reaches the broken pods.
- [ ] HPA scales out under load and back in; PDB prevents full outage during a node drain; zone spread verified.
- [ ] NetworkPolicy tests: forbidden pod-to-pod/egress paths blocked; allowed paths work.
- [ ] Workload Identity: each pod can reach only its own resources; no node-level credentials used; **no GCP keys** in secrets.
- [ ] Policy checks fail on a seeded violation (root user, missing limits, `latest` tag, privileged) and pass on the real charts.
- [ ] Secret rotation propagates to pods without a redeploy of unrelated services.
- [ ] Cost: resource requests right-sized from observed usage; `docs/cost.md` updated with Autopilot/Cloud Run comparison; teardown tested.

## 5. Acceptance criteria
- [ ] Full customer, admin and chat acceptance flows (Phases 4/6/14) pass against the GKE staging via the Gateway API load balancer.
- [ ] ADRs 0041–0046 (why GKE, Autopilot vs Standard, ingress via Gateway API, secrets approach, scaling strategy, progressive delivery), runbook for deploy/rollback/scale/debug (`kubectl` cheat-sheet included).
- [ ] The Cloud Run path still deploys successfully (kept as the documented production-simple option).
- [ ] Security baseline ticked.

## 6. Out of scope
Service mesh (decision recorded) · multi-cluster/DR region · custom operators · running Kafka/Postgres inside the cluster for production.

## 7. Commits & tag
`infra: gke cluster, node sa, namespaces, quotas` → `deploy: helm library chart and service charts` → `deploy: gateway api, routes, cloud armor policy` → `deploy: network policies, pdb, hpa` → `deploy: secrets csi and workload identity` →
`ci: helm atomic deploy, canary, rollback` → `docs: runbook, adrs, cost`. Tag `phase-15-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** Pods/Deployments/ReplicaSets/Services · rolling updates & `maxSurge/maxUnavailable` · probes (startup/readiness/liveness) · requests vs limits & QoS · HPA/VPA/cluster autoscaler · PDBs · NetworkPolicy (default deny) · RBAC · **Workload Identity** · Gateway API vs Ingress · Helm templating & hooks · GitOps · Autopilot vs Standard · why Cloud Run is often the better answer.
1. A deployment rolls out and users see 502s for two seconds. Which three settings do you check first?
2. Requests vs limits: what happens when memory exceeds the limit vs the request? What about CPU?
3. Why a startup probe for a Spring Boot app?
4. How does Workload Identity let a pod call GCP APIs with no key file?
5. What does a default-deny NetworkPolicy buy you, and how do you test it?
6. When would you choose Cloud Run over GKE for this exact system — and defend it?
7. Explain how HPA decides to scale and why scaling on Kafka lag can be better than CPU for consumers.
