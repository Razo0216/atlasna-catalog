# Phase 6 (Frontend) — Admin Back-office & Driver View

| | |
|---|---|
| **Repo** | `atlasna_front` (admin lives in the same SPA, lazy-loaded under `/admin`; the driver view under `/driver`) |
| **Prerequisite** | Phase 5 backend done (refresh tokens, uploads, lots, audit log); Phase 4 frontend done |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §5–6, `02-…` §2–3 (roles matrix), `03-API-CONTRACT.md` §3–5 |
| **Estimate** | 3 weeks |

## 1. Goal
The owner and staff can **run the store from a browser**: manage catalog, stock, lots and expiry; process orders on a live board; assign drivers; manage zones, slots and settings; read reports and the audit log.
Drivers get a **phone-first** screen for their deliveries. Customers never download admin code.

## 2. Scope
**In:** adopt the **refresh-token auth flow**, role-based routing (`ADMIN`, `STAFF`, `DRIVER`), dashboard, product & category admin (with image upload), stock adjustments & lots, orders board & detail (packing with weights), driver assignment, zones/slots/settings, reports, audit log viewer, driver app view, user (staff/driver) management.
**Out:** real-time push (Phase 8), PWA install for drivers (Phase 8), analytics warehouse, multi-language admin UI beyond French + Arabic (admin defaults to French; Arabic supported).

## 3. Steps

### Step 0 — Preflight
Branch `phase-6-admin`. Capture real responses for every admin endpoint (products incl. `active=false`, lots, stock movements, orders board, reports, audit logs, users). Fixtures come from there.

### Step 1 — Auth upgrade (breaking change for the session layer)
Implement the Phase 5 token strategy (ADR-0009): the **access token lives in memory only**; the refresh token is an httpOnly cookie. On app start call `POST /api/auth/refresh` (with the CSRF header) to restore the session; on any `401` try **one** refresh then retry the request once (single-flight so parallel requests share one refresh); a failed refresh ends the session. Remove the `localStorage` token and update the README's security note.
Dev note: cookies need the API and SPA on the same site — configure the Vite dev **proxy** (`/api → :8080`) instead of cross-origin calls, and `credentials: 'include'` only where required. Update tests (the session store changes shape; keep the 401-vs-login rule).

### Step 2 — Role-based shell
`/admin/*` for `ADMIN` and `STAFF` (STAFF sees only: orders, stock, lots, expiry), `/driver/*` for `DRIVER`. `RequireRole(roles)` guard: unauthenticated → login; wrong role → a clear 403 page (not a redirect loop). **Guards are UX only — the backend enforces everything.** Navigation items are derived from the role, not hard-coded per page. Lazy routes + a separate chunk per area.
Layout: sidebar (collapsible; bottom tab bar on phones), top bar with language switcher and logout, breadcrumb, toast region, confirm-dialog component, unsaved-changes guard.

### Step 3 — Data table kit (build once, reuse everywhere)
Server-driven table: URL-synced pagination/sort/filters, column visibility, empty/error/loading states, row actions, keyboard navigation, sticky header, responsive (cards on mobile). CSV export of the current filtered view. Reusable `FormField`, `Money`, `StatusBadge`, `Localized` (fr+ar paired inputs with a "copy from French" helper), `ConfirmDialog`.

### Step 4 — Catalog admin
- **Products:** list (filters: active/inactive/all, low stock, category, search), create/edit form with **fr + ar** names/descriptions, category (leaf only), price/compare-at, sold-by (UNIT/WEIGHT) with step, **unit weight with live heavy-item feedback** (the limit comes from settings; server still enforces → show `PRODUCT_TOO_HEAVY` nicely), stock, thresholds, perishable/featured, **attributes editor** (known keys as typed controls, free JSON hidden behind "advanced"), SKU/barcode.
  Soft delete with confirm, **restore** from the inactive filter, optimistic-lock conflict (`409 CONCURRENT_MODIFICATION`) → "Quelqu'un a modifié ce produit" with a reload/merge prompt.
- **Images:** drag-and-drop/camera capture on mobile → request signed URL → direct upload with progress → attach, reorder, alt text fr/ar, remove. Client-side checks (type, size, preview) are conveniences; the server re-validates.
- **Categories:** tree editor (rename, reorder, activate); delete blocked with the backend's message when not empty.

### Step 5 — Stock, lots & expiry
Per-product stock panel: current stock, **movements ledger** (paged, filter by reason), **adjust stock** (delta + reason + note), **receive lot** (code, quantity, expiry), lots table with expiry colouring (expired / ≤ 7 days / ok). Pages: **Low stock**, **Expiring soon**. Optimistic-locking and error handling as above.

### Step 6 — Orders board (the daily driver)
- **Board view** by status columns (PLACED → … → DELIVERED) filtered by date, zone and slot; **list view** for search by order number/phone. Phone-friendly.
- **Order detail:** items with **packing controls** (enter weighed quantity for per-kg lines, mark OUT_OF_STOCK/SUBSTITUTED with a substitute picker), live recomputed totals vs estimate (tolerance badge), customer contact with `tel:` link, landmark and map link (`geo:`/Google Maps URL from lat/lng when present), status history, assign driver, **allowed next actions only** (derived from role + state; illegal ones aren't shown), cancel with mandatory reason.
- Auto-refresh every 20 s while visible (SSE replaces it in Phase 8). Visible "new orders" indicator with an optional sound toggle.
- Print view: **packing slip** and **delivery note** (`@media print`, fr/ar, order number, items with quantities, address + landmark, total to collect).

### Step 7 — Zones, slots, settings, users
Zones (fee, min order, free-delivery threshold, ETA), slots (day/time/capacity per zone), **settings form** (limits, hours, lead time, cut-off, tolerance) with validation hints and a "last changed by/when" line from the audit log. Users: list/create/disable STAFF and DRIVER accounts (no role escalation UI beyond that; no self-role-change).

### Step 8 — Dashboard & reports
Dashboard: today's orders by status, revenue (collected vs expected), slots utilisation, **low stock** and **expiring** counts, failed deliveries. Reports (date-range, CSV): sales by day, top products, by category/type, by zone, cash collected per driver. Lightweight charts (SVG-based library or hand-rolled bars) — keep the bundle small and **lazy-load** charts.

### Step 9 — Audit log viewer
Filter by entity/actor/date; readable before/after diffs (changed fields only); never render values as HTML.

### Step 10 — Driver view `/driver`
Large-touch, one-handed UI: **today's assigned orders** in slot order, big customer name, **call** and **navigate** buttons, address + landmark, items summary, amount to collect. Actions: *Start delivery* → *Delivered* (confirm cash collected = total, with an "autre montant" path requiring a reason) or *Failed* (reason picker: no answer / refused / wrong address / other). Works on a slow connection: optimistic UI with queued retries for status actions (idempotent endpoints), clear offline banner. Daily **cash summary** at the end.

## 4. Required tests
- [ ] Auth: parallel `401`s trigger **one** refresh; refresh failure logs out; no token in `localStorage`; 401-on-login still not a session end.
- [ ] `RequireRole` matrix: anonymous / CUSTOMER / STAFF / DRIVER / ADMIN × each area; nav items per role; STAFF cannot reach products or settings pages.
- [ ] Data table: URL ⇄ state, empty/error/loading, CSV matches the filtered rows.
- [ ] Product form: bilingual validation, heavy-item message from `PRODUCT_TOO_HEAVY`, `soldBy=UNIT` forbids fractional step, optimistic-lock conflict flow, attributes editor writes the exact JSON the API expects (assert request body in MSW).
- [ ] Image upload: progress, wrong type/size blocked, server rejection surfaced, order of images persisted.
- [ ] Orders: only allowed transitions offered; per-kg weighing recomputes totals; `409 INVALID_STATE_TRANSITION` refreshes the card; cancellation requires a reason.
- [ ] Driver: sees only assigned orders (MSW returns a mix; UI shows backend-filtered list); fail-reason flow; queued retry on transient error uses the same request (idempotent).
- [ ] RTL: admin tables, board, forms mirrored; `lint:rtl` and `i18n:check` pass for all new strings.
- [ ] Accessibility: dialogs trap focus, tables have headers, board columns keyboard-reachable, status colours not the only signal.
- [ ] Bundle: customer entry chunk size unchanged (admin/driver in separate lazy chunks) — add a size check script.

## 5. Acceptance criteria
- [ ] Owner flow on a laptop and staff/driver flow on a phone, against the real backend: create a product with images in both languages → it appears in the storefront → receive a lot with an expiry → an order arrives → pack it with a weighed quantity → assign a driver → driver delivers and records cash → reports reflect it → audit log shows each admin action.
- [ ] A STAFF token cannot call admin-only endpoints (try from DevTools) and the UI never offers them.
- [ ] Customer bundle does not include admin code (inspect the build output).
- [ ] Security baseline ticked; no `dangerouslySetInnerHTML`; uploads/filenames never rendered unescaped.

## 6. Out of scope
Realtime push (Phase 8) · full PWA/offline for drivers (Phase 8) · e-signature on delivery · route optimisation · payroll.

## 7. Commits & tag
`refactor(auth): in-memory access token + refresh cookie flow` → `feat(admin): role shell and guards` → `feat(admin): data table kit` → `feat(admin): products, categories, images` → `feat(admin): stock, lots, expiry` →
`feat(admin): orders board and packing` → `feat(admin): zones, slots, settings, users` → `feat(admin): dashboard, reports, audit log` → `feat(driver): delivery view`. Tag `phase-6-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** in-memory access token + httpOnly refresh cookie · single-flight refresh · RBAC UI vs enforcement · server-driven tables · optimistic locking in the UI · direct-to-storage uploads with signed URLs · code splitting by role · print stylesheets · designing for one-handed mobile use.
1. Why is "hiding the admin button" not security, and what actually is?
2. How do you prevent ten simultaneous 401s from triggering ten refresh calls?
3. Compare storing the access token in memory vs `localStorage`; what does each defend against?
4. Why do uploads go straight to storage via a signed URL instead of through the API?
5. Two admins edit the same product. Walk through what each sees.
6. What makes a driver screen different from an admin screen in terms of UX constraints?
