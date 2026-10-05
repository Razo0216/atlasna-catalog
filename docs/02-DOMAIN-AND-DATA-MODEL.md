# Atlasna — Domain Rules & Data Model

Business rules and the end-state data model. Each table is tagged with the phase that introduces it.
**Assumptions marked ⚙ are defaults the store owner must confirm** — they are *configuration*, never hard-coded.

---

## 1. Glossary

| Term | Meaning |
|---|---|
| **Sold by UNIT** | Priced per piece/pack ("Couscous 1 kg bag — 130 DA"). Quantity is a whole number. |
| **Sold by WEIGHT** | Priced **per kg** ("Tomates — 80 DA/kg"). Quantity is decimal (step 0.5 kg). Final price is set after weighing. |
| **Unit weight** | Weight in grams of **one sold unit** (a 1 kg bag = 1000; a per-kg item = 1000 per kg). Drives the heavy-item rules. |
| **Zone** | A delivery area in Annaba wilaya with its own fee, minimum order and ETA. |
| **Slot** | A delivery time window (e.g. 14:00–16:00) with a capacity (max orders). |
| **Substitution policy** | What staff may do if an item is out of stock at packing time. |
| **Lot (batch)** | A received quantity of a product with an expiry date. Stock is the sum of lots (Phase 5). |
| **FEFO** | First-Expired-First-Out: sell the lot that expires soonest first. |
| **Landmark** | "Point de repère" — free text that actually finds the house ("derrière la pharmacie Ibn Sina"). |

## 2. Business rules (the ones code must enforce)

### Catalog
1. Categories form a **two-level tree**: root `Épicerie` (GROCERY) and `Beauté` (BEAUTY) → children. Admin-editable. A child's `type` equals its parent's.
2. Every product belongs to exactly one **leaf** category. Product names exist in **French (required)** and **Arabic (optional)**; clients fall back ar → fr.
3. **Heavy-item ban:** a product's `unitWeightGrams` must be ≤ `maxItemWeightGrams` ⚙ **5000** → otherwise `422 PRODUCT_TOO_HEAVY`. (Enforced on create *and* update.)
4. **Order weight cap:** total order weight must be ≤ `maxOrderWeightGrams` ⚙ **15000** → `422 ORDER_TOO_HEAVY` (Phase 3).
5. `price > 0`; `compareAtPrice` (the "was" price) must be greater than `price` when present.
6. `slug` is unique, URL-safe, generated once from the French name (accents stripped) and stable afterwards. `sku` unique (auto-generated `GRC-…`/`BTY-…` if blank). `barcode` unique when present.
7. Public API never reveals exact stock — only `availability`: `IN_STOCK` / `LOW_STOCK` / `OUT_OF_STOCK` — plus `maxOrderQuantity`.
8. "Delete" is a **soft delete** (`active=false`); admins can restore. Inactive products vanish from the public API and cannot be ordered, but stay in order history.
9. Cosmetics and food `attributes` (JSONB): `halal` (bool), `origin` (ISO country), `ingredients`, `skinType[]`, `hairType[]`, `shade`, `vegan`, `alcoholFree`, `allergens[]`, `shelfLifeMonthsAfterOpening`. Free-form but **documented**; the AI assistant (Phase 13) reads these.

### Orders & delivery (Phase 3)
10. Minimum order amount per zone ⚙ **1 500 DA**; delivery fee per zone ⚙ (centre 200 DA, outskirts 300 DA); free delivery above a threshold ⚙ **5 000 DA**.
11. A slot has `capacity`; booking beyond capacity → `409 SLOT_FULL`. Slots can't be booked inside the lead time ⚙ **90 min** or after the day's cut-off ⚙ **19:00 Africa/Algiers**. Opening hours ⚙ 09:00–21:00; Ramadan hours are a configuration change, not a code change.
12. **Prices are always computed by the server** from the database at order time. Clients never send prices. Order lines store a **snapshot** (name, unit price, unit, weight) so later catalog edits don't rewrite history.
13. Stock is **reserved (decremented) when the order is placed** and **restored** on cancellation. Never oversell: concurrent orders for the last unit → exactly one succeeds, the other gets `409 INSUFFICIENT_STOCK`.
14. **Weight-based lines:** the customer orders an *estimated* quantity; staff records the **fulfilled quantity** while packing; the final total is recomputed; for COD the driver collects the final total. A tolerance ⚙ ±20 % applies before the customer must be notified.
15. **Substitution policy** per line: `ALLOW_SIMILAR` · `REMOVE_ITEM` · `CALL_ME`. Default `CALL_ME`.
16. Order cancellation: customer may cancel until `PREPARING`; staff may cancel any time with a mandatory reason. Cancelling restores stock (and, once online payment exists, triggers a refund).
17. **COD reality:** failed deliveries (`DELIVERY_FAILED`: no answer, refused, wrong address) are a normal outcome. Three failed COD orders for the same phone number in 30 days flags the customer for manual confirmation ⚙.
18. Order numbers are human-friendly and non-guessable-in-sequence enough for a phone call: `ATL-YYMMDD-XXXX` (e.g. `ATL-261005-7K2F`); the numeric id stays internal.
19. Idempotent placement: the same `Idempotency-Key` returns the original order, never a second one.

### Identity
20. Roles: `CUSTOMER`, `ADMIN`, and from Phase 3 `STAFF` (prepares orders) and `DRIVER` (delivers). A user has exactly one role.
21. Phone is the delivery contact. Accepted forms normalise to **`+213` + 9 digits** for mobiles (`05/06/07…`). Regex after removing spaces/dots/dashes/parentheses: `^(?:\+213|00213|0)([567]\d{8})$`. Duplicate phone → `409 PHONE_ALREADY_IN_USE`. Login stays email + password (OTP/phone login is a backlog item).
22. Passwords ≥ 8 chars (BCrypt). Login rate-limited (5 failures / email / 15 min; 20 attempts / IP / min).

## 3. Role permissions (target)

| Capability | Anonymous | CUSTOMER | STAFF | DRIVER | ADMIN |
|---|:-:|:-:|:-:|:-:|:-:|
| Browse catalog, categories | ✅ | ✅ | ✅ | ✅ | ✅ |
| Register / login | ✅ | – | – | – | – |
| Own cart, addresses, orders; place/cancel own order | ❌ | ✅ | – | – | – |
| See **all** orders, move status to PREPARING/READY, record weights | ❌ | ❌ | ✅ | ❌ | ✅ |
| See **assigned** orders, mark OUT_FOR_DELIVERY/DELIVERED/DELIVERY_FAILED, record cash | ❌ | ❌ | ❌ | ✅ | ✅ |
| Manage products, categories, stock, lots | ❌ | ❌ | stock only | ❌ | ✅ |
| Manage zones, slots, settings, users, reports, audit log | ❌ | ❌ | ❌ | ❌ | ✅ |

## 4. Data model — PostgreSQL (relational, strongly consistent)

> Columns shown are the important ones. Add `created_at`, `updated_at` (TIMESTAMPTZ) and `version` (BIGINT) where entities mutate.

### Phase 1 (migration `V2__grocery_beauty_catalog.sql`, verified against Postgres 16)
| Table | Key columns |
|---|---|
| `users` | id, full_name, email (unique), **phone** (unique, nullable, `+213…`), password_hash, role, enabled |
| `categories` | id, parent_id, slug (unique), type `GROCERY\|BEAUTY`, name_fr, name_ar, sort_order, active |
| `products` | id, name_fr, name_ar, description_fr, description_ar, slug, sku, barcode, brand, category_id, **price**, compare_at_price, **sold_by** `UNIT\|WEIGHT`, quantity_step, **unit_weight_grams**, stock_quantity `NUMERIC(10,3)`, low_stock_threshold, perishable, featured, **attributes JSONB**, active, version |
| `product_images` | id, product_id, storage_key, alt_fr, alt_ar, sort_order |

### Phase 3
| Table | Key columns |
|---|---|
| `store_settings` | single row: max_item_weight_grams, max_order_weight_grams, lead_time_minutes, cutoff_time, opening/closing time, cod_change_tolerance, … (typed, not key/value soup) |
| `delivery_zones` | id, slug, name_fr, name_ar, fee, min_order_amount, free_delivery_threshold, eta_minutes, active |
| `delivery_slots` | id, zone_id (nullable = all zones), day_of_week, start_time, end_time, capacity, active |
| `addresses` | id, user_id, label, recipient_name, phone, zone_id, street, **landmark**, building_notes, lat/lng (nullable), is_default |
| `carts`, `cart_items` | cart per user; items: product_id, quantity, substitution_policy |
| `orders` | id, **order_number**, user_id, status, zone_id, address snapshot (recipient, phone, street, landmark, lat/lng), slot_date, slot_start, slot_end, subtotal, delivery_fee, total, total_weight_grams, payment_method, customer_note, change_for, assigned_driver_id, idempotency_key (unique per user), cancelled_reason |
| `order_items` | id, order_id, product_id, **snapshot** (name_fr, name_ar, sku, sold_by, unit_price, unit_weight_grams), quantity_ordered, quantity_fulfilled, line_total, substitution_policy, status `PENDING\|PACKED\|OUT_OF_STOCK\|SUBSTITUTED` |
| `order_status_history` | id, order_id, from_status, to_status, actor_id, reason, at |
| `payments` | id, order_id, method, status, amount, collected_by, collected_at, provider_ref |
| `stock_movements` | id, product_id, delta, reason `SALE\|CANCEL_RESTORE\|RESTOCK\|ADJUSTMENT\|EXPIRED`, order_id?, actor_id, at — **the ledger; stock_quantity is a cached sum** |
| `idempotency_records` | key, user_id, request_hash, response_status, response_body, created_at |

### Phase 5
`inventory_lots` (product_id, lot_code, quantity_remaining, received_at, **expiry_date**), `refresh_tokens` (or Redis only), `payment_attempts`/`webhook_events` (provider events, **unique event id** for idempotent processing), image metadata.

### Phase 7+
Per-service databases (`identity`, `catalog`, `orders`, `notification`) · `event_publication` (Modulith outbox) · `notifications` (user_id, channel, template, status, payload) · `saga_state` for stock reservation.

### Phase 13
`knowledge_documents` + `vector_store` (pgvector; policy/FAQ/product embeddings, language, source id, content hash) · chat memory table.

## 5. MongoDB (documents — only these two things)

| Collection | Shape |
|---|---|
| `audit_logs` | `{ actorId, actorRole, action, entityType, entityId, before?, after?, ip, at }` — TTL/retention policy |
| `chat_conversations` | `{ sessionId, userId?, language, messages[{role, content, toolCalls?, at}], feedback?, startedAt }` — PII-minimised, retention-limited |

## 6. State machines

**Order status**
```
PLACED ──► CONFIRMED ──► PREPARING ──► READY_FOR_DELIVERY ──► OUT_FOR_DELIVERY ──► DELIVERED
  │            │             │                  │                     │
  └────────────┴─────────────┴──────────┬───────┴─────────────────────┤
                                        ▼                             ▼
                                   CANCELLED                   DELIVERY_FAILED ──► (retry: READY_FOR_DELIVERY) or CANCELLED
```
Transitions are an explicit allow-table in code (`from → {to}`), each requires a role, each is recorded in `order_status_history`.
Anything else → `409 INVALID_STATE_TRANSITION`. Customers may cancel only while `PLACED`/`CONFIRMED`.

**Payment (COD):** `PENDING → COLLECTED` (driver confirms cash) · `PENDING → NOT_COLLECTED` (failed delivery).
**Payment (card, Phase 5):** `PENDING → PAID | FAILED | REFUNDED`, driven by **verified webhooks**, never by the browser's redirect.

## 7. Stable error codes (extend as needed; never rename)

`VALIDATION_FAILED` · `INVALID_SORT` · `UNAUTHENTICATED` · `FORBIDDEN` · `INVALID_CREDENTIALS` · `ACCOUNT_DISABLED` ·
`EMAIL_ALREADY_IN_USE` · `PHONE_ALREADY_IN_USE` · `TOO_MANY_REQUESTS` · `PRODUCT_NOT_FOUND` · `CATEGORY_NOT_FOUND` ·
`PRODUCT_TOO_HEAVY` · `CONCURRENT_MODIFICATION` · `SLUG_CONFLICT` · `SKU_CONFLICT` · `INSUFFICIENT_STOCK` · `PRODUCT_UNAVAILABLE` · `ORDER_NOT_FOUND` ·
`ORDER_BELOW_MINIMUM` · `ORDER_TOO_HEAVY` · `ZONE_NOT_SERVED` · `SLOT_FULL` · `SLOT_UNAVAILABLE` · `INVALID_STATE_TRANSITION` ·
`IDEMPOTENCY_KEY_REUSED` · `PAYMENT_FAILED` · `INTERNAL_ERROR`

## 8. Demo seed data (dev profile only — **illustrative**; have a native speaker review the Arabic)

Use **no real brand names**. Prices are plausible demo values in DA.

| Category (slug) | Product (fr / ar) | sold_by | Price | Weight g | Notes |
|---|---|---|---:|---:|---|
| fruits-legumes | Tomates / طماطم | WEIGHT (step 0.5) | 80 /kg | 1000 | perishable |
| fruits-legumes | Pommes de terre / بطاطا | WEIGHT | 60 /kg | 1000 | |
| fruits-legumes | Oranges / برتقال | WEIGHT | 120 /kg | 1000 | perishable |
| produits-laitiers-oeufs | Lait demi-écrémé 1 L / حليب نصف منزوع الدسم 1 لتر | UNIT | 120 | 1050 | perishable |
| produits-laitiers-oeufs | Œufs ×6 / بيض ×6 | UNIT | 110 | 400 | perishable |
| epicerie-salee | Couscous fin 1 kg / كسكس رفيع 1 كغ | UNIT | 130 | 1000 | |
| epicerie-salee | Semoule moyenne 1 kg / سميد متوسط 1 كغ | UNIT | 95 | 1000 | |
| epicerie-sucree | Dattes Deglet Nour 500 g / تمر دقلة نور 500 غ | UNIT | 450 | 520 | origin DZ |
| huiles-condiments-epices | Huile d'olive extra vierge 500 ml / زيت الزيتون البكر الممتاز 500 مل | UNIT | 1 050 | 650 | featured |
| huiles-condiments-epices | Harissa 380 g / هريسة 380 غ | UNIT | 180 | 450 | |
| boissons | Café moulu 250 g / قهوة مطحونة 250 غ | UNIT | 420 | 260 | |
| boissons | Thé vert 100 g / شاي أخضر 100 غ | UNIT | 250 | 110 | |
| naturel-traditionnel | Huile d'argan pure 50 ml / زيت الأركان الصافي 50 مل | UNIT | 1 800 | 120 | skinType dry/normal, vegan |
| naturel-traditionnel | Eau de rose 200 ml / ماء الورد 200 مل | UNIT | 400 | 280 | alcoholFree |
| naturel-traditionnel | Henné naturel 100 g / حناء طبيعية 100 غ | UNIT | 200 | 110 | |
| soins-visage | Crème hydratante visage 50 ml / كريم مرطب للوجه 50 مل | UNIT | 1 200 | 110 | skinType normal/dry |
| cheveux | Shampooing au karité 250 ml / شامبو بزبدة الشيا 250 مل | UNIT | 700 | 300 | |
| soins-corps | Gel douche 250 ml / جل استحمام 250 مل | UNIT | 450 | 290 | |
| maquillage | Rouge à lèvres mat / أحمر شفاه مات | UNIT | 900 | 30 | shade |
| hygiene-personnelle | Savon noir beldi 200 g / صابون أسود بلدي 200 غ | UNIT | 450 | 230 | |

## 9. Delivery zone seed (dev) — **replace with your real service area**
`annaba-centre` (200 DA, min 1 500), `el-bouni` (250 DA), `sidi-amar` (300 DA), `el-hadjar` (300 DA). Slots: 10:00–12:00, 12:00–14:00, 14:00–16:00, 16:00–18:00, 18:00–20:00, capacity 8 each.
