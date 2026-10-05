# Atlasna — Master Plan (v4)

> **Read this first, every session.** This is the single source of truth for what Atlasna is,
> what it is *not*, the target architecture, the tech stack (with versions verified on
> 2026-10-05), and the 17-phase build order. Shared rules live in `01-CONVENTIONS.md`,
> business rules and the data model in `02-DOMAIN-AND-DATA-MODEL.md`, the REST contract in
> `03-API-CONTRACT.md`. Each phase has its own file in `phases/`.
>
> **v4 replaces every earlier plan.** The earlier versions assumed a generic marketplace
> (electronics, fashion, sports, books). That was wrong. See `04-REVIEW-OF-OLD-PLAN.md`.

---

## 1. What Atlasna is

**Atlasna is a single mini-mart in Annaba, Algeria, selling groceries and beauty / personal-care
products online, delivered by its own staff.** One store. One owner. No vendors.

| | |
|---|---|
| **Sells** | Groceries (fruit & veg, dairy & eggs, bakery, dry goods, oils & spices, drinks, snacks) and beauty / personal care (face, body, hair, make-up, fragrance, hygiene, natural & traditional care such as argan, henna, rose water) |
| **Does NOT sell** | Electronics, clothing, sports, books, furniture, appliances, **heavy or bulky items** (e.g. 25 kg flour, water packs, large oil cans), fresh meat/fish and frozen goods (cold chain — out of scope for now) |
| **Covers** | Annaba wilaya only. Delivery zones are configured by the admin (communes/neighbourhoods such as Annaba centre, El Bouni, Sidi Amar, El Hadjar), each with its own fee. Not nationwide. |
| **Customers** | Households in Annaba. Mostly on mobile phones, often on mediocre connections. Speak Arabic and/or French (and Darija). Most prefer **cash on delivery**. Addresses are vague — landmarks matter more than street numbers. |
| **Delivery** | Own staff and drivers. Customer picks a delivery slot. Minimum order and per-zone delivery fee. |
| **Payment** | Cash on delivery first. Online card payment (CIB / Edahabia) later, through a local gateway. |
| **Languages** | **French and Arabic (RTL)** everywhere in the customer UI. Arabic is not an afterthought. |
| **Currency** | Algerian dinar (DZD), formatted by `Intl` per language (French `4 900,50 DA`, Arabic `4.900,50 د.ج.`) — never hand-written symbols. |
| **Team of one** | A developer learning modern Java/cloud/AI by building it, using Claude Code to speed up implementation. |

### Why this shapes the engineering
- **Weight-based goods** (tomatoes by the kg) → decimal quantities, "estimated vs final price", weighing at packing time.
- **Perishables** → expiry dates, FEFO stock, low-stock and expiry alerts.
- **Heavy-item ban** → a real, enforced rule (per-unit weight cap and per-order weight cap), not a convention.
- **Own delivery** → zones, slots with capacity, driver assignment, cash reconciliation.
- **Vague addresses** → landmark field, phone-first contact, optional map pin.
- **COD dominant** → no-shows and failed deliveries are a first-class state, not an edge case.
- **Single store** → no seller/tenant concept anywhere in the model. Do not add one.

### Features (end state)
Browse / search / filter groceries & beauty · customer accounts · server-side cart · delivery zone + slot
selection · checkout (COD, later card) · order tracking in real time · order history & reorder ·
admin back-office (catalog, stock & expiry, orders board, zones/slots, reports) · driver mobile view ·
**AI assistant** (order status, prices, availability, delivery info, product advice — fr/ar/Darija).

### Explicit non-goals
Multi-vendor marketplace · seller payouts · nationwide/international shipping · native mobile apps ·
fresh meat/fish/frozen · loyalty/coupon engine (simple "was price" promotions only) · marketplace reviews.

---

## 2. ⚠️ Decision gates (real-world constraints — read before building Phase 5, 11, 13, 16)

These are not engineering preferences; they are facts that can change the plan. Each becomes an
ADR in `docs/adr/` when its phase arrives.

| Gate | Fact (verified 2026-10-05) | Consequence |
|---|---|---|
| **G1 — Hosting & legal (Phase 11)** | Algeria's e-commerce law **18-05** (2018) requires e-commerce activity to be registered (commercial register) and to run a website **hosted in Algeria with a `.com.dz` extension** (Ministry of Commerce / TSA reporting; secondary sources describe enforcement). Google Cloud has **no Algerian region**. | GCP is **fine for learning, staging and portfolio**. If you launch for real as a business, get a local lawyer's opinion *before* choosing production hosting. The architecture stays **portable** (containers, Terraform modules behind interfaces, no GCP-only code paths in the domain) so production can move to an Algerian host. *This is not legal advice.* |
| **G2 — Payments (Phase 5)** | **SATIM** is the national interbank gateway for CIB and Edahabia (Algérie Poste). **Chargily Pay V2** is a developer-friendly gateway on those rails, with an official Java library. International processors (Stripe/PayPal) are not a domestic solution. Law 18-05 also expects sale proceeds to land in an Algerian bank / Algérie Poste account. | Build a `PaymentGateway` interface; COD works without any gateway; implement the Chargily adapter in sandbox; merchant onboarding needs a registered business. |
| **G3 — Notifications (Phase 7)** | No SMS/WhatsApp provider chosen. | `NotificationChannel` interface; start with in-app + email + a logging adapter; pick an SMS provider later. |
| **G4 — Kafka hosting (Phase 11)** | Managed Kafka on GCP has real monthly cost; Spring Cloud GCP starters are **not offered for Boot 4.1** (Initializr range `[4.0.0, 4.1.0.M1)`). | Decide managed vs self-hosted vs Pub/Sub on cost. Use plain Google client libraries (not Spring Cloud GCP) unless it has caught up — re-check in Phase 11. |
| **G5 — AI & personal data (Phase 13)** | Law **18-07** (2018) governs personal-data processing. Sending customer text to a foreign LLM is a transfer. | Minimise PII sent to the model (never phone/address), log consent, retention limits, human hand-off. |
| **G6 — Business basics (Phase 16)** | Law 18-05 also requires disclosing identity (NIF, address, contacts), full prices incl. taxes and delivery, and return/refund terms before purchase. | Legal pages + checkout disclosures are launch blockers, not polish. |

---

## 3. Target architecture (end state)

Built as a **modular monolith first** (Phases 1–6), then **deliberately split** into services
(Phase 7) for learning. The monolith is the version you would actually ship for one mini-mart —
tag it `monolith-v1` before splitting and keep it.

```
 Customer (mobile PWA, fr/ar RTL)        Admin / Staff / Driver (responsive web)
              │                                         │
              └──────────────┬──────────────────────────┘
                             ▼  HTTPS
              Cloud CDN + External HTTPS Load Balancer (+ Cloud Armor WAF)      ← Phase 11/12
                  │ static assets (React build)        │ /api/*
                  ▼                                    ▼
          Cloud Storage bucket                  API Gateway (Spring Cloud Gateway) ← Phase 7
                                       JWT check · rate limit (Redis) · routing · CORS
                      ┌──────────┬───────────┴──────┬──────────────┬─────────────┐
                      ▼          ▼                  ▼              ▼             ▼
                 identity     catalog            orders        notification   assistant
                 (users,      (products,         (cart,         (in-app,       (Spring AI,
                  auth, JWT)   categories,        delivery,      email, SMS     RAG + MCP,
                               inventory+lots)    orders,        adapters)      Gemini)
                                                  payments)
                      │          │                  │              ▲             │
                      │          └── stock reserve ─┘   events     │             │
                      │              (saga, REST+compensation)      │             │
                      ▼          ▼                  ▼              │             ▼
                  PostgreSQL  PostgreSQL        PostgreSQL     Kafka topics    pgvector
                  (identity)  (catalog)         (orders)       order.* stock.*  (RAG index)
                                                  │  outbox ─────►             + MongoDB
                                                  ▼                              (chat logs)
                                              Redis (cache · rate limit · refresh tokens)
                   MongoDB: audit logs + chat transcripts only (never orders/products)
   Cross-cutting: Resilience4j on every outbound call · OpenTelemetry traces · Prometheus metrics ·
   Secret Manager · least-privilege IAM service accounts · Terraform for all infra
   Event-triggered functions (Cloud Functions): low-stock alert, nightly report, image thumbnails
```

**Two things people confuse:** the *load balancer* (infrastructure; spreads raw traffic, terminates TLS,
knows nothing about our domain) and the *API gateway* (our code; knows routes, JWT, rate limits).
We use both. They are not interchangeable.

---

## 4. Tech stack (versions observed via start.spring.io on 2026-10-05 — **re-verify before use**)

### Backend — `atlasna-catalog` (Java)
| Area | Choice | Notes |
|---|---|---|
| Language/runtime | **Java 21** | Virtual threads available |
| Framework | **Spring Boot 4.1.1** (Spring Framework 7) | Boot 3 tutorials are often wrong now — verify against the installed version |
| Web | `spring-boot-starter-webmvc` | Not `starter-web` |
| Security | Spring Security 7, JWT (jjwt), BCrypt; later OAuth2 resource server | Method security `@PreAuthorize` |
| Data | Spring Data JPA (Hibernate 7) + **PostgreSQL 16** + **Flyway** (`spring-boot-starter-flyway` + `flyway-database-postgresql`) | `ddl-auto=validate`, never `update` |
| Modularity | **Spring Modulith 2.1.1** | Enforce module boundaries; event publication registry = outbox |
| Cache / limits | `spring-boot-starter-data-redis` | Cache-aside, token-bucket, refresh tokens |
| Documents | `spring-boot-starter-data-mongodb` | Audit logs + chat transcripts **only** |
| Messaging | `spring-boot-starter-kafka` + `spring-modulith-events-kafka` | Phase 7 |
| Gateway | `spring-cloud-starter-gateway-server-webmvc` (Spring Cloud **2025.1.3**) | Phase 7 |
| Resilience | `spring-cloud-starter-circuitbreaker-resilience4j` (+ Framework 7 built-in `@Retryable`/`@ConcurrencyLimit`) | Phase 5 |
| Scheduling | `spring-boot-starter-quartz` | Expiry / low-stock jobs |
| Observability | `spring-boot-starter-actuator`, `spring-boot-starter-opentelemetry`, Micrometer Prometheus | Phase 9 |
| AI | **Spring AI 2.0.1**: `spring-ai-starter-model-google-genai` (Gemini), `spring-ai-starter-vector-store-pgvector`, `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-model-chat-memory-repository-jdbc` | Phase 13 |
| Tests | JUnit 5, Mockito, AssertJ, Spring Boot test slices, **Testcontainers** (Postgres/Kafka/Mongo/Redis) | No H2 for anything touching Postgres features |
| Build | Maven wrapper | |

### Frontend — `atlasna_front` (versions observed on the developer's machine)
React **19.2** · TypeScript **6** (`erasableSyntaxOnly`, `verbatimModuleSyntax`) · **Vite 8** · React Router **8**
(import from `react-router`; `RouterProvider` from `react-router/dom`) · TanStack Query · **Tailwind v4**
(`@tailwindcss/vite`, no config file) · **Zod v4** · react-hook-form · **i18next + react-i18next (fr/ar, RTL)** ·
Vitest + Testing Library + **MSW** · Playwright (Phase 10) · vite-plugin-pwa (Phase 8).

### Cloud & platform — Google Cloud Platform
Cloud Run → GKE · Cloud SQL (Postgres) · Memorystore (Redis) · Cloud Storage + Cloud CDN · Secret Manager ·
IAM + Workload Identity (+ **Workload Identity Federation for GitHub Actions — no JSON keys**) · Cloud Load
Balancing + Cloud Armor · Cloud Functions (2nd gen) + Cloud Scheduler · Artifact Registry · Cloud Logging/Monitoring ·
Vertex AI / Gemini · **Terraform** for everything. MongoDB: Atlas (GCP has no native Mongo). Kafka: see G4.

---

## 5. Build order — 17 phases, alternating Backend / Frontend

Odd phases = backend (and platform) · even phases = the frontend that consumes what the previous
backend phase built. Week estimates assume ~2 h/day of learning + review with Claude Code writing
most of the code; treat them as planning numbers, not promises.

| # | Repo | Phase | Learn (headline) | Ships | Weeks |
|---|---|---|---|---|---|
| **1** | BE | **Catalog foundation — REVISION of the existing build** | Flyway migrations, JPA mapping, JSONB, Specifications, Testcontainers, security carry-over fixes | Grocery/beauty catalog API, categories tree, bilingual products, admin API, `/me` | 1–2 |
| **2** | FE | **Storefront foundation** | React 19/TS/Vite/Tailwind v4, **i18n + RTL**, TanStack Query, Zod v4, MSW | Bilingual storefront: browse, search, filter, product page, auth, guest cart | 3–5 |
| **3** | BE | **Delivery, cart & orders** | Order state machine, idempotency, stock concurrency, Spring Modulith | Zones/slots, addresses, server cart, COD checkout, order lifecycle, stock ledger | 6–10 |
| **4** | FE | **Cart, checkout & orders** | Complex forms, optimistic UI, server-driven validation | Slot picker, checkout, order history/detail, reorder, address book | 11–13 |
| **5** | BE | **Data layer, resilience & inventory depth** | Redis, MongoDB, Resilience4j, payment adapter, object storage, search quality | Cache, rate limiting, audit log, Chargily adapter (sandbox), image upload, lots/expiry | 14–17 |
| **6** | FE | **Admin back-office & driver view** | Data tables, RBAC UI, file upload, dashboards | Catalog/stock/orders board/zones/reports, driver mobile view | 18–20 |
| **7** | BE | **Events, gateway & service split** | Kafka, outbox, saga, API gateway, DB-per-service | Notification service, gateway, split services (monolith tagged first) | 21–26 |
| **8** | FE | **Real-time, notifications & PWA** | SSE, service workers, offline strategy | Live tracking, notification centre, installable PWA | 27–29 |
| **9** | BE | **DevOps & observability** | Docker, GitHub Actions, scanning, Testcontainers CI, OpenTelemetry | CI/CD, images, full local stack, dashboards | 30–32 |
| **10** | FE | **Quality, performance & security** | Playwright, axe, Lighthouse, CSP | E2E suite, a11y/perf budgets, error monitoring | 33–34 |
| **11** | BE | **GCP + Terraform** | IAM, Cloud Run, Cloud SQL, Secret Manager, WIF, Cloud Functions | Staging live on GCP, all infra as code, **G1 ADR** | 35–38 |
| **12** | FE | **Frontend production delivery** | CDN hosting, cache headers, runtime config | Storefront on GCP behind CDN, CI/CD, rollbacks | 39–40 |
| **13** | BE | **AI assistant (RAG + MCP)** | Spring AI, embeddings, pgvector, MCP, prompt-injection defence, evals | Grounded assistant: orders, prices, availability, delivery, advice | 41–44 |
| **14** | FE | **AI chat widget** | Streaming, safe rendering, RTL chat | Chat widget with product/order cards, hand-off | 45–46 |
| **15** | BE | **Kubernetes (GKE) & scaling** | Helm, HPA, probes, Workload Identity, canary | Services on GKE, autoscaling, zero-downtime releases | 47–50 |
| **16** | FE | **Launch readiness** | Legal pages, SEO, content, user testing | Law 18-05 pages, Ramadan hours, store-closed state, polish | 51–52 |
| **17** | BE | **Security hardening & system design review** | OWASP/API Top 10, mTLS, load testing, DR | Threat model, audit, k6 results, ADRs, final design doc | 53–55 |

**Why alternate?** Each frontend phase proves the previous backend contract against a real UI before
more backend is built on top of a mistake. **Why is microservices in the middle, not the start?** Because
you cannot split a system you don't understand yet, and for one mini-mart the modular monolith is the honest
production answer; Phase 7 exists to learn distributed-systems skills, and says so.

---

## 6. Cross-phase threads (always on)

- **Security is not a phase.** Every phase has a security checklist (`01-CONVENTIONS.md` §6). Phase 17 is an audit, not the first time security is considered.
- **Contract first.** `03-API-CONTRACT.md` is updated *before* code that changes it; the frontend builds against MSW mocks of that contract, then the real API.
- **Tests with every feature**, authorization tests for every endpoint, concurrency tests for stock.
- **i18n from day one** (fr/ar). **Money is `BigDecimal`/centimes, never float.**
- **Everything portable.** No domain code imports a GCP SDK; cloud access sits behind interfaces (`ObjectStorage`, `PaymentGateway`, `NotificationChannel`, `LlmClient`).
- **Cost awareness** (Phase 11+): budgets and alerts before the first paid resource.

## 7. Risk register (short)

| Risk | Mitigation |
|---|---|
| Over-engineering for one store | Monolith-first; Phase 7 labelled as learning; tag `monolith-v1` |
| Stock oversell under concurrency | `UPDATE … WHERE stock >= qty` / optimistic locking, ledger, concurrency tests (Phase 3) |
| Law 18-05 hosting/domain | Gate G1; portable architecture |
| LLM hallucinating prices/status | Tools return facts; assistant never answers prices/status from memory (Phase 13) |
| Prompt injection via product text | Tool outputs treated as data; allow-listed tools; no HTML rendering (Phases 13–14) |
| Cloud cost surprise | Budgets/alerts, scale-to-zero, smallest tiers, teardown scripts (Phase 11) |
| Arabic/RTL bugs found late | RTL tests and a bilingual checklist from Phase 2 |
| Dependency churn (Boot 4 is new) | Pin versions, verify docs for the installed version, never copy Boot 3 snippets blindly |
