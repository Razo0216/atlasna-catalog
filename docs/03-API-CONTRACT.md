# Atlasna — REST API Contract

**Source of truth for backend ↔ frontend.** Backend repo owns this file; copy it into the frontend repo whenever it changes.
Update it **before** changing code. Each endpoint is tagged with the phase that delivers it. Rules for status codes, pagination,
errors, i18n shapes: `01-CONVENTIONS.md` §4.

---

## 0. Shared shapes

```jsonc
// Localised text — ar may be null; clients fall back ar → fr
{ "fr": "Lait demi-écrémé 1 L", "ar": "حليب نصف منزوع الدسم 1 لتر" }

// Page<T>  (spring.data.web.pageable.serialization-mode: via-dto, page is 0-based)
{ "content": [ /* T */ ], "page": { "size": 20, "number": 0, "totalElements": 57, "totalPages": 3 } }

// ApiError — every non-2xx. `code` is stable and machine-readable; `message` is English for developers.
{ "timestamp": "2026-10-05T10:15:30.123Z", "status": 400, "error": "Validation Failed",
  "code": "VALIDATION_FAILED", "message": "One or more fields are invalid",
  "fieldErrors": { "phone": "Enter a valid Algerian mobile number" } }   // fieldErrors only for validation
```
`429` also sends `Retry-After: <seconds>` (exposed through CORS). Rate limits (Phase 5, ADR-0011) are token buckets:
login 5 wrong passwords/account/15 min and 20 attempts/IP/min · sign-up 10/IP/hour · order placement 10/customer/hour. Error `code`s: `02-DOMAIN-AND-DATA-MODEL.md` §7.

---

## 1. Auth & identity — Phase 1

| Method & path | Auth | Request | Success | Notable errors |
|---|---|---|---|---|
| `POST /api/auth/register` | public | `{fullName, email, phone, password}` | `201 AuthResponse` | `400 VALIDATION_FAILED`, `409 EMAIL_ALREADY_IN_USE`, `409 PHONE_ALREADY_IN_USE`, `429 TOO_MANY_REQUESTS` (10 sign-ups/IP/hour) |
| `POST /api/auth/login` | public | `{email, password}` | `200 AuthResponse` | `400`, `401 INVALID_CREDENTIALS`, `401 ACCOUNT_DISABLED`*, `429 TOO_MANY_REQUESTS`, `503 RATE_LIMIT_UNAVAILABLE` (+`Retry-After`; login is refused while its throttling store is down) |
| `GET /api/auth/me` | any user | – | `200 UserResponse` | `401 UNAUTHENTICATED` |
| `POST /api/auth/refresh` | refresh cookie + header `X-Atlasna-Refresh: 1` | – | `200 AuthResponse` + rotated cookie | `403 CSRF_CHECK_FAILED`, `401 REFRESH_TOKEN_INVALID`, `401 REFRESH_TOKEN_REUSED` (session family revoked), `401 ACCOUNT_DISABLED`, `503 SESSION_STORE_UNAVAILABLE` — every failure also clears the cookie |
| `POST /api/auth/logout` | header `X-Atlasna-Refresh: 1` (+ cookie) | – | `204` + cleared cookie | `403 CSRF_CHECK_FAILED` |

\* `ACCOUNT_DISABLED` must not reveal whether the password was right; return it only after a correct password.

```jsonc
// AuthResponse
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresInSeconds": 3600,
  "user": { "id": 7, "fullName": "Yasmine Amrani", "email": "yasmine@example.com",
            "phone": "+213551234567", "role": "CUSTOMER" } }
```
Validation: `fullName` 1–120 chars · `email` valid, stored trimmed + lower-case · `phone` accepts `0551234567`, `0551 23 45 67`, `+213 551 23 45 67`,
`00213…` → stored `+213551234567` · `password` ≥ 8 chars. JWT lifetime 60 min (15 min once the storefront uses refresh, Phase 6).

**Refresh tokens (Phase 5, ADR-0009).** Register and login also set
`Set-Cookie: atlasna_refresh=<opaque>; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=2592000` (30 days).
Browsers call refresh/logout with `credentials: 'include'` and the `X-Atlasna-Refresh: 1` header. Each refresh token
works **once**: the response carries the next one. Presenting a used token again revokes that login's whole session
family. Changing the password or an admin disabling the account revokes every session. The client should keep the access
token in memory and run at most one refresh at a time (two tabs refreshing with the same token = reuse = logged out).

---

## 2. Catalog (public) — Phase 1

> **Caching (Phase 5):** these four GETs are served from a Redis cache (categories 5 min, product 2 min, lists 1 min).
> Any catalog, stock or settings change invalidates it at commit, so responses are normally current; if an invalidation
> is missed (Redis briefly unreachable) a response can be up to one TTL old. Same shapes, same errors. `availability` /
> `maxOrderQuantity` are display hints — the cart and checkout always re-check against the database.

### `GET /api/categories` → `200 CategoryNode[]` (only active; sorted by `sortOrder`)
```jsonc
[ { "id": 1, "slug": "epicerie", "type": "GROCERY", "name": {"fr":"Épicerie","ar":"البقالة"}, "sortOrder": 1,
    "children": [ { "id": 3, "slug": "fruits-legumes", "type": "GROCERY",
                    "name": {"fr":"Fruits & légumes","ar":"فواكه وخضروات"}, "sortOrder": 1, "children": [] } ] },
  { "id": 2, "slug": "beaute", "type": "BEAUTY", "name": {"fr":"Beauté","ar":"الجمال"}, "sortOrder": 2, "children": [ /* … */ ] } ]
```

### `GET /api/products` → `200 Page<ProductResponse>` (active products only)
Query: `page`, `size` (≤100) · `sort` ∈ **`createdAt,desc` · `price,asc` · `price,desc` · `name,asc`** (anything else → `400 INVALID_SORT`) ·
`category` = category **slug** (a root slug includes all its children) · `type` = `GROCERY|BEAUTY` · `q` = search text (name fr/ar, brand, sku; case-insensitive, `%`/`_` escaped) ·
`inStock=true` · `featured=true`. **All filters combine** (AND). Unknown `category` slug → empty page, not an error.

### `GET /api/products/{id}` and `GET /api/products/by-slug/{slug}` → `200 ProductResponse` · `404 PRODUCT_NOT_FOUND` (missing **or inactive**) · `400` non-numeric id

```jsonc
// ProductResponse (public — no exact stock, no cost, no active flag)
{ "id": 17, "slug": "huile-d-argan-pure-50-ml", "sku": "BTY-NAT-0001", "barcode": null,
  "name": {"fr": "Huile d'argan pure 50 ml", "ar": "زيت الأركان الصافي 50 مل"},
  "description": {"fr": "…", "ar": null}, "brand": null,
  "category": { "id": 21, "slug": "naturel-traditionnel", "type": "BEAUTY",
                "name": {"fr": "Soins naturels & traditionnels", "ar": "العناية الطبيعية والتقليدية"} },
  "price": 1800.00, "compareAtPrice": null,
  "soldBy": "UNIT",            // UNIT = price per piece/pack · WEIGHT = price per kg
  "quantityStep": 1,           // 1 for pieces; 0.5 for loose produce
  "unitWeightGrams": 120,
  "availability": "IN_STOCK",  // IN_STOCK | LOW_STOCK | OUT_OF_STOCK
  "maxOrderQuantity": 10,      // min(stock, store limit) — what the UI may offer
  "perishable": false, "featured": true,
  "attributes": { "skinType": ["dry","normal"], "vegan": true },
  "images": [ { "url": "https://…/p/17-1.webp", "alt": {"fr": null, "ar": null} } ],   // [] until Phase 5 uploads
  "createdAt": "2026-10-05T10:15:30.123456Z" }
```

---

## 3. Catalog admin — Phase 1 (role `ADMIN`; `401` anonymous, `403` other roles)

| Method & path | Request | Success | Errors |
|---|---|---|---|
| `GET /api/admin/products` | filters as public + `active=true\|false\|all`, `lowStock=true` | `200 Page<AdminProductResponse>` | – |
| `GET /api/admin/products/{id}` | – | `200 AdminProductResponse` | `404` |
| `POST /api/admin/products` | `ProductRequest` | `201 AdminProductResponse` | `400`, `409 SKU_CONFLICT`, `404 CATEGORY_NOT_FOUND`, `422 PRODUCT_TOO_HEAVY` |
| `PUT /api/admin/products/{id}` | `ProductRequest` + `version` (full replace) | `200` | `400`, `404`, `409 CONCURRENT_MODIFICATION`, `422 PRODUCT_TOO_HEAVY` |
| `DELETE /api/admin/products/{id}` | – | `204` (soft delete) | `404` |
| `POST /api/admin/products/{id}/restore` | – | `200 AdminProductResponse` | `404` |
| `GET /api/admin/categories` | – | `200 CategoryNode[]` incl. inactive | |
| `POST/PUT /api/admin/categories[/{id}]`, `DELETE /api/admin/categories/{id}` | `{slug?, parentId, type, name{fr,ar}, sortOrder, active}` | `201/200/204` | `409` delete a category that still has products/children |

```jsonc
// ProductRequest
{ "name": {"fr": "Tomates", "ar": "طماطم"}, "description": {"fr": null, "ar": null}, "brand": null,
  "sku": null /* auto */, "barcode": null, "categoryId": 3, "price": 80.00, "compareAtPrice": null,
  "soldBy": "WEIGHT", "quantityStep": 0.5, "unitWeightGrams": 1000,
  "stockQuantity": 42.500, "lowStockThreshold": 5, "perishable": true, "featured": false,
  "attributes": { "origin": "DZ" }, "version": 3 /* PUT only */ }
// AdminProductResponse = ProductResponse + { "stockQuantity", "lowStockThreshold", "active", "updatedAt", "version" }
```
Validation: `name.fr` 1–200 · `price` > 0, ≤ 2 decimals, ≤ 10 integer digits · `stockQuantity` ≥ 0, ≤ 3 decimals · `unitWeightGrams` > 0 **and ≤ store max (default 5000)** ·
`soldBy=UNIT` ⇒ `quantityStep` is a whole number ≥ 1 · `categoryId` must be a **leaf** category · `compareAtPrice` > `price` when set.

---

## 4. Delivery, cart, orders — Phase 3 (built 2026-11-13; consumed by the frontend in Phase 4)

**Account required to order** (no guest checkout); `/api/cart`, `/api/me/**`, `/api/orders/**` are for the CUSTOMER role.
Guest carts live in the browser and are merged on login. Times are `"HH:mm"` in Africa/Algiers; dates `YYYY-MM-DD`.
Some errors carry `details` (e.g. `INSUFFICIENT_STOCK` → `[{productId, available}]`, `ORDER_BELOW_MINIMUM` → `[{minimum, missing}]`,
`SLOT_UNAVAILABLE` → `[{reason}]`, `ORDER_TOO_HEAVY` → `[{maxGrams, totalGrams}]`, `PRODUCT_UNAVAILABLE` → `[{productId}]`).
New codes in Phase 3: `CART_EMPTY`, `ADDRESS_NOT_FOUND`, `ORDER_ITEM_NOT_FOUND`, `ZONE_NOT_FOUND`, `SLOT_NOT_FOUND`, `USER_NOT_FOUND`.

### Delivery (public)
`GET /api/delivery/zones` → `[{id, slug, name{fr,ar}, fee, minOrderAmount, freeDeliveryThreshold, etaMinutes}]` (active zones)
`GET /api/delivery/slots?zoneId=&date=YYYY-MM-DD` → `[{slotId, start:"14:00", end:"16:00", remaining: 3, available: true, reason: null|"FULL"|"PAST_CUTOFF"|"TOO_SOON"|"CLOSED"}]`
(unknown/inactive zone → `422 ZONE_NOT_SERVED`)

### Addresses (customer) `/api/me/addresses`
`GET` list (default first) · `POST` → `201` · `PUT /{id}` · `DELETE /{id}` → `204` · `POST /{id}/default`.
Body `{label?, recipientName, phone, zoneId, street, landmark, buildingNotes?, lat?, lng?}` → `{id, label, recipientName, phone (+213…), zoneId, street, landmark, buildingNotes, lat, lng, isDefault}`.
Another user's address id → `404 ADDRESS_NOT_FOUND`. The first address becomes the default. At most 10.

### Cart (customer)
`GET /api/cart` → `CartResponse`:
```jsonc
{ "items": [ { "productId": 9, "product": ProductResponse | null /* null when no longer public */, "name": {"fr":"Tomates","ar":"طماطم"},
               "quantity": 1.5, "substitutionPolicy": "CALL_ME", "unitPrice": 80.00, "lineTotal": 120.00,
               "warnings": [ "PRICE_CHANGED" | "LOW_STOCK" | "UNAVAILABLE" ] } ],
  "subtotal": 120.00, "totalWeightGrams": 1500, "maxWeightGrams": 15000,   // the store's order weight cap (settings)
  "issues": [ { "productId": 12, "code": "UNAVAILABLE" | "QUANTITY_EXCEEDS_STOCK" } ],   // these block checkout
  "checkoutReady": true }
```
`PUT /api/cart/items/{productId}` `{quantity, substitutionPolicy?}` — snapped DOWN to `quantityStep`, clamped to `maxOrderQuantity`; `0` removes;
unknown product `404 PRODUCT_NOT_FOUND`; hidden or out of stock `422 PRODUCT_UNAVAILABLE` · `DELETE /api/cart/items/{productId}` · `DELETE /api/cart` ·
`POST /api/cart/merge` `{items:[{productId, quantity}]}` → `{cart: CartResponse, notes:[{productId, reason: "NOT_FOUND"|"UNAVAILABLE"|"QUANTITY_REDUCED", requested, added}]}`
(idempotent: a line ends at the larger quantity, never the sum). All cart reads re-price from the catalog.

### Orders (customer)
`POST /api/orders` — header **`Idempotency-Key: <uuid>`** required (missing → `400`, `fieldErrors["Idempotency-Key"]`). Body:
```jsonc
{ "addressId": 12, "slotId": 4, "slotDate": "2026-11-14", "paymentMethod": "CASH_ON_DELIVERY",
  "customerNote": "Appelez avant d'arriver", "changeFor": 5000 }      // items come from the SERVER cart; prices are never sent
```
→ `201 OrderResponse` · same key + same body again → `200` with the **original** order · same key + different body → `409 IDEMPOTENCY_KEY_REUSED` ·
`422 CART_EMPTY` · `404 ADDRESS_NOT_FOUND` · `422 ZONE_NOT_SERVED` · `409 PRODUCT_UNAVAILABLE` (hidden product) · `422 ORDER_BELOW_MINIMUM` ·
`422 ORDER_TOO_HEAVY` · `409 SLOT_UNAVAILABLE` (rules) · `409 SLOT_FULL` · `409 INSUFFICIENT_STOCK` (sold out meanwhile) ·
`429 TOO_MANY_REQUESTS` (more than 10 placement attempts in an hour, Phase 5).

```jsonc
// OrderResponse (captured from the real API)
{ "orderNumber": "ATL-261113-7K2F", "status": "PLACED", "createdAt": "…",
  "items": [ { "id": 91, "productId": 9, "name": {"fr":"Tomates","ar":"طماطم"}, "sku": "GRC-0001", "soldBy": "WEIGHT", "unitPrice": 80.00,
               "quantityOrdered": 1.5, "quantityFulfilled": null, "lineTotal": 120.00,
               "substitutionPolicy": "CALL_ME", "status": "PENDING", "substituteProductId": null } ],
  "subtotal": 2220.00, "deliveryFee": 200.00, "total": 2420.00, "totalIsEstimate": true,   // true while any WEIGHT line is unweighed
  "paymentMethod": "CASH_ON_DELIVERY", "paymentStatus": "PENDING", "customerNote": null, "changeFor": null,
  "delivery": { "zone": {"id":1,"name":{"fr":"Annaba centre","ar":"وسط عنابة"}}, "recipientName": "…", "phone": "+2135…", "street": "…",
                "landmark": "…", "buildingNotes": null, "slotDate": "2026-11-14", "slotStart": "10:00", "slotEnd": "12:00" },
  "history": [ { "status": "PLACED", "at": "…", "reason": null } ] }
```
`GET /api/orders?page&size` → page of `{orderNumber, status, createdAt, itemCount, total, totalIsEstimate, slotDate, slotStart, slotEnd}` (newest first) ·
`GET /api/orders/{orderNumber}` (**404 if not mine**) · `POST /api/orders/{orderNumber}/cancel` `{reason?}` → `OrderResponse` (`409 INVALID_STATE_TRANSITION` once PREPARING) ·
`POST /api/orders/{orderNumber}/reorder` → `{cart: CartResponse, notes:[…]}` (live prices; `PRICE_CHANGED` where the price moved since the order).

### Staff / admin / driver
Order board (STAFF, ADMIN): `GET /api/admin/orders?status&date&zoneId&q&assignedDriverId&page&size` → page of
`{orderNumber, status, createdAt, recipientName, phone, zoneId, slotDate, slotStart, slotEnd, total, totalIsEstimate, assignedDriverId, totalChangeFlagged}` ·
`GET /api/admin/orders/{n}` → `AdminOrderResponse {order: OrderResponse, customerId, assignedDriverId, estimatedTotal, totalChangeFlagged,
customerFlagged /* ≥3 failed COD deliveries for this phone in 30 days */, failedDeliveriesRecently, cashCollected, cancelledReason, failureReason, totalWeightGrams, version}` ·
`POST /api/admin/orders/{n}/transition` `{to, reason?, cashCollected?}` (allow-table + role; cancelling as staff needs a `reason`) ·
`PATCH /api/admin/orders/{n}/items/{itemId}` `{quantityFulfilled?, status?: "PACKED"|"OUT_OF_STOCK"|"SUBSTITUTED", substituteProductId?}` (PREPARING only; recomputes totals, moves stock) ·
`POST /api/admin/orders/{n}/assign-driver` `{driverId}`. All return `AdminOrderResponse`.
Driver (assigned orders only, others `404`): `GET /api/driver/orders` (READY/OUT/FAILED queue) · `GET /api/driver/orders/{n}` ·
`POST /api/driver/orders/{n}/start` · `/deliver` `{cashCollected, note?}` (a different amount needs a `note`) · `/fail` `{reason}`.
Stock (ADMIN; STAFF except `EXPIRED`): `POST /api/admin/products/{id}/stock-adjustments` `{delta, reason: "RESTOCK"|"ADJUSTMENT"|"EXPIRED", note?}` → `{productId, stockQuantity}` ·
`GET /api/admin/products/{id}/stock-movements?page&size`.
Admin only: `/api/admin/delivery/zones` and `/api/admin/delivery/slots` (GET, POST, PUT `/{id}` with `version`, DELETE = deactivate) ·
`GET/PUT /api/admin/settings` (full replace with `version`) · `POST /api/admin/users` `{fullName, email, phone?, role: STAFF|DRIVER}` →
`201 {user, temporaryPassword}` (shown once) · `GET /api/admin/users?role=` · `PATCH /api/admin/users/{id}` `{enabled}`.
Any signed-in user: `POST /api/auth/change-password` `{currentPassword, newPassword}` → `204`; `UserResponse` gains `mustChangePassword`.

---

## 5. Data layer & resilience — Phase 5 (built 2026-11-15; consumed by the frontend in Phase 6)

New error codes: `RATE_LIMIT_UNAVAILABLE` (503), `CSRF_CHECK_FAILED` (403), `REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_REUSED`
(401), `SESSION_STORE_UNAVAILABLE` (503), `CARD_PAYMENT_UNAVAILABLE` (422, `details:[{reason: NOT_CONFIGURED|ESTIMATED_TOTAL}]`),
`PAYMENT_UNAVAILABLE` (503), `PAYMENT_NOT_REQUIRED` / `ALREADY_PAID` (409), `WEBHOOK_SIGNATURE_INVALID` (401),
`WEBHOOK_INVALID` / `WEBHOOK_STALE` (400), `UPLOAD_URL_INVALID` (403), `UPLOAD_TYPE_MISMATCH` (400), `IMAGE_INVALID` (422),
`IMAGE_TOO_LARGE` (413), `IMAGE_NOT_UPLOADED` (422).

### Sessions (ADR-0009) — see §1
`POST /api/auth/refresh`, `POST /api/auth/logout` (cookie `atlasna_refresh` + header `X-Atlasna-Refresh: 1`).

### Card payment (ADR-0013, sandbox)
- `PlaceOrderRequest.paymentMethod`: `CASH_ON_DELIVERY` | **`CARD_ONLINE`** (not for orders with per-kg lines).
- `paymentStatus` gains `PAID`, `FAILED`, `REFUND_PENDING`.
- `POST /api/orders/{n}/pay` `{locale?: fr|ar|en}` → `200 {checkoutUrl, orderNumber, amountDzd}` (redirect the customer
  there; a second call within 30 min returns the same URL) · `409 PAYMENT_NOT_REQUIRED|ALREADY_PAID|INVALID_STATE_TRANSITION`
  · `503 PAYMENT_UNAVAILABLE` (offer cash).
- `POST /api/orders/{n}/pay-on-delivery` → `OrderResponse` with `paymentMethod: CASH_ON_DELIVERY` (before payment and
  confirmation only).
- The gateway returns the customer to `{storefront}/orders/{n}?payment=success|failed` — **display only**: the order is
  paid when `paymentStatus` becomes `PAID` (poll the order).
- `POST /api/webhooks/payments` (provider only; HMAC `signature` header over the raw body) → `200 {outcome:
  PROCESSED|DUPLICATE|IGNORED|AMOUNT_MISMATCH}`.
- Unpaid card orders are cancelled after 30 min. Drivers deliver card-paid orders without `cashCollected`.

### Product photos (ADR-0014; ADMIN, STAFF)
`POST /api/admin/uploads/product-image {productId, contentType: image/jpeg|png|webp, sizeBytes ≤ 5 MB}` →
`{key, url, method: "PUT", headers: {"Content-Type": …}, expiresAt}` → `PUT <url>` with the file bytes (10 min) →
`PUT /api/admin/products/{id}/images {images: [{key, alt?: {fr, ar}}]}` (display order; max 8; keep an image by sending its
key) → `AdminProductResponse`. `ProductImageResponse` gains `key`.

### Lots & stock reports (ADR-0015; ADMIN, STAFF)
`GET /api/admin/products/{id}/lots` → `LotView[]` (FEFO order) · `POST /api/admin/products/{id}/lots {quantity,
expiryDate? (required for perishables, not in the past), lotCode?, note?}` → `201 LotView` ·
`GET /api/admin/inventory/expiring?days=3` → `LotView[]` · `GET /api/admin/inventory/low-stock` →
`[{productId, sku, name, stockQuantity, lowStockThreshold}]`.
`LotView = {id, productId, lotCode, quantityReceived, quantityRemaining, receivedAt, expiryDate, note}`.

### Audit log (ADR-0012; ADMIN)
`GET /api/admin/audit-logs?entityType&entityId&actorId&action&from&to&page&size` → page of
`{id, at, actorId, actorRole, ip, action, entityType, entityId, before, after, details}`.

### Search (ADR-0016)
`GET /api/products?q=` (and the admin list) now ignores accents and Arabic diacritics/letter variants, tolerates typos
for word queries (≥ 3 letters), and ranks best matches first (then the requested sort). Shapes unchanged.

## 6. Later phases (shapes defined when the phase starts)

| Phase | Endpoints |
|---|---|
| 6 | `GET /api/admin/reports/{sales\|top-products\|low-stock\|expiring}` · `GET /api/admin/dashboard` |
| 7–8 | `GET /api/orders/{n}/events` (SSE) · `GET /api/me/notifications`, `POST …/{id}/read` · `POST /api/me/push-subscriptions` |
| 13–14 | `POST /api/assistant/chat` (SSE stream of tokens + structured cards) · `POST /api/assistant/feedback` |
