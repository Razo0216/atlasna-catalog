# Phase 2 (Frontend) — Bilingual Storefront Foundation

| | |
|---|---|
| **Repo** | `atlasna_front` (React 19 · TypeScript 6 · Vite 8) |
| **Type** | Frontend |
| **Prerequisite** | **Phase 1 (backend revision) done and running** on `localhost:8080` with the grocery/beauty catalog |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` (§5 especially), `02-DOMAIN-AND-DATA-MODEL.md`, `03-API-CONTRACT.md` §0–3 |
| **Also keep at hand** | `docs/archive/phase1-frontend-task.md` (the earlier frontend task — its plumbing sections are reused, see §2) |
| **Estimate** | 3–5 weeks |

---

## 1. Goal

A fast, **bilingual (French + Arabic, RTL)**, mobile-first storefront for a grocery & beauty mini-mart in Annaba. A visitor can browse the category tree,
search and filter, open a product page, put items in a **guest cart** (with kg-based quantities), register with an Algerian phone number, log in, and stay logged in.
Checkout is **not** in this phase (Phase 4) — the cart page ends with a clearly disabled "bientôt disponible" action.

## 2. Two possible starting points — Claude Code must detect which one applies

**Step 0 — inspect `atlasna_front`.** Report: is there a `src/api/client.ts`, `src/lib/session.ts`, `src/router.tsx`, a product list page? Are there commits?

* **A. The earlier frontend task was already implemented** → this is a **migration**. Keep the plumbing, replace the domain layer (table below). Do not rewrite what works.
* **B. Only the Vite scaffold exists** → build from this document; for plumbing follow the archived earlier task's sections as marked below.

| Earlier task section | Verdict | What to do |
|---|---|---|
| §A backend contract tables (7-category enum, `/api/products` admin writes, mock `stockQuantity`) | ❌ **Replace** | The contract is now `03-API-CONTRACT.md`. Categories are **data**, not an enum; admin routes moved to `/api/admin/*` |
| §B toolchain notes | ✅ Keep | Repeated in §3 below |
| §0 CORS `Retry-After` | ✅ Done in backend Phase 1 | Verify the header is readable in the browser |
| §1–2 dependencies & config (Vite port 5173 `strictPort`, `.env.*`, Tailwind v4, Vitest) | ✅ Keep | Add i18next packages (§4) |
| §4 types (hard-coded `CATEGORIES`, `CATEGORY_LABELS`, wilayas, mock `order.ts`) | ❌ **Replace** | Use the verified `catalog.ts` (§6). **Delete** `wilayas.ts` and the mock order types — delivery is Annaba-only and orders arrive in Phase 3/4 |
| §5 money (`toCentimes`, float-safe sums) | ♻️ Replace | `money.ts` + `quantity.ts` (§6): milli-units for kg, Intl formatting per language |
| §6 session store, `useAuth`, `SessionExpiryWatcher` | ✅ Keep | Add `phone`, roles `STAFF`/`DRIVER` to the user schema; call `/api/auth/me` once on start |
| §7 API client (`ApiError`, `NetworkError`, `ContractError`, 401-vs-login handling, 429 `Retry-After`) | ✅ Keep | Add `code` to `ApiError`; map `code → i18n key` |
| §8 `createQueryClient`, query keys | ✅ Keep | Keys change to the new endpoints |
| §9 client cart | ♻️ **Rework** | Step-aware quantities, per-kg lines, milli-units, versioned storage key, `CartRepository` interface (§9) |
| §10 router, guards | ♻️ Rework | New routes (§8); `RequireAdmin` kept but admin pages move to **Phase 6** |
| §11 shared components | ✅ Keep list | Make every one RTL-safe (logical CSS) |
| §12 pages | ♻️ Rework | Home, list, detail, cart, login, register redone for the new domain; **checkout / confirmation / admin pages are removed from this phase** |
| §13 MSW mocks | ♻️ Rework | New handlers + fixtures **captured from the real Phase 1 backend** |
| §14 tests | ✅ Keep approach | New required list in §12 |

## 3. Toolchain facts (verified on the developer's machine — do not "fix" them with older-major patterns)

1. `tsconfig.app.json` has **`erasableSyntaxOnly: true`**: no TypeScript `enum`, `namespace`, or constructor parameter properties. Use `as const` arrays, `z.enum`, union types, explicit class fields.
2. **`verbatimModuleSyntax: true`**: type-only imports use `import type { X }`.
3. **React Router 8**: import everything from `react-router`, except `RouterProvider` from `react-router/dom`. Do **not** install `react-router-dom`.
4. **Tailwind v4**: no `tailwind.config.js`, no PostCSS config; `@tailwindcss/vite` plugin and `@import "tailwindcss";`. Design tokens in `@theme`.
5. **Zod v4**: `z.email()`, `z.enum()`, `z.infer`, `.safeParse()`, errors in `error.issues`; recursive schemas via property getters.
6. **MSW**: `http`, `HttpResponse` from `msw`; `setupWorker` from `msw/browser`; `setupServer` from `msw/node`.
7. The dev server **must** stay on port **5173** (`strictPort: true`) — the backend CORS allows only that origin.

After **every** step: `npm run build && npm run lint && npm test`. Never batch.

## 4. Dependencies

```bash
npm install react-router @tanstack/react-query zod react-hook-form @hookform/resolvers i18next react-i18next i18next-browser-languagedetector
npm install -D tailwindcss @tailwindcss/vite vitest jsdom @testing-library/react @testing-library/dom @testing-library/user-event \
  @testing-library/jest-dom msw @tanstack/react-query-devtools
npx msw init public/ --save
```
Self-host fonts (no third-party font CDN — privacy and speed): Inter for Latin and an Arabic face such as **Noto Sans Arabic** (or Cairo) from `@fontsource*` — verify the exact package names on npm.
`dist/` and `.DS_Store` in `.gitignore`.

## 5. Internationalisation & RTL — the core new work

1. **Languages:** `fr` (default) and `ar`. Initial language: saved choice (`localStorage: atlasna.lang`) → else browser language if it starts with `ar` → else `fr`. A visible switcher (FR | العربية) in the header.
2. **On every language change:** set `document.documentElement.lang` and `dir` (`ltr`/`rtl`), update `document.title`, persist the choice. No page reload.
3. **Resources:** `src/i18n/{fr,ar}/{common,catalog,auth,cart,errors}.json`. **No string concatenation** — use interpolation. **Arabic needs all six CLDR plural forms** (`_zero,_one,_two,_few,_many,_other`) for any counted noun (a "N produits" label).
4. **Key parity check:** `npm run i18n:check` fails if `fr` and `ar` have different key sets. Run it in CI later.
5. **Logical CSS only.** Use `ms-* me-* ps-* pe-* start-* end-* text-start text-end border-s border-e rounded-s rounded-e`. Forbidden for layout: `ml- mr- pl- pr- left- right- text-left text-right`.
   Add `npm run lint:rtl` (a grep/ESLint rule) that fails on those classes in `src/`.
6. **Mirror directional icons** (chevrons, arrows, back buttons) with `rtl:-scale-x-100`. Don't mirror logos, play buttons, or numbers.
7. **Latin-content fields stay LTR inside RTL:** email, phone, password, SKU inputs get `dir="ltr"` (and `text-start` so the caret and placeholder behave).
8. **Fonts:** Latin `Inter`, Arabic `Noto Sans Arabic`; apply per `:lang(ar)`. Arabic text is visually smaller at the same px — bump line-height and size ~1.05–1.1× for `ar`.
9. **Numbers/dates/money:** only through `Intl` — `formatDzd(amount, lang)` (verified below): French → `4 900,50 DA`, Arabic → `‏4.900,50 د.ج.‏` with **Latin digits**. Dates use `fr-DZ` / `ar-DZ-u-nu-latn` with time zone `Africa/Algiers`. Never hand-write "DA".
   Do not use the `fr-FR` locale for money (it prints "DZD").
10. **Error messages:** every backend `code` (see `02-DOMAIN-AND-DATA-MODEL.md` §7) has `errors:<CODE>` in both languages. Show the translated message; fall back to a generic localised "Something went wrong" — never show raw English `message` to users (only in dev tools).
11. **Content that comes from the API** (`name`, `description`) is chosen with `pickText(text, lang)` — Arabic UI falls back to French; French UI never falls back to Arabic.
12. **Manual RTL checklist (do it, write the result in the PR):** header, filters drawer, product card, stepper, cart, forms, toasts, pagination — mirrored correctly at 360 px and 1280 px, no clipped text, no wrong-side icons.

## 6. Verified building blocks — add these files as-is, plus their tests

All of the following were **type-checked under `strict` + `erasableSyntaxOnly` + `verbatimModuleSyntax`** and exercised by assertions (quantity/money math, localisation fallback,
Intl output, contract parsing, phone vectors identical to the backend's Java tests). Convert the assertions into Vitest tests; keep the vectors.

**`src/types/catalog.ts`** — Zod v4 schemas mirroring `03-API-CONTRACT.md` (parse the contract's own examples; reject unknown `availability`, old `ELECTRONICS`-style types, `price: 0`, missing `soldBy`; unknown keys are stripped):
```ts
import { z } from 'zod'

export const localizedTextSchema = z.object({ fr: z.string(), ar: z.string().nullable() })
export const nullableLocalizedSchema = z.object({ fr: z.string().nullable(), ar: z.string().nullable() })

export const CATEGORY_TYPES = ['GROCERY', 'BEAUTY']
export const categoryTypeSchema = z.enum(CATEGORY_TYPES)

export const categoryNodeSchema = z.object({
  id: z.number().int(),
  slug: z.string(),
  type: categoryTypeSchema,
  name: localizedTextSchema,
  sortOrder: z.number().int(),
  get children() { return z.array(categoryNodeSchema) },   // Zod v4 recursive object (no z.lazy needed)
})

export const soldBySchema = z.enum(['UNIT', 'WEIGHT'])
export const availabilitySchema = z.enum(['IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK'])

export const productSchema = z.object({
  id: z.number().int(),
  slug: z.string(),
  sku: z.string(),
  barcode: z.string().nullable(),
  name: localizedTextSchema,
  description: nullableLocalizedSchema,
  brand: z.string().nullable(),
  category: z.object({ id: z.number().int(), slug: z.string(), type: categoryTypeSchema, name: localizedTextSchema }),
  price: z.number().positive(),
  compareAtPrice: z.number().positive().nullable(),
  soldBy: soldBySchema,
  quantityStep: z.number().positive(),
  unitWeightGrams: z.number().int().positive(),
  availability: availabilitySchema,
  maxOrderQuantity: z.number().nonnegative(),
  perishable: z.boolean(),
  featured: z.boolean(),
  attributes: z.record(z.string(), z.unknown()),
  images: z.array(z.object({ url: z.string(), alt: nullableLocalizedSchema })),
  createdAt: z.string(),
})

export const pageSchema = <T extends z.ZodType>(item: T) => z.object({
  content: z.array(item),
  page: z.object({ size: z.number().int(), number: z.number().int(), totalElements: z.number().int(), totalPages: z.number().int() }),
})

export const userSchema = z.object({
  id: z.number().int(), fullName: z.string(), email: z.string(), phone: z.string().nullable(),
  role: z.enum(['ADMIN', 'STAFF', 'DRIVER', 'CUSTOMER']),
})
export const authResponseSchema = z.object({
  accessToken: z.string().min(1), tokenType: z.literal('Bearer'), expiresInSeconds: z.number().int().positive(), user: userSchema,
})
export const apiErrorBodySchema = z.object({
  timestamp: z.string(), status: z.number().int(), error: z.string(),
  code: z.string().nullish(), message: z.string(),
  fieldErrors: z.record(z.string(), z.string()).nullish(),
})

export type LocalizedText = z.infer<typeof localizedTextSchema>
export type CategoryNode = z.infer<typeof categoryNodeSchema>
export type Product = z.infer<typeof productSchema>
export type SoldBy = z.infer<typeof soldBySchema>
export type Availability = z.infer<typeof availabilitySchema>
export type User = z.infer<typeof userSchema>
export type AuthResponse = z.infer<typeof authResponseSchema>
export type Page<T> = { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number } }
```
**`src/lib/quantity.ts`** — integer milli-units; `snapQuantity`, `lineTotalCentimes` (1.5 kg × 80 DA = 12 000 centimes; no float drift):
```ts
/**
 * Quantities are handled as INTEGER MILLI-UNITS (1.5 kg = 1500) so float drift
 * (0.1 + 0.2 !== 0.3) can never reach money. Everything user-facing converts at the edge.
 */
export const toMilli = (quantity: number): number => Math.round(quantity * 1000)
export const fromMilli = (milli: number): number => milli / 1000

/** Snap a requested quantity DOWN to a valid multiple of `step`, within [step, max]. Returns 0 if nothing can be offered. */
export function snapQuantity(requested: number, step: number, max: number): number {
  const stepMilli = toMilli(step)
  const maxMilli = toMilli(max)
  if (maxMilli < stepMilli) return 0
  const snapped = Math.floor(toMilli(requested) / stepMilli) * stepMilli
  const highest = Math.floor(maxMilli / stepMilli) * stepMilli
  return fromMilli(Math.min(Math.max(snapped, stepMilli), highest))
}

/** unitPriceDa is DA per unit (or per kg); quantity in units (or kg). Returns centimes, rounded half-up. */
export function lineTotalCentimes(unitPriceDa: number, quantity: number): number {
  return Math.round((Math.round(unitPriceDa * 100) * toMilli(quantity)) / 1000)
}

export const sumCentimes = (values: readonly number[]): number => values.reduce((a, b) => a + b, 0)
export const fromCentimes = (centimes: number): number => centimes / 100
```
**`src/lib/i18nText.ts`**:
```ts
export type Lang = 'fr' | 'ar'
export type Localized = { readonly fr: string | null; readonly ar: string | null }

/** Rule: Arabic UI falls back to French; French UI never falls back to Arabic. Returns null if nothing usable. */
export function pickText(text: Localized, lang: Lang): string | null {
  const value = lang === 'ar' ? (text.ar?.trim() || text.fr) : text.fr
  return value && value.trim() !== '' ? value : null
}
```
**`src/lib/money.ts`** — Intl formatting (verified output: `"4 900,50 DA"` / `"‏4.900,50 د.ج.‏"`; the separators are non-breaking spaces, so tests use regexes):
```ts
import type { Lang } from './i18nText'

// Let Intl format money: fr-DZ -> "4 900,50 DA", ar-DZ -> "4.900,50 د.ج." (Latin digits). Never hand-roll symbols.
const LOCALES: Record<Lang, string> = { fr: 'fr-DZ', ar: 'ar-DZ-u-nu-latn' }
const formatters = new Map<Lang, Intl.NumberFormat>()

export function formatDzd(amountDa: number, lang: Lang): string {
  let formatter = formatters.get(lang)
  if (!formatter) {
    formatter = new Intl.NumberFormat(LOCALES[lang], { style: 'currency', currency: 'DZD' })
    formatters.set(lang, formatter)
  }
  return formatter.format(amountDa)
}
```
**`src/lib/phone.ts`** — must stay equivalent to the backend `PhoneNumbers.java`; **share the same test vectors**:
```ts
/**
 * Algerian MOBILE numbers only (05 / 06 / 07). Must stay byte-for-byte equivalent to the backend's PhoneNumbers.java.
 * Canonical form: "+213" + 9 digits. Returns null when invalid.
 */
const SEPARATORS = /[\s.\-()]/g
const ALGERIAN_MOBILE = /^(?:\+213|00213|0)([567]\d{8})$/

export function normalizeAlgerianMobile(raw: string): string | null {
  const match = ALGERIAN_MOBILE.exec(raw.replace(SEPARATORS, ''))
  return match ? `+213${match[1]}` : null
}

export const isAlgerianMobile = (raw: string): boolean => normalizeAlgerianMobile(raw) !== null
```
Add to `ApiError` a `code: string | null`; keep the rest of the earlier client unchanged.

## 7. API modules (`src/api/`)

* `catalog.ts` — `categoriesApi.tree()`; `productsApi.list(params)`, `.get(id)`, `.getBySlug(slug)`.
  Params: `page` (0-based), `size`, `sort` ∈ **`createdAt,desc` · `price,asc` · `price,desc` · `name,asc`** (a fixed list; never user text), `category` (slug), `type`, `q`, `inStock`, `featured`.
  **Filters combine now** — unlike the old backend, search and category are **not** mutually exclusive; don't clear one when the other is set.
* `auth.ts` — `register({fullName,email,phone,password})` (send the **raw** phone; the server normalises), `login`, `me`.
  On app start, if a stored session exists, call `me()` once: update the stored user, or end the session on `401`; ignore network failures (offline must not log people out).
* Everything validated by the Zod schemas; a mismatch is a `ContractError`.
* Attach the token **only** to calls that need it (the earlier `auth: true` option). Public GETs work even with an expired token.

## 8. Routes (`src/router.tsx`)

| Path | Page | Notes |
|---|---|---|
| `/` | Home | |
| `/products` | Product list | URL = state: `?type=&category=&q=&inStock=&sort=&page=` |
| `/products/:slug` | Product detail | by slug (SEO-friendly); non-matching → friendly 404 |
| `/cart` | Cart | guest cart |
| `/login`, `/register` | Auth | redirect back via `state.from` |
| `/account` | Account stub | profile, language, logout. Orders arrive in Phase 4 |
| `/about`, `/delivery` | Static stubs | real legal/info content in Phase 16 |
| `*` | 404 | |

Lazy-load every route except Home. `RequireAuth` guard from the earlier task is kept.

## 9. Guest cart (browser-local) — rework

Behind a `CartRepository` interface (`load/add/setQuantity/remove/clear/reconcile`) so Phase 4 can swap in the server cart and merge the guest cart at login.
Storage key `atlasna.cart.v2` (versioned), validated with Zod on load; corrupt → empty cart; synced across tabs (external store).

```ts
type CartLine = {
  productId: number; slug: string; name: Localized /* snapshot */; imageUrl: string | null
  unitPrice: number                 // last known DA (per unit, or per kg)
  soldBy: 'UNIT' | 'WEIGHT'; quantityStep: number; maxOrderQuantity: number; unitWeightGrams: number
  quantity: number                  // units, or kg — always a multiple of quantityStep
  flags?: { unavailable?: boolean; priceChanged?: { from: number; to: number }; overMax?: boolean }
}
```
Rules (unit-test every one):
* `add`: out-of-stock → no-op; existing line → add quantities; always `snapQuantity(q, step, max)`.
* `setQuantity`: snap; `<= 0` removes; never above `maxOrderQuantity`.
* `reconcile(productId, fresh | null)`: update price/step/max/weight; set `priceChanged` when price differs; `null` (404/inactive) → `unavailable`; if `quantity > max` set `overMax` and **do not silently reduce** — the UI shows "Only N left" and blocks the next phase's checkout.
* Totals only via `lineTotalCentimes`/`sumCentimes`. Per-kg lines display "≈" and "prix final selon la pesée / السعر النهائي بعد الوزن".
* Total weight = Σ `unitWeightGrams × quantity`; show it as information (the 15 kg cap is enforced by the backend in Phase 3 and surfaced in Phase 4).
* Cart is not cleared on logout (per-device); it **is** cleared after a successful order (Phase 4).

## 10. Pages

**Header/footer:** logo, search box (submits to `/products?q=`), language switcher, cart icon with item-count badge, account menu (login/register or name + logout). `SessionExpiryWatcher` mounted once. Footer: delivery area (Annaba), hours, contact placeholder.

**Home:** bilingual hero ("Épicerie & beauté livrées chez vous à Annaba"), two large tiles *Épicerie* / *Beauté* (→ `?type=`), category chips from `GET /api/categories`, **featured** products (`featured=true`), **new arrivals**. Skeletons while loading, `ErrorState` with retry, empty state.

**Product list `/products`:** all state in the URL; invalid values fall back to defaults and are **never forwarded** to the API. Type tabs (All · Épicerie · Beauté), collapsible category tree (a bottom sheet on mobile), debounced (300 ms) search, in-stock toggle, sort select, pagination; keeps previous results dimmed while loading the next page (`placeholderData: keepPreviousData`); result count; "Clear filters"; page beyond `totalPages` → redirect to the last page.

**Product card:** category-coloured placeholder tile with an icon (real images arrive in Phase 5 — `images: []` today), name via `pickText`, brand, **price with unit** ("80 DA / kg"), "was" price + discount % when `compareAtPrice`, availability badge (`LOW_STOCK` → "Plus que quelques-uns"; `OUT_OF_STOCK` → disabled add), add button + step-aware stepper.

**Product detail `/products/:slug`:** breadcrumb (type › category › product), price per unit, `attributes` rendered through an **allow-list** of known keys (`halal`, `origin`, `ingredients`, `skinType`, `hairType`, `alcoholFree`, `vegan`, `allergens`) with localised labels — unknown keys ignored, **never raw HTML** — quantity stepper (`quantityStep`, `maxOrderQuantity`), add to cart + toast with "Voir le panier", per-kg note, same-category suggestions (optional).

**Cart `/cart`:** lines with steppers and remove; `useQueries` revalidates each line against the live catalog on mount (price changed / unavailable / only N left); subtotal marked "estimé" if any per-kg line; delivery fee shown as "calculée à l'étape suivante"; primary action **disabled** with "Commande bientôt disponible" (Phase 4).

**Login / Register:** react-hook-form + Zod. Register: full name, email, **phone** (client-validated with `normalizeAlgerianMobile`, hint "0551 23 45 67", `dir="ltr"`), password (≥ 8). Server `fieldErrors` → `setError` per field; `409 EMAIL_ALREADY_IN_USE` / `PHONE_ALREADY_IN_USE` → field errors with a "log in instead" link; login `401 INVALID_CREDENTIALS` → form error without saying which field; `401 ACCOUNT_DISABLED`; `429` → live countdown from `Retry-After`, submit disabled until 0; "session expired" banner via `consumeEndReason()`.

**Account:** name, email, phone, language preference, logout. (Addresses/orders → Phase 4.)

## 11. Design direction

Mobile-first (design at 360 px first). Brand tokens in `@theme`: Atlasna emerald + warm gold from the earlier mockups; **grocery** feels fresh (green), **beauty** softer (rose/plum accent) — a single `categoryTheme(type)` helper. Large touch targets (≥ 44 px), strong contrast (WCAG AA), skeleton loaders (no layout shift), subtle motion honouring `prefers-reduced-motion`.
No stock photos required. Keep copy short; every string is translated. Do **not** use any electronics/fashion sample content anywhere.

## 12. Mocks & tests

**MSW** (`npm run dev:mock` runs with no backend; also used by tests): handlers behave like the real backend **including quirks** — combined filters, the sort whitelist (`400 INVALID_SORT`), `404` for unknown slug, `409` on duplicate email/phone, `401/429` on login with `Retry-After`.
**Fixtures must be captured from the real Phase 1 backend** (the `curl` outputs from its acceptance run), covering: a `UNIT` product, a `WEIGHT` product (step 0.5), a beauty product with `attributes`, an `OUT_OF_STOCK` one, a `LOW_STOCK` one, the category tree. Mocks are reachable only via dynamic import behind `VITE_USE_MOCKS`; verify `grep -r "mock-" dist/` is empty after a normal build.

**Required tests (minimum — every one with real assertions, and it must be able to fail):**
- [ ] Schemas parse the captured real payloads; reject drift (unknown availability, old categories).
- [ ] `quantity.ts`, `money.ts` (regex on the separators), `i18nText.ts`, `phone.ts` — use the verified vectors.
- [ ] Cart reducer: every rule in §9, including snap-to-step, over-max flag, price-changed flag, unavailable.
- [ ] **i18n:** `i18n:check` key parity; switching language sets `lang` and `dir="rtl"`; Arabic plural form for a count of 2, 3, 11 renders (no raw key).
- [ ] `lint:rtl` fails if a forbidden physical class is added (prove it by adding one temporarily).
- [ ] API client: `ApiError.code` populated; 401 on `auth:true` ends the session but 401 on login does not; `429` exposes `retryAfterSeconds` from the header; wrong shape → `ContractError`; fetch rejection → `NetworkError`.
- [ ] Product list: URL ⇄ state round-trip; **search + category together** sends both; `?type=FOO` and `?sort=nope` are not forwarded; empty and error states.
- [ ] Product detail: unknown slug → friendly 404; out-of-stock disables add; per-kg stepper moves in 0.5 steps; unknown `attributes` keys are not rendered.
- [ ] Cart: revalidation shows "price updated", "unavailable" and "only N left"; checkout button stays disabled.
- [ ] Register: valid phone variants accepted; invalid shows the localised message; server `fieldErrors` land on the right fields; duplicate email/phone handled.
- [ ] Login: wrong password message, lockout countdown, success redirects to `from`.
- [ ] Accessibility smoke: every form control has a label; the language switcher is keyboard-operable (add `vitest-axe` if it works with the installed versions).

## 13. Acceptance criteria

- [ ] `rm -rf node_modules dist && npm ci && npm run build && npm run lint && npm test && npm run i18n:check && npm run lint:rtl` all green; test count well above 40.
- [ ] Against the **real backend**: browse both root categories, combine filters (e.g. `type=GROCERY` + `q=couscous` + `inStock`), open a per-kg product and add 1.5 kg (total shows ≈ 120 DA for tomatoes at 80 DA/kg), register with `0551 23 45 67` (stored as `+213551234567`), log in, reload (still signed in), wrong password ×6 (lockout countdown).
- [ ] `npm run dev:mock` works with the backend **stopped**.
- [ ] Switch to Arabic: layout mirrors, digits are Latin, money shows `د.ج.`, no clipped text; keyboard-only run-through; 360 px width.
- [ ] No leftover electronics/fashion/sports/books/wilaya content: `grep -rniE "electronics|fashion|sports|books|wilaya|headphone|sneaker" src` is empty.
- [ ] Security (`01-CONVENTIONS.md` §5–6): no `dangerouslySetInnerHTML`, no secrets in `VITE_*`, token handling as before; README documents the localStorage-token trade-off (XSS can steal it) and that it is revisited in Phase 5 with refresh tokens.

## 14. Out of scope
Checkout, addresses, delivery slots, orders (Phase 4) · admin pages (Phase 6) · product images/upload (Phase 5/6) · PWA/offline (Phase 8) · e2e tests (Phase 10) · legal pages (Phase 16) · AI chat (Phase 14).

## 15. Commit plan
`chore: i18n, tailwind logical css, lint:rtl` → `feat: verified catalog types, quantity, money, phone libs` → `feat: api client with error codes + catalog/auth modules` →
`feat: i18n shell with RTL + language switcher` → `feat: product list with url-driven combined filters` → `feat: product detail with attributes and per-kg quantity` →
`feat: guest cart with revalidation` → `feat: login and register with algerian phone` → `test: msw fixtures captured from backend` → `docs: readme`. Tag `phase-2-done`.

---

## 16. Concepts & interview questions (for the developer — Claude Code may ignore)

**Concepts:** i18n vs l10n · RTL and **logical properties** · CLDR plural categories (Arabic has six) · `Intl.NumberFormat`/`DateTimeFormat` · why money/quantities use integer arithmetic · schema-first types with Zod
(compile-time vs runtime) · server state vs client state · URL as state · optimistic vs revalidated cart · `CartRepository` as a seam (ports & adapters) · why not trust the client for prices.

**Interview questions**
1. Why can `0.1 + 0.2 !== 0.3` break a shopping cart, and how did we avoid it for kilograms?
2. A TypeScript type says `price: number`. Why also validate at runtime? What does Zod give you that `tsc` cannot?
3. What changes in a React app to properly support Arabic beyond translating strings?
4. Why keep list filters in the URL instead of component state?
5. The JWT lives in `localStorage`. What is the attack, and what are the alternatives and their costs?
6. How does the cart page know a price changed since the item was added — and why is the cart total on the client never authoritative?
7. Explain `placeholderData: keepPreviousData` and the UX problem it solves.
