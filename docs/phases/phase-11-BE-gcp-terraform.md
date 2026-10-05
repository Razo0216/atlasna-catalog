# Phase 11 (Platform) — Google Cloud + Terraform

| | |
|---|---|
| **Repo** | `atlasna-catalog` + a new `infra/` directory (Terraform) in it (or a separate `atlasna-infra` repo — record the choice in an ADR) |
| **Prerequisite** | Phase 9 done (images in GHCR, CI, observability) |
| **Read first** | `00-MASTER-PLAN.md` — **gates G1 (hosting/legal), G4 (Kafka)** — and `01-CONVENTIONS.md` §3, §6 |
| **Estimate** | 4 weeks |

> ### ⚠️ Gate G1 — decide before spending money
> Law 18-05 describes e-commerce sites hosted in Algeria with a `.com.dz` domain, and Google Cloud has **no Algerian region**. GCP is therefore **the learning, staging and portfolio environment**; whether it can be *production* for a real Annaba business is a **legal question for a local lawyer**.
> Write **ADR-0026 "Production hosting"** with the options (Algerian host running the same containers / legal opinion on foreign hosting / launch later) and **do not delete it**. Everything in this phase stays **portable**: containers, 12-factor config, Terraform modules behind clear boundaries, no GCP SDK in domain code.
> *This is not legal advice.*

## 1. Goal
Atlasna runs on GCP in a **staging environment created entirely by Terraform**, deployed by GitHub Actions **without any long-lived credentials**, with secrets in Secret Manager, least-privilege IAM, a load balancer + WAF in front, event-triggered Cloud Functions, monitoring and **cost guardrails** — and can be torn down and rebuilt on demand.

## 2. Scope
**In:** project/billing hygiene · Terraform foundation (remote state, modules, environments) · Artifact Registry · Cloud Run services + jobs · Cloud SQL · Memorystore/Redis · MongoDB Atlas · Kafka decision & setup · Secret Manager · IAM + Workload Identity Federation · external HTTPS load balancer + Cloud Armor · Cloud Storage buckets · Cloud Functions + Scheduler · logging/monitoring/alerts · cost controls · deployment pipeline.
**Out:** Kubernetes/GKE (Phase 15), frontend hosting (Phase 12 — but the load balancer is built here with a placeholder backend bucket), production cut-over.

## 3. Steps

### Step 0 — Accounts, projects, and money safety (do this FIRST)
- Create a GCP organisation-less setup: **one project per environment** (`atlasna-staging`, later `atlasna-prod`) under one billing account. Enable only the APIs you use.
- **Budget + alerts on day one** (e.g. 50/80/100 % of a small monthly cap ⚙) to email *and* Pub/Sub; understand that budgets **alert, they don't stop spending** — add a small Cloud Function that disables billing (or scales to zero) on a hard-cap alert, documented as a last-resort kill switch.
- Check the **current free-tier/credits** and the pricing of each service on the official pages **before** creating it (prices and offers change); write a one-page cost table (`docs/cost.md`) with an estimated monthly cost per resource and a teardown command. Cheapest sane tiers everywhere; scale-to-zero (`min instances 0`) except where noted.
- Pick the **region** by measuring latency from Annaba (try `europe-west8` Milan, `europe-southwest1` Madrid, `europe-west9` Paris with a simple ping/HTTP test from a local machine) and record the result in the ADR.
- Local tooling: `gcloud`, `terraform`, `tflint`, `checkov`/`trivy config`. `terraform fmt/validate` in CI.

### Step 1 — Terraform foundation
Layout: `infra/modules/{network,artifact_registry,cloud_run_service,cloud_run_job,cloud_sql_postgres,redis,secrets,iam,storage_bucket,load_balancer,monitoring,functions,wif_github}` and `infra/envs/{staging,prod}/main.tf` calling them. **Remote state** in a dedicated GCS bucket with **versioning**, uniform access, restricted IAM, created by a tiny bootstrap config (state of the bootstrap kept carefully). Pin provider and Terraform versions; commit `.terraform.lock.hcl`. Variables typed with validation; **no secrets in `.tfvars` or in state where avoidable** (state can contain secrets → lock down the bucket; create secret *containers* in Terraform and set versions out-of-band). Consistent labels on every resource (`app`, `env`, `owner`, `cost-centre`).

### Step 2 — Identity & access (the part to get right)
- A **separate service account per service** (`sa-orders`, `sa-catalog`, `sa-identity`, `sa-notification`, `sa-gateway`, `sa-deployer`, `sa-functions`, …) with the **minimum roles on specific resources** (e.g. `roles/secretmanager.secretAccessor` on named secrets only; `roles/cloudsql.client`; bucket-level `objectAdmin` on the media bucket only). **Never use the default compute service account; never grant `Owner`/`Editor` to workloads.**
- **Workload Identity Federation** for GitHub Actions: a pool + provider bound to **your repo and branch/environment** (`attribute.repository == "…"` and ref conditions); the deploy workflow impersonates `sa-deployer`. **No JSON service-account keys anywhere** (disable key creation with an org/project policy if available).
- Audit with IAM Recommender / Policy Analyzer; export a `docs/iam-matrix.md` (who can do what and why).

### Step 3 — Data & messaging
- **Cloud SQL (PostgreSQL 16):** private IP only, automated backups + **point-in-time recovery**, maintenance window, `deletion_protection = true` for prod, flags for connection limits; separate databases/users per service (`identity`, `catalog`, `orders`, `notification`) with **least-privilege DB roles** (the app role cannot `DROP`/`CREATE EXTENSION`; migrations use a separate migrator role). Enable `pg_trgm`, `unaccent`, `vector` (pgvector) extensions via the migrator.
- **Redis:** Memorystore smallest tier on the private network (check price; if too costly for learning, use a documented alternative with the same interface). Network access via VPC (Direct VPC egress or Serverless VPC Access).
- **MongoDB:** Atlas (GCP-hosted) free/shared tier for audit + chat logs, IP/peering allow-listing, least-privilege user; credentials in Secret Manager. (GCP has no native Mongo.)
- **Kafka (gate G4):** compare (a) Google Cloud Managed Service for Apache Kafka, (b) a small self-managed broker on a VM/GKE, (c) a hosted Kafka free tier, (d) swapping the transport to Pub/Sub behind the same `EventPublisher` abstraction. Decide by **cost and operational burden**, record in ADR-0027. Keep the topic names and envelopes identical regardless.
- **Cloud Storage:** `media` bucket (uniform bucket-level access, public access **prevented**; images served via the load balancer/CDN or signed URLs), `tfstate`, `backups`, `frontend` (placeholder for Phase 12). Lifecycle rules, versioning where useful, CORS only for the signed upload origin.

### Step 4 — Compute & delivery
- **Artifact Registry** (Docker, vulnerability scanning on); CI pushes `<service>:<git-sha>`.
- **Cloud Run** service per app: CPU/memory sized small, `min-instances=0` (staging), `max-instances` capped (cost + abuse guard), concurrency tuned, request timeout, **ingress internal-only except the gateway** (others reachable only inside the VPC / by the gateway), service account per service, secrets mounted from Secret Manager (env or file), health probes, graceful shutdown. Consider CPU-always-allocated only for services with background consumers (Kafka consumers/Quartz) and document why.
- **Migrations as a Cloud Run Job** executed by the pipeline **before** a new revision gets traffic; the app role cannot migrate.
- **Deployment workflow** (`deploy-staging.yml`): on `main` after CI → build/push → run migration job → deploy new revisions with **no traffic** → smoke test → shift traffic (**gradual: 10 % → 100 %**) → on failure automatic rollback to the previous revision. Production requires a **manual approval environment**. Immutable tags only.

### Step 5 — Edge: load balancer & WAF
External Application Load Balancer with **serverless NEGs** → gateway service; **backend bucket** (placeholder) for the SPA; managed TLS certificate (needs a domain you control — use a test subdomain; `.com.dz` and the legal domain question live in ADR-0026); HTTP→HTTPS redirect; **Cloud Armor** policy: OWASP preconfigured rules (SQLi/XSS/LFI/RCE) in preview then enforce, per-IP **rate-based ban** on `/api/auth/*`, geo/IP allow-lists only if you can justify them, adaptive protection noted. Security headers added at the LB for the SPA (Phase 12). Cloud Run default URLs disabled for the gateway so the WAF can't be bypassed.

### Step 6 — Cloud Functions & Scheduler (the "Lambda-style" pieces)
2nd-gen functions (language of your choice — a small Java 21 or Node/Python function; justify):
1. **Low-stock / expiring alert:** triggered by a Kafka/Pub-Sub event or Scheduler → emails the admin (calls the notification API or sends via a provider) — idempotent.
2. **Nightly sales report:** Cloud Scheduler (cron, `Africa/Algiers`) → function queries a **read-only** reporting view → emails a CSV/summary.
3. **Image thumbnails:** Storage `finalize` event → validate magic bytes → generate WebP/AVIF sizes → write to `media/…/w{320,640,1024}` (this is what Phase 10 assumed). Handles retries idempotently; never trusts file names.
Each function has its own service account, minimal roles, concurrency/timeout limits, structured logs, and Terraform-managed triggers.

### Step 7 — Observability on GCP & cost guardrails
Cloud Logging (structured JSON ingested with trace ids), **Log-based metrics** for business errors, **uptime checks** on `/actuator/health/readiness` through the LB, **alert policies** (5xx rate, p95 latency, instance restarts, Cloud SQL CPU/connections/storage, Kafka lag/DLT, budget thresholds, error-reporting spike) with notification channels, a Cloud Monitoring dashboard. Export Prometheus metrics via Managed Service for Prometheus *or* keep Grafana locally — decide and document. Log retention and **no PII in logs** (re-run the redaction test against Cloud Logging output).
**Cost guardrails:** `max-instances` caps, budget alerts, `make nuke-staging` (Terraform destroy with confirmation), labels for cost reports, a monthly "cost review" note in `docs/cost.md`.

## 4. Required verification (record outputs/screenshots in the PR)
- [ ] `terraform plan` on an empty project creates everything; `apply` succeeds; **a second `plan` shows no changes** (idempotent).
- [ ] `terraform destroy` followed by `apply` rebuilds staging end to end in under N minutes ⚙ (record the time).
- [ ] The deployment pipeline authenticates via **WIF only** (prove no `credentials_json`/key secrets exist: `grep` the repo and Actions secrets list).
- [ ] A deploy with a **deliberately failing smoke test** rolls back automatically and traffic stays on the old revision.
- [ ] Gateway reachable only through the LB; direct Cloud Run URL blocked; other services unreachable from the internet.
- [ ] Cloud Armor blocks a canned SQLi/XSS request in the staging environment; rate ban triggers on login hammering.
- [ ] IAM check: try (as each service account) an action it must NOT be able to do (read another service's secret; write another bucket) → denied.
- [ ] Cloud SQL backup restored into a scratch instance and the app boots against it (**restore drill** — record RPO/RTO observed).
- [ ] The three Cloud Functions each fire from their trigger in staging; duplicate trigger delivery causes no duplicate side effect.
- [ ] Budget alert fires on a forced test threshold; kill-switch documented and tested on a throw-away project.
- [ ] The full frontend acceptance flow (Phases 4/6) works against staging via the LB (frontend served locally pointing at staging, until Phase 12).

## 5. Acceptance criteria
- [ ] Everything above, plus ADRs 0026–0032 (hosting/legal gate, region, Kafka choice, secrets & state handling, IAM model, deploy & rollback strategy, migration job).
- [ ] `docs/cost.md`, `docs/iam-matrix.md`, `docs/runbook.md` (deploy, rollback, restore, rotate a secret, add a service, tear down).
- [ ] No GCP SDK imported from domain modules (verify with an ArchUnit/Modulith test): cloud access only inside `*.adapter.gcp` packages behind ports (`ObjectStorage`, event publisher, secrets provider).
- [ ] Security baseline ticked.

## 6. Out of scope
GKE & Helm (Phase 15) · frontend hosting (Phase 12) · production cut-over & legal registration · multi-region · committed-use discounts.

## 7. Commits & tag
`docs: adr production hosting gate and region` → `infra: remote state, providers, labels` → `infra: iam, wif, service accounts` → `infra: cloud sql, redis, storage, secrets` → `infra: artifact registry, cloud run, migration job` → `infra: load balancer and cloud armor` →
`infra: functions and scheduler` → `infra: monitoring, alerts, budgets` → `ci: deploy workflow with canary and rollback`. Tag `phase-11-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** GCP resource hierarchy (org → project → resource) · IAM (principals, roles, policies, **least privilege**) · service accounts vs user accounts · **Workload Identity Federation** (why keys are a liability) · VPC, private IP, serverless connectors · managed vs self-managed trade-offs · load balancer vs WAF vs API gateway · IaC, state, drift, idempotency · blue/green & canary · backup, PITR, RPO/RTO · serverless cost model · event-driven functions.
1. Why are downloaded service-account JSON keys dangerous, and how does WIF remove the need for them?
2. What is least privilege? Show one concrete role you *removed* from a service account and why.
3. Terraform state contains secrets. What do you do about that?
4. What's the difference between Cloud Armor, the load balancer, and the API gateway — and why do you need all three?
5. Walk through a canary deployment on Cloud Run and how rollback is triggered.
6. You must restore the database to 10 minutes ago. How, and what are RPO and RTO here?
7. When is a Cloud Function the wrong tool compared with a Cloud Run service or Job?
8. Why can a budget alert not prevent a surprise bill, and what does?
