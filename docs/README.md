# Atlasna — Engineering Docs (v4)

**Atlasna** = a single mini-mart in **Annaba, Algeria** selling **groceries and beauty/personal-care** products online, delivered by its own staff. One store, no vendors,
no electronics, no heavy items. French + Arabic (RTL). Cash on delivery first. Built phase by phase to learn modern Java, cloud, system design and AI.

> Start with **`00-MASTER-PLAN.md`**. Then do **Phase 1** (a revision of the backend you already built) and **Phase 2** (the frontend).

---

## What's in this folder

```
atlasna-docs/
├── README.md                          ← you are here
├── 00-MASTER-PLAN.md                  ← business, scope, decision gates, architecture, tech stack, 17-phase roadmap
├── 01-CONVENTIONS.md                  ← how Claude Code must work + backend/frontend/security/testing/git rules (applies to every phase)
├── 02-DOMAIN-AND-DATA-MODEL.md        ← business rules, roles, full data model, state machines, error codes, demo seed data
├── 03-API-CONTRACT.md                 ← the REST contract (backend ⇄ frontend source of truth)
├── 04-REVIEW-OF-OLD-PLAN.md           ← what was wrong, what stays, what changes, where each old gap is fixed
├── phases/
│   ├── phase-01-BE-catalog-revision.md        Backend  — grocery/beauty catalog (REVISION of your existing build)
│   ├── phase-02-FE-storefront.md              Frontend — bilingual storefront, guest cart, auth
│   ├── phase-03-BE-delivery-cart-orders.md    Backend  — zones, slots, server cart, idempotent COD orders, stock ledger, Modulith
│   ├── phase-04-FE-cart-checkout-orders.md    Frontend — checkout, address book, order tracking, reorder
│   ├── phase-05-BE-data-layer-resilience.md   Backend  — Redis, refresh tokens, Mongo audit, payments (Resilience4j), images, lots/FEFO, search
│   ├── phase-06-FE-admin-backoffice.md        Frontend — admin, staff, driver apps; refresh-token flow
│   ├── phase-07-BE-events-gateway-services.md Backend  — Kafka, outbox, notification service, API gateway, service split
│   ├── phase-08-FE-realtime-pwa.md            Frontend — SSE tracking, notification centre, PWA
│   ├── phase-09-BE-devops-observability.md    Platform — Docker, GitHub Actions, scanning, metrics/traces/logs
│   ├── phase-10-FE-quality-performance.md     Frontend — Playwright, a11y, performance budgets, CSP, error monitoring
│   ├── phase-11-BE-gcp-terraform.md           Platform — GCP + Terraform, WIF, Cloud Run, Cloud SQL, Cloud Armor, Functions, cost guardrails
│   ├── phase-12-FE-production-delivery.md     Frontend — CDN hosting, cache/headers, deploy + rollback, portable image
│   ├── phase-13-BE-ai-assistant.md            Backend  — Spring AI, RAG (pgvector), read-only tools, MCP ops copilot, evals
│   ├── phase-14-FE-ai-chat-widget.md          Frontend — streaming chat widget with safe rendering
│   ├── phase-15-BE-kubernetes-gke.md          Platform — GKE, Helm, Gateway API, HPA, Workload Identity, canary
│   ├── phase-16-FE-launch-readiness.md        Frontend — legal pages, store hours/Ramadan, content, user testing
│   └── phase-17-BE-security-system-design.md  Backend  — threat model, pentest, mTLS, load/chaos/DR, SLOs, final design doc
├── sql/V2__grocery_beauty_catalog.sql         ← Phase 1 migration (executed against PostgreSQL 16)
└── snippets/                                  ← code that was compiled/executed (also embedded in the phase docs)
    ├── PhoneNumbers.java, Slugs.java
    └── frontend/ catalog.ts, quantity.ts, i18nText.ts, money.ts, phone.ts
```
Every phase file has the same shape: goal → scope → ordered steps → required tests → acceptance criteria → out of scope → commits/tag → **concepts & interview questions** (for you; Claude Code can ignore that last section).

## Where to put the files

1. Copy **everything in this folder** into `docs/` of **both** repos (`atlasna-catalog/docs/` and `atlasna_front/docs/`).
2. Move your **old** files into `docs/archive/` of the matching repo (see `04-REVIEW-OF-OLD-PLAN.md` §4) and tell Claude Code: *"Only follow documents outside `docs/archive/`."*
3. The backend repo owns `03-API-CONTRACT.md`; re-copy it to the frontend repo whenever it changes.

## How to run a phase with Claude Code

Open Claude Code in the right repo (backend for odd phases, frontend for even phases) and paste:

```
Read docs/00-MASTER-PLAN.md, docs/01-CONVENTIONS.md, docs/02-DOMAIN-AND-DATA-MODEL.md,
docs/03-API-CONTRACT.md and docs/phases/phase-NN-<name>.md. Ignore anything in docs/archive/.
Do NOT start coding yet. First:
1. Inspect the actual repository and summarise what exists and where it differs from the phase document.
2. Give me a numbered plan of the steps you will take.
Then wait for my "go". After that work one step at a time: build and run the relevant tests after each step,
show me the real output, commit with a Conventional Commit message, and stop at any failure instead of guessing.
At the end of each step explain in 3–5 lines which concept you applied.
```

**Phase 1 (do this first)** — same prompt with `phase-01-BE-catalog-revision.md`, plus: *"This is a revision of the existing build. Do Step 0 (preflight) and report before changing anything."*
**Phase 2** — same prompt with `phase-02-FE-storefront.md`, plus: *"Start with its Step 0 to detect whether the earlier frontend task was already implemented."*
**When stuck:** paste the *exact* error and the *step number* — not "it doesn't work".
**When done with a phase:** run its acceptance checklist yourself, tag it (`phase-N-done`), then answer three of its interview questions out loud before moving on. Ask me for the "notes" for that phase if you want a revision sheet.

## Decision gates — real-world facts that can change the plan (details in `00-MASTER-PLAN.md` §2)

| Gate | Needed by | In one line |
|---|---|---|
| **G1 Hosting & legal** | Phase 11 | Law 18-05 describes Algerian-hosted `.com.dz` e-commerce sites; GCP has no Algerian region → treat GCP as learning/staging until a lawyer says otherwise |
| **G2 Payments** | Phase 5 | SATIM rails; Chargily Pay V2 behind a port; COD works without any gateway |
| **G3 Notifications** | Phase 7 | SMS/WhatsApp provider not chosen; adapters first |
| **G4 Kafka hosting** | Phase 11 | Managed Kafka costs money; Spring Cloud GCP starters aren't offered for Boot 4.1 |
| **G5 AI & personal data** | Phase 13 | Customer text goes to a foreign LLM → minimise PII, retention limits, human hand-off |
| **G6 Business basics** | Phase 16 | RC/NIF, CGV, price disclosure, return terms before purchase |

*Not legal advice — confirm with a local lawyer/accountant.*

## What was verified (and what wasn't) — so you know how much to trust each part

**Executed / compiled before I wrote it into these docs**
- `sql/V2__grocery_beauty_catalog.sql` applied on **PostgreSQL 16** on top of an old-style schema *with old rows present*; 12 constraint/index rules confirmed to reject bad data; cascade and partial-unique-index behaviour confirmed.
- `PhoneNumbers.java` and `Slugs.java` compiled with JDK 21; 16 assertions pass.
- Frontend `catalog.ts` (Zod 4.6), `quantity.ts`, `i18nText.ts`, `money.ts`, `phone.ts`: type-checked under `strict` + `erasableSyntaxOnly` + `verbatimModuleSyntax`; 17 + 8 assertions pass, including parsing the contract's own JSON examples, rejecting drift, kg/centime arithmetic with no float drift, and real `Intl` output (`fr-DZ` → `4 900,50 DA`; `ar-DZ` → `4.900,50 د.ج.` with Latin digits; `fr-FR` prints `DZD`, so it's forbidden).
- Dependency names and versions taken from **start.spring.io for Boot 4.1.1** (2026-10-05): Spring AI 2.0.1, Spring Cloud 2025.1.3, Spring Modulith 2.1.1; exact starter artifact names; Spring Cloud GCP **not** offered for Boot 4.1.
- Legal and payment facts checked against the Ministry of Commerce / press sources and Chargily's and SATIM's public documentation.

**Written as specifications, NOT compiled or run (Claude Code must verify against the installed versions)**
- Everything in Phases 3–17 (they are requirements and designs, not code). Spring AI property names, Testcontainers 2.x class names, `vite-plugin-pwa` with Vite 8, `@fontsource` package names, Spring Modulith APIs, GKE/Gateway API resource kinds, MCP-security (experimental) — all marked "verify".
- The Arabic strings (categories, demo products) are mine — **have a native speaker review them**.
- Prices, fees, slots, weight limits, opening hours are **placeholders marked ⚙** for the owner to confirm; they live in configuration, not code.

## Honest advice
- Phases **1–6 are what a real mini-mart needs** (plus 16). Phase 7 (microservices) and Phase 15 (Kubernetes) are **learning exercises** — the docs say so and keep the simpler version tagged and runnable.
- Don't skip the tests that are written to fail first (concurrency, authorisation, idempotency). They are the point.
- Keep notes and interview answers outside git; everything else (docs, ADRs, runbooks) belongs in the repo.
