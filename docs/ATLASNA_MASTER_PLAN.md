# Atlasna — Master Engineering Plan

> **Purpose of this document:** this is the single reference doc for the Atlasna project — business context, full architecture, every technology decision and why it exists, the data model, API surface, and a phase-by-phase build plan. Paste this into Claude Code (or any AI pair-programmer) at the start of a session so it has full context without re-explaining the project from scratch. Update it as decisions change; keep it accurate rather than aspirational.

---

## 1. Executive Summary — What We're Building

**Atlasna** is a single-store online marketplace serving Algeria — conceptually a digital-first mini-mart. One entity (us) owns the inventory, sets the pricing, and handles delivery. There is **no multi-vendor / seller onboarding** in this version — that's a deliberate scope cut, not an oversight. Multi-seller support is a real architectural expansion (seller accounts, commission logic, per-seller inventory, payout schedules) and will only be tackled once the single-store version is solid end to end.

**Core features (MVP → full build):**
- Browse/search a product catalog, view product detail pages
- Customer registration/login (JWT-based auth)
- Cart → checkout → order placement, with local payment methods (CIB, Edahabia) and cash-on-delivery
- Order tracking (status: placed → paid → packed → out for delivery → delivered)
- Admin-only product management (create/edit/delete/stock)
- An AI chatbot that can answer real questions using the store's actual data — order status, product pricing, policies — not generic/hallucinated answers

**Explicit non-goals (for now):** multi-vendor marketplace, seller payouts, international shipping, native mobile apps. All of these are plausible *future* phases once the core product proves out.

**Target platform:** Google Cloud Platform (GCP). Two repositories: `atlasna_front` (React/TypeScript) and `atlasna-catalog` (Java/Spring Boot).

---

## 2. High-Level Architecture (target end-state)

This is where we're heading — not all of this exists on day one. Each piece is tagged with the phase that introduces it.

```
                                    ┌─────────────────────┐
                                    │   React Storefront    │  (atlasna_front)
                                    └──────────┬───────────┘
                                               │ HTTPS
                                    ┌──────────▼───────────┐
                                    │  GCP Cloud Load       │  Phase 7 — TLS termination,
                                    │  Balancing (L7)        │  distributes across instances
                                    └──────────┬───────────┘
                                               │
                                    ┌──────────▼───────────┐
                                    │   API Gateway          │  Phase 5 — single entry point:
                                    │  (Spring Cloud Gateway)│  routing, JWT validation,
                                    │                        │  rate limiting (Redis-backed)
                                    └──────────┬───────────┘
                    ┌──────────────┬───────────┼───────────┬──────────────┐
                    ▼              ▼           ▼           ▼              ▼
              ┌──────────┐  ┌───────────┐ ┌─────────┐ ┌──────────┐ ┌────────────┐
              │ Catalog  │  │   Order   │ │ Payment │ │  Notify  │ │  Chatbot   │
              │ Service  │  │  Service  │ │ Service │ │ Service  │ │  Service   │
              └────┬─────┘  └─────┬─────┘ └────┬────┘ └────┬─────┘ └─────┬──────┘
                   │              │            │           │             │
              (each service wrapped in Resilience4j: circuit breaker, retry, timeout)
                   │              │            │           │             │
                   ▼              ▼            ▼           ▼             ▼
              PostgreSQL     PostgreSQL         │      Cloud Functions  Vertex AI
              (catalog db)   (orders db)        │      (email/alerts)   (Gemini)
                                                 │                        │
                                    ┌────────────▼───────┐          ┌─────▼──────┐
                                    │   Kafka (events)    │          │  pgvector  │
                                    │  order.placed        │          │ (RAG store)│
                                    │  order.paid           │          └────────────┘
                                    │  inventory.low        │
                                    └───────────────────────┘
                                                 │
                                    ┌────────────▼───────┐
                                    │  Redis               │  cache + rate-limit counters
                                    │  (cache, rate limits,│  + chatbot short-term context
                                    │   chatbot session)   │
                                    └───────────────────────┘

                                    ┌───────────────────────┐
                                    │  MongoDB               │  chatbot conversation logs,
                                    │  (logs, non-relational)│  audit logs — NOT orders/products
                                    └───────────────────────┘

     All service accounts scoped via GCP IAM (least privilege). Secrets in Secret Manager.
```

**Important nuance on "API Gateway" vs. "Load Balancer" — these are not the same thing, and we use both:**
- **Cloud Load Balancing** (GCP infrastructure layer) — distributes raw traffic across multiple running instances of our services, terminates TLS, handles failover. It doesn't know anything about our business logic.
- **API Gateway** (Spring Cloud Gateway, our own application) — sits just behind the load balancer, and *does* know our business logic: routes `/api/products/**` to the Catalog service, `/api/orders/**` to the Order service, validates the JWT once at the edge instead of in every service, and enforces rate limits.

Both exist in a mature system. Neither replaces the other.

---

## 3. Complete Tech Stack

### Frontend (`atlasna_front`)

| Tech | Role |
|---|---|
| React 19 + TypeScript | UI framework; TypeScript mirrors backend DTOs as compile-time-checked types |
| Vite | Build tool / dev server |
| React Router | Client-side routing |
| TanStack Query | Server-state management (caching, refetching, loading states) |
| Tailwind CSS | Styling |
| Zod | Runtime validation of API responses (TypeScript types are compile-time only — Zod catches a backend sending unexpected shapes at runtime) |
| Vitest + Testing Library | Unit/component testing |

### Backend (`atlasna-catalog`, evolving into multiple services)

| Tech | Role |
|---|---|
| Java 21 | Language — LTS, virtual threads |
| Spring Boot 4 | Application framework |
| Spring Security 7 | Auth/authz — JWT-based, method-level `@PreAuthorize` |
| Spring Data JPA + Hibernate | ORM over PostgreSQL |
| PostgreSQL | Primary relational database — products, orders, users. Strong consistency, foreign keys, transactions matter here. |
| **Spring Cloud Gateway** | API Gateway — routing, auth enforcement, rate limiting, once services split (Phase 5) |
| **Resilience4j** | Circuit breaker, retry, timeout, bulkhead — wraps every external/inter-service call (payment gateway, Vertex AI, Kafka, other microservices) |
| **Apache Kafka** | Event bus — decouples side effects from the main request path (e.g. placing an order shouldn't block on sending a confirmation email) |
| **Redis** | Cache-aside for product reads, token-bucket rate-limit counters, chatbot short-term conversation context |
| **MongoDB** | Specifically for chatbot conversation logs and audit/activity logs — flexible schema, no joins needed, NOT a replacement for Postgres on orders/products |
| Spring AI | Abstraction layer for calling LLMs (Vertex AI/Gemini) and defining MCP tools |
| JUnit 5 + Mockito | Testing |
| Docker | Containerization |

### Cloud & Infrastructure (GCP)

| Tech | Role |
|---|---|
| Cloud Run / GKE | Compute — Cloud Run for simpler services, GKE once we need full Kubernetes control |
| Cloud SQL (Postgres) | Managed database |
| Cloud Storage | Product images, static assets |
| **Cloud Load Balancing** | L7 load balancing, TLS termination, global routing |
| **Cloud Armor** | WAF — blocks malicious traffic at the edge (SQLi patterns, DDoS, etc.) |
| **IAM** | Every service gets its own service account with only the permissions it needs — principle of least privilege. No shared "god" credentials. |
| **Secret Manager** | JWT signing keys, DB passwords, API keys — never in code or config files |
| **Cloud Functions** | Serverless, event-triggered functions (GCP's equivalent of AWS Lambda) — see §4.6 for the specific ones we'll build |
| Cloud Monitoring & Logging | Observability |
| Terraform (GCP provider) | Infrastructure as code |

### AI

| Tech | Role |
|---|---|
| Vertex AI / Gemini | The actual LLM powering the chatbot |
| pgvector (on Cloud SQL) | Vector store for RAG — embeddings of product info, policies, FAQs |
| MCP (Model Context Protocol) | Standardized way to expose real backend functions (`getOrderStatus`, `getProductPrice`) as callable tools for the model, instead of a fragile custom integration |

---

## 4. Cross-Cutting System Design Patterns (detail)

### 4.1 API Gateway
**What:** one front door for all client requests. **Why:** without it, every microservice has to independently validate JWTs, handle CORS, and enforce rate limits — duplicated logic, duplicated bugs. **Tech:** Spring Cloud Gateway (Java-native, fits our stack). **Introduced:** Phase 5, once we actually have more than one service to route between.

### 4.2 Load Balancing
**What:** distributing incoming traffic across multiple running instances of a service so no single instance is overwhelmed, and traffic keeps flowing if one instance dies. **Tech:** GCP Cloud Load Balancing (L7/HTTP-aware). **Introduced:** Phase 7 (GCP deployment) — meaningless before we have multiple instances to balance across.

### 4.3 Rate Limiting
**What:** capping how many requests a client can make in a time window. **Why:** protects against brute-force login attempts, scraping, and a single misbehaving client (or bug) taking down the service. **Two layers:**
- **Gateway-level, coarse:** basic per-IP request caps at Spring Cloud Gateway.
- **Redis-backed, fine-grained:** a token-bucket algorithm for specific sensitive actions — login attempts (e.g. 5 per 15 min per account), chatbot queries (cost control — LLM calls aren't free), checkout attempts.
**Introduced:** basic version alongside login endpoint in Phase 1 (simple counter), full gateway + Redis version in Phase 5.

### 4.4 Resilience4j (fault tolerance)
**What it provides, and where each pattern applies:**
- **Circuit Breaker** — if the payment gateway (or Vertex AI) starts failing repeatedly, stop hammering it and fail fast instead, giving it time to recover.
- **Retry** — transient network blips get retried automatically (with backoff) instead of immediately failing the user's request.
- **Timeout** — no call waits forever; a slow downstream dependency can't freeze our whole request thread.
- **Bulkhead** — limits how many concurrent calls can hit a given dependency, so one slow dependency can't exhaust all our threads.
**Applied to:** payment gateway calls, Vertex AI/Gemini calls, Kafka producer calls, and all inter-service HTTP calls once split. **Introduced:** Phase 5.

### 4.5 Kafka (event-driven architecture)
**Why it matters even for a single store:** placing an order should trigger several side effects — send a confirmation email, decrement inventory, check for low-stock alerts, log for analytics. If `OrderService` calls all of those synchronously and directly, it becomes tightly coupled and slow, and one failing side effect (e.g. email service down) shouldn't block the order itself.

**Initial topics:**
| Topic | Producer | Consumer(s) |
|---|---|---|
| `order.placed` | Order Service | Notification Service, Inventory logic |
| `order.paid` | Payment Service | Order Service (update status), Notification Service |
| `inventory.low` | Catalog Service | Notification Service (admin alert), Cloud Function |

**Introduced:** Phase 5.

### 4.6 Redis
**Uses:**
1. **Cache-aside** for product catalog reads — most-viewed products cached with a short TTL, invalidated on admin update.
2. **Rate-limit counters** — token-bucket state (see §4.3).
3. **Chatbot short-term memory** — the last few turns of a conversation, keyed by session, so the AI has context without re-sending the whole history to the LLM every time.

**Introduced:** caching in Phase 3 (once there's enough traffic to matter), rate-limiting use in Phase 5, chatbot use in Phase 11.

### 4.7 MongoDB — specific, narrow use
**Not a Postgres replacement.** Orders, products, and users stay in PostgreSQL because they need real relational integrity (foreign keys, ACID transactions on checkout). MongoDB is added specifically for:
- `chatbot_conversations` — full conversation transcripts, variable structure, no joins needed
- `audit_logs` — "who did what, when" across the system, high write volume, rarely queried relationally

**Introduced:** Phase 3 (audit logs) and Phase 11 (chatbot logs).

### 4.8 GCP IAM
**Principle:** least privilege — every service gets its own service account, with only the specific GCP resource permissions it actually needs (e.g. the Catalog service can read/write Cloud SQL but has zero permission to touch Secret Manager entries it doesn't own). **Workload Identity** lets a GKE pod assume a GCP service account's identity without ever storing a downloaded key file on disk — the modern replacement for hardcoded credentials. **Introduced:** Phase 7, audited again in Phase 12.

### 4.9 Cloud Functions (serverless, event/schedule-triggered)
GCP's equivalent of AWS Lambda — small functions that run in response to an event, with no server to manage. Planned functions:
- **Order confirmation email** — triggered by the `order.placed` Kafka event (or Pub/Sub)
- **Low-stock admin alert** — triggered by `inventory.low`
- **Nightly sales report** — triggered on a Cloud Scheduler cron
- **Product image thumbnail generation** — triggered when a new image lands in Cloud Storage

**Introduced:** Phase 7.

---

## 5. Data Model (core entities)

**PostgreSQL (relational — strong consistency required):**
- `users` — id, full_name, email, password_hash, role (ADMIN/CUSTOMER), created_at
- `products` — id, name, description, price, category, stock_quantity, active, created_at, updated_at
- `orders` — id, user_id, status, total, payment_method, created_at, updated_at
- `order_items` — id, order_id, product_id, quantity, unit_price_at_purchase
- `payments` — id, order_id, provider, status, amount, transaction_ref

**MongoDB (flexible, non-relational):**
- `chatbot_conversations` — session_id, user_id (nullable — guests can chat), messages[], started_at
- `audit_logs` — actor_id, action, entity_type, entity_id, timestamp, metadata{}

---

## 6. API Surface (high-level)

```
POST   /api/auth/register
POST   /api/auth/login

GET    /api/products              (public, paginated, filterable by category/search)
GET    /api/products/{id}         (public)
POST   /api/products              (ADMIN)
PUT    /api/products/{id}         (ADMIN)
DELETE /api/products/{id}         (ADMIN)

POST   /api/orders                (authenticated — places an order from cart)
GET    /api/orders/{id}           (authenticated — owner or ADMIN)
GET    /api/orders                (authenticated — current user's order history)
PATCH  /api/orders/{id}/status    (ADMIN — advance order status)

POST   /api/chatbot/message       (public/authenticated — chatbot query)
```

---

## 7. Phase-by-Phase Build Plan

| # | Phase | Weeks | Tags | Ships |
|---|---|---|---|---|
| 1 | Java 21 + Spring Boot + Security Foundations | 1–5 | Backend | Catalog & Auth API — CRUD, JWT auth, admin-only writes |
| 2 | React + TypeScript Storefront | 6–9 | Frontend | Browse/search/cart/checkout UI, built against mocked data then real API |
| 3 | Redis caching + MongoDB (audit logs) | 10–12 | Backend | Cached product reads, audit trail for admin actions |
| 4 | Order & Payment Flow | 13–16 | Backend | Full checkout: cart → order → payment → confirmation, idempotent, state machine |
| 5 | Microservices split + API Gateway + Kafka + Resilience4j + Rate Limiting | 17–23 | Backend | Catalog/Order/Payment/Notification services behind a gateway, event-driven, fault-tolerant |
| 6 | DevOps Foundations | 24–27 | Infra | Docker Compose, CI/CD, dependency & image scanning |
| 7 | Google Cloud Platform | 28–33 | Infra | Live on GCP — Cloud Run/GKE, Cloud SQL, Load Balancing, IAM, Secret Manager, Cloud Functions |
| 8 | Kubernetes on GKE | 34–37 | Infra | Autoscaling, zero-downtime deploys, Helm |
| 9 | Terraform / IaC | 38–40 | Infra | All GCP resources reproducible from code |
| 10 | System Design Deep Dive | 41–45 | Both | Formal design doc — caching, CAP theorem, scale for Algeria's specific constraints |
| 11 | AI Chatbot — RAG + MCP | 46–51 | Both | Customer-facing chatbot answering order/pricing/policy questions from real data |
| 12 | Security Hardening Pass | 52–55 | Both | OWASP audit, mTLS, IAM least-privilege review, MCP tool-scoping audit |

*(Timeline extended from the original 10-month estimate to ~13 months to properly cover the added system-design scope — Kafka, the gateway, Resilience4j, and Redis/Mongo weren't in the original v1/v2 roadmap. This is the honest estimate, not a compressed one.)*

---

## 8. Repo & Folder Structure

```
atlasna_front/
  src/
    types/         ← mirrors backend DTOs exactly (Product, Order, User, ChatMessage)
    api/           ← fetch wrappers; mocked first, swapped to real endpoints per-phase
    components/
    pages/
    hooks/
  .claude/commands/
    explain-code.md

atlasna-catalog/
  src/main/java/com/atlasna/catalog/
    config/            ← security, CORS, gateway routes (once added)
    security/          ← JWT, filters
    user/
    product/
    order/             ← Phase 4+
    payment/           ← Phase 4+
    chatbot/           ← Phase 11 — RAG + MCP tool definitions
    common/exception/
  src/main/resources/
    application.yml
  docker-compose.yml   ← Postgres, Redis, Kafka, MongoDB for local dev
```

---

## 9. Non-Functional Targets (rough, revisit in Phase 10)

- API p95 latency: < 300ms for catalog reads, < 800ms for checkout
- Availability target: 99.5% (realistic for this stage, not claiming five-nines prematurely)
- All passwords BCrypt-hashed; all secrets in Secret Manager, never in git
- All service-to-service calls wrapped in Resilience4j once split into microservices

---

## 10. Glossary

| Term | Meaning |
|---|---|
| JWT | JSON Web Token — a signed, stateless token proving a user's identity and role |
| IAM | Identity and Access Management — controls *what* an authenticated identity is allowed to do |
| mTLS | Mutual TLS — both sides of a connection prove their identity, not just the client |
| OIDC | OpenID Connect — an identity layer built on top of OAuth2, used for "Login with Google"-style flows |
| RAG | Retrieval-Augmented Generation — grounding an LLM's answer in retrieved real data instead of its memorized training |
| MCP | Model Context Protocol — a standard way to expose real functions/tools to an AI model safely |
| Circuit Breaker | A pattern that stops calling a failing dependency temporarily, to let it recover and to fail fast for the caller |
| Idempotent | An operation that produces the same result no matter how many times it's (accidentally) repeated — critical for payment retries |
| CAP theorem | A distributed system can only fully guarantee two of Consistency, Availability, Partition-tolerance at once |
