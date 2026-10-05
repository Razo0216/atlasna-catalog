# Phase 9 (Backend/Platform) — DevOps & Observability

| | |
|---|---|
| **Repo** | `atlasna-catalog` (all services) — plus a small shared `.github/` workflow library |
| **Prerequisite** | Phase 7 done (several services running via Compose) |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §6–9 |
| **Estimate** | 3 weeks |

## 1. Goal
Every push is **built, tested, scanned and packaged automatically**; the whole system starts with one command; and when something misbehaves you can **see it** (metrics, traces, logs) without guessing.

## 2. Scope
**In:** production-grade Dockerfiles · full local stack · GitHub Actions CI · security scanning (code, dependencies, images, secrets) · SBOM · image publishing (GHCR for now) · Testcontainers in CI · metrics, tracing, structured logs, dashboards, alerts · health probes & graceful shutdown · a first performance baseline.
**Out:** cloud deployment (Phase 11), Kubernetes (Phase 15), frontend CI (Phase 10/12).

## 3. Steps

### Step 0 — Preflight
Branch `phase-9-devops`. Record the baseline: build time, test time, image sizes, startup time per service. Decide ADR-0021: CI provider (GitHub Actions), registry (GHCR now → Artifact Registry in Phase 11), versioning scheme (git SHA tags + semver on releases).

### Step 1 — Dockerfiles (one per service, same pattern)
- **Multi-stage:** build with the Maven wrapper in a JDK 21 stage (cache dependencies in a separate layer), run on a **slim JRE 21** base. Use Spring Boot **layered jars** (`java -Djarmode=tools extract --layers` / the Boot-documented equivalent for 4.1 — verify) so dependency layers are cached.
- **Non-root user**, read-only root filesystem where possible, no shell tools in the final image if feasible, `HEALTHCHECK` using the readiness endpoint, `.dockerignore` (no `.git`, `target`, `.env`).
- JVM flags for containers: `-XX:MaxRAMPercentage=75`, `-XX:+ExitOnOutOfMemoryError`, GC suited to small heaps; enable **graceful shutdown** (`server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase`).
- Optional experiment (ADR, not a requirement): compare startup/memory with **virtual threads** enabled and with a native image — record numbers, decide with evidence.
- Image build produces **reproducible tags** (`<service>:<git-sha>`); never `latest` in deploy manifests.

### Step 2 — Local stack
`docker-compose.yml` with **profiles**: `infra` (Postgres ×N, Redis, Mongo, Kafka, Mailpit, Prometheus, Grafana, tracing backend), `core` (services), `all`. Health-gated `depends_on`. Named volumes, seeded dev data, `.env.example` (no secrets). `make up | down | reset | logs | seed | smoke` wrappers (or `just`). A `smoke` script runs the end-to-end journey with `curl` and fails loudly.

### Step 3 — Continuous Integration (`.github/workflows/ci.yml`)
Triggers: PR and push to `main`. Jobs (parallel where possible, cached Maven repo):
1. **build-test** — `./mvnw -B verify` with **Testcontainers** (Docker is available on GitHub runners); publish JUnit + **JaCoCo** coverage; enforce a coverage floor on changed code (start modest, raise later); fail on flaky tests (no auto-retry hiding them).
2. **static analysis** — CodeQL for Java; Checkstyle/Spotless (formatting) and **ArchUnit/Modulith verify** as tests; fail on warnings you chose to care about.
3. **dependency scan** — Dependabot (Maven + GitHub Actions + Docker) + `OWASP Dependency-Check` or `trivy fs`; fail the build on **critical/high with a fix available**; keep an explicit, dated **allow-list file** with reasons for exceptions.
4. **secret scan** — `gitleaks` on the diff and full history once.
5. **image build & scan** — build with the Dockerfile, scan with **Trivy** (fail on critical), generate an **SBOM** (CycloneDX) and attach it, push to **GHCR** on `main` only (permissions: `contents: read`, `packages: write`, least privilege).
6. **conventional-commit / PR title lint**, required status checks and **branch protection** on `main` (no direct pushes, linear history, required reviews or self-review checklist).
Pin third-party actions by **commit SHA** (supply-chain hygiene); use `permissions:` at job level; never echo secrets; no long-lived cloud keys (GCP uses Workload Identity Federation from Phase 11).

### Step 4 — Metrics (Micrometer → Prometheus → Grafana)
`spring-boot-starter-actuator` + Prometheus registry; expose `/actuator/health/**` publicly (readiness/liveness groups) and **everything else on a separate management port or behind auth**. Dashboards (as code, in the repo): JVM & GC, HTTP rate/errors/latency (p50/p95/p99 per route), DB pool, Kafka consumer lag, Redis hit rate, circuit-breaker states.
**Business metrics** (custom meters): orders placed/min, order placement failures by `code`, stock-conflict rate, slot-full rate, payment success rate, DLT depth, notification send failures. Alert rules (Prometheus/Alertmanager locally): 5xx rate, p95 latency, breaker open > 2 min, consumer lag, DLT > 0, no orders for N hours during opening hours ⚙.

### Step 5 — Tracing & logs
`spring-boot-starter-opentelemetry`: OTLP export to a local collector/Tempo or Jaeger; **trace context propagates through the gateway, REST calls and Kafka headers**; sample 100 % locally, configurable later. Logs: **structured JSON** (Boot structured logging), always with `traceId`/`spanId` and `correlationId`; one place that **redacts** tokens, passwords, phone numbers, addresses. A test asserts a sample request's logs contain no PII. Demonstrate: follow one order across gateway → orders → catalog → Kafka → notification in the tracing UI.

### Step 6 — Probes, shutdown, resilience of the platform itself
Readiness = can serve traffic (DB reachable, migrations done); liveness = process healthy (no dependency checks that cause restart storms). **Run Flyway as a separate step/job** in non-dev environments (ADR) so a bad migration can't crash-loop the fleet. Verify graceful shutdown drains in-flight requests and finishes Kafka commits.

### Step 7 — Performance baseline (k6)
Scripts in `perf/`: browse (cache warm/cold), search, login, place-order flow. Record p95/p99 and error rate on your laptop stack as the **baseline**, committed as a markdown table. (Hard targets are set in Phase 17.)

## 4. Required checks
- [ ] A PR that breaks a test fails CI; a PR with a seeded critical CVE (use a known-vulnerable test dependency in a throw-away branch) fails the scan; a committed fake secret is caught by `gitleaks`. **Demonstrate each once, then revert.**
- [ ] Images run as non-root; Trivy reports no critical findings; image size and startup time recorded vs baseline.
- [ ] `make up && make smoke` works from a clean machine state (document the prerequisites).
- [ ] Dashboards show real traffic; an induced failure (stop Kafka / slow the DB) fires the matching alert.
- [ ] One trace spans gateway → services → Kafka consumer.
- [ ] Log-redaction test passes.

## 5. Acceptance criteria
- [ ] CI green on `main`, required checks enforced by branch protection, third-party actions pinned by SHA, workflow permissions minimal.
- [ ] SBOM published per image; Dependabot PRs flowing; allow-list file reviewed.
- [ ] README: how to run, how to see metrics/traces/logs, how CI works, how to add a service.
- [ ] ADRs 0021–0024 (CI/registry/versioning, container hardening, observability stack, migration strategy in deployment).

## 6. Out of scope
Cloud deployment · Kubernetes · SLO/error-budget policy (Phase 17) · frontend pipeline.

## 7. Commits & tag
`build: multi-stage hardened dockerfiles` → `build: compose profiles and make targets` → `ci: build, test, coverage` → `ci: codeql, dependency, secret, image scans, sbom` → `feat(obs): metrics, business meters, dashboards, alerts` → `feat(obs): tracing and structured redacted logs` → `perf: k6 baseline`. Tag `phase-9-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** CI vs CD · build caching & layered images · least-privilege pipelines · supply-chain security (SBOM, pinned actions, signed/scanned images) · the three pillars (metrics, logs, traces) and how they differ · RED/USE methods · readiness vs liveness · graceful shutdown · SLIs/SLOs (preview).
1. Why pin GitHub Actions by SHA and scope `permissions`?
2. What's the difference between readiness and liveness, and what goes wrong if liveness checks the database?
3. A p99 latency spike but a healthy p50: which tool do you open first, and why?
4. Why run database migrations as a separate job rather than at app startup in production?
5. What does an SBOM let you do the day a new CVE is announced?
6. What should *never* appear in logs, and how do you enforce that automatically?
