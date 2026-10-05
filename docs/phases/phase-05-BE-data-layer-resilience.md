# Phase 5 (Backend) — Data Layer, Resilience & Inventory Depth

| | |
|---|---|
| **Repo** | `atlasna-catalog` (modular monolith) |
| **Prerequisite** | Phase 3 done; Phase 4 frontend consuming it |
| **Read first** | `00-MASTER-PLAN.md` (**gate G2 payments**), `01-CONVENTIONS.md` §3 (external calls), `02-…` §5, `03-API-CONTRACT.md` §5 (Phase 5 row) |
| **Estimate** | 4 weeks |

## 1. Goal
Make the store **fast, safe under failure, and operationally honest**: Redis for caching/rate limiting/refresh tokens, MongoDB for audit logs, a resilient online-payment adapter (CIB/Edahabia via a local gateway, sandbox only),
real product images in object storage, expiry-aware inventory lots, and search that understands accents and Arabic spelling variants.

## 2. Scope
**In:** Redis · refresh tokens · MongoDB audit log · `PaymentGateway` + Chargily adapter (sandbox) with Resilience4j · `ObjectStorage` + image upload · inventory lots/FEFO/expiry jobs · accent-insensitive & Arabic-normalised search.
**Out:** Kafka/microservices (Phase 7), production payment onboarding (needs a registered business — gate G2), GCP deployment (Phase 11), the AI assistant.

## 3. Steps

### Step 0 — Preflight & ADRs to decide first
Branch `phase-5-data-layer`. Add Redis, MongoDB (and later Kafka) to `docker-compose.yml`. Write **ADR-0009 token strategy** *before coding*: access token in memory + **refresh token in an httpOnly, Secure, SameSite=Strict cookie** (requires frontend and API to share a registrable domain/origin — Phase 12 serves both behind one load balancer) with a custom-header CSRF check on `/api/auth/refresh`; compare with "both tokens in localStorage" and state the trade-off.
Dependencies (Boot-managed): `spring-boot-starter-data-redis`, `spring-boot-starter-data-mongodb`, `spring-cloud-starter-circuitbreaker-resilience4j`, `spring-boot-starter-quartz`; test: `testcontainers` Redis/MongoDB + WireMock.

### Step 1 — Redis: cache-aside, with discipline
- Cache **public catalog reads** only: category tree, product detail by slug, list pages (key = normalised query). TTL ≈ 60–300 s. Keys namespaced and versioned `atlasna:v1:catalog:…`.
- **Never base an ordering decision on a cached stock number.** Order placement always hits the database. Cached `availability` may be slightly stale — that's acceptable for display and documented.
- Invalidate on write via Modulith events (`ProductChanged`, `CategoryChanged`): evict by key set / version bump. Add **stampede protection** (single-flight/lock or probabilistic early expiry) for hot keys.
- Serialise JSON (not Java serialisation). Cache failure must **degrade to the database**, not fail the request (wrap with a circuit breaker; log + metric).
- Tests with Testcontainers Redis: hit/miss, invalidation after admin update, behaviour when Redis is down (request still succeeds), no PII ever cached.

### Step 2 — Rate limiting (replace the in-memory limiter)
Token-bucket in Redis (atomic Lua script). Policies (configurable): login 5 failures/email/15 min and 20/IP/min (as today); register 5/IP/hour; order placement 10/user/hour; assistant (Phase 13) per-user daily budget. Return `429 TOO_MANY_REQUESTS` + `Retry-After`. Key by user id when authenticated, else by client IP — **take the IP from the trusted proxy header only when configured** (spoofable `X-Forwarded-For` is a classic bypass). Tests: limits hold under parallel requests; window resets; fails open or closed *by explicit policy* (login fails closed).

### Step 3 — Refresh tokens
`POST /api/auth/refresh`, `POST /api/auth/logout`. Opaque random refresh token (≥ 256 bit), stored **hashed** (SHA-256) in Redis with user id, device info and expiry (e.g. 30 days); **rotation on every use** with reuse detection (a reused old token revokes the whole family). Access token stays a short-lived JWT (15 min).
Tests: rotation, reuse detection revokes family, logout revokes, expired/forged rejected, cookie flags (`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`), CSRF header required.
(The frontend adopts this in Phase 6.)

### Step 4 — MongoDB audit log
`audit_logs` collection (shape in `02-…` §5). Write from **application events** (not scattered calls): admin catalog changes (with before/after diff of changed fields only), stock adjustments, order status changes with actor, settings changes, role/user changes, login failures (no passwords). TTL index (e.g. 365 days ⚙). `GET /api/admin/audit-logs?entityType&entityId&actor&from&to` (ADMIN, paged).
Writes are **asynchronous and non-blocking for the request**; failure to write the audit log must not fail the business operation but must raise an alert metric. Never store secrets, tokens or full phone numbers.

### Step 5 — Payments: `PaymentGateway` port + Chargily adapter (sandbox)
- Domain port in `payment`: `createCheckout(order, returnUrls) → {checkoutUrl, providerRef}`, `parseAndVerifyWebhook(headers, body) → PaymentEvent`, `refund(providerRef, amount)` (stub).
- Adapter for **Chargily Pay V2** (test mode): verify the **current API docs and the official Java library** before coding (Gate G2). Store provider keys in config/secret manager, never in git.
- `POST /api/orders/{n}/pay` → `{checkoutUrl}` for an order with `paymentMethod=CARD_ONLINE`. Order is `PLACED` with payment `PENDING` until the webhook.
- **Webhook = source of truth.** `POST /api/webhooks/payments`: verify the **signature** over the raw body (constant-time compare), reject stale timestamps, **idempotent by provider event id** (`webhook_events` unique key), then move `PENDING → PAID|FAILED`. Never trust the browser's success redirect.
- Unpaid online orders expire (Quartz job, e.g. 30 min ⚙) → cancel + restore stock + release slot.
- **Resilience4j on every outbound call:** connect/read **timeouts** (2 s / 5 s), **retry** only idempotent calls (e.g. GET status; create-checkout only with a provider idempotency key) with exponential backoff + jitter, **circuit breaker** (sliding window, failure-rate and slow-call thresholds), **bulkhead** (limit concurrent gateway calls). Fallback: respond `503 PAYMENT_UNAVAILABLE` and the frontend offers COD. Expose breaker state via Actuator/Micrometer.
- Tests (WireMock): provider timeout → retry → success; repeated 5xx → breaker opens → fast failure → half-open → closes; duplicate webhook → processed once; bad signature → 401 and nothing changes; webhook before the client returns; amount mismatch → flagged, not marked paid.

### Step 6 — Object storage & product images
- `ObjectStorage` port: `createUploadUrl(key, contentType, maxBytes)`, `publicUrl(key)`, `delete(key)`. Adapters: `LocalFsObjectStorage` (dev: served from a static path) and `GcsObjectStorage` (Phase 11) using the **plain Google Cloud Storage client** — Spring Cloud GCP starters are not offered for Boot 4.1 (re-check at Phase 11).
- Flow: admin asks `POST /api/admin/uploads/product-image` → server returns a **short-lived signed URL** + the object key → browser uploads directly → admin attaches via `PUT /api/admin/products/{id}/images` (keys, order, alt text fr/ar). The server **verifies the object** after upload: size cap (e.g. 5 MB), allowed types (JPEG/PNG/WebP) by **magic bytes**, dimensions cap; random key `products/{id}/{uuid}.ext`; never trust the client's filename or content-type. Delete orphaned uploads with a scheduled job.
- `ProductResponse.images[].url` is derived from `publicUrl(key)` (CDN base URL configurable).
- Tests: wrong type/size rejected, magic-byte spoofing (a `.jpg` that's an HTML file) rejected, only ADMIN/STAFF can request upload URLs, keys can't escape the prefix.

### Step 7 — Inventory lots, FEFO and expiry
- `inventory_lots(product_id, lot_code, quantity_remaining, received_at, expiry_date)`; `products.stock_quantity` remains the cached sum; **sale/restock/adjust operations now go through lots** for perishables (FEFO: earliest `expiry_date` first; non-perishables use a single open lot).
- Admin: `GET/POST /api/admin/products/{id}/lots` (receive stock = new lot + `RESTOCK` movement). Order packing records which lot(s) were used (`order_item_lots`) so recalls are traceable.
- Quartz jobs (idempotent, safe to run twice): **expire** lots past `expiry_date` (movement `EXPIRED`, stock down), **expiring-soon** report (≤ N days ⚙), **low-stock** report. Publish `StockLow`/`LotExpiring` events (consumed in Phase 7).
- Invariants (tested): `stock = Σ lot remaining = Σ ledger`; FEFO picks the right lot; concurrent sales across lots never go negative.

### Step 8 — Search quality
Enable `unaccent` and `pg_trgm` (migration). Add normalised search columns/indexes: French → lower-case + unaccent; Arabic → strip tashkeel (diacritics) and tatweel, unify alef forms (`أ إ آ → ا`), `ى → ي`, `ة` handled consistently; keep both original and normalised text. Rank by trigram similarity + exact-prefix boost; handle typos (`couscouss`), mixed scripts, and Algerian spelling variants. Keep `q` combinable with all other filters; escape input; cap length.
Tests with a fixture list of real-world query variants (accents, missing accents, Arabic with/without diacritics, plural/singular, typos) and expected top results; a `EXPLAIN` check that the index is used on a few thousand generated rows.

## 4. Cross-cutting tests
Chaos-style: stop Redis → catalog still served; stop Mongo → orders still work, alert metric increments; slow payment gateway → no thread starvation elsewhere (bulkhead). Authorization matrix extended to every new route. Contract doc updated; frontend fixtures regenerated.

## 5. Acceptance criteria
- [ ] `./mvnw clean verify` green with Testcontainers for Postgres, Redis, MongoDB and WireMock for the gateway.
- [ ] With Redis down the catalog API still responds (slower); with the gateway down `/pay` returns `503 PAYMENT_UNAVAILABLE` quickly (circuit open) and COD still works.
- [ ] A sandbox card payment goes `PENDING → PAID` **only** via a verified webhook; replaying the webhook changes nothing.
- [ ] Upload a real JPEG through the signed-URL flow; it appears in `ProductResponse.images`; a disguised HTML file is rejected.
- [ ] Refresh-token rotation + reuse detection demonstrated with `curl`.
- [ ] FEFO verified with two lots of different expiry; expiry job writes `EXPIRED` movements; invariants hold.
- [ ] Search finds "Dattes Deglet Nour" for `dattes deglet`, `deglet nour`, `تمر`, `تمر دقلة` and `dates`‑style typos per the fixture list.
- [ ] Security baseline ticked; ADRs 0009–0013 written (token strategy, cache policy, payment webhook trust, image validation, search normalisation).

## 6. Out of scope
Production payment merchant onboarding · refunds beyond the stub · CDN (Phase 12) · Kafka · AI.

## 7. Commits & tag
`feat(cache): redis cache-aside with invalidation` → `feat(security): redis rate limiting, refresh tokens` → `feat(audit): mongodb audit log` → `feat(payment): gateway port, chargily adapter, resilience4j` →
`feat(media): object storage port and image upload` → `feat(inventory): lots, fefo, expiry jobs` → `feat(search): unaccent, trigram, arabic normalisation` → `test: failure-mode suite`. Tag `phase-5-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** cache-aside vs write-through · TTL vs explicit invalidation · cache stampede · token bucket vs fixed window · refresh-token rotation & reuse detection · when a document DB is (and isn't) right · ports & adapters ·
**timeouts → retries → circuit breakers → bulkheads** (order matters) · webhook security & idempotency · signed upload URLs · FEFO · Postgres trigram/unaccent.
1. Why must the payment webhook — not the browser redirect — mark an order paid?
2. A retry policy makes an outage worse. How? How do circuit breakers and jitter help?
3. Why shouldn't ordering ever rely on cached stock?
4. Explain refresh-token rotation and what "reuse detection" catches.
5. Why is MongoDB right for audit logs but wrong for orders in this system?
6. What attack does checking magic bytes stop that checking the file extension doesn't?
7. How does `X-Forwarded-For` break IP rate limiting, and how do you trust it correctly?
