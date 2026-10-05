# Phase 7 (Backend) — Events, API Gateway & Service Split

| | |
|---|---|
| **Repo** | `atlasna-catalog` → becomes a multi-module Maven build (or several repos — see Step 1) |
| **Prerequisite** | Phase 5 done; Phase 6 frontend done; **tag `monolith-v1` before touching anything** |
| **Read first** | `00-MASTER-PLAN.md` §3 (architecture), `01-CONVENTIONS.md` §3–4, `02-…` §4 (Phase 7+) |
| **Estimate** | 5–6 weeks — the largest phase |

> ### ⚠️ Read this before you start
> For **one mini-mart**, the modular monolith you have now is the architecture you would actually ship: simpler, cheaper, faster, easier to reason about.
> This phase splits it **on purpose, to learn distributed systems** (events, sagas, gateways, per-service databases, service-to-service security). That is a legitimate goal — but be clear-eyed:
> you are trading simplicity for skills. Keep `monolith-v1` runnable; if the split hurts, the monolith is your fallback and your portfolio can show both with an ADR explaining the trade-off.

## 1. Goal
The same customer-facing API (`/api/*`, **no frontend contract change**) served by an **API gateway** in front of independently deployable services that communicate via REST (with resilience) and **Kafka events (via the outbox)**, each with its own database, plus a working **notification service**.

## 2. Target services
| Service | Owns | Notes |
|---|---|---|
| `identity` | users, roles, refresh tokens, **token issuing + JWKS** | Moves JWT from a shared HMAC secret to **asymmetric keys (ES256/RS256)**; others verify via the JWKS endpoint |
| `catalog` | categories, products, images, **inventory (stock, lots, ledger)** | Stock stays with products to keep stock updates local and atomic |
| `orders` | cart, delivery (zones/slots/addresses/settings), orders, payments | Calls `catalog` to reserve stock (saga) |
| `notification` | notification log, templates, preferences, channel adapters | Pure event consumer; also serves the in-app notifications API |
| `gateway` | nothing (stateless) | Spring Cloud Gateway Server WebMVC |
The AI `assistant` service arrives in Phase 13.

## 3. Steps

### Step 0 — Safety net
Tag `monolith-v1`. Branch `phase-7-services`. Keep the full test suite green on the monolith **while** extracting. ADR-0014: why split, what we gain/lose, rollback plan. Add Kafka (KRaft mode) + a Kafka UI to `docker-compose.yml`; add Mailpit for dev email.

### Step 1 — Repository & build layout
Choose and record in an ADR: **(a)** one repo, Maven multi-module (`gateway/`, `identity/`, `catalog/`, `orders/`, `notification/`, `shared-contracts/`), or **(b)** repo per service. Recommended: (a) for a solo developer. `shared-contracts` holds **only** event/DTO schemas (versioned records), never entities or business logic.
Dependencies (Boot-managed; confirmed available via start.spring.io on 2026-10-05): `spring-boot-starter-kafka`, `spring-modulith-events-kafka`, `spring-cloud-starter-gateway-server-webmvc`, `spring-cloud-starter-circuitbreaker-resilience4j`, `spring-boot-starter-security-oauth2-resource-server`, Spring Cloud BOM `2025.1.x`.
**Re-check the Spring Cloud ↔ Boot compatibility table** before pinning anything.

### Step 2 — Events & the outbox (inside the monolith first)
Define event envelopes: `{eventId (UUID), type, version, occurredAt, aggregateId, correlationId, payload}`; JSON; **additive-only evolution** (never rename/remove a field within a version).
Topics: `order.placed`, `order.status-changed`, `payment.updated`, `stock.low`, `lot.expiring`, `product.changed`, `user.registered`. Key = aggregate id (ordering per aggregate).
Externalise the Modulith event publication registry to Kafka (`@Externalized`) so events are written **in the same DB transaction** as the state change and published afterwards (**transactional outbox**); incomplete publications are retried on restart. Test: kill the app between commit and publish → the event still goes out; broker down → business operation still succeeds, events drain later.
**Consumers are idempotent:** a `processed_events(event_id)` table (unique) checked in the same transaction as the side effect. Configure retries with backoff and a **dead-letter topic**; alert on DLT depth.

### Step 3 — Notification service (first extraction — the easy one)
Consumes events → creates notifications. Channels behind `NotificationChannel`: **in-app** (stored, served at `GET /api/me/notifications`, mark-read), **email** (SMTP/Mailpit), **SMS** stub/log adapter (gate G3 — pick a provider later), **web push** (stretch). Templates in **fr + ar** per event type with the customer's language preference (`users.language`, add it). Rules: order placed, status changes (esp. `OUT_OF_STOCK` substitution needing a call, out for delivery, delivered, failed), weighed total differs beyond tolerance, payment result; admin alerts: low stock, expiring lots, DLT growth.
Dedupe by `(eventId, channel)`; rate-limit per user; never put full addresses/phones in logs. Extract with its own database and Dockerfile. Tests: Testcontainers Kafka; duplicate event ⇒ one notification; poison message ⇒ DLT, consumer keeps going; template renders fr/ar.

### Step 4 — Identity service + asymmetric JWT
Extract `identity`. Switch signing to **ES256 (or RS256)**: private key only in `identity` (from env/secret), `GET /.well-known/jwks.json` publishes the public key(s) with a `kid`; support **key rotation** (two active keys). All other services and the gateway become **OAuth2 resource servers** validating via JWKS. Keep claims minimal (`sub`, `roles`, `iat`, `exp`, `jti`).
Service-to-service calls use **short-lived service tokens** from `identity` (client-credentials style with a per-service secret) — documented as an interim measure that **mTLS replaces in Phase 17**. Tests: tampered/expired/wrong-`kid`/`alg:none` tokens rejected; rotation works with tokens signed by the old key until expiry.

### Step 5 — Gateway
Spring Cloud Gateway Server WebMVC: route `/api/auth/**`, `/api/me/**` (identity/orders/notification by path), `/api/products/**`, `/api/categories/**`, `/api/admin/products|categories/**` (catalog), `/api/cart|orders|delivery|addresses|driver|admin/orders…` (orders), `/api/me/notifications` (notification). **The public paths do not change.**
Gateway responsibilities: CORS (only here), JWT verification (defence in depth — services verify again), **Redis-backed rate limiting** (reuse Phase 5 policies), request/correlation id injection (`X-Request-Id`), strip/forward headers safely, timeouts per route, circuit breaker with a clean `503 SERVICE_UNAVAILABLE` body, request size limits, access logs (no tokens). Do **not** put business logic in the gateway.
Tests: route table, auth required per route, rate limit, downstream failure → `503` JSON with `code`, headers not spoofable (client-sent `X-User-*` is dropped).

### Step 6 — Split `catalog` and `orders`; database per service
- Separate databases (same Postgres instance locally, separate in GCP): `identity`, `catalog`, `orders`, `notification`. **No cross-database joins or shared tables.** Each service has its own Flyway history.
- `orders` keeps **snapshots** of product data and a minimal read model (`product_ref`: id, name, price, sold_by, unit_weight, active) updated from `product.changed` events — it must keep working when `catalog` is briefly down.
- **Stock reservation saga (orchestrated by `orders`):** (1) `orders` creates the order in `PLACED` with payment/slot state in a local transaction; (2) calls `catalog` `POST /internal/stock/reservations` with a **reservation id = order id** (idempotent) — all-or-nothing for the order's lines; (3) on success marks lines reserved and publishes `order.placed`; (4) on failure or timeout runs **compensation** (release slot, cancel order, `release` call if partially reserved); (5) `catalog` also exposes `DELETE /internal/stock/reservations/{id}` (idempotent) used on cancel/expiry. A **reaper job** finds orders stuck in a half-state and resolves them. Document the states in `saga_state` and the sequence diagrams in `docs/`.
- All inter-service HTTP calls: **Resilience4j** timeout + (idempotent-only) retry + circuit breaker + bulkhead; correlation id propagated; a contract test per client (WireMock or consumer-driven stubs).
- Frontend contract unchanged: the same Phase 3/4 acceptance flow must still pass through the gateway.

### Step 7 — Operability of many services
Same logging format, health endpoints (`/actuator/health/readiness|liveness`), correlation ids in logs, Docker Compose profiles (`infra`, `core`, `all`), a `make up/down/logs/seed` helper, seed data per service, and a **single script that proves the system works end-to-end** (register → order → notification received).

## 4. Required tests
- [ ] **Saga:** happy path; stock insufficient → compensation leaves no orphan slot/stock; `catalog` down → order creation fails fast with `503`, nothing half-created; timeout **after** catalog reserved → retry is idempotent (reserved once); reaper heals a stuck order.
- [ ] **Outbox:** crash between commit and publish; broker outage; duplicate delivery → consumer idempotent.
- [ ] **Concurrency across services:** 20 parallel orders for the last unit through the gateway → exactly 1 succeeds, stock never negative, no leaked reservations.
- [ ] **Security:** every service rejects missing/invalid/expired/wrong-audience tokens; internal endpoints reject end-user tokens and public traffic; key rotation; BOLA matrix passes through the gateway; spoofed identity headers ignored.
- [ ] **Resilience:** breaker opens/closes per downstream; bulkhead limits; gateway returns clean errors.
- [ ] **Contracts:** event schemas versioned and validated (producer and consumer tests share `shared-contracts`).
- [ ] The Phase 3 and Phase 6 acceptance scripts pass **unchanged** against the gateway.

## 5. Acceptance criteria
- [ ] `docker compose --profile all up` runs gateway + 4 services + Postgres + Redis + Mongo + Kafka + Mailpit; the frontend works with only its API base URL pointing at the gateway.
- [ ] Place an order → in-app notification and an email in Mailpit (fr or ar per user) → status change → another notification. Duplicate event replay creates none.
- [ ] Stop `catalog` mid-checkout: customer gets a clear localised error, no inconsistent data; restart → system recovers.
- [ ] ADRs 0014–0020: split rationale, repo layout, event envelope & evolution rules, outbox, saga design, token model & rotation, gateway responsibilities. Sequence diagrams committed.
- [ ] `monolith-v1` tag still builds and passes its tests.

## 6. Out of scope
Service mesh · mTLS (Phase 17) · production Kafka hosting (gate G4, Phase 11) · event sourcing · CQRS read stores · the AI service.

## 7. Commits & tag
`docs: adr split rationale` → `feat(events): envelopes, outbox via modulith, kafka externalisation` → `feat(notification): service with in-app/email/sms adapters` → `feat(identity): service, ES256 jwt, jwks, rotation` →
`feat(gateway): routes, rate limit, correlation id` → `feat(catalog): extract with internal stock reservation api` → `feat(orders): extract with saga and reaper` → `test: saga, outbox, security, resilience suites`. Tag `phase-7-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** monolith vs modular monolith vs microservices (and the real costs) · database-per-service · **saga (orchestration vs choreography)** and compensation · **transactional outbox** · idempotent consumers · at-least-once delivery · dead-letter queues · event versioning ·
API gateway vs load balancer · asymmetric JWT & JWKS & key rotation · zero-trust between services · correlation ids & distributed tracing.
1. Why can't one ACID transaction span `orders` and `catalog` databases, and what replaces it?
2. Explain the outbox pattern. What failure does it prevent that "save, then publish" doesn't?
3. Kafka delivers a message twice. What breaks if the consumer isn't idempotent? How do you make it so?
4. Why move from HMAC to asymmetric JWT signing when you add services?
5. When is choreography better than orchestration for a saga — and why did we choose orchestration for stock?
6. What are the real downsides of this split for a single store, and how would you argue for or against it in a design review?
7. A circuit breaker is open on `catalog`. What does the customer see, and what happens to orders in flight?
