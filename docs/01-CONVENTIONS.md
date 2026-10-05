# Atlasna — Conventions & Working Agreement

Applies to **every** phase. A phase document may add rules; it may not weaken these.

---

## 1. How Claude Code must work on this project

The developer is **learning** modern Java, cloud, system design and AI by building this. Working fast is
good; working *opaquely* is not.

1. **Read first.** Before writing code read: `00-MASTER-PLAN.md`, this file, `02-DOMAIN-AND-DATA-MODEL.md`,
   `03-API-CONTRACT.md` and the phase file you were pointed at. Then **inspect the actual repository** —
   the docs describe intent; the code is the truth. If they disagree, stop and report the difference.
2. **Plan, then act.** Start with a short numbered plan (files to add/change, in order). Wait for a "go"
   only if the phase file says "confirm before proceeding"; otherwise proceed.
3. **Small steps, always green.** After every step run the build and the tests relevant to it
   (`./mvnw -q verify` / `npm run build && npm run lint && npm test`). Do not batch ten files and compile once.
   Never leave the repo red at a commit.
4. **Never guess an API.** Spring Boot 4.1, Spring Security 7, Spring AI 2.0, React Router 8, Zod 4, Tailwind 4
   and TypeScript 6 are newer than most tutorials. When something doesn't compile: read the installed
   version's source/docs (`./mvnw dependency:tree`, inspect the jar, read the migration guide). Do **not** patch it
   with a snippet from an older major version.
5. **No scope creep.** Do what the phase says. Note good ideas in `docs/BACKLOG.md` instead of building them.
   Never add concepts that are out of scope (vendors/sellers, electronics, nationwide shipping…).
6. **Explain the important parts.** For security-, concurrency-, money- and migration-critical code add a short
   comment saying *why* (not what). At the end of each step, print a 3–5 line summary: what you did, which concept
   it applies, and anything the developer should double-check.
7. **Ask when ambiguous.** One precise question beats a wrong 400-line change.
8. **Be honest about verification.** Say what you ran and what you saw. "Should work" is not a result.
   If you could not run something (no Docker, no network), say so.
9. **Leave breadcrumbs.** Update `03-API-CONTRACT.md` when an API changes, add an ADR in `docs/adr/NNNN-title.md`
   for any real design decision (context → decision → consequences), keep the README runnable.

## 2. Repositories & layout

| Repo | Purpose | Notes |
|---|---|---|
| `atlasna-catalog` | Backend (Java 21 / Spring Boot 4.1) | Root package `com.atlasna.catalog`. Evolves into several deployables in Phase 7 |
| `atlasna_front` | Frontend (React 19 / TS 6 / Vite 8) | Dev server **must** run on port 5173 (backend CORS) |
| `docs/` (in both repos) | These documents | Backend repo is the source of truth for `03-API-CONTRACT.md`; copy to the frontend repo when it changes |

Backend packages are **modules**, not technical layers: `identity`-ish (`user`, `security`), `product`
(catalog), later `order`, `delivery`, `inventory`, `payment`, `notification`, `assistant`, plus `common`/`config`.
Inside a module: `web` (controllers + DTOs), `service`, `domain` (entities, repositories). Another module may only call
a module's **public API** (types at the module's root package); never its internals (enforced by Spring Modulith from Phase 3).

## 3. Backend conventions (Java)

- **Java 21.** Records for DTOs and value objects. Constructor injection (`@RequiredArgsConstructor`). No field `@Autowired`.
- **Lombok** only for `@RequiredArgsConstructor`, `@Getter/@Setter`, `@Builder` on entities/config. **No `@Data` or `@EqualsAndHashCode` on JPA entities.**
- **Never expose entities** in the API. Map to DTOs. Public and admin views are *different* DTOs (public never shows exact stock, cost, or internal flags).
- **Money:** `BigDecimal`, scale 2, `NUMERIC(12,2)`. Never `double`/`float`. Quantities that can be fractional (kg): `NUMERIC(10,3)`.
- **Time:** `Instant` in UTC; JSON ISO-8601. Store `TIMESTAMP WITH TIME ZONE`. Business-time rules (slots, cut-offs) use `Africa/Algiers` explicitly.
- **Database:** Flyway only. `spring.jpa.hibernate.ddl-auto=validate`. **Never edit an applied migration**; add the next version.
  Migrations are forward-only; destructive ones need a comment explaining why they are safe.
  Constraints belong in the database *and* validation in the app (the DB is the last line of defence).
- **Transactions:** `@Transactional` on service methods (read-only for queries). No lazy loading outside a transaction; `open-in-view=false`.
  Use explicit fetch strategies / DTO projections; watch for N+1 (assert query counts in tests for list endpoints).
- **Concurrency:** stock changes use an atomic conditional `UPDATE … SET stock = stock - :q WHERE id = :id AND stock >= :q`
  or optimistic locking (`@Version`) with retry. Every such code path has a multi-threaded test.
- **Config:** `application.yml` + env vars; typed `@ConfigurationProperties` records. No secrets in git (dev placeholders are labelled as such).
- **Logging:** SLF4J, structured, never log passwords, tokens, full phone numbers or addresses. Include a request/correlation id.
- **External calls** (payment gateway, LLM, object storage, SMS): behind an interface in the domain module + an adapter,
  wrapped with timeout, retry (idempotent calls only) and circuit breaker (Phase 5+). No raw `RestTemplate` calls scattered about.

## 4. API conventions (see `03-API-CONTRACT.md`)

- JSON, camelCase, ISO-8601 UTC timestamps, money as JSON numbers with ≤2 decimals, quantities as numbers with ≤3.
- Pagination: `page` (0-based), `size` (default 20, max 100); response `{ content, page:{size,number,totalElements,totalPages} }`
  (`spring.data.web.pageable.serialization-mode: via-dto`).
- **Sort fields are whitelisted per endpoint**; anything else → `400 INVALID_SORT`.
- **Localised text** is always an object `{ "fr": string, "ar": string|null }`. Clients fall back `ar → fr`.
- **Errors** — one shape everywhere (`ApiError`):
  `{ timestamp, status, error, code, message, fieldErrors? }`. `code` is a **stable machine-readable string**
  (`PRODUCT_NOT_FOUND`, `INSUFFICIENT_STOCK`, `INVALID_STATE_TRANSITION`, …); the frontend maps `code → translated message`.
  `message` is English for developers/logs. Never leak stack traces or SQL.
- Status codes: `400` validation/malformed · `401` unauthenticated · `403` forbidden · `404` not found (**also** for resources the caller may not know exist — avoid enumeration) ·
  `409` conflict/state/stock · `422` business-rule violation where useful · `429` + `Retry-After` · `5xx` never on bad client input.
- **Idempotency:** any `POST` that creates an order or a payment accepts `Idempotency-Key` and is safe to retry.
- Public endpoints are an explicit allow-list; `anyRequest().authenticated()` is last.

## 5. Frontend conventions

- Folder rule: **components never call `fetch`** → pages use hooks → hooks use `api/` → `api/` uses `api/client.ts`.
- Zod v4 schemas are the single source of types (`z.infer`). Every API response is validated at runtime; a mismatch is a `ContractError`, not a crash.
- No `enum` / `namespace` / parameter properties (`erasableSyntaxOnly`). Type-only imports use `import type` (`verbatimModuleSyntax`).
- Server state in TanStack Query; URL is the source of truth for list filters; small client state in external stores / context.
- **i18n/RTL:** every user-visible string via i18next (`fr`, `ar`). `<html lang dir>` updated on language change. Use **logical** CSS
  (`ms-*`, `me-*`, `ps-*`, `pe-*`, `text-start`, `start-*`) — never `ml-*`/`left-*` for layout. Mirror directional icons. Numbers use Latin digits
  (`ar-DZ-u-nu-latn`); money only via `Intl` (`fr-DZ` → "4 900,50 DA", `ar-DZ` → "4.900,50 د.ج."; never `fr-FR`, which prints "DZD").
- **Accessibility:** semantic HTML, labels, focus rings, keyboard reachable, `aria-live` for toasts/status, contrast ≥ WCAG AA, touch targets ≥ 44 px.
- **Performance:** mobile-first, lazy-load routes and images, no layout shift (reserve image space), budgets from Phase 10.
- **Security:** never `dangerouslySetInnerHTML`; never render model/product text as HTML; never put secrets in `VITE_*` variables;
  token storage rules from the phase files.

## 6. Security baseline (every phase)

- [ ] Every new endpoint is in the security config *or* protected by `@PreAuthorize`; default is deny.
- [ ] **Object-level authorization:** a customer can only read/modify **their own** orders/addresses/cart. There is a test where user A requests user B's resource and gets `404`.
- [ ] Role checks tested: anonymous → 401, wrong role → 403, right role → 2xx.
- [ ] Input validated (Bean Validation) and normalised (emails lower-case, phone → `+213…`). File uploads: type + size + magic-byte check, random server-side names.
- [ ] No sensitive data in logs, URLs, error messages, or the JWT payload beyond `sub`/roles.
- [ ] Rate limits on login, registration, checkout, chatbot.
- [ ] Dependencies free of known critical CVEs at merge time (enforced in CI from Phase 9).
- [ ] No secrets committed; `git log -p | grep -i -E "secret|password|token"` shows placeholders only.

## 7. Testing

- **Pyramid:** many unit tests · slice tests (`@WebMvcTest`, `@DataJpaTest`) · fewer full-stack integration tests · a handful of E2E (Playwright, Phase 10).
- **Real Postgres via Testcontainers** for repositories, migrations and anything using JSONB/partial indexes/locking. H2 is not acceptable for these.
- Every phase: authorization tests, validation tests, at least one concurrency test where money/stock is involved.
- Frontend: MSW handlers mirror real backend behaviour *including its quirks*; fixtures are **captured from the real backend**, not invented.
- A test must be able to fail. After writing a security/stock test, break the code once and watch the test go red.

## 8. Git & delivery

- Branch per phase/step: `phase-N-short-name`; PR (even if self-reviewed) with the DoD checklist ticked.
- **Conventional Commits:** `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`, `db:` (migrations). One logical change per commit.
- Tag when a phase is done: `phase-N-done`. Tag `monolith-v1` before Phase 7.
- Never force-push `main`. Never commit `.env`, keys, `target/`, `node_modules/`, `dist/`, `.DS_Store`.

## 9. Definition of Done (every phase)

- [ ] Phase acceptance criteria all met and **demonstrated** (commands/screens in the PR description).
- [ ] Build + lint + tests green from a clean checkout (`./mvnw clean verify` / `npm ci && npm run build && npm test`).
- [ ] Security baseline (§6) ticked.
- [ ] `03-API-CONTRACT.md`, README, and ADRs updated.
- [ ] Migrations apply to an empty DB **and** to the previous phase's DB.
- [ ] Both languages (fr/ar) checked for any user-facing change.
- [ ] Developer has been given the "Concepts & interview questions" for the phase and has answered at least three out loud.
