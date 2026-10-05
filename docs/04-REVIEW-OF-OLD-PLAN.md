# Review of the Old Plan & Old Phase 1 Docs

You asked two things: **(1)** does the old work align with a grocery + beauty mini-mart in Annaba, and **(2)** do we change everything or make modifications?
Short answers: **(1) the architecture and plumbing align; the domain does not. (2) Modify — do not restart.** Details below.

---

## 1. What the earlier plans got wrong (my mistakes, stated plainly)

| # | Mistake | Where it showed up | Fixed in v4 |
|---|---|---|---|
| 1 | Treated Atlasna as a **generic marketplace**: categories `ELECTRONICS, FASHION, HOME_AND_KITCHEN, BEAUTY, GROCERIES, SPORTS, BOOKS`; demo data "headphones, sneakers, smartphone, tea set" | Master plan, backend task, frontend task, homepage mockup | Grocery + beauty only; heavy items excluded **by enforced rule**; no electronics anywhere |
| 2 | **Categories as a closed enum** | Backend `Category` enum; frontend hard-coded `CATEGORIES` | Categories are a table (admin-editable tree); API `GET /api/categories` |
| 3 | **No groceries-specific modelling:** integer stock, no units, no per-kg pricing, no weights, no expiry | `Product` entity | `sold_by`, `quantity_step`, `NUMERIC` stock, `unit_weight_grams`, perishables, lots/FEFO (Phase 5) |
| 4 | **No delivery model:** no zones, slots, landmarks, minimum order, driver, cash handling | Plans stopped at "orders" | Phase 3/4/6 cover zones, slots with capacity, addresses with landmark, driver view, COD reconciliation |
| 5 | **Nationwide assumptions:** a 58-wilaya dropdown at checkout | Frontend task `wilayas.ts` | Annaba-only delivery zones |
| 6 | **Arabic/French ignored:** frontend task listed i18n/RTL as a non-goal | Frontend task "Non-goals" | Bilingual + RTL from Phase 2; error codes translated; bilingual product fields |
| 7 | **Did not check the legal/hosting reality** of selling online in Algeria before proposing GCP | Master plan | Gate G1: Law 18-05 describes Algerian-hosted `.com.dz` sites; GCP has no Algerian region → GCP is for learning/staging, architecture stays portable, decision recorded as an ADR |
| 8 | **Payments hand-waved** ("CIB/Edahabia") | Master plan | Gate G2: SATIM rails, Chargily Pay V2 adapter behind a port, COD first, webhook-as-truth |
| 9 | **H2 for tests** while the app needs Postgres features (JSONB, partial indexes, locking) | Backend task | Testcontainers Postgres from Phase 1 |
| 10 | **DevOps and security audit pushed to the end**; CI absent for 8 phases | Plan order | Security baseline + tests in every phase; CI-lite from Phase 1 habits, full CI in Phase 9; Phase 17 is an audit, not the first look |
| 11 | **Backend/frontend not alternating** as you asked | Phase tables | 17 phases: odd = backend/platform, even = frontend |
| 12 | Cart/checkout modelled as **client-side + mocked orders** | Frontend task | Server cart, idempotent orders, real lifecycle (Phases 3–4) |
| 13 | **Unverified tech details** stated confidently | Various | v4 states what was verified (see README §"What was verified") and marks the rest "verify before use" |

---

## 2. Verdict on each file you uploaded

### `ATLASNA_MASTER_PLAN.md` → **Superseded** by `00-MASTER-PLAN.md`
Keep for history only. Its value: the architecture sketch (gateway/LB distinction, Kafka/Redis/Mongo roles, GCP stack) — all retained and refined. Its problems are in §1 above.

### `phase1-backend-task.md` → **Modify (≈ keep the foundation, rework one vertical)**
Your running backend has already moved beyond this document (the frontend task shows Flyway, login rate limiting, `via-dto` paging and an admin bootstrap), so the revision doc **tells Claude Code to inspect the real code first**.

| Area | Verdict | Notes |
|---|---|---|
| JWT issuing/validation, `JwtAuthFilter`, BCrypt, stateless `SecurityConfig` skeleton | ✅ **Keep** | Only CORS settings and route rules change |
| Login rate limiting, admin bootstrap, Docker Compose Postgres, Flyway setup | ✅ **Keep** | |
| `GlobalExceptionHandler` structure | ♻️ **Extend** | Add stable `code` to every error |
| `User` / `AuthService` / `AuthController` | ♻️ **Small change** | Add `phone`, `/me`, `ACCOUNT_DISABLED` |
| `Category` enum | ❌ **Replace** | → `categories` table + endpoints |
| `Product` entity, DTOs, service, controller, repository, seeder | ❌ **Rewrite this vertical** | New fields, bilingual, units/weights, JSONB, public vs admin DTOs, combinable filters, whitelisted sort |
| Tests mentioning old categories / H2 | ♻️ **Rework** | Testcontainers + the 18-test list |
| Database | ♻️ **One new migration** (`V2`, executed and verified) | Destructive for demo rows only |

**Effort:** roughly 1–2 weeks with Claude Code, versus ~5 for a full restart — and you keep every security lesson already learned.

### `phase1-frontend-task.md` → **Modify if built; reuse as reference either way**
Its engineering is good and was written against your real versions (Vite 8, TS 6, React Router 8, Zod 4, Tailwind 4). **Keep:** API client with `ApiError`/`NetworkError`/`ContractError`, session store, 401-vs-login handling, `Retry-After` handling, QueryClient config, MSW + Vitest setup, folder rules.
**Replace:** the domain layer — hard-coded categories, product types, wilayas, mock orders, the product list page assumptions (search clears category), admin pages (moved to Phase 6), the cart model (now step-aware kg quantities).
The section-by-section table is in `phases/phase-02-FE-storefront.md` §2.

### `atlasna-roadmap.html` / earlier HTML & PPTX roadmaps → **Archive**
Use `00-MASTER-PLAN.md` instead.

---

## 3. The 13 backend gaps the frontend review found → where each is resolved

| # | Gap (from your frontend task) | Resolved in |
|---|---|---|
| 1 | CORS doesn't expose `Retry-After` | **Phase 1** (Step 8) |
| 2 | No `GET /api/auth/me` | **Phase 1** (Step 6) |
| 3 | `search` and `category` can't combine | **Phase 1** (Specifications) |
| 4 | `sort` not whitelisted (probably 500) | **Phase 1** (`400 INVALID_SORT`, test 5) |
| 5 | No admin list of inactive products / no restore | **Phase 1** (`/api/admin/products?active=`, `/restore`) |
| 6 | No image field | **Phase 1** (`images` in the contract, table exists) → **Phase 5** upload → **Phase 6** UI |
| 7 | No refresh token; 60-min logout | **Phase 5** (rotation + reuse detection) → **Phase 6** (frontend adoption) |
| 8 | Disabled account → confusing 401 | **Phase 1** (`ACCOUNT_DISABLED`) |
| 9 | `LoginRequest` has no custom messages | **Phase 1** (Step 6) |
| 10 | CORS origins hard-coded | **Phase 1** (config) + **Phase 12** (same-origin hosting) |
| 11 | `allowCredentials(true)` unnecessary | **Phase 1** (set to false; revisited with cookies in Phase 5/6) |
| 12 | `ProductResponse` lacks `updatedAt` | **Phase 1** (`AdminProductResponse`) |
| 13 | No orders/payments | **Phases 3–5** (backend), **4** (frontend) |

---

## 4. How to handle your existing files

```
atlasna-catalog/docs/archive/        ← put ATLASNA_MASTER_PLAN.md and phase1-backend-task.md here (history, don't feed to Claude Code)
atlasna_front/docs/archive/          ← put phase1-frontend-task.md here (Phase 2 refers to it for plumbing)
atlasna-catalog/docs/                ← put the NEW docs here (copy the whole atlasna-docs folder contents)
atlasna_front/docs/                  ← copy the same new docs here
```
Tell Claude Code which folder is current: **"Only follow documents outside `docs/archive/`."**
