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
`429` also sends `Retry-After: <seconds>` (exposed through CORS). Error `code`s: `02-DOMAIN-AND-DATA-MODEL.md` §7.

---

## 1. Auth & identity — Phase 1

| Method & path | Auth | Request | Success | Notable errors |
|---|---|---|---|---|
| `POST /api/auth/register` | public | `{fullName, email, phone, password}` | `201 AuthResponse` | `400 VALIDATION_FAILED`, `409 EMAIL_ALREADY_IN_USE`, `409 PHONE_ALREADY_IN_USE`, `429` |
| `POST /api/auth/login` | public | `{email, password}` | `200 AuthResponse` | `400`, `401 INVALID_CREDENTIALS`, `401 ACCOUNT_DISABLED`*, `429 TOO_MANY_REQUESTS` |
| `GET /api/auth/me` | any user | – | `200 UserResponse` | `401 UNAUTHENTICATED` |

\* `ACCOUNT_DISABLED` must not reveal whether the password was right; return it only after a correct password.

```jsonc
// AuthResponse
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresInSeconds": 3600,
  "user": { "id": 7, "fullName": "Yasmine Amrani", "email": "yasmine@example.com",
            "phone": "+213551234567", "role": "CUSTOMER" } }
```
Validation: `fullName` 1–120 chars · `email` valid, stored trimmed + lower-case · `phone` accepts `0551234567`, `0551 23 45 67`, `+213 551 23 45 67`,
`00213…` → stored `+213551234567` · `password` ≥ 8 chars. JWT lifetime 60 min (refresh tokens arrive in Phase 5).

---

## 2. Catalog (public) — Phase 1

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

## 4. Delivery, cart, orders — Phase 3 (consumed by the frontend in Phase 4)

**Account required to order** (no guest checkout). Guest carts live in the browser and are merged on login.

### Delivery (public)
`GET /api/delivery/zones` → `[{id, slug, name{fr,ar}, fee, minOrderAmount, freeDeliveryThreshold, etaMinutes}]`
`GET /api/delivery/slots?zoneId=&date=YYYY-MM-DD` → `[{slotId, start:"14:00", end:"16:00", remaining: 3, available: true, reason?: "FULL|PAST_CUTOFF|TOO_SOON|CLOSED"}]`

### Addresses (customer) `/api/me/addresses`
`GET` list · `POST` · `PUT /{id}` · `DELETE /{id}` · `POST /{id}/default`. Body `{label, recipientName, phone, zoneId, street, landmark, buildingNotes?, lat?, lng?}`.
Another user's address id → `404`.

### Cart (customer)
`GET /api/cart` → `{ items:[{productId, product: ProductResponse-lite, quantity, substitutionPolicy, lineTotal, warnings:[ "PRICE_CHANGED"|"LOW_STOCK"|"UNAVAILABLE" ]}], subtotal, totalWeightGrams, issues:[…] }`
`PUT /api/cart/items/{productId}` `{quantity, substitutionPolicy?}` (quantity must respect `quantityStep`, ≤ `maxOrderQuantity`; `0` removes) · `DELETE /api/cart/items/{productId}` · `DELETE /api/cart` ·
`POST /api/cart/merge` `{items:[{productId, quantity}]}` (guest → account; clamps and reports what it could not add).

### Orders (customer)
`POST /api/orders` — header **`Idempotency-Key: <uuid>`** required. Body:
```jsonc
{ "addressId": 12, "slotId": 4, "slotDate": "2026-10-06", "paymentMethod": "CASH_ON_DELIVERY",
  "customerNote": "Appelez avant d'arriver", "changeFor": 5000 }      // items come from the SERVER cart; prices are never sent
```
→ `201 OrderResponse` · `409 INSUFFICIENT_STOCK` (with `details:[{productId, available}]`) · `409 SLOT_FULL` · `422 ORDER_BELOW_MINIMUM` · `422 ORDER_TOO_HEAVY` · `422 ZONE_NOT_SERVED` ·
`409 IDEMPOTENCY_KEY_REUSED` (same key, different body) · replaying the same key + body returns the **original** order with `200`.

```jsonc
// OrderResponse
{ "orderNumber": "ATL-261005-7K2F", "status": "PLACED", "createdAt": "…",
  "items": [ { "id": 91, "name": {"fr":"Tomates","ar":"طماطم"}, "soldBy": "WEIGHT", "unitPrice": 80.00,
               "quantityOrdered": 1.5, "quantityFulfilled": null, "lineTotal": 120.00,
               "substitutionPolicy": "CALL_ME", "status": "PENDING" } ],
  "subtotal": 120.00, "deliveryFee": 200.00, "total": 320.00, "totalIsEstimate": true,   // true while any WEIGHT line is unfulfilled
  "paymentMethod": "CASH_ON_DELIVERY", "paymentStatus": "PENDING",
  "delivery": { "zone": {"id":1,"name":{"fr":"Annaba centre","ar":"عنابة وسط"}}, "recipientName": "…", "phone": "+2135…", "street": "…", "landmark": "…",
                "slotDate": "2026-10-06", "slotStart": "14:00", "slotEnd": "16:00" },
  "history": [ { "status": "PLACED", "at": "…" } ] }
```
`GET /api/orders?page&size` (mine, newest first) · `GET /api/orders/{orderNumber}` (**404 if not mine**) · `POST /api/orders/{orderNumber}/cancel` `{reason?}` (`409 INVALID_STATE_TRANSITION` after PREPARING) ·
`POST /api/orders/{orderNumber}/reorder` → rebuilds the cart from a past order, returns `CartResponse` with warnings.

### Staff / admin / driver — Phase 3
`GET /api/admin/orders?status&date&zoneId&q&assignedDriverId` (STAFF, ADMIN) · `GET /api/admin/orders/{orderNumber}` ·
`POST /api/admin/orders/{orderNumber}/transition` `{to, reason?}` (allow-table + role per transition) ·
`PATCH /api/admin/orders/{orderNumber}/items/{itemId}` `{quantityFulfilled?, status?, substituteProductId?}` (recomputes totals) ·
`POST /api/admin/orders/{orderNumber}/assign-driver` `{driverId}` ·
`GET /api/driver/orders` (own, active) · `POST /api/driver/orders/{orderNumber}/start` · `/deliver` `{cashCollected}` · `/fail` `{reason}` ·
`POST /api/admin/products/{id}/stock-adjustments` `{delta, reason, note?}` · `GET /api/admin/products/{id}/stock-movements` ·
Admin CRUD: `/api/admin/delivery/zones`, `/api/admin/delivery/slots`, `/api/admin/settings` (GET/PUT) · `GET /api/admin/users?role=` (list drivers/staff).

---

## 5. Later phases (shapes defined when the phase starts)

| Phase | Endpoints |
|---|---|
| 5 | `POST /api/auth/refresh`, `POST /api/auth/logout` · `POST /api/orders/{n}/pay` → `{checkoutUrl}` · `POST /api/webhooks/payments` (signature-verified) · `POST /api/admin/uploads/product-image` → signed upload URL · `GET/POST /api/admin/products/{id}/lots` · `GET /api/admin/audit-logs` |
| 6 | `GET /api/admin/reports/{sales\|top-products\|low-stock\|expiring}` · `GET /api/admin/dashboard` |
| 7–8 | `GET /api/orders/{n}/events` (SSE) · `GET /api/me/notifications`, `POST …/{id}/read` · `POST /api/me/push-subscriptions` |
| 13–14 | `POST /api/assistant/chat` (SSE stream of tokens + structured cards) · `POST /api/assistant/feedback` |
