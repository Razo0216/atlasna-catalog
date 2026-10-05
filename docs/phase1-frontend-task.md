# Task: Build Atlasna Phase 1 Frontend — Storefront + Admin on the Phase 1 API

## Context

This is `atlasna_front`, a React 19 + TypeScript project scaffolded with Vite
(`npm create vite`, template `react-ts`). It currently contains only the
Vite starter (`src/App.tsx`, `src/main.tsx`, `App.css`, `index.css`) and no
git commits.

**Where this sits in the master plan:** `docs/ATLASNA_MASTER_PLAN.md` §7
calls this **Phase 2 — React + TypeScript Storefront** ("browse / search /
cart / checkout UI, built against mocked data then real API"). It is the
frontend counterpart of the **Phase 1 backend** (`docs/phase1-backend-task.md`),
so it is called "Phase 1 frontend" here. Everything it talks to must already
exist in `atlasna-catalog` — **except orders**, which arrive in master-plan
Phase 4 and are mocked behind a stable interface in this task.

**Business model:** single-store marketplace (Atlasna, Algeria). No sellers.
Two roles: `ADMIN` (manages the catalog) and `CUSTOMER` (browses, buys).
Currency is Algerian dinar (DZD).

**Goal of this task:**

1. Public storefront: home, product list with search / category / sort /
   pagination, product detail.
2. Auth: register, login, logout, session expiry — JWT from the backend.
3. Client-side cart (persisted in the browser), revalidated against the
   live catalog.
4. Checkout + order confirmation, **mocked** until the Phase 4 orders API.
5. Admin area: list, create, edit, delete (soft) products.
6. Every API response validated at runtime with Zod; every error shape from
   the backend handled; tested with Vitest + Testing Library + MSW.

**Non-goals for this task:** real payments, order history, i18n/Arabic RTL,
product images (the API has no image field yet), SSR, deployment.

---

## Important — read before writing code

### A. The backend contract (source of truth: `atlasna-catalog` code, not the old task doc)

The backend has moved on from `phase1-backend-task.md` (Flyway, rate
limiting, paged DTO serialization, admin bootstrap). These are the facts the
frontend is built on — taken from the current code:

| Endpoint | Auth | Success | Errors the UI must handle |
|---|---|---|---|
| `POST /api/auth/register` `{fullName, email, password}` | public | `201` `AuthResponse` | `400` with `fieldErrors`, `409` email taken |
| `POST /api/auth/login` `{email, password}` | public | `200` `AuthResponse` | `400`, `401` "Invalid email or password", `429` + `Retry-After` |
| `GET /api/products?page&size&sort&category&search` | public | `200` `Page<ProductResponse>` | `400` bad `category` value |
| `GET /api/products/{id}` | public | `200` `ProductResponse` | `404` (missing **or soft-deleted**), `400` non-numeric id |
| `POST /api/products` `ProductRequest` | ADMIN | `201` `ProductResponse` | `400` `fieldErrors`, `401`, `403` |
| `PUT /api/products/{id}` `ProductRequest` (full replace) | ADMIN | `200` `ProductResponse` | `400`, `401`, `403`, `404` |
| `DELETE /api/products/{id}` (soft delete) | ADMIN | `204` no body | `401`, `403`, `404` |

**JSON shapes:**

```jsonc
// AuthResponse
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresInSeconds": 3600,
  "user": { "id": 1, "fullName": "Test", "email": "test@example.com", "role": "CUSTOMER" } }

// ProductResponse — note: no `active`, no `updatedAt`, no image
{ "id": 1, "name": "Ceramic Tea Set", "description": "6-piece traditional tea set.",
  "price": 3100.00, "category": "HOME_AND_KITCHEN", "stockQuantity": 30,
  "createdAt": "2026-10-01T10:15:30.123456Z" }

// Page<T> — application.yml sets spring.data.web.pageable.serialization-mode: via-dto
{ "content": [ /* T */ ], "page": { "size": 20, "number": 0, "totalElements": 3, "totalPages": 1 } }

// ApiError — every error, from GlobalExceptionHandler
{ "timestamp": "...", "status": 400, "error": "Validation Failed",
  "message": "One or more fields are invalid",
  "fieldErrors": { "email": "Email must be valid" } }   // null for non-validation errors
```

**Rules the backend enforces that the UI must mirror (and why):**

- `Category` is a closed enum: `ELECTRONICS, FASHION, HOME_AND_KITCHEN,
  BEAUTY, GROCERIES, SPORTS, BOOKS`. There is no categories endpoint — the
  frontend hard-codes the list. Adding a category needs a backend migration
  *and* a frontend change.
- `search` and `category` are **not combined**: if `search` is non-blank the
  backend ignores `category` (`ProductService.list`). The UI must not pretend
  otherwise — choosing one clears the other.
- `page` is **0-based**. Default `size` is 20. `sort` is `field,dir`
  (e.g. `price,asc`). The backend does **not** whitelist sort fields — only
  ever send values from the frontend's fixed list.
- Register validation: `fullName` not blank; `email` valid; `password` ≥ 8
  chars. Product validation: `name` 1–200 chars; `description` ≤ 2000;
  `price` > 0 with at most 2 decimals and ≤ 10 integer digits
  (`NUMERIC(12,2)`); `stockQuantity` integer ≥ 0; `category` required.
- Emails are normalized server-side (trim + lowercase). Send them trimmed.
- Login is rate-limited: 5 failures per email / 15 min, 20 attempts per IP /
  1 min. `429` carries `Retry-After` (seconds) and a message
  "Too many login attempts. Try again in N seconds."
- `401` on `/api/auth/login` means **wrong credentials**. `401` on any other
  authenticated call means **the session is gone** (expired/invalid token).
  These must be handled differently.
- Public `GET`s work even with an expired token (the JWT filter ignores bad
  tokens) — so only attach the token to requests that need it.
- Soft-deleted products return `404` on detail and vanish from lists. There
  is no admin endpoint to list or restore deleted products.
- JWT lifetime is 60 min, no refresh token. When it expires the user must
  log in again.
- CORS allows only `http://localhost:5173` and `http://localhost:3000`,
  headers `Authorization` and `Content-Type`. **The Vite dev server must run
  on port 5173** (`strictPort`), or every request fails with a CORS error.

### B. Toolchain notes (verify, don't assume)

The scaffold is on **Vite 8, TypeScript 6, React 19.2**, and the latest
majors of several libraries are newer than most tutorials online:

1. `tsconfig.app.json` has **`erasableSyntaxOnly: true`** — TypeScript
   `enum`s, `namespace`s and constructor parameter properties
   (`constructor(private x: string)`) **do not compile**. Use `as const`
   arrays + `z.enum(...)` / union types, and declare class fields explicitly.
2. **`verbatimModuleSyntax: true`** — type-only imports must use
   `import type { X }` (or `import { type X }`).
3. **React Router**: import everything from `react-router`, except
   `RouterProvider`, which comes from `react-router/dom`. Do **not** install
   `react-router-dom` (removed as of v8).
4. **Tailwind v4**: no `tailwind.config.js`, no PostCSS config. Use the
   `@tailwindcss/vite` plugin and `@import "tailwindcss";` in CSS.
5. **Zod v4**: `z.email()`, `z.enum([...])`, `z.infer<>`, `.safeParse()`;
   errors live on `error.issues`.
6. **MSW**: `import { http, HttpResponse } from 'msw'`, `setupWorker` from
   `msw/browser`, `setupServer` from `msw/node`.

If anything here fails to compile, check the installed version's docs
(`npm ls <pkg>`, then the package's changelog / migration guide) before
"fixing" it with a pattern from an older major.

**After every step below, run `npm run build` and `npm run lint` and fix
errors before moving on.** Once tests exist (step 4), also run `npm test`.
Do not batch everything and compile once at the end.

---

## 0. Backend prerequisite — one line in `atlasna-catalog`

Browsers only let JavaScript read the CORS-safelisted response headers.
`Retry-After` is not one of them, so the 429 countdown can't read it
cross-origin. In `SecurityConfig.corsConfigurationSource()` add:

```java
config.setExposedHeaders(List.of("Retry-After"));
```

Restart the backend. (The frontend also falls back to parsing the seconds
from the 429 message, so this isn't blocking — but do it.)

---

## 1. Dependencies

```bash
cd atlasna_front
npm install react-router @tanstack/react-query zod react-hook-form @hookform/resolvers
npm install -D tailwindcss @tailwindcss/vite \
  vitest jsdom @testing-library/react @testing-library/dom \
  @testing-library/user-event @testing-library/jest-dom \
  msw @tanstack/react-query-devtools
npx msw init public/ --save
```

`react-hook-form` + `@hookform/resolvers` aren't in the master-plan stack
table; they're added because five forms (login, register, checkout, product
create/edit) all need field-level errors from **both** Zod and the server's
`fieldErrors`, which is what `setError` gives us. Add them to the master
plan's §3 table.

Add scripts to `package.json`:

```json
"scripts": {
  "dev": "vite",
  "dev:mock": "vite --mode mock",
  "build": "tsc -b && vite build",
  "lint": "eslint .",
  "preview": "vite preview",
  "test": "vitest run",
  "test:watch": "vitest"
}
```

Delete the starter content: `src/App.css`, `src/assets/*`, the body of
`src/App.tsx`. Add `dist/` to `.gitignore` if it isn't there (a built
`dist/` folder currently exists — don't commit it).

---

## 2. Config

### `vite.config.ts`

```ts
/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // The backend's CORS config only allows http://localhost:5173.
  // strictPort makes Vite fail loudly instead of silently moving to 5174.
  server: { port: 5173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
```

### Environment files

`.env.development`
```
VITE_API_BASE_URL=http://localhost:8080
VITE_USE_MOCKS=false
```

`.env.mock` (used by `npm run dev:mock` — no backend needed)
```
VITE_API_BASE_URL=http://localhost:8080
VITE_USE_MOCKS=true
```

`.env.test`
```
VITE_API_BASE_URL=http://localhost:8080
```

These hold no secrets — never put a secret in a `VITE_*` variable; they are
inlined into the public JS bundle.

### `src/env.d.ts`

```ts
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_USE_MOCKS?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}
```

### `src/lib/env.ts`

```ts
export const env = {
  apiBaseUrl: (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, ''),
  useMocks: import.meta.env.VITE_USE_MOCKS === 'true',
}
```

### `src/index.css`

```css
@import "tailwindcss";

@theme {
  --color-brand-50: #fff7ed;
  --color-brand-500: #c2410c;
  --color-brand-600: #9a3412;
  --font-sans: "Inter", system-ui, sans-serif;
}

html { color-scheme: light; }
body { @apply bg-stone-50 text-stone-900 antialiased; }
```

Commit: `chore: add router, query, zod, tailwind, vitest, msw`.

---

## 3. Folder structure (master plan §8)

```
src/
  types/        ← Zod schemas + inferred types mirroring backend DTOs
  api/          ← fetch client + one module per backend resource
  hooks/        ← TanStack Query hooks, useAuth, useCart
  lib/          ← env, money, session store, cart store, query client
  components/   ← reusable UI (ProductCard, Pagination, FormField, guards, layout)
  pages/        ← one component per route
  mocks/        ← MSW handlers + fixtures (dev mock mode and tests)
  test/         ← test setup and render helpers
  router.tsx
  main.tsx
```

Rule: **components never call `fetch`**. Pages use hooks, hooks use `api/`,
`api/` uses `api/client.ts`. Swapping a mock for a real endpoint later then
touches one file.

---

## 4. Types — `src/types/` (Zod is the single source)

Types are *inferred* from the schemas, so the compile-time type and the
runtime check can't drift apart.

### `src/types/common.ts`

```ts
import { z } from 'zod'

/** Spring's Page serialized with `serialization-mode: via-dto`. */
export const pageSchema = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: z.object({
      size: z.number().int(),
      number: z.number().int(),
      totalElements: z.number().int(),
      totalPages: z.number().int(),
    }),
  })

export type Page<T> = {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

/** Mirrors com.atlasna.catalog.common.exception.ApiError */
export const apiErrorBodySchema = z.object({
  timestamp: z.string(),
  status: z.number().int(),
  error: z.string(),
  message: z.string(),
  fieldErrors: z.record(z.string(), z.string()).nullish(),
})
```

### `src/types/product.ts`

```ts
import { z } from 'zod'

/** Mirrors com.atlasna.catalog.product.Category — closed list, keep in sync. */
export const CATEGORIES = [
  'ELECTRONICS', 'FASHION', 'HOME_AND_KITCHEN', 'BEAUTY', 'GROCERIES', 'SPORTS', 'BOOKS',
] as const
export const categorySchema = z.enum(CATEGORIES)
export type Category = z.infer<typeof categorySchema>

export const CATEGORY_LABELS: Record<Category, string> = {
  ELECTRONICS: 'Electronics',
  FASHION: 'Fashion',
  HOME_AND_KITCHEN: 'Home & Kitchen',
  BEAUTY: 'Beauty',
  GROCERIES: 'Groceries',
  SPORTS: 'Sports',
  BOOKS: 'Books',
}

/** Mirrors ProductResponse. */
export const productSchema = z.object({
  id: z.number().int(),
  name: z.string(),
  description: z.string().nullable(),
  price: z.number().positive(),
  category: categorySchema,
  stockQuantity: z.number().int().nonnegative(),
  createdAt: z.string().nullable(),
})
export type Product = z.infer<typeof productSchema>

/** Mirrors ProductRequest (what we send). */
export type ProductRequest = {
  name: string
  description: string | null
  price: number
  category: Category
  stockQuantity: number
}

/**
 * Admin form schema. Mirrors ProductRequest's Bean Validation, with the same
 * messages. Price stays a string in the form so "12.345" can be rejected
 * exactly instead of being silently rounded by float math.
 */
export const productFormSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(200, 'Name must be at most 200 characters'),
  description: z.string().max(2000, 'Description must be at most 2000 characters'),
  price: z
    .string()
    .trim()
    .min(1, 'Price is required')
    .regex(/^\d{1,10}(\.\d{1,2})?$/, 'Use a number with at most 2 decimals')
    .refine((v) => Number(v) > 0, 'Price must be greater than 0'),
  category: categorySchema,
  stockQuantity: z
    .string()
    .trim()
    .min(1, 'Stock quantity is required')
    .regex(/^\d+$/, 'Stock quantity must be a whole number')
    .refine((v) => Number(v) <= 2_147_483_647, 'Stock quantity is too large'),
})
export type ProductFormValues = z.infer<typeof productFormSchema>

export function toProductRequest(v: ProductFormValues): ProductRequest {
  const description = v.description.trim()
  return {
    name: v.name.trim(),
    description: description === '' ? null : description,
    price: Number(v.price),
    category: v.category,
    stockQuantity: Number(v.stockQuantity),
  }
}
```

### `src/types/user.ts`

```ts
import { z } from 'zod'

export const ROLES = ['ADMIN', 'CUSTOMER'] as const
export const roleSchema = z.enum(ROLES)
export type Role = z.infer<typeof roleSchema>

/** Mirrors UserResponse. */
export const userSchema = z.object({
  id: z.number().int(),
  fullName: z.string(),
  email: z.string(),
  role: roleSchema,
})
export type User = z.infer<typeof userSchema>

/** Mirrors AuthResponse. */
export const authResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.literal('Bearer'),
  expiresInSeconds: z.number().int().positive(),
  user: userSchema,
})
export type AuthResponse = z.infer<typeof authResponseSchema>

/** Mirrors RegisterRequest validation + messages. */
export const registerFormSchema = z.object({
  fullName: z.string().trim().min(1, 'Full name is required'),
  email: z.string().trim().min(1, 'Email is required').pipe(z.email('Email must be valid')),
  password: z.string().min(8, 'Password must be at least 8 characters'),
})
export type RegisterRequest = z.infer<typeof registerFormSchema>

/** Mirrors LoginRequest. */
export const loginFormSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').pipe(z.email('Email must be valid')),
  password: z.string().min(1, 'Password is required'),
})
export type LoginRequest = z.infer<typeof loginFormSchema>
```

### `src/types/order.ts` — DRAFT contract (no backend yet)

```ts
import { z } from 'zod'

/**
 * DRAFT — no orders API exists until master-plan Phase 4. Shapes follow the
 * master plan §5 data model. When Phase 4 lands, replace these with the real
 * DTOs and delete anything that doesn't match.
 */
export const ORDER_STATUSES = ['PLACED', 'PAID', 'PACKED', 'OUT_FOR_DELIVERY', 'DELIVERED'] as const
export const orderStatusSchema = z.enum(ORDER_STATUSES)

export const PAYMENT_METHODS = ['CASH_ON_DELIVERY', 'CIB', 'EDAHABIA'] as const
export const paymentMethodSchema = z.enum(PAYMENT_METHODS)
export type PaymentMethod = z.infer<typeof paymentMethodSchema>

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH_ON_DELIVERY: 'Cash on delivery',
  CIB: 'CIB card',
  EDAHABIA: 'Edahabia card',
}
/** Card payments need the Phase 4 payment gateway; only COD is selectable now. */
export const ENABLED_PAYMENT_METHODS: readonly PaymentMethod[] = ['CASH_ON_DELIVERY']

export const shippingAddressSchema = z.object({
  fullName: z.string().trim().min(1, 'Full name is required').max(200),
  // Algerian mobile: 05/06/07 + 8 digits. Spaces are stripped before checking.
  phone: z
    .string()
    .transform((v) => v.replace(/\s+/g, ''))
    .pipe(z.string().regex(/^0[567]\d{8}$/, 'Enter a mobile number like 0551 23 45 67')),
  wilaya: z.string().min(1, 'Choose a wilaya'),
  city: z.string().trim().min(1, 'City is required').max(100),
  addressLine: z.string().trim().min(5, 'Address is required').max(300),
})
export type ShippingAddress = z.infer<typeof shippingAddressSchema>

export const checkoutFormSchema = shippingAddressSchema.extend({
  paymentMethod: paymentMethodSchema,
})
export type CheckoutFormValues = z.input<typeof checkoutFormSchema>

export type PlaceOrderRequest = {
  items: { productId: number; quantity: number }[]
  paymentMethod: PaymentMethod
  shippingAddress: ShippingAddress
}

export const orderSchema = z.object({
  id: z.number().int(),
  status: orderStatusSchema,
  total: z.number(),
  paymentMethod: paymentMethodSchema,
  shippingAddress: shippingAddressSchema,
  items: z.array(
    z.object({
      productId: z.number().int(),
      productName: z.string(),
      quantity: z.number().int().positive(),
      unitPriceAtPurchase: z.number(),
    }),
  ),
  createdAt: z.string(),
})
export type Order = z.infer<typeof orderSchema>
```

### `src/lib/wilayas.ts`

Export `WILAYAS: readonly { code: string; name: string }[]` — the official
list of Algerian wilayas (58 since 2019: `01 Adrar` … `58 El Meniaa`).
**Verify the current list against an official source** before shipping; the
administrative map has been reformed more than once.

Commit: `feat: add Zod schemas mirroring backend DTOs`.

---

## 5. Money — `src/lib/money.ts`

Prices arrive as JSON numbers (Java `BigDecimal`). Never add floats for
totals: `0.1 + 0.2 !== 0.3`. Convert to integer centimes, sum, convert back.

```ts
const dzd = new Intl.NumberFormat('fr-DZ', { style: 'currency', currency: 'DZD' })

export const formatDzd = (amount: number): string => dzd.format(amount)
export const toCentimes = (amount: number): number => Math.round(amount * 100)
export const fromCentimes = (centimes: number): number => centimes / 100
```

---

## 6. Session — `src/lib/session.ts`

A tiny external store (read with `useSyncExternalStore`) instead of React
context, because the API client — which lives outside React — must read the
token and end the session on a `401`.

```ts
import { z } from 'zod'
import { type AuthResponse, userSchema } from '../types/user'

const STORAGE_KEY = 'atlasna.session'
/** Treat the token as expired a little early so we never send one that dies mid-request. */
const EXPIRY_SKEW_MS = 30_000

const sessionSchema = z.object({
  accessToken: z.string().min(1),
  expiresAt: z.number(),
  user: userSchema,
})
export type Session = z.infer<typeof sessionSchema>
export type SessionEndReason = 'signed-out' | 'expired'

function load(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = sessionSchema.safeParse(JSON.parse(raw))
    if (!parsed.success || parsed.data.expiresAt <= Date.now()) {
      localStorage.removeItem(STORAGE_KEY)
      return null
    }
    return parsed.data
  } catch {
    return null // storage blocked or corrupt JSON: behave as signed out
  }
}

function persist(session: Session | null) {
  try {
    if (session) localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // storage unavailable (private mode): the session still lives in memory
  }
}

let current: Session | null = load()
let lastEndReason: SessionEndReason | null = null
const listeners = new Set<() => void>()
const notify = () => listeners.forEach((l) => l())

export function sessionFromAuthResponse(r: AuthResponse, now = Date.now()): Session {
  return {
    accessToken: r.accessToken,
    expiresAt: now + r.expiresInSeconds * 1000 - EXPIRY_SKEW_MS,
    user: r.user,
  }
}

export const sessionStore = {
  get: (): Session | null => current,
  /** The token, or null if missing or past its expiry. */
  getValidToken: (): string | null =>
    current && current.expiresAt > Date.now() ? current.accessToken : null,
  start(session: Session) {
    current = session
    lastEndReason = null
    persist(session)
    notify()
  },
  end(reason: SessionEndReason) {
    if (!current) return
    current = null
    lastEndReason = reason
    persist(null)
    notify()
  },
  /** Read-and-clear, so the "session expired" banner shows once. */
  consumeEndReason(): SessionEndReason | null {
    const r = lastEndReason
    lastEndReason = null
    return r
  },
  subscribe(listener: () => void) {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
}

// Keep tabs in sync: logging out in one tab logs out all of them.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === STORAGE_KEY) {
      current = load()
      notify()
    }
  })
}
```

**Security note (write it in the README too):** a token in `localStorage`
can be stolen by any XSS on the page. Accepted for now because the backend
is bearer-only (CSRF is disabled on that basis — see `SecurityConfig`).
Mitigations required in this task: never use `dangerouslySetInnerHTML`,
never render product text as HTML, no third-party scripts. Moving to an
httpOnly cookie is a Phase 12 item and **requires re-enabling CSRF on the
backend**.

### `src/hooks/useAuth.ts`

```ts
import { useSyncExternalStore } from 'react'
import { sessionFromAuthResponse, sessionStore } from '../lib/session'
import type { AuthResponse } from '../types/user'

export function useAuth() {
  const session = useSyncExternalStore(sessionStore.subscribe, sessionStore.get)
  return {
    session,
    user: session?.user ?? null,
    isAuthenticated: session !== null,
    isAdmin: session?.user.role === 'ADMIN',
    signIn: (r: AuthResponse) => sessionStore.start(sessionFromAuthResponse(r)),
    signOut: () => sessionStore.end('signed-out'),
  }
}
```

### `src/components/SessionExpiryWatcher.tsx`

Renders nothing. `useEffect` on `session?.expiresAt`: set a `setTimeout`
for `expiresAt - Date.now()` that calls `sessionStore.end('expired')`;
clear it on cleanup. Mounted once in the app layout.

---

## 7. API client — `src/api/client.ts`

One place that knows about `fetch`, headers, the token, the error shape and
runtime validation.

```ts
import type { z } from 'zod'
import { env } from '../lib/env'
import { sessionStore } from '../lib/session'
import { apiErrorBodySchema } from '../types/common'

/** A non-2xx response from the backend, parsed from its ApiError body. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly fieldErrors: Record<string, string>
  readonly retryAfterSeconds: number | null

  constructor(
    status: number,
    title: string,
    message: string,
    fieldErrors: Record<string, string> = {},
    retryAfterSeconds: number | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** The request never got a response: backend down, offline, or blocked by CORS. */
export class NetworkError extends Error {
  constructor() {
    super("Can't reach the server. Check your connection and try again.")
    this.name = 'NetworkError'
  }
}

/** The backend answered 2xx with a body that doesn't match our schema. A bug, not user error. */
export class ContractError extends Error {
  readonly issues: unknown
  constructor(path: string, issues: unknown) {
    super(`Unexpected response shape from ${path}`)
    this.name = 'ContractError'
    this.issues = issues
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'
type BaseOptions = {
  method?: Method
  body?: unknown
  /** Attach the bearer token. 401 on such a request ends the session. */
  auth?: boolean
  signal?: AbortSignal
}

async function send(path: string, { method = 'GET', body, auth = false, signal }: BaseOptions) {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth) {
    const token = sessionStore.getValidToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  let res: Response
  try {
    res = await fetch(`${env.apiBaseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (signal?.aborted) throw e // let TanStack Query see its own cancellation
    throw new NetworkError()
  }

  if (!res.ok) throw await toApiError(res, auth)
  return res
}

/** Request that returns a body, validated against `schema`. */
export async function apiRequest<T>(
  path: string,
  options: BaseOptions & { schema: z.ZodType<T> },
): Promise<T> {
  const res = await send(path, options)
  const json: unknown = await res.json()
  const parsed = options.schema.safeParse(json)
  if (!parsed.success) {
    console.error('API contract mismatch', path, parsed.error.issues)
    throw new ContractError(path, parsed.error.issues)
  }
  return parsed.data
}

/** Request with no response body (e.g. DELETE → 204). */
export async function apiSend(path: string, options: BaseOptions): Promise<void> {
  await send(path, options)
}

async function toApiError(res: Response, authenticated: boolean): Promise<ApiError> {
  // 401 on an authenticated call = token expired/invalid. 401 on /auth/login
  // is just wrong credentials and must NOT end anything.
  if (res.status === 401 && authenticated) sessionStore.end('expired')

  let body: unknown = null
  try {
    body = await res.json()
  } catch {
    // non-JSON error body (proxy page, empty 502): fall through to defaults
  }
  const parsed = apiErrorBodySchema.safeParse(body)
  const title = parsed.success ? parsed.data.error : res.statusText || 'Error'
  const message = parsed.success ? parsed.data.message : 'Something went wrong. Please try again.'
  const fieldErrors = parsed.success ? (parsed.data.fieldErrors ?? {}) : {}
  const retryAfter = res.status === 429 ? parseRetryAfter(res.headers.get('Retry-After'), message) : null
  return new ApiError(res.status, title, message, fieldErrors, retryAfter)
}

/** Header first (needs CORS exposedHeaders), then the seconds in the backend's message. */
function parseRetryAfter(header: string | null, message: string): number | null {
  const fromHeader = header === null ? Number.NaN : Number.parseInt(header, 10)
  if (Number.isFinite(fromHeader)) return fromHeader
  const match = /in (\d+) seconds/.exec(message)
  return match ? Number(match[1]) : null
}

/** User-facing text for any error thrown by the API layer. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return "You don't have permission to do that."
    if (error.status >= 500) return 'Something went wrong on our side. Please try again.'
    return error.message
  }
  if (error instanceof NetworkError) return error.message
  return 'Something went wrong. Please try again.'
}
```

### `src/api/auth.ts`

```ts
import { authResponseSchema, type LoginRequest, type RegisterRequest } from '../types/user'
import { apiRequest } from './client'

export const authApi = {
  register: (req: RegisterRequest) =>
    apiRequest('/api/auth/register', { method: 'POST', body: req, schema: authResponseSchema }),
  login: (req: LoginRequest) =>
    apiRequest('/api/auth/login', { method: 'POST', body: req, schema: authResponseSchema }),
}
```

### `src/api/products.ts`

```ts
import { pageSchema } from '../types/common'
import { type Category, type ProductRequest, productSchema } from '../types/product'
import { apiRequest, apiSend } from './client'

/** The only sort values ever sent: the backend doesn't whitelist sort fields. */
export const PRODUCT_SORTS = [
  { value: 'createdAt,desc', label: 'Newest' },
  { value: 'price,asc', label: 'Price: low to high' },
  { value: 'price,desc', label: 'Price: high to low' },
  { value: 'name,asc', label: 'Name: A–Z' },
] as const
export type ProductSort = (typeof PRODUCT_SORTS)[number]['value']
export const DEFAULT_SORT: ProductSort = 'createdAt,desc'

export type ProductListParams = {
  page: number // 0-based, like the backend
  size: number
  sort: ProductSort
  category?: Category
  search?: string
}

const productPageSchema = pageSchema(productSchema)

export const productsApi = {
  list(params: ProductListParams, signal?: AbortSignal) {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size), sort: params.sort })
    const search = params.search?.trim()
    // The backend ignores category when search is present; don't send both.
    if (search) q.set('search', search)
    else if (params.category) q.set('category', params.category)
    return apiRequest(`/api/products?${q}`, { schema: productPageSchema, signal })
  },
  get: (id: number, signal?: AbortSignal) =>
    apiRequest(`/api/products/${id}`, { schema: productSchema, signal }),
  create: (req: ProductRequest) =>
    apiRequest('/api/products', { method: 'POST', body: req, schema: productSchema, auth: true }),
  update: (id: number, req: ProductRequest) =>
    apiRequest(`/api/products/${id}`, { method: 'PUT', body: req, schema: productSchema, auth: true }),
  remove: (id: number) => apiSend(`/api/products/${id}`, { method: 'DELETE', auth: true }),
}
```

### `src/api/orders.ts` — MOCK until Phase 4

Implements the **interface** the real API will have, so Phase 4 only
replaces function bodies.

- `place(req: PlaceOrderRequest): Promise<Order>`
  - Throws `ApiError(401, ...)` if `sessionStore.getValidToken()` is null
    (the real endpoint is authenticated).
  - For each item, calls **the real** `productsApi.get(productId)` — this
    simulates server-side pricing: the client never sends prices.
    - `404` → throw `ApiError(409, 'Product Unavailable', '<name> is no longer available')`.
    - `quantity > stockQuantity` → throw `ApiError(409, 'Insufficient Stock', 'Only N left of <name>')`.
  - Totals in centimes. Status `PLACED`. `id` from an incrementing counter.
  - Waits ~600 ms (so loading states are visible), stores the order in
    `sessionStorage` under `atlasna.mockOrders`, returns it.
- `get(id: number): Promise<Order>` — reads it back; missing → `ApiError(404, ...)`.

Put a header comment: `// MOCK — replace with apiRequest('/api/orders', ...) in Phase 4.`
Do **not** decrement stock (that's the backend's job in Phase 4).

Commit: `feat: add typed API client with runtime validation`.

---

## 8. Server state — TanStack Query

### `src/lib/queryClient.ts`

```ts
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '../api/client'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Retry network blips and 5xx, never 4xx: a 404 won't fix itself.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status < 500) && failureCount < 2,
      },
      mutations: { retry: false },
    },
  })
}
```

### `src/hooks/queryKeys.ts`

```ts
import type { ProductListParams } from '../api/products'

export const productKeys = {
  all: ['products'] as const,
  lists: () => [...productKeys.all, 'list'] as const,
  list: (params: ProductListParams) => [...productKeys.lists(), params] as const,
  detail: (id: number) => [...productKeys.all, 'detail', id] as const,
}
export const orderKeys = {
  detail: (id: number) => ['orders', 'detail', id] as const,
}
```

### `src/hooks/useProducts.ts`

- `useProducts(params)` → `useQuery({ queryKey: productKeys.list(params), queryFn: ({ signal }) => productsApi.list(params, signal), placeholderData: keepPreviousData })`
  (keeps the old page on screen while the next page loads — no flicker).
- `useProduct(id)` → `useQuery({ queryKey: productKeys.detail(id), queryFn: ..., enabled: Number.isInteger(id) && id > 0 })`
- `useCreateProduct()` → on success: `invalidateQueries({ queryKey: productKeys.lists() })`, `setQueryData(productKeys.detail(p.id), p)`.
- `useUpdateProduct()` → same.
- `useDeleteProduct()` → on success: invalidate lists, `removeQueries({ queryKey: productKeys.detail(id) })`.

### `src/hooks/useOrders.ts`

`usePlaceOrder()` (mutation) and `useOrder(id)` (query) over `ordersApi`.

---

## 9. Cart — `src/lib/cart.ts` + `src/hooks/useCart.ts`

Client-side only (no cart API exists). Persisted in `localStorage` under
`atlasna.cart`, validated with Zod on load (corrupt → empty cart). Same
external-store pattern as the session, so it syncs across tabs.

```ts
export type CartItem = {
  productId: number
  name: string
  category: Category
  unitPrice: number      // last known price — revalidated on the cart page
  maxQuantity: number    // last known stock
  quantity: number
  unavailable?: boolean  // product returned 404 (soft-deleted)
}

export type CartAction =
  | { type: 'add'; product: Product; quantity: number }
  | { type: 'setQuantity'; productId: number; quantity: number }
  | { type: 'remove'; productId: number }
  | { type: 'reconcile'; productId: number; product: Product | null } // null = 404
  | { type: 'clear' }

export function cartReducer(items: CartItem[], action: CartAction): CartItem[]
export function cartSubtotalCentimes(items: CartItem[]): number
export function cartCount(items: CartItem[]): number
```

Reducer rules (unit-test every one):

- `add`: out-of-stock product → no-op. Existing line → add quantities.
  Always clamp to `1 … min(stockQuantity, 99)`.
- `setQuantity`: clamp to `1 … maxQuantity`; `0` or less removes the line.
- `reconcile` with a product: update `name`, `unitPrice`, `maxQuantity`,
  clear `unavailable`; **don't** silently lower `quantity` — the UI shows
  the "only N left" warning and blocks checkout until the user fixes it.
- `reconcile` with `null`: set `unavailable: true`.
- Subtotal: `Σ toCentimes(unitPrice) × quantity`, excluding unavailable lines.

`useCart()` returns `{ items, count, subtotalCentimes, hasProblems, add,
setQuantity, remove, clear }`, where `hasProblems` is true if any line is
unavailable or `quantity > maxQuantity`.

The cart is **not** cleared on logout (it's per-device, like most shops).
It **is** cleared after a successful order.

---

## 10. Routing — `src/router.tsx`

```ts
import { createBrowserRouter } from 'react-router'
// ...page imports

export const router = createBrowserRouter([
  {
    Component: AppLayout,
    ErrorBoundary: RouteErrorPage,
    children: [
      { index: true, Component: HomePage },
      { path: 'products', Component: ProductListPage },
      { path: 'products/:id', Component: ProductDetailPage },
      { path: 'cart', Component: CartPage },
      { path: 'login', Component: LoginPage },
      { path: 'register', Component: RegisterPage },
      {
        Component: RequireAuth,
        children: [
          { path: 'checkout', Component: CheckoutPage },
          { path: 'orders/:id/confirmation', Component: OrderConfirmationPage },
        ],
      },
      {
        path: 'admin',
        Component: RequireAdmin,
        children: [
          { index: true, Component: AdminRedirect },        // → /admin/products
          { path: 'products', Component: AdminProductsPage },
          { path: 'products/new', Component: AdminProductFormPage },
          { path: 'products/:id/edit', Component: AdminProductFormPage },
        ],
      },
      { path: '*', Component: NotFoundPage },
    ],
  },
])
```

Lazy-load the admin pages (`lazy: () => import(...)` on the route) so
customers never download admin code.

### `src/main.tsx`

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router/dom'
import './index.css'
import { env } from './lib/env'
import { createQueryClient } from './lib/queryClient'
import { router } from './router'

async function enableMocking() {
  if (!env.useMocks) return
  const { worker } = await import('./mocks/browser')
  await worker.start({ onUnhandledRequest: 'bypass' })
}

const queryClient = createQueryClient()

enableMocking().then(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <ReactQueryDevtools initialIsOpen={false} />
      </QueryClientProvider>
    </StrictMode>,
  )
})
```

Delete `src/App.tsx` (the router replaces it).

### Guards — `src/components/guards.tsx`

- `RequireAuth`: no session → `<Navigate to="/login" replace state={{ from: location }} />`, else `<Outlet />`.
- `RequireAdmin`: no session → same as above; session but `role !== 'ADMIN'`
  → render a 403 page ("This area is for store admins"), **not** a redirect
  loop. Else `<Outlet />`.

These guards are **UX only**. The backend's `@PreAuthorize` is the real
security; the UI hiding a button proves nothing (see checklist §4).

---

## 11. Layout and shared components — `src/components/`

| Component | Requirements |
|---|---|
| `AppLayout` | Header (logo → `/`, "Shop" → `/products`, search box, cart icon with count badge, account menu), `<main>` with `<Outlet />`, footer. Mounts `SessionExpiryWatcher`. Header search submits to `/products?search=...`. |
| Account menu | Signed out: "Log in", "Register". Signed in: user's `fullName`, "Admin" link if `isAdmin`, "Log out" (→ `signOut()`, navigate `/`). |
| `ProductCard` | Category placeholder (colored block + category label — no images yet), name, `formatDzd(price)`, "Out of stock" badge when `stockQuantity === 0`, links to detail. |
| `ProductGrid` | Responsive grid (1 / 2 / 3 / 4 columns). Takes `products` and `isLoading`; shows skeleton cards while loading. |
| `Pagination` | Prev / next + "Page X of Y" (display 1-based, backend is 0-based). Hidden when `totalPages <= 1`. |
| `QuantityStepper` | − / number / +, bounded by `min`/`max`, keyboard accessible. |
| `FormField` | Label + input + error text, wired with `aria-invalid` and `aria-describedby`. |
| `ErrorState` | Message from `errorMessage(error)` + "Try again" button (calls `refetch`). |
| `EmptyState` | Icon, title, optional action. |
| `Toast` | Minimal toast region (`role="status"`) for "Added to cart", "Product saved". No library needed. |
| `RouteErrorPage` | Router `ErrorBoundary`: friendly message, link home; logs the error. |

All interactive elements reachable by keyboard, visible focus rings, images
/ icons with `alt` or `aria-hidden`. Mobile-first: everything works at
360 px wide.

---

## 12. Pages — `src/pages/`

### 12.1 `HomePage` — `/`
- Hero: store name, one line of copy, "Shop now" → `/products`.
- Category tiles (all 7) → `/products?category=X`.
- "New arrivals": `useProducts({ page: 0, size: 8, sort: 'createdAt,desc' })`.
- Loading → skeletons; error → `ErrorState`; empty → "No products yet".

### 12.2 `ProductListPage` — `/products`
**All state lives in the URL** (`useSearchParams`) so results are
shareable and Back works: `?search=&category=&sort=&page=` (page 1-based in
the URL, converted to 0-based for the API).

```tsx
const [params, setParams] = useSearchParams()
const search = params.get('search') ?? ''
const categoryParam = params.get('category')
const category = categorySchema.safeParse(categoryParam).success ? (categoryParam as Category) : undefined
const sortParam = params.get('sort')
const sort = PRODUCT_SORTS.some((s) => s.value === sortParam) ? (sortParam as ProductSort) : DEFAULT_SORT
const page = Math.max(0, (Number.parseInt(params.get('page') ?? '1', 10) || 1) - 1)

const query = useProducts({ page, size: 12, sort, category, search })
```

- Invalid URL values (unknown category, bad sort, `page=abc`) fall back to
  defaults — they are **never** forwarded to the API.
- Search input: debounced 300 ms; setting it **removes `category`** and
  resets page. Choosing a category **removes `search`** and resets page.
  When a search is active, show "Searching all categories" next to the
  disabled category filter so the behavior is explained, not hidden.
- Changing sort resets to page 1.
- If `page` is beyond `totalPages` (e.g. after a delete), redirect to the
  last page.
- Result count: "N products" from `page.totalElements`.
- Empty result: "No products match '<search>'" + "Clear filters".
- While fetching the next page, keep old results visible and dim them
  (`query.isPlaceholderData`).

### 12.3 `ProductDetailPage` — `/products/:id`
- `id` not a positive integer → render `NotFoundPage` (don't call the API).
- `404` → "This product isn't available" + link back to the shop (it may
  have been removed by an admin).
- Shows name, category, price, description (plain text, `whitespace-pre-line`
  — never as HTML), stock status: "In stock", "Only N left" (≤ 5), "Out of stock".
- `QuantityStepper` (max = min(stock, 99)) + "Add to cart" → cart `add`,
  toast "Added to cart" with "View cart" link. Disabled when out of stock.
- Admins also see "Edit product" → `/admin/products/:id/edit`.

### 12.4 `CartPage` — `/cart`
- Empty → `EmptyState` with "Continue shopping".
- Lines: name (link), unit price, `QuantityStepper`, line total, remove.
- **Revalidation:** `useQueries` fetches `productsApi.get` for every line on
  mount; results dispatch `reconcile`. Show per-line warnings:
  - unavailable → "No longer available — remove it to continue"
  - price changed → "Price updated from X to Y"
  - `quantity > maxQuantity` → "Only N left — reduce the quantity"
- Summary: item count, subtotal (`formatDzd(fromCentimes(subtotal))`), note
  "Delivery fee is confirmed when you order" (no delivery pricing exists yet).
- "Checkout" disabled while revalidating or when `hasProblems`. Signed out
  → button reads "Log in to checkout" and goes to `/login` with
  `state.from = /checkout`.

### 12.5 `LoginPage` — `/login`
Representative form — the other forms follow the same pattern:

```tsx
const form = useForm<LoginRequest>({ resolver: zodResolver(loginFormSchema) })
const { signIn } = useAuth()
const navigate = useNavigate()
const location = useLocation()
const from = (location.state as { from?: Location } | null)?.from?.pathname ?? '/'
const [expired] = useState(() => sessionStore.consumeEndReason() === 'expired')
const [lockedFor, setLockedFor] = useState<number | null>(null)

const login = useMutation({
  mutationFn: authApi.login,
  onSuccess: (res) => {
    signIn(res)
    navigate(from, { replace: true })
  },
  onError: (error) => {
    if (error instanceof ApiError) {
      for (const [field, message] of Object.entries(error.fieldErrors)) {
        form.setError(field as keyof LoginRequest, { message })
      }
      if (error.status === 429) setLockedFor(error.retryAfterSeconds ?? 60)
    }
  },
})
```

- `expired` → banner "Your session expired. Please log in again."
- `401` → form-level error, the server's message ("Invalid email or password").
  Don't say which field is wrong — the backend deliberately doesn't either.
- `429` → banner with a live countdown from `retryAfterSeconds`; submit
  disabled until it reaches 0.
- Submit button disabled + spinner while pending. Password field has a
  show/hide toggle. Link to `/register`.
- Already signed in → redirect to `from`.

### 12.6 `RegisterPage` — `/register`
- Fields: full name, email, password (with "at least 8 characters" hint).
- Server `400` `fieldErrors` → `setError` on each field (same keys as ours).
- `409` → error on the email field: "An account with this email already
  exists" + link "Log in instead".
- Success → `signIn(response)` (register returns a token — no second login)
  → navigate to `from` or `/`.

### 12.7 `CheckoutPage` — `/checkout` (auth required)
- Empty cart or `hasProblems` → redirect to `/cart`.
- Form (`checkoutFormSchema`): full name (prefilled from `user.fullName`),
  phone, wilaya (select from `WILAYAS`), city, address line, payment method
  radios — CIB and Edahabia shown **disabled** with "Coming soon".
- Order summary sidebar (lines + subtotal).
- Submit → `usePlaceOrder().mutate({ items, paymentMethod, shippingAddress })`.
  - Success → `cart.clear()`, navigate to `/orders/:id/confirmation`
    (`replace: true`, so Back doesn't resubmit).
  - `409` → show the message, link back to the cart (which will revalidate).
- Double-submit protection: button disabled while pending.
- A visible notice: "Demo checkout — no order is created yet." (remove in Phase 4).

### 12.8 `OrderConfirmationPage` — `/orders/:id/confirmation` (auth required)
- `useOrder(id)`. Shows order number, status "Placed", items, total,
  shipping address, payment method. "Continue shopping" link.
- `404` → "We couldn't find that order".

### 12.9 `AdminProductsPage` — `/admin/products` (ADMIN)
- Table: name, category, price, stock (red when 0), created date, actions
  (Edit, Delete). Same search / category / sort / page URL params as the
  shop (reuse the hook and a shared `useProductListParams()`).
- "New product" button → `/admin/products/new`.
- Delete → confirm dialog naming the product: "Delete '<name>'? Customers
  won't see it any more. This can't be undone from the admin panel." (there
  is no restore endpoint). On success: toast, list refetches.
- Note above the table: "Deleted products are hidden and not listed here."
- `403` on any mutation (role changed server-side) → toast with
  `errorMessage(error)`; `401` → session ends, guard sends to login.

### 12.10 `AdminProductFormPage` — `/admin/products/new` and `/:id/edit` (ADMIN)
- Edit mode: load with `useProduct(id)`; `404` → "Product not found".
  Prefill with `price` as `String(product.price)`, `stockQuantity` as string,
  `description ?? ''`.
- Fields: name (counter /200), description (textarea, counter /2000), price
  (`inputMode="decimal"`, suffix "DA"), category (select), stock
  (`inputMode="numeric"`).
- Submit → `toProductRequest(values)` → create or update (PUT sends **every**
  field — it's a full replace).
- Server `fieldErrors` → `setError` per field (keys match).
- Success → toast "Product saved", navigate to `/admin/products`.
- Leaving with unsaved changes → `useBlocker` confirm.

### 12.11 `NotFoundPage`
"Page not found" + links to home and shop.

Commit after each page or pair of pages, e.g. `feat: product list with URL-driven filters`.

---

## 13. Mocks — `src/mocks/` (MSW)

Used by `npm run dev:mock` (frontend without a backend) **and** by tests.

- `src/mocks/fixtures.ts` — ~15 products across all categories (some with
  `stockQuantity: 0`, one with `description: null`), an admin and a customer
  user. **Capture at least one product and one page response from the real
  backend with curl and paste them in verbatim**, so the fixtures can't
  drift from the real shape.
- `src/mocks/handlers.ts` — handlers that behave like the backend, including
  its quirks:

```ts
import { http, HttpResponse } from 'msw'
import { env } from '../lib/env'

const api = (path: string) => `${env.apiBaseUrl}${path}`

const apiError = (status: number, error: string, message: string, fieldErrors?: Record<string, string>) =>
  HttpResponse.json(
    { timestamp: new Date().toISOString(), status, error, message, fieldErrors: fieldErrors ?? null },
    { status },
  )

export const handlers = [
  http.get(api('/api/products'), ({ request }) => {
    const url = new URL(request.url)
    // search wins over category, exactly like ProductService.list
    // honour page (0-based), size, sort=field,dir
    // return { content, page: { size, number, totalElements, totalPages } }
  }),
  http.get(api('/api/products/:id'), ({ params }) => { /* 404 via apiError when missing */ }),
  http.post(api('/api/auth/login'), async ({ request }) => { /* 401 "Invalid email or password" */ }),
  http.post(api('/api/auth/register'), async ({ request }) => { /* 400 fieldErrors, 409 */ }),
  http.post(api('/api/products'), ({ request }) => { /* 401 no token, 403 customer token */ }),
  http.put(api('/api/products/:id'), /* ... */),
  http.delete(api('/api/products/:id'), /* 204 */),
]
```

  Mock tokens: `mock-admin-token` / `mock-customer-token`; the handlers
  check the `Authorization` header to return 401/403 like the backend.
- `src/mocks/browser.ts` — `export const worker = setupWorker(...handlers)` (from `msw/browser`).
- `src/mocks/server.ts` — `export const server = setupServer(...handlers)` (from `msw/node`).

The mocks must never be bundled into a production build: they're only
reachable through the dynamic `import('./mocks/browser')` behind
`env.useMocks`. Verify with `npm run build` + searching `dist/` for
`mock-admin-token` (should find nothing when built with the default mode).

---

## 14. Tests — Vitest + Testing Library + MSW

### `src/test/setup.ts`

```ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { sessionStore } from '../lib/session'
import { server } from '../mocks/server'

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  sessionStore.end('signed-out')
  localStorage.clear()
  sessionStorage.clear()
})
afterAll(() => server.close())
```

### `src/test/render.tsx`

`renderWithProviders(ui, { route = '/', session? })` — fresh
`QueryClient` per test (`retry: false`), `createMemoryRouter` with the app
routes at `initialEntries: [route]`, optionally `sessionStore.start(...)`
first. Returns the router too, so tests can assert `router.state.location`.

### Required tests (minimum — each with real assertions)

**Pure logic**
- [ ] `types/*.test.ts` — `productSchema`, `pageSchema(productSchema)` and
      `authResponseSchema` parse the **captured real backend payloads**;
      a payload with an unknown category fails.
- [ ] `productFormSchema` — rejects `''`, `0`, `-1`, `12.345`, `abc`
      prices; accepts `3100`, `3100.5`, `0.01`; rejects 201-char names;
      `toProductRequest` turns blank description into `null`.
- [ ] `lib/money.test.ts` — `0.1 + 0.2` items total exactly `0.30`;
      `formatDzd(4900)` contains `4` `900` and `DA` (use a regex: the
      separator is a narrow no-break space).
- [ ] `lib/cart.test.ts` — every reducer rule from §9.
- [ ] `lib/session.test.ts` — expired stored session loads as `null`;
      corrupt JSON loads as `null`; `end('expired')` then
      `consumeEndReason()` returns `'expired'` once.

**API client** (MSW, override handlers with `server.use(...)`)
- [ ] 400 → `ApiError` with `fieldErrors`.
- [ ] 401 on an `auth: true` request ends the session; 401 on login does **not**.
- [ ] 429 → `retryAfterSeconds` from the header, and from the message
      when the header is absent.
- [ ] 2xx with a wrong shape → `ContractError`.
- [ ] fetch rejection → `NetworkError`.
- [ ] Token is attached only when `auth: true`.

**Components / pages**
- [ ] `ProductListPage`: typing a search updates the URL and drops `category`;
      picking a category drops `search`; `?category=NOPE` sends no category.
- [ ] `ProductDetailPage`: 404 shows "isn't available"; out-of-stock
      disables "Add to cart".
- [ ] `LoginPage`: wrong password shows "Invalid email or password"; 429
      shows the countdown and disables submit; success navigates to `from`.
- [ ] `RegisterPage`: server `fieldErrors` appear under the right fields; 409
      shows the email error.
- [ ] `RequireAdmin`: customer session sees the 403 page; no session
      redirects to `/login`.
- [ ] `CartPage`: a line whose product now 404s shows "No longer available"
      and disables checkout.
- [ ] `AdminProductFormPage`: submit sends the exact `ProductRequest` body
      (assert in the MSW handler); 403 shows the permission message.

---

## 15. README for `atlasna_front`

Replace the Vite README with: what this is; prerequisites (Node version
from `package.json` engines — add `"engines": { "node": ">=22" }`); how to
run against the real backend (`docker compose up -d` + `./mvnw
spring-boot:run` in `atlasna-catalog`, then `npm run dev`); how to run
without it (`npm run dev:mock`); dev logins (`admin@atlasna.dz` /
`ChangeMe123!`, dev profile only); scripts; folder rules from §3; the
token-storage security note from §6; what's mocked and why (orders).

---

## Final verification steps

1. `rm -rf node_modules dist && npm ci && npm run build` — no errors.
2. `npm run lint` — no errors, no `eslint-disable` added to silence real issues.
3. `npm test` — all pass, and the count is well above 20.
4. **Against the real backend** (`docker compose up -d`, `./mvnw spring-boot:run`, `npm run dev`):
   - Products page shows the 3 seeded products; DevTools Network shows no
     CORS errors and **no `Authorization` header** on the GETs.
   - Search "tea" → 1 result; pick "Fashion" → search clears, 1 result.
   - Register a new customer → lands signed in; reload → still signed in.
   - Customer visits `/admin/products` → 403 page.
   - Log out → log in with a wrong password 6 times → the 6th shows the
     lockout countdown (5 failures per email).
   - Log in as `admin@atlasna.dz` / `ChangeMe123!` → create a product →
     it appears in the shop → edit its price → shop shows the new price →
     delete it → gone from shop and admin list; its old URL shows "isn't available".
   - Add 2 products to cart → as admin delete one in another tab → cart
     shows "No longer available" and blocks checkout → remove it → checkout
     as customer → confirmation page → cart is empty.
   - In DevTools → Application, change the stored session's `expiresAt` to
     the past and reload → signed out; edit the token to garbage and try an
     admin save → session ends with "Your session expired".
   - Test at 360 px width and with keyboard only (Tab / Enter / Space).
5. `npm run dev:mock` with the backend **stopped** — the whole flow above
   still works against MSW.
6. `npm run build && grep -r "mock-admin-token" dist/` — no matches.
7. Commit in logical steps, then: `git push`.

---

## Backend gaps found while reviewing the code (not part of this task — log them)

| # | Gap | Effect on the frontend | Suggested fix / phase |
|---|---|---|---|
| 1 | CORS doesn't expose `Retry-After` | Header unreadable cross-origin | Step 0 above (one line) |
| 2 | No `GET /api/auth/me` | Can't revalidate a stored user/role; role changes show only after re-login | Add `me` endpoint — Phase 3 |
| 3 | `search` and `category` can't be combined | UI must clear one when the other is set | JPA Specification — Phase 3 |
| 4 | `sort` isn't whitelisted; an unknown property is unhandled (probably a `500`) — verify with `curl "localhost:8080/api/products?sort=nope"` | Frontend must only send fixed values | Map `PropertyReferenceException` to 400 or whitelist — Phase 3 |
| 5 | No admin list of inactive products, no restore | Deletes are irreversible from the UI | `GET /api/admin/products?active=false`, `PATCH .../restore` — Phase 3 |
| 6 | No image field on products | Placeholder tiles only | `imageUrl` + Cloud Storage — Phase 7 |
| 7 | No refresh token; 60 min JWT | Users are logged out hourly | Refresh tokens or longer sessions — Phase 12 |
| 8 | Disabled account at login falls through to the generic 401 "Authentication is required…" message | Confusing login error | Catch `DisabledException` in `AuthService` |
| 9 | `LoginRequest` has no custom messages | Server returns "must not be blank" | Add messages like `RegisterRequest` |
| 10 | CORS origins hard-coded to localhost | Deployed frontend will be blocked | Make origins configurable — before Phase 7 |
| 11 | `setAllowCredentials(true)` with bearer-only auth | Not needed; widens CORS for no benefit | Set `false` unless cookies are introduced |
| 12 | `ProductResponse` lacks `updatedAt` | Admin table can't show "last edited" | Add the field — optional |
| 13 | Orders/payments don't exist | Checkout is mocked | Phase 4 replaces `api/orders.ts` |
