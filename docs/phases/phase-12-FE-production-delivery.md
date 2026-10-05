# Phase 12 (Frontend) — Production Delivery on GCP

| | |
|---|---|
| **Repo** | `atlasna_front` (+ a few resources in `infra/` from Phase 11) |
| **Prerequisite** | Phase 11 staging live (load balancer with a placeholder `frontend` bucket, WIF, Cloud Armor); Phase 10 frontend quality gates |
| **Read first** | `00-MASTER-PLAN.md` (gate G1), `01-CONVENTIONS.md` §5–6, `docs/security-headers.md` (Phase 10) |
| **Estimate** | 2 weeks |

## 1. Goal
The storefront, admin and driver app are served **from the same origin as the API** (`https://staging.<your-domain>`), fast and cached correctly, deployed by a pipeline with **safe rollback**, hardened with the CSP/headers designed in Phase 10, and monitored — and the exact same build can also be served from **any plain web server** (portability for gate G1).

## 2. Scope
**In:** build-once/deploy-anywhere config · static hosting on Cloud Storage behind the existing load balancer + Cloud CDN · same-origin routing (`/` → bucket, `/api/*` → gateway) · cache policy · security headers · SPA routing · CI/CD with WIF · rollback · preview builds · uptime/RUM · a portable container image.
**Out:** SSR/prerendering · multi-region · custom domain registration/legal (ADR-0026).

## 3. Steps

### Step 0 — Preflight
Branch `phase-12-delivery`. Confirm from Phase 11: LB URL map, backend bucket, certificate, Cloud Armor policy, Terraform layout. Decide the staging domain (a subdomain you control). Re-read ADR-0009: the **refresh cookie needs same-site** — same-origin hosting satisfies it.

### Step 1 — Build once, configure at runtime
The artifact must not contain environment-specific values. Add `public/config.json` (loaded **before** React mounts) with `{ "apiBaseUrl": "/api", "environment": "staging", "storeInfo": {…}, "features": {…} }`; validate it with Zod; fail with a clear screen if missing. Same-origin means `apiBaseUrl` is just `/api` — no CORS, no cookies across sites. Remove any `VITE_*` that varies per environment (keep only build-time constants). **No secrets** in config or bundle (a CI grep for key-like strings).
`vite preview` and the dev proxy keep working.

### Step 2 — Hosting design (Cloud Storage + Cloud CDN behind the existing LB)
- Bucket: uniform access, **not public**; the LB's backend bucket serves it with **Cloud CDN enabled**.
- URL map: `/api/*`, `/actuator`(blocked), `/.well-known/jwks.json` → gateway NEG; everything else → bucket. **SPA fallback:** unknown paths must return `index.html` with `200` for client routes — configure the bucket's `not_found_page = index.html` or an LB rewrite (verify the chosen mechanism returns the right status/headers for deep links such as `/products/couscous-fin-1-kg`).
- **Cache policy (the part people get wrong):** hashed assets (`/assets/*`) → `public, max-age=31536000, immutable`; `index.html`, `config.json`, `sw.js`, `manifest.webmanifest` → `no-cache` (always revalidate); images by hash/version → long-lived; **never cache** `/api/*` at the CDN unless an endpoint is explicitly public and safe (the public catalog may use short `s-maxage` + `stale-while-revalidate` — decide per endpoint, record in an ADR; authenticated responses `Cache-Control: private, no-store`).
- Compression: Brotli/gzip for text assets (CDN/bucket) — verify with `curl -I --compressed`.
- Terraform: add the bucket settings, URL map rules, CDN policy and **response headers policy** to `infra/` (PR in the infra module).

### Step 3 — Security headers (from Phase 10's design)
Apply at the LB (custom response headers) or via the bucket metadata: `Content-Security-Policy` (start **report-only**, then enforce after a clean E2E run), `Strict-Transport-Security` (with a short `max-age` first, then raise; `includeSubDomains`/`preload` only when you're sure), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, `frame-ancestors 'none'` (via CSP), `Cross-Origin-Opener-Policy`. Add a CSP **report endpoint** (a tiny backend route or a log sink) and watch it. Verify with a header scanner and record the grade. The service worker must not weaken any of this (its scope is `/`; it must not cache the headers-bearing documents incorrectly — `index.html` stays revalidated).

### Step 4 — CI/CD (`.github/workflows/deploy.yml`)
Trigger: push to `main` (staging), manual approval for prod. Steps: install → lint/typecheck/tests/e2e (reuse Phase 10 jobs) → build with a **release id** (git SHA) → authenticate to GCP via **WIF** (no keys) → upload to `gs://…/releases/<sha>/` → **atomically promote**: upload hashed assets first, then `index.html`/`config.json`/`sw.js` last with `no-cache`, then invalidate the CDN for those paths → **smoke test** the deployed URL (HTTP 200, expected headers, `config.json` valid, one real API call, service worker registers) → keep the last N releases for rollback.
**Rollback:** a one-click workflow that re-promotes a previous release's HTML/config (assets are immutable and still present). Test it. Also keep `source maps` private (uploaded to the error-monitoring service only, not the public bucket).
**Preview deployments (nice-to-have):** per-PR build to `previews/<pr>/` behind an authenticated or unguessable URL, auto-deleted on close — mind that previews must never use production data.

### Step 5 — Portability (answers gate G1 in practice)
Provide `Dockerfile.web`: multi-stage build → **nginx-unprivileged** serving the same static build with the same cache headers, SPA fallback, security headers and a runtime `config.json` templated from env at container start; non-root, read-only FS, health endpoint. Document how to run the **identical build** on any VM/host (including an Algerian provider) behind that host's proxy. CI builds and scans this image too.

### Step 6 — Monitoring & real-user metrics
Uptime checks (home, a product page, `config.json`, API readiness through the LB) with alerts; **RUM:** send Core Web Vitals (`web-vitals`) to a small backend endpoint (`POST /api/telemetry/web-vitals`, rate-limited, no PII, sampled) feeding a dashboard (LCP/INP/CLS p75 per route and per connection type); frontend error rate from the monitoring service; CDN cache-hit ratio. Alert on LCP p75 regression and on error spikes after a release (release tagging).

### Step 7 — Release hygiene
Version shown in the footer/`/config.json` (git SHA) for support; `CHANGELOG` from conventional commits; a **go/no-go checklist** (`docs/release-checklist.md`): migrations applied, backend compatible, smoke green, rollback tested, alerts armed.

## 4. Required verification (paste evidence in the PR)
- [ ] Deep links (`/products/…`, `/orders/…`, `/admin/…`) load directly from the CDN URL with the SPA fallback and correct status.
- [ ] `curl -I` proves the cache policy per path class (hashed asset immutable; `index.html` revalidated; `/api` not cached/`no-store` for authenticated).
- [ ] After a deploy, a user with the old tab open gets the **update prompt** (service worker) and no broken chunk errors (old hashed assets still exist).
- [ ] CSP enforced with **zero** violations across the full Playwright suite run against staging; header scan recorded.
- [ ] Rollback workflow restores the previous release in under 2 minutes; verified live.
- [ ] Deploy uses **WIF only**; no long-lived secrets in the repo or Actions.
- [ ] Same-origin: browser network tab shows no CORS preflights; the refresh cookie flows (`HttpOnly; Secure; SameSite=Strict`) — login survives reload and token refresh.
- [ ] `Dockerfile.web` image serves the same build locally (`docker run`) with identical behaviour for routing, headers and config.
- [ ] Lighthouse mobile on the **deployed** staging URL meets the Phase 10 targets; RUM data arrives.

## 5. Acceptance criteria
All verification items above plus: ADR-0033 (hosting & cache design), ADR-0034 (release/rollback strategy), updated README/runbook (deploy, rollback, rotate config, debug a bad release).

## 6. Out of scope
SSR/prerender · A/B testing · feature-flag service · multi-CDN.

## 7. Commits & tag
`feat: runtime config.json` → `infra: static hosting, cdn, spa fallback, cache policy` → `infra: security headers` → `ci: deploy with wif, atomic promote, smoke, rollback` → `feat: container image for portability` → `feat: rum web vitals + uptime checks` → `docs: release checklist, runbook`. Tag `phase-12-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** build once, deploy many · 12-factor config · CDN caching (browser vs edge, `immutable`, `no-cache` vs `no-store`, `stale-while-revalidate`) · content hashing & cache busting · atomic deploys & rollbacks · same-origin vs cross-origin (CORS, SameSite) · HSTS/CSP/headers · RUM vs synthetic monitoring · portability as a design constraint.
1. Why is `index.html` never cached for long but hashed JS files are cached "forever"?
2. How does same-origin hosting simplify security (CORS, cookies, CSRF)?
3. A user keeps an old tab open across a deploy. What can break, and what prevents it?
4. Explain `no-cache` vs `no-store` and where each belongs in this app.
5. How would you move this frontend to a different host in a different country in an afternoon?
6. What is the difference between synthetic monitoring and RUM, and what does each catch?
