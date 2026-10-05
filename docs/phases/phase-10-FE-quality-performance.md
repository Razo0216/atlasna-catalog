# Phase 10 (Frontend) — Quality, Performance & Security

| | |
|---|---|
| **Repo** | `atlasna_front` |
| **Prerequisite** | Phase 9 done (full Compose stack + seed data + CI patterns); Phase 8 frontend done |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §5–7 |
| **Estimate** | 2 weeks |

## 1. Goal
Prove the storefront is **correct, fast on a weak Algerian mobile connection, accessible, and hard to attack** — with automated gates that keep it that way.

## 2. Scope
**In:** Playwright end-to-end suite (fr + ar) · automated accessibility checks · performance budgets (Lighthouse CI, bundle size) · image/fonts/JS optimisation · security headers & CSP design · XSS/token review · dependency hygiene · error monitoring · privacy-friendly analytics decision · SEO basics · frontend CI.
**Out:** hosting/CDN (Phase 12), legal pages and content (Phase 16).

## 3. Steps

### Step 0 — Preflight
Branch `phase-10-quality`. Start the full backend stack from Phase 9 with seeded data. Record baselines: build size (gzip) per chunk, Lighthouse mobile scores for Home, Product list, Product detail, Cart, Checkout.

### Step 1 — End-to-end tests with Playwright
Install Playwright (Chromium + WebKit + Firefox if time allows; **WebKit matters** — it approximates iPhone Safari). Tests run against the real stack (Compose) with a **reset/seed endpoint or script** per run; each test creates its own users/data (no order dependence). Mobile viewport (Pixel 5 / iPhone 13) is the default project; add one desktop project.
**Critical journeys (each in French *and* Arabic RTL):**
1. Browse → filter (type + category + search) → product → add 1.5 kg to cart → cart totals.
2. Register (Algerian phone variants) → login → logout → session persistence after reload.
3. Full checkout: address with landmark → slot → COD → confirmation → appears in history.
4. Network drop during confirm → retry → exactly one order (use Playwright's offline mode / route interception).
5. Out-of-stock race: two contexts, last unit → one wins, other sees the stock message.
6. Cancel and reorder.
7. Admin: create product with images (both languages) → visible in the storefront; staff packs/weighs; driver delivers (three roles, three contexts).
8. Language switch mid-flow keeps the cart and state; `dir`/`lang` correct; digits stay Latin.
9. Offline: catalog browsing from cache; checkout blocked with the explanation.
10. Authorisation: customer cannot open `/admin`; expired session handled.
Stability rules: **no fixed sleeps** (use auto-waiting and explicit conditions), stable `data-testid`/role selectors, trace + video on failure, tests isolated and parallel-safe, a flaky test is a bug (quarantine with an issue, don't retry blindly).

### Step 2 — Accessibility automation
Integrate `axe-core` (via `@axe-core/playwright`) into the journeys at key states (home, list with filters open, product, cart, checkout errors, dialogs, admin table) in both languages; **zero serious/critical violations**. Plus manual checks recorded in the PR: keyboard-only for checkout, screen-reader pass (VoiceOver) on the cart and the status timeline, 200 % zoom, `prefers-reduced-motion`, high-contrast, colour-blind check on stock/status colours.

### Step 3 — Performance engineering
**Targets (mobile, Lighthouse "slow 4G" + 4× CPU throttling, run in CI via Lighthouse CI):** LCP ≤ 2.5 s · CLS ≤ 0.1 · INP ≤ 200 ms · TBT ≤ 200 ms · **initial JS ≤ 170 KB gzip** · Performance score ≥ 90 on Home and Product pages.
Work items (measure → change → re-measure, log each in `docs/perf-log.md`):
- Route-level code splitting (already lazy) + vendor chunk strategy; analyse with a bundle visualiser; remove/replace heavy deps; dynamic-import charts, admin, and the chat widget (Phase 14).
- **Images:** responsive `srcset`/`sizes`, WebP/AVIF thumbnails (generated server-side/Cloud Function in Phase 11 — here define the contract: `?w=` sizes or pre-generated variants), `loading="lazy"`, explicit width/height (no layout shift), `fetchpriority="high"` on the LCP image, blurred/colour placeholder.
- **Fonts:** self-hosted, subsetted (Latin + Arabic), `font-display: swap`, preload only the critical weight; avoid a large Arabic font blocking render.
- Preconnect to the API/CDN origins, HTTP caching headers (design now, applied in Phase 12), avoid waterfalls (prefetch next-page data on hover/idle), TanStack Query `staleTime` tuned per query.
- Rendering: avoid re-render storms in lists (memo/keys/virtualisation only if profiling shows a need), debounce inputs, move expensive work off the main thread.
- Add a **bundle-size budget check** (fails CI above the limit) and **Web Vitals reporting** (`web-vitals` → a tiny `POST` endpoint or console in dev) to measure real users later.
- Test on a **real low-end Android phone** over throttled/real 3G/4G if you can; note results.

### Step 4 — Security review (frontend)
- **Sinks audit:** grep for `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function`, `document.write`, dynamic `href`/`src` from data (validate schemes: `https:`, `tel:`, `geo:` only), `target="_blank"` without `rel="noopener noreferrer"`. Add an ESLint rule/CI grep to keep them out. Test that API text such as `<img src=x onerror=…>` renders as text everywhere (products, notes, notifications, audit log).
- **Tokens:** access token in memory only; refresh cookie flags verified in the browser; no secrets in `VITE_*`; no tokens in URLs, logs, error monitoring payloads or analytics.
- **CSP design** (applied by the host in Phase 12; build it now and test in report-only): `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'` (tighten if possible — Tailwind builds static CSS, so `'unsafe-inline'` may be removable); `img-src 'self' data: <cdn>`; `connect-src 'self' <api>`; `font-src 'self'`; `frame-ancestors 'none'`; `base-uri 'self'`; `form-action 'self'`; `object-src 'none'`. Plus `Referrer-Policy`, `Permissions-Policy` (geolocation only on demand), `X-Content-Type-Options`, HSTS (host-level). Document each header and why in `docs/security-headers.md`.
- **Dependencies:** `npm audit` in CI (fail on high with fix), Renovate/Dependabot, lockfile committed, `npm ci` only, review postinstall scripts, avoid abandoned packages; produce an SBOM.
- **Clickjacking, open redirects** (`state.from` must be a same-origin path — validate), **prototype pollution** in any deep-merge usage, **CSRF** for the refresh endpoint (custom header present), **subresource integrity** if any third-party asset ever exists (there should be none).

### Step 5 — Error monitoring & analytics (decide, don't default)
Error monitoring: Sentry SDK or a self-hosted compatible (e.g. GlitchTip) — **scrub PII** (`beforeSend`: strip tokens, emails, phones, addresses, request bodies), release + source-map upload in CI, sample rates, user context limited to an opaque id. Analytics: if any, **privacy-friendly and cookieless** (e.g. a self-hosted Plausible/Umami), documented in the privacy policy (Phase 16); track funnel events only (view product, add to cart, start checkout, place order) with no PII. ADR-0025 records the choices (including "no analytics yet" as a valid answer).

### Step 6 — SEO basics (SPA-aware)
Per-route `<title>`/meta description (localised), canonical URLs, `hreflang` for fr/ar if separate URLs are used (otherwise document the single-URL language approach), Open Graph/Twitter tags for product links (static prerender or edge function is out of scope — note the SPA limitation), **JSON-LD** `Product` + `Offer` (price in DZD, availability) on product pages, `robots.txt`, a generated `sitemap.xml` from the catalog (script run in CI/CD), semantic headings. Note honestly in the README that a client-rendered SPA limits SEO; prerendering the home/category pages is a backlog item if organic search matters.

### Step 7 — Frontend CI (`.github/workflows/ci.yml`)
`npm ci` → lint (`eslint`, `lint:rtl`, sinks check) → `tsc -b` → unit tests → `i18n:check` → build → bundle-size budget → **Playwright against the Compose stack** (cache browsers; upload traces/videos on failure) → Lighthouse CI against `vite preview` → `npm audit` → SBOM. Pin actions by SHA, minimal `permissions`.

## 4. Acceptance criteria
- [ ] Playwright suite (≥ 10 journeys × fr/ar) green locally and in CI, **no sleeps**, flake rate recorded (run 20× → 0 flaky).
- [ ] axe: zero serious/critical in both languages at all listed states; manual a11y notes in the PR.
- [ ] Lighthouse CI meets the targets above on the throttled-mobile profile for Home and Product pages; bundle budget enforced; `docs/perf-log.md` shows before/after numbers.
- [ ] Sinks audit clean; XSS-as-text tests pass; CSP report-only produces **no violations** in the full E2E run; `docs/security-headers.md` written.
- [ ] `npm audit` clean (or documented, dated exceptions); SBOM produced.
- [ ] Error monitoring scrubs PII (proven by a test that throws an error containing a fake token/phone and inspecting the outgoing payload).

## 5. Out of scope
Hosting & CDN configuration (Phase 12) · legal/cookie content (Phase 16) · prerendering/SSR (backlog) · load testing the backend (Phase 17).

## 6. Commits & tag
`test(e2e): playwright setup and critical journeys (fr/ar)` → `test(a11y): axe in journeys` → `perf: code splitting, images, fonts, budgets` → `sec: sinks audit, csp design, redirect validation` →
`feat: error monitoring with pii scrubbing` → `feat(seo): meta, json-ld, sitemap` → `ci: frontend pipeline`. Tag `phase-10-done`.

## 7. Concepts & interview questions (for the developer)
**Concepts:** test pyramid & where E2E fits · flaky tests · auto-waiting · Core Web Vitals (LCP, CLS, INP) · performance budgets · critical rendering path · responsive images · CSP · XSS (stored/reflected/DOM) · open redirect · supply-chain risk in npm · PII scrubbing · SPA vs SSR for SEO.
1. What makes a Playwright test flaky, and how do you design against it without `sleep`?
2. Explain LCP, CLS and INP in plain words, and one concrete fix for each in this app.
3. How does a Content-Security-Policy stop an XSS payload that got into product text?
4. Why must the `state.from` redirect after login be validated?
5. Your JS bundle grows by 40 KB in a PR. How does the pipeline catch it, and how do you decide whether it's acceptable?
6. What data must never reach your error-monitoring vendor?
