# Phase 4 (Frontend) — Cart, Checkout & Orders

| | |
|---|---|
| **Repo** | `atlasna_front` |
| **Prerequisite** | Phase 3 backend done and running; `03-API-CONTRACT.md` §4 copied into this repo; Phase 2 frontend done |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §5–6, `02-…` §2 (rules 10–19), `03-API-CONTRACT.md` §4 |
| **Estimate** | 3 weeks |

## 1. Goal
A customer in Annaba can go from cart to a **placed cash-on-delivery order** in a few taps on a phone: choose or add an address (with landmark), pick a delivery slot, review an honest total
(delivery fee, estimate for per-kg items), place the order **safely even on a flaky connection**, and then follow, cancel or **reorder** it. Everything in French and Arabic (RTL).

## 2. Scope
**In:** server-backed cart (replacing the guest cart's backing store, with merge-on-login), address book, zone/slot selection, checkout, order confirmation, order history & detail with status timeline, cancel, reorder, order-status refresh by polling, inline handling of every Phase 3 error code.
**Out:** card payment, live SSE tracking & push (Phase 8), admin/driver screens (Phase 6), legal pages (Phase 16).

## 3. Steps

### Step 0 — Preflight
Branch `phase-4-checkout`. Run the backend; capture **real** responses with `curl` for: cart, zones, slots (one `available:true`, one `FULL`, one `PAST_CUTOFF`), order created, order list, order detail (each status), and each error (`INSUFFICIENT_STOCK`, `SLOT_FULL`, `ORDER_BELOW_MINIMUM`, `ORDER_TOO_HEAVY`, `ZONE_NOT_SERVED`, `INVALID_STATE_TRANSITION`). These become the MSW fixtures — **do not invent shapes**.

### Step 1 — Types & API modules
Zod v4 schemas for `Cart`, `CartLine`, `Zone`, `Slot`, `Address`, `Order`, `OrderLine`, `OrderStatus` (`as const` + `z.enum`; **no TS enums**), `PaymentMethod`, error `details`. Modules `api/cart.ts`, `api/delivery.ts`, `api/addresses.ts`, `api/orders.ts`. The earlier mock `orders.ts` is deleted.
`createOrder` always sends `Idempotency-Key`.

### Step 2 — Cart repository swap
Implement `ServerCartRepository` behind the Phase 2 `CartRepository` interface. Anonymous → still the local guest cart. **On login/register:** call `POST /api/cart/merge` with the guest lines, then clear the local cart and show a localised summary of anything that couldn't be added.
Cart page now shows server warnings (`PRICE_CHANGED`, `LOW_STOCK`, `UNAVAILABLE`), the **total weight** with a progress meter toward the order cap (fetched from the backend settings/zone response, not hard-coded), and a "Commander" button that goes to `/checkout` (requires login → `state.from`).
Optimistic quantity updates with rollback on error; debounce rapid stepper taps (≈400 ms) so a kilo-by-half-kilo tap-fest doesn't flood the API.

### Step 3 — Address book
`/account/addresses`: list, add, edit, delete, set default. Form: label, recipient name (prefilled), phone (reuse `normalizeAlgerianMobile`, `dir="ltr"`), **zone select (Annaba zones only — there is no wilaya field)**, street, **landmark** (prominent, with helper text "Un repère connu: pharmacie, mosquée, café…"), building notes, optional "use my location" (Geolocation API → lat/lng, **opt-in, explained, never required**).
Inline "add address" inside checkout (same form in a dialog). Another user's address id never appears (server returns 404 — handle gracefully).

### Step 4 — Checkout `/checkout` (auth required)
One scrolling mobile-first page (stepper on desktop), sections in this order:
1. **Address** (default preselected; its zone drives fee/min order/slots).
2. **Delivery slot:** a horizontally scrolling **date strip** (today … +maxDaysAhead) and a slot grid for the chosen date from `GET /api/delivery/slots`. Show remaining capacity ("Plus que 2 places"), and **disabled slots with a localised reason** (`FULL`, `TOO_SOON`, `PAST_CUTOFF`, `CLOSED`). Refetch on focus and every 60 s while open. Times shown in `Africa/Algiers`.
3. **Items:** per-line **substitution policy** selector (default "M'appeler" / "اتصلوا بي"), per-kg lines marked "≈ estimé".
4. **Payment:** COD only ("Paiement à la livraison"); optional "J'aurai besoin de monnaie pour…" (`changeFor`, must be ≥ total); card options shown disabled with "Bientôt".
5. **Note** for the driver (≤ 300 chars, plain text).
6. **Summary:** subtotal, delivery fee (or "Gratuite"), **total**, "estimé" badge when any per-kg line exists with the sentence "Le montant final dépend du poids réel". This disclosure is also a **legal requirement** direction (G6): total incl. delivery shown before confirming.
The "Confirmer la commande" button is disabled until address + slot are chosen and while submitting.

**Safe submission (the important part):**
- Generate an `Idempotency-Key` (UUID) when the user *first* presses confirm; **persist it (sessionStorage) with a hash of the payload** until a terminal result. A retry (network drop, double tap, refresh) reuses the same key. Create a new key only when the payload changes.
- `201`/`200` → clear the cart, `navigate('/orders/:n/confirmation', {replace:true})` (Back must not resubmit).
- `NetworkError`/timeout → "Vérifiez vos commandes avant de réessayer" + automatically check `GET /api/orders` for a just-created order before offering retry.
- `409 INSUFFICIENT_STOCK` → mark the affected lines using `details`, offer "Ajuster le panier", never auto-remove.
- `409 SLOT_FULL` → refetch slots, keep everything else the user entered, ask to choose another slot.
- `422 ORDER_BELOW_MINIMUM` → show exactly how much is missing ("Ajoutez 350 DA") with a link back to the shop. `422 ORDER_TOO_HEAVY` → show the weight meter and which lines to reduce. `422 ZONE_NOT_SERVED` → explain and offer another address.
- Every other `code` → localised message from `errors:<CODE>`; unknown → generic.

### Step 5 — Orders
- `/orders/:orderNumber/confirmation`: number, status, items, totals (with the estimate note), address, slot, payment method, "Suivre ma commande", "Continuer mes achats".
- `/orders` history (paged, newest first, status chips, total, date) and `/orders/:orderNumber` detail with a **status timeline** from `history` (localised labels, times in Algiers), live refresh by polling every 30 s while the tab is visible (stop when terminal; SSE replaces this in Phase 8).
- **Cancel** (only offered when allowed; confirm dialog; handles `409 INVALID_STATE_TRANSITION` by refreshing the order).
- **Reorder** → rebuilds the cart from the order, shows warnings for repriced/unavailable lines, goes to the cart.
- "Appeler le magasin" `tel:` link on the detail page (number from a config constant for now).
- A customer opening someone else's order number (or a bad one) sees the friendly "Commande introuvable".

### Step 6 — Mocks & tests
MSW handlers mirror the real behaviour **including idempotent replay, every 409/422 above, slot availability reasons, merge results**. Fixtures from Step 0.

## 4. Required tests
- [ ] Schemas parse the captured real payloads for every order status; reject unknown status/payment method.
- [ ] Guest→account merge: lines merged, summary shown, local cart cleared; partial failures reported.
- [ ] Cart: optimistic update rolls back on error; stepper debounce coalesces taps; weight meter reflects the server cap.
- [ ] Checkout: cannot submit without address/slot; disabled slots show the right localised reason; `changeFor < total` rejected client-side.
- [ ] **Idempotency:** double-click and "network error then retry" send the **same** `Idempotency-Key`; changed payload ⇒ new key; refresh mid-submit reuses the persisted key; success clears it.
- [ ] Each backend error code produces its specific UX (stock lines flagged, slot refetch, missing-amount message, weight meter, zone message).
- [ ] After a network failure the UI checks existing orders before offering retry (no duplicate order in the mock).
- [ ] Orders: history paging, detail timeline order and localisation (fr/ar, `dir`), cancel disabled after PREPARING, 404 page for foreign order, polling stops at terminal status and when the tab is hidden.
- [ ] Reorder shows warnings for unavailable/repriced lines.
- [ ] RTL: checkout, slot grid, timeline and address form mirrored; Latin-content inputs stay LTR; `lint:rtl` and `i18n:check` pass.
- [ ] Accessibility: slot grid is a keyboard-navigable radio group; errors announced (`aria-live`); focus moves to the first error after a failed submit.

## 5. Acceptance criteria
- [ ] Against the real backend: register → add address with landmark → fill cart (incl. 1.5 kg of tomatoes) → pick tomorrow 14:00–16:00 → place COD order → see confirmation → see it in history → cancel → stock restored (check in admin DB/API) → reorder.
- [ ] Kill the network after pressing confirm, restore it, retry: exactly **one** order exists.
- [ ] Two browsers race for the last unit: one succeeds, the other sees the stock message and can adjust.
- [ ] All new strings exist in `fr` and `ar`; keyboard-only and 360 px runs recorded in the PR.
- [ ] `npm ci && npm run build && npm run lint && npm test && npm run i18n:check && npm run lint:rtl` green.
- [ ] No prices ever sent from the client (inspect the request body).

## 6. Out of scope
Card payments · SSE/push · admin & driver UI · legal pages · loyalty/coupons · guest checkout.

## 7. Commits & tag
`feat: order, cart, delivery schemas and api modules` → `feat: server cart repository with guest merge` → `feat: address book with landmarks` → `feat: delivery slot picker` →
`feat: checkout with idempotent submission` → `feat: orders history, detail, cancel, reorder` → `test: msw fixtures from backend, error-code matrix`. Tag `phase-4-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** idempotent clients · optimistic UI & rollback · server-driven validation · debounce vs throttle · forms with multi-section state · polling vs push · accessibility of custom radio groups · time zones in UI · never trusting client-side totals.
1. A user double-taps "Confirm" on a bad connection. Describe every mechanism (client and server) preventing two orders.
2. Why persist the idempotency key, and when must it change?
3. The server returns `409 INSUFFICIENT_STOCK`. What should the UI do — and what should it *never* do automatically?
4. Why is the "estimated" total honest UX for per-kg items, and where is the final price decided?
5. How would you test the "network drop then retry" scenario without a real flaky network?
6. Why does the slot picker refetch on focus even though the data was fresh a minute ago?
