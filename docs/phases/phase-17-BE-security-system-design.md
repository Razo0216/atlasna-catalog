# Phase 17 (Backend) — Security Hardening & System Design Review

| | |
|---|---|
| **Repo** | all backend services + `infra/` + `deploy/` |
| **Prerequisite** | Phases 1–16 done (or at least 1–15 + 16 in progress) |
| **Read first** | Everything — especially `01-CONVENTIONS.md` §6, `00-MASTER-PLAN.md` §2 (gates), and every ADR written so far |
| **Estimate** | 3 weeks |

## 1. Goal
Turn "we were careful along the way" into **evidence**: a threat model, a verified security posture, measured performance and failure behaviour, practised recovery, and a **system design document** you can walk an interviewer (or a future teammate) through in 45 minutes. Finish with a tagged `v1.0.0`.

## 2. Scope
**In:** threat modelling · OWASP Web Top 10 + API Security Top 10 audit · automated authorisation matrix · account recovery & verification · **mTLS** between services · secrets & key rotation drills · IAM re-audit · SAST/DAST/dependency/container gates · manual penetration test · business-logic abuse review · data-protection (Law 18-07) work · **load/stress/soak/spike testing** · chaos & dependency-failure drills · backup/restore & DR drill · SLOs and alerting · final system design document.
**Out:** third-party pen-test engagement (recommended before real launch, not a coding task) · compliance certification · multi-region.

## 3. Steps

### Step 0 — Preflight
Branch `phase-17-hardening`. Freeze features. Create `docs/security/` and `docs/perf/`. Build an **inventory**: every route (generate it from the Spring mappings), every datastore, every secret, every external integration, every role, every trust boundary.

### Step 1 — Threat model (STRIDE) — `docs/security/threat-model.md`
Data-flow diagrams for: customer login/refresh, checkout, payment webhook, admin catalog change with image upload, driver delivery update, chat/tool call, MCP ops copilot, CI/CD deploy. For each trust boundary list **Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege** threats, existing mitigation, evidence (test/ADR link), residual risk and owner. Rank by likelihood × impact; every **high** residual risk gets a ticket or an accepted-risk note signed off by you.

### Step 2 — OWASP audit with evidence (not opinions)
Walk **OWASP Top 10** and the **OWASP API Security Top 10** (use the current published editions — check them) against this system. For each item: how the system addresses it, the **test or artefact proving it**, and gaps fixed. Pay special attention to:
- **Broken object-level authorisation (BOLA/IDOR):** extend the automated matrix — generate from the route table: for **every** route × **every** role × (own resource / other user's resource / nonexistent) assert the exact status. A new route without a matrix entry fails CI.
- **Broken authentication:** token lifetime/rotation, refresh reuse detection, brute-force and credential-stuffing limits, enumeration (login, register, recovery return uniform responses/timing), password policy, lockout vs account-DoS trade-off.
- **Property-level authorisation / mass assignment:** DTOs never bind `role`, `active`, `price` fields a client shouldn't set (try them in requests).
- **Unrestricted resource consumption:** pagination caps, request/body size limits, expensive-query guards, file upload limits, chat budgets.
- **Security misconfiguration:** actuator exposure, verbose errors, default credentials, CORS, headers, debug flags, container/K8s hardening, cloud buckets and IAM.
- **SSRF:** any server-side fetch of user-influenced URLs (image import? webhooks?) — either absent or allow-listed with DNS-rebinding protection.
- **Unsafe consumption of APIs:** payment gateway and LLM responses are validated, not trusted.
- **Software supply chain:** pinned actions, SBOMs, dependency scanning, image provenance/signing (cosign, optional), lockfiles.

### Step 3 — Account recovery & verification (the missing pieces)
Implement (with the notification service as the delivery channel): **password reset** by emailed single-use token (random ≥ 256-bit, stored hashed, short TTL ≈ 30 min, invalidated on use and on password change, all refresh tokens revoked on reset), **email verification** for new accounts (unverified accounts can browse and order COD but are limited as you decide), **uniform responses** that never reveal whether an email exists, rate limits per email/IP, and audit-log entries. Optional: change-email and change-password flows requiring the current password. Tests include token replay, expiry, enumeration timing, and reset-then-old-sessions-dead.

### Step 4 — Service-to-service security: mTLS
Replace Phase 7's interim service tokens as the *only* inter-service protection with **mutual TLS**: choose and ADR **(a)** Cloud Service Mesh / Istio sidecar-less or sidecar mTLS on GKE (`STRICT` PeerAuthentication + AuthorizationPolicies per service identity) **or (b)** Spring Boot **SSL bundles** with certificates issued by an internal CA (e.g. Google CAS / cert-manager) and short-lived certs with rotation. Keep **JWT-based authorisation** (who is calling and on whose behalf) *on top of* mTLS (who is the workload). Demonstrate: a pod without a valid identity can't connect; a valid-but-unauthorised service gets `403`; certificate rotation causes no downtime.

### Step 5 — Secrets, keys, and IAM drills
Run and document each drill in `docs/runbook.md`: **rotate** the JWT signing key (zero-downtime via two active `kid`s), a DB password, the payment webhook secret, the LLM credentials (Workload Identity → nothing to rotate), and a leaked-secret response (revoke, rotate, audit usage). Re-run the IAM Recommender / Policy Analyzer: remove unused permissions, confirm no `Owner/Editor` on workloads, no service-account keys exist, state bucket locked down, Artifact Registry/Storage buckets not public, Secret Manager access logs reviewed. Add **organisation-style guardrails** you can apply at project level (disable SA key creation, restrict public buckets, require OS login, etc.).

### Step 6 — Automated security gates
CI/CD adds (or confirms): SAST (CodeQL), dependency scanning with failure thresholds, secret scanning, container scanning, IaC scanning (`checkov`/`trivy config`/`tflint`), Kubernetes policy tests, **DAST** (OWASP ZAP baseline/API scan against staging in a scheduled job, authenticated with test users; triage findings into fixes or documented false positives), licence check. Everything has an owner and a dated exception list.

### Step 7 — Manual penetration test (you play the attacker) — `docs/security/pentest-report.md`
Using ZAP/Burp and scripts against **staging**: IDOR on every id-bearing route; JWT attacks (`alg:none`, key confusion, tampered claims, expired/replayed, wrong `aud`); refresh-token theft/replay; **race conditions** (double-spend of the last unit, double order, double cancel, concurrent slot booking) with parallel request tooling; business-logic abuse (negative/NaN/huge quantities, fractional quantities on unit products, price/total tampering, forging `Idempotency-Key` collisions across users, replaying another user's key, reorder abuse, hoarding slots with fake COD orders, cart flooding); rate-limit bypass (header spoofing, distributed IPs, case variations of emails); injection (SQL via search `q`, JSONB attribute injection, header/CRLF, log injection); file upload attacks (polyglots, SVG with script, oversize, path traversal in keys); SSRF probes; open redirects; CORS misconfigurations; GraphQL/actuator/endpoints discovery; chat prompt-injection/leakage cases (reuse Phase 13 red-team set); MCP endpoint access. Every finding: severity, reproduction, fix, regression test. **No finding is "closed" without a test that fails before the fix.**

### Step 8 — Data protection (Law 18-07 alignment) — `docs/security/data-protection.md`
Data inventory (what personal data, where, why, retention, processors/transfers — LLM provider, payment gateway, email/SMS, error monitoring, cloud). Implement: **retention jobs** (chat logs, audit logs, old carts/addresses of deleted users, expired tokens), **data export** (`GET /api/me/data-export`) and **account deletion/anonymisation** (`DELETE /api/me` — keep legally required order records with identifiers anonymised), consent/acceptance records, **PII-in-logs scanner** test on all services, encryption in transit everywhere and at rest (Cloud SQL/Storage defaults documented; consider application-level encryption for phone/address if you judge the risk warrants it — ADR), least-privilege access to production data (no shared DB superuser; break-glass procedure), backup encryption. Confirm the privacy policy (Phase 16) matches reality, line by line.

### Step 9 — Performance, capacity & failure testing
**Define targets first** (⚙ confirm): p95 catalog read < 300 ms · p95 add-to-cart < 400 ms · p95 place-order < 800 ms · error rate < 0.5 % at target load · chat time-to-first-token p95 < 2.5 s. **Capacity model:** estimate peak (e.g. Friday evening / Ramadan before iftar): users, requests/s per route, orders/hour, DB size growth/year, Kafka throughput, LLM cost/day — then test at **10× the expected peak**.
k6 scenarios in `perf/`: realistic mixed journey, **spike** (sudden 10×), **stress** (until it breaks — find the first bottleneck: DB connections? CPU? lock contention on hot products?), **soak** (6–12 h, memory/connection leaks, GC), **checkout race** at scale (hot product). Use `pg_stat_statements`, slow-query logging, `EXPLAIN (ANALYZE, BUFFERS)` on the top queries; add/adjust indexes; tune connection pools (HikariCP vs DB limits), JVM, HPA/Cloud Run concurrency, Redis TTLs. Record **before/after** numbers and the bottleneck found at each step (`docs/perf/results.md`).
**Failure drills (game day, documented):** kill each service/pod, add latency/packet loss to a dependency (Toxiproxy or the platform), take Redis/Mongo/Kafka/LLM/payment gateway down in turn, fill the disk/connection pool, expire a certificate — for each: what the customer sees, what alerts fire, how long to recover. Fix the gaps found.

### Step 10 — Backup, restore & disaster recovery
Targets ⚙: **RPO ≤ 15 min**, **RTO ≤ 2 h**. Prove them: restore Cloud SQL to a point in time into a scratch instance and boot the stack against it; restore MongoDB; rebuild infrastructure from Terraform in a fresh project; recover from a bad deploy (rollback) and from a bad migration (forward-fix plan, backup restore as last resort). Time each drill and write the observed RPO/RTO. Store runbooks where you can read them **when the system is down** (not only in the cluster you're fixing).

### Step 11 — SLOs, alerts, on-call basics
SLIs/SLOs: availability 99.5 % monthly for the storefront/API, order-placement success ≥ 99 %, latency targets above, chat availability 99 %. **Error-budget** policy (what you stop doing when it's spent) and **burn-rate alerts** (fast + slow windows). Alerts must be **actionable** with a runbook link; delete noisy ones. A one-page incident process (detect → triage → mitigate → communicate to customers → post-mortem template).

### Step 12 — The system design document (the deliverable that makes you employable)
`docs/system-design.md` — written for a reader who has never seen the code:
1. **Problem & requirements** (functional, non-functional with numbers, constraints, explicit non-goals).
2. **Capacity estimates** and the assumptions behind them.
3. **High-level architecture** diagram + request flows (browse, checkout, payment webhook, chat, delivery update).
4. **Data model & storage choices** — why Postgres for orders/stock, why Mongo only for logs, why Redis, why pgvector.
5. **Consistency & concurrency** — the stock ledger, atomic conditional updates, idempotency, the saga, outbox; where you chose **consistency over availability** and where the reverse (**CAP/PACELC in concrete terms**).
6. **Caching, rate limiting, load balancing, API gateway** — what each does, where, why.
7. **Resilience** — timeouts/retries/circuit breakers/bulkheads, graceful degradation table (dependency down → customer experience).
8. **Security architecture** — trust boundaries, authN/authZ, secrets, network, data protection, the threat-model summary.
9. **Deployment & operations** — environments, IaC, CI/CD, observability, SLOs, DR.
10. **Scaling plan:** what breaks first at 10×/100× and what you'd change (read replicas, partitioning orders by date, CQRS read models, a search engine, queue-based order intake).
11. **Cost model.**
12. **Trade-offs & alternatives** — the modular monolith vs microservices honesty (ADR-0014), Cloud Run vs GKE, Kafka vs Pub/Sub, build vs buy.
13. **Future: multi-vendor** — exactly which seams exist and what would change (a `seller_id`, per-seller inventory, payouts, commissions, seller roles) — **documented, not built**.
14. **ADR index** and a **"how I'd present this in 45 minutes"** outline with likely follow-up questions.

## 4. Acceptance criteria
- [ ] Threat model, OWASP audit table, pen-test report, data-protection doc and system design doc committed, each with **links to evidence** (tests, dashboards, ADRs).
- [ ] Authorisation matrix is generated from the route table and **fails CI when a route lacks an entry**; zero BOLA/IDOR findings open.
- [ ] mTLS enforced between services (proven by negative tests); key/secret rotation drills completed with zero downtime; IAM re-audit clean.
- [ ] Password reset + email verification shipped with tests (token replay, expiry, enumeration, session revocation).
- [ ] DAST scheduled and triaged; all critical/high findings fixed with regression tests.
- [ ] Load/stress/soak/spike results recorded; targets met at 10× expected peak or limits documented with a plan; no memory/connection leaks in the soak.
- [ ] Chaos drills run; every customer-visible degradation is graceful and matches the table in the design doc.
- [ ] Restore drill executed: **observed RPO/RTO documented** and within targets (or the gap and plan stated).
- [ ] SLOs and burn-rate alerts live; runbooks linked from alerts.
- [ ] Data export/delete working; PII-in-logs test green across services.
- [ ] Tag `v1.0.0` with a changelog; `docs/` index (`README`) updated.

## 5. Out of scope
External audit/certification · bug bounty · multi-region active-active · building the multi-vendor features.

## 6. Commits & tag
`docs(security): threat model` → `test(security): generated authorization matrix` → `feat(auth): password reset and email verification` → `feat(security): mtls between services` → `chore(security): rotation drills, iam cleanup` → `ci: dast, iac and policy scans` →
`fix(security): pentest findings with regression tests` → `feat(privacy): export, deletion, retention jobs` → `perf: load tests, tuning, results` → `docs: dr drill, slos, runbooks` → `docs: system design`. Tag `phase-17-done` and `v1.0.0`.

## 7. Concepts & interview questions (for the developer)
**Concepts:** STRIDE · OWASP Top 10 & API Top 10 · BOLA/IDOR · mass assignment · mTLS & workload identity · secret/key rotation · SAST vs DAST vs SCA · race-condition exploitation · capacity planning & Little's law · spike/stress/soak testing · bottleneck analysis · RPO/RTO · SLI/SLO/error budgets · CAP/PACELC · graceful degradation · data-protection principles.
1. Threat-model the payment webhook: list three threats and the control for each.
2. Your load test hits a wall at 120 requests/s. How do you find the bottleneck, and in what order do you look?
3. Explain the difference between mTLS and JWT authentication between services; why do you want both?
4. How do you design password reset so it doesn't leak whether an email is registered?
5. Prove to me your authorisation is correct — what evidence would you show?
6. State your RPO and RTO and how you measured them. What would you change to halve the RTO?
7. Where does this system choose consistency over availability, and where the reverse? Defend each.
8. If the store wanted to add third-party sellers next year, which parts of the design would hold and which would need rework?
