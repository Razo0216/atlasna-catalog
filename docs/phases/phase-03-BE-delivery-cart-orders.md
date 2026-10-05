# Phase 3 (Backend) — Delivery, Cart & Orders

| | |
|---|---|
| **Repo** | `atlasna-catalog` (still one deployable — a **modular monolith**) |
| **Prerequisite** | Phase 1 + 2 done (`phase-1-done`, `phase-2-done` tags); frontend contract frozen |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md`, `02-DOMAIN-AND-DATA-MODEL.md` §2 (rules 10–19), §4 (Phase 3 tables), §6 (state machines), `03-API-CONTRACT.md` §4 |
| **Estimate** | 4–5 weeks — the most important backend phase: money, stock and concurrency |

## 1. Goal
A logged-in customer can pick an Annaba delivery zone and slot, fill a server-side cart, and **place a cash-on-delivery order** that is priced by the server, reserves stock atomically,
and moves through a strict lifecycle handled by staff and drivers. Nothing can oversell, double-charge, or leak another customer's data.

## 2. Scope
**In:** `store_settings`, delivery zones & slots (with capacity), addresses, server cart (+ guest merge), order placement (idempotent), order lifecycle & history, COD payment record,
stock ledger + adjustments, roles `STAFF`/`DRIVER`, staff/driver/admin order endpoints, **Spring Modulith** module boundaries.
**Out:** online card payment, Redis, Kafka, notifications (Phase 5/7 — only publish in-process Modulith events here), images, lots/expiry.

## 3. Steps (in order — compile and test after each)

### Step 0 — Preflight
Branch `phase-3-orders`. Confirm tags/tests green. Report the current package layout. Re-read `01-CONVENTIONS.md` §3 (money, concurrency, transactions).

### Step 1 — Module boundaries with Spring Modulith
Add `spring-modulith-starter-core`, `spring-modulith-starter-jpa`, `spring-modulith-starter-test` (versions from the Boot-managed BOM `spring-modulith-bom` — Initializr gave 2.1.1 on 2026-10-05).
Modules (top-level packages under `com.atlasna.catalog`): `user`+`security` (identity), `product` (catalog), `inventory` (stock ledger — new), `delivery` (settings, zones, slots, addresses — new),
`order` (cart, orders, payment — new), `common`. Each module exposes a small **public API** at its root package (service interface + DTO records) and keeps entities/repositories in sub-packages.
Rule: `order` may call `product`, `inventory`, `delivery` **only through their public API**; nothing reaches into another module's entities or repositories.
Add a test: `ApplicationModules.of(AtlasnaCatalogApplication.class).verify()` — it must pass, and you must demonstrate it **fails** when you add one illegal import. Generate the module diagram (`Documenter`) into `docs/`.
Inter-module notifications use Modulith **application events** (`OrderPlaced`, `OrderStatusChanged`, `StockLow`) published inside the transaction; the event publication registry (JPA) is the **outbox** Phase 7 will ship to Kafka.

### Step 2 — Migrations (`V3`, `V4`, … never edit older ones)
Create tables from `02-DOMAIN-AND-DATA-MODEL.md` §4 (Phase 3). Non-negotiable details:
- `users.role` check allows `CUSTOMER, ADMIN, STAFF, DRIVER`.
- `orders.order_number` **unique**; `UNIQUE (user_id, idempotency_key)`; `status` and `payment_method` check constraints; money columns `NUMERIC(12,2)`, quantities `NUMERIC(10,3)`; `total_weight_grams INTEGER`.
- `order_items` stores the **snapshot** (names, sku, `sold_by`, `unit_price`, `unit_weight_grams`) — no reliance on live product rows for history.
- `stock_movements` is **append-only** (no UPDATE/DELETE grants in code; consider a trigger that rejects updates).
- **Slot capacity without races:** table `slot_usage(slot_id, slot_date, booked, PRIMARY KEY(slot_id, slot_date))` and a single atomic statement
  `INSERT … ON CONFLICT (slot_id, slot_date) DO UPDATE SET booked = slot_usage.booked + 1 WHERE slot_usage.booked < :capacity RETURNING booked` — zero rows returned ⇒ `409 SLOT_FULL`.
- Indexes for the real queries: orders by `(user_id, created_at desc)`, by `(status, slot_date)`, by `assigned_driver_id`; carts by `user_id` unique.
- Seed (migration or dev seeder): store settings defaults ⚙, the four zones and five slots from `02-…` §9, one `STAFF` and one `DRIVER` dev account.
Verify every migration on an empty DB **and** on the Phase 1 DB.

### Step 3 — Settings, zones, slots, addresses
- `store_settings` single row, typed columns, `GET/PUT /api/admin/settings` (ADMIN). Replace Phase 1's `StoreProperties` heavy-item limits with these (migrate the reads; keep the config as defaults).
- Zones & slots CRUD (ADMIN); public `GET /api/delivery/zones`, `GET /api/delivery/slots?zoneId&date`.
- **Availability rule** (one pure, heavily unit-tested function): a slot is available on `date` iff the store is open, `now + leadTime ≤ slotStart` (zone-local time **Africa/Algiers**), the day's cut-off has not passed for same-day, `booked < capacity`, the date is within `maxDaysAhead` ⚙ (3). Return the *reason* when unavailable (`FULL|PAST_CUTOFF|TOO_SOON|CLOSED`). Inject a `Clock`; test around midnight and DST-free Algeria time (UTC+1 all year).
- Addresses: CRUD under `/api/me/addresses`; every query filtered by the authenticated user id; accessing another user's id returns **404**. Phone normalised with `PhoneNumbers`. `zoneId` must be active.

### Step 4 — Server cart
`carts`/`cart_items` (one cart per user). `PUT /api/cart/items/{productId}` snaps quantity to `quantityStep`, clamps to `maxOrderQuantity`, rejects inactive products.
`GET /api/cart` returns lines **re-priced from the catalog** with `warnings` (`PRICE_CHANGED`, `LOW_STOCK`, `UNAVAILABLE`), `subtotal`, `totalWeightGrams` and `issues`.
`POST /api/cart/merge` merges a guest cart (idempotent, clamping, reports what could not be added). Cart does **not** reserve stock.

### Step 5 — Order placement (`POST /api/orders`) — one transaction, in this order
1. Require `Idempotency-Key`. Look up `(user, key)`: same key + same request hash ⇒ return the stored response (`200`); same key + different body ⇒ `409 IDEMPOTENCY_KEY_REUSED`. Insert the record at the start of the transaction (a unique-violation race ⇒ re-read and replay).
2. Load the user's cart; empty ⇒ `422`. Validate address ownership, zone active (`ZONE_NOT_SERVED`), `subtotal ≥ minOrderAmount` (`ORDER_BELOW_MINIMUM`, include the missing amount), `totalWeight ≤ maxOrderWeightGrams` (`ORDER_TOO_HEAVY`).
3. **Reserve the slot** with the atomic upsert above.
4. **Decrement stock per line, atomically:** `UPDATE products SET stock_quantity = stock_quantity - :q WHERE id = :id AND active AND stock_quantity >= :q` — 0 rows ⇒ `409 INSUFFICIENT_STOCK` (with `details:[{productId, available}]`) and the whole transaction rolls back (slot reservation included). Append a `stock_movements` row (`SALE`, negative delta, order ref). Process lines in **ascending product id** to avoid deadlocks.
5. Compute prices **server-side** with `BigDecimal` (`unitPrice × quantity`, scale 2, `HALF_UP`); delivery fee from the zone (free above the threshold); total; weight. Write `orders`, `order_items` (snapshots), `order_status_history`, a `payments` row (`COD`, `PENDING`), generate `order_number` (`ATL-YYMMDD-XXXX`, random base32, retry on collision).
6. Clear the cart. Publish `OrderPlaced` (Modulith event, inside the transaction). Store the response in the idempotency record. Return `201`.
Per-kg lines: `totalIsEstimate=true` until every weighted line has `quantity_fulfilled`.

### Step 6 — Lifecycle, cancellation, fulfilment
- Transition table in code and tested exhaustively (`02-…` §6): who (`role`) may move `from → to`; each move writes `order_status_history` and publishes `OrderStatusChanged`. Illegal ⇒ `409 INVALID_STATE_TRANSITION`.
- Customer cancel: allowed in `PLACED`/`CONFIRMED`. Staff cancel: any non-terminal state with a mandatory reason. Cancel ⇒ restore stock (`CANCEL_RESTORE` movements, `UPDATE … +q`), release the slot (`booked - 1`), set payment `NOT_COLLECTED`/void.
- Packing: `PATCH …/items/{id}` records `quantityFulfilled` and item `status` (`PACKED|OUT_OF_STOCK|SUBSTITUTED`); recompute line and order totals; if the final total differs from the estimate by more than the tolerance ⚙, flag the order (Phase 7 will notify).
- Drivers see only **assigned** orders; `deliver` records `cashCollected` (must equal the total unless a documented reason) and sets payment `COLLECTED`; `fail` records the reason → `DELIVERY_FAILED`.
- Repeat-failure flag (rule 17) as a computed property on the admin order view.
- `POST /api/orders/{n}/reorder` rebuilds the cart from a past order's lines with warnings for anything unavailable or repriced.

### Step 7 — Stock ledger & adjustments
`POST /api/admin/products/{id}/stock-adjustments {delta, reason, note}` (ADMIN, and STAFF for `RESTOCK|ADJUSTMENT`) — atomic update + ledger row. `GET …/stock-movements` (paged).
**Invariant (tested):** for every product, `stock_quantity = Σ ledger deltas` (starting from an opening-balance movement written by a migration/seeder).

### Step 8 — Admin users for staff/drivers
`POST /api/admin/users` (create STAFF/DRIVER with a temporary password and forced change flag — or invite flow if time), `GET /api/admin/users?role=`, `PATCH` enable/disable. No self-service role changes, ever.

## 4. Required tests (Testcontainers Postgres; each must be able to fail)

| Area | Test |
|---|---|
| **Concurrency** | 20 threads order the **last unit** of one product → exactly **1** order created, 19 get `409 INSUFFICIENT_STOCK`; stock = 0, never negative; ledger invariant holds |
| Concurrency | 30 threads book a slot with capacity 8 → exactly 8 succeed, `booked = 8` |
| Concurrency | Two orders for products {A,B} and {B,A} in opposite order don't deadlock (ordered locking) |
| Idempotency | Same key twice → one order, second call returns the same `orderNumber`; same key + different body → `409`; 10 parallel identical requests → one order |
| **Authorization (BOLA)** | User B requesting A's order / address / cart item ⇒ **404** (not 403, not 200); STAFF cannot hit `/api/admin/settings`; DRIVER cannot see unassigned orders; anonymous ⇒ 401 — full matrix over every route |
| State machine | Every `(from,to)` pair × every role: only the allowed ones succeed; history rows written |
| Pricing | Decimal edge cases (`59.99 × 0.5`, rounding half-up), free-delivery threshold boundary, min-order boundary (exactly equal passes), weight cap boundary |
| Cancellation | Stock restored, slot released, ledger rows present; double-cancel is rejected |
| Fulfilment | Weighed quantity changes totals; `totalIsEstimate` flips; tolerance flag |
| Availability | Slot rules around cut-off, lead time, midnight, `maxDaysAhead`, capacity |
| Cart | Snap-to-step, clamp, inactive product, merge idempotency |
| Modulith | `verify()` passes; fails with an illegal dependency (prove it once) |
| Migrations | Apply on empty DB and on Phase-1 DB; `validate` passes |

## 5. Acceptance criteria
- [ ] `./mvnw clean verify` green; concurrency tests run 20× in a loop locally without a single failure (`-Dsurefire.rerunFailingTestsCount=0`).
- [ ] Manual run through `curl` (see §6) places, packs, delivers and cancels orders; the DB invariants hold (`SELECT … stock vs Σ ledger`).
- [ ] `03-API-CONTRACT.md` §4 matches reality; `OrderResponse` example captured from the real API for the frontend.
- [ ] Security baseline ticked (`01-CONVENTIONS.md` §6) including the BOLA matrix.
- [ ] ADRs: 0005 modular monolith + Modulith · 0006 stock = ledger + atomic conditional update · 0007 idempotency design · 0008 slot capacity upsert.

## 6. Verification (paste outputs in the PR)
```bash
# customer: register → address → cart → slots → order (note the Idempotency-Key)
curl -s -X PUT localhost:8080/api/cart/items/1 -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"quantity":1.5}'
curl -s "localhost:8080/api/delivery/slots?zoneId=1&date=$(date -v+1d +%F)" | jq
KEY=$(uuidgen); for i in 1 2; do curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $T" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d '{"addressId":1,"slotId":2,"slotDate":"2026-10-07","paymentMethod":"CASH_ON_DELIVERY"}' | jq -r .orderNumber; done   # same number twice
# staff packs, driver delivers, customer sees DELIVERED; user B gets 404 on the order
psql … -c "SELECT p.id,p.stock_quantity,COALESCE(SUM(m.delta),0) ledger FROM products p LEFT JOIN stock_movements m ON m.product_id=p.id GROUP BY p.id HAVING p.stock_quantity<>COALESCE(SUM(m.delta),0)"   # 0 rows
```

## 7. Out of scope
Card payments (Phase 5) · refunds · notifications (Phase 7) · lots/FEFO (Phase 5) · coupons/loyalty · multi-store.

## 8. Commits & tag
`db: settings, zones, slots, addresses, carts` → `feat(delivery): zones, slots, availability rules` → `feat(order): server cart and guest merge` → `feat(order): idempotent atomic order placement` →
`feat(order): lifecycle, cancellation, fulfilment` → `feat(inventory): ledger and adjustments` → `refactor: spring modulith boundaries + verify test` → `test: concurrency, bola, state machine`. Tag `phase-3-done`.

## 9. Concepts & interview questions (for the developer)
**Concepts:** ACID and isolation levels · atomic conditional update vs optimistic vs pessimistic locking · deadlock avoidance via lock ordering · idempotency keys · the **outbox pattern** (why events are written in the same transaction) ·
state machines as data · event sourcing-lite (ledger) · BOLA / IDOR · snapshotting vs referencing · modular monolith & enforced boundaries · time zones in business rules.
1. Two customers order the last tomato crate at the same millisecond. Walk through exactly what the database does and why nobody oversells.
2. Why `UPDATE … WHERE stock >= :q` instead of read-check-write? What isolation level would the naive version need?
3. A mobile client times out and retries `POST /orders`. How do you guarantee one order — and what must the client do?
4. Why store `unit_price` on the order line instead of joining to products?
5. Why return 404, not 403, for another user's order?
6. Why is `stock_quantity` a cached sum of the ledger, and what test keeps it honest?
7. What does Spring Modulith give you that package naming conventions don't?
8. Where could a distributed transaction appear when we split services in Phase 7, and how will we avoid it?
