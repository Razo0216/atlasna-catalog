# Atlasna Catalog (backend)

The backend for **Atlasna**, a single mini-mart in Annaba, Algeria, selling groceries and beauty / personal-care
products online. This service holds the **catalog** (categories and products, in French and Arabic) and
**accounts** (register, login, JWT).

Spring Boot 4.1 · Java 21 · PostgreSQL 16 · Flyway · Spring Security (stateless JWT) · Spring Modulith · Redis 7 ·
MongoDB 8 · Resilience4j · Quartz · Testcontainers.

Start with [`docs/README.md`](docs/README.md) and [`docs/00-MASTER-PLAN.md`](docs/00-MASTER-PLAN.md).
The REST contract the frontend codes against is [`docs/03-API-CONTRACT.md`](docs/03-API-CONTRACT.md).

## Requirements

- JDK 21 or newer (the code targets 21)
- Docker Desktop running (for the local database **and** for the tests)

## Run it locally

```bash
docker compose up -d          # PostgreSQL 16 :5432, Redis 7 :6379, MongoDB 8 :27017 (dev credentials, not used anywhere real)
./mvnw spring-boot:run        # starts on http://localhost:8080 with the "dev" profile
```

On start, Flyway builds the schema (V1, V2) and Hibernate validates it. The `dev` profile also:

- creates the dev admin: **`admin@atlasna.dz` / `ChangeMe123!`** (from `application-dev.yml`; local only)
- creates a dev staff account **`staff@atlasna.dz`** and a dev driver **`driver@atlasna.dz`** (same password; local only)
- seeds about 20 demo products if the table is empty

Customers sign up through `POST /api/auth/register` (a valid Algerian mobile number is required).

Check it's up: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.

Reset all local data: `docker compose down -v` (the schema is rebuilt on the next start).

In IntelliJ, run `AtlasnaCatalogApplication` with **Active profiles: `dev`**; without it the app has no
database credentials and no JWT secret and refuses to start (on purpose).

## Tests

```bash
./mvnw clean verify
```

Integration tests run against real PostgreSQL, Redis and MongoDB in Testcontainers containers, so Docker must be running.
The payment-gateway tests (Phase 5) use WireMock as a fake provider: no real gateway is ever called.

## API at a glance

| Route | Who |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | public |
| `GET /api/auth/me` | any logged-in user |
| `GET /api/categories`, `GET /api/products`, `GET /api/products/{id}`, `GET /api/products/by-slug/{slug}` | public |
| `/api/admin/categories/**`, `/api/admin/products/**` | `ADMIN` |
| `GET /api/delivery/zones`, `GET /api/delivery/slots` | public |
| `/api/cart/**`, `/api/me/addresses/**`, `/api/orders/**` | `CUSTOMER` (own data only; someone else's → 404) |
| `/api/admin/orders/**`, stock adjustments and movements | `STAFF`, `ADMIN` |
| `/api/driver/orders/**` | `DRIVER` (assigned orders only) |
| `/api/admin/settings`, `/api/admin/delivery/**`, `/api/admin/users/**` | `ADMIN` |

Every error has the same JSON shape with a stable `code` (e.g. `PRODUCT_TOO_HEAVY`, `PHONE_ALREADY_IN_USE`) that the
frontend translates. Full details: [`docs/03-API-CONTRACT.md`](docs/03-API-CONTRACT.md).

A Postman collection is in [`docs/postman/`](docs/postman/): import it, run **Auth → Login as admin** first, then
the other folders (the token is saved to a collection variable automatically).

## Orders (Phase 3)

Customers pick an Annaba zone and a delivery slot, fill a server-side cart and place a **cash-on-delivery** order:
`POST /api/orders` with an `Idempotency-Key` header (a retry never creates a second order). The server prices everything,
books the slot and takes the stock in one transaction — concurrent buyers can't oversell the last item or overfill a slot.
Staff confirm, prepare (weighing per-kg lines) and mark orders ready; drivers deliver and record the cash; cancelling puts
stock back. Every stock change is in a ledger (`stock_movements`). Shop rules (minimum order 2 000 DA, fees, three delivery rounds a day — 10–12, 14–16, 18–20 —
hours, cut-off, weight limits) are **settings in the database**, edited with `/api/admin/settings` and
`/api/admin/delivery/**`. Details: `docs/03-API-CONTRACT.md` §4, ADRs 0005–0008, module map in `docs/modules/`.

## Data layer & resilience (Phase 5)

- **Catalog cache (Redis).** The public catalog (`/api/categories`, `/api/products…`) is cached in Redis for 1–5 minutes.
  Any product, category, stock or settings change makes the cache start fresh (a "generation" number in every key), so
  admin edits and sold-out products show on the next request. If Redis is down, a circuit breaker sends reads straight
  to Postgres: slower, never broken. Carts and orders never read the cache. Details: ADR-0010.
- **Rate limits (Redis).** Login (5 wrong passwords per account per 15 min, 20 attempts per IP per minute), sign-up
  (10 per IP per hour) and order placement (10 per customer per hour) are token buckets in Redis, shared by every
  instance and kept across restarts. If Redis is down, login is refused (503) rather than left unthrottled; sign-up and
  orders go through. The client IP only comes from `X-Forwarded-For` when the request arrives from a configured trusted
  proxy (`ATLASNA_TRUSTED_PROXIES`). Details: ADR-0011.
- **Refresh tokens (Redis).** Login and sign-up also set a 30-day `atlasna_refresh` cookie (httpOnly, SameSite=Strict,
  only sent to `/api/auth`). `POST /api/auth/refresh` (with header `X-Atlasna-Refresh: 1`) returns a new access token and
  the next refresh token; each one works once, and replaying a used one logs that session out everywhere it was copied.
  `POST /api/auth/logout` ends the session; a password change or a disabled account ends all of them. Only token hashes
  are stored. The storefront switches to it in Phase 6. Details: ADR-0009; try it with Postman folder 7.

- **Audit log (MongoDB).** Who changed what and when — product/category edits (changed fields only), stock adjustments,
  order placement and status changes, settings, staff accounts, failed logins — written from application events after
  commit, on another thread; MongoDB down never fails an operation (failures are counted and re-delivered at startup).
  `GET /api/admin/audit-logs` (ADMIN). Entries expire after 365 days. ADR-0012.
- **Card payment (sandbox).** `CARD_ONLINE` orders (not for per-kg items) → `POST /api/orders/{n}/pay` → the gateway's
  page; only the provider's signed webhook marks the order PAID (once per event, amount checked). Timeouts, bulkhead,
  circuit breaker; a slow or dead gateway answers 503 at once and the customer can switch to cash. Unpaid card orders
  are cancelled after 30 min. Set `CHARGILY_SECRET_KEY` (test key) to enable it. ADR-0013.
- **Product photos.** Signed upload link → the browser uploads straight to storage → attach to the product. Checked by
  content (JPEG/PNG/WebP magic bytes, ≤ 5 MB, ≤ 4000 px), keys generated by the server; abandoned uploads are deleted.
  Stored in `var/media` locally (GCS in Phase 11). ADR-0014.
- **Lots, FEFO, expiry.** Deliveries are lots with an expiry date (`POST /api/admin/products/{id}/lots`); sales take the
  lot that expires first and never an expired one; an hourly job writes expired lots off; reports for "expiring soon"
  and "low stock". Stock = Σ lots = Σ ledger, always. ADR-0015.
- **Search.** Accents, Arabic diacritics and letter variants don't matter, typos are tolerated, best matches first —
  Postgres `unaccent` + `pg_trgm` with a trigram index. ADR-0016.

### Trying a card payment locally
The gateway can't call `localhost`, so either expose the API with a tunnel (e.g. `cloudflared tunnel --url
http://localhost:8080`) and set `ATLASNA_PAYMENT_WEBHOOK_URL=https://<tunnel>/api/webhooks/payments`, or simulate the
webhook yourself — sign the exact body with your test secret key:
```bash
BODY='{"id":"evt_1","type":"checkout.paid","data":{"id":"<checkout id>","amount":2600},"created_at":'$(date +%s)'}'
SIG=$(printf %s "$BODY" | openssl dgst -sha256 -hmac "$CHARGILY_SECRET_KEY" | sed 's/.* //')
curl -X POST localhost:8080/api/webhooks/payments -H "signature: $SIG" -H 'Content-Type: application/json' -d "$BODY"
```

## Configuration

| Property | Env var | Default |
|---|---|---|
| `atlasna.jwt.secret` | `ATLASNA_JWT_SECRET` | none outside `dev` (startup fails without it) |
| `atlasna.cors.allowed-origins` | `ATLASNA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` |
| Datasource | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | none outside `dev` |
| First admin (any env) | `ATLASNA_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | dev values in `application-dev.yml` |
| Redis | `SPRING_DATA_REDIS_HOST` / `_PORT` / `_PASSWORD` | dev values in `application-dev.yml` |
| MongoDB | `SPRING_MONGODB_URI` | dev value in `application-dev.yml` |
| `atlasna.cache.enabled` / `…-ttl` | `ATLASNA_CACHE_ENABLED` | `true`; categories 5 min, product 2 min, lists 1 min |
| `atlasna.rate-limit.*` | — | see ADR-0011 (dev profile raises sign-ups to 1000/h for Postman) |
| `atlasna.http.trusted-proxies` | `ATLASNA_TRUSTED_PROXIES` | empty: `X-Forwarded-For` ignored |
| `atlasna.refresh-token.lifetime` / `cookie-secure` | — | `30d` / `true` (`false` in dev for Safari on http://localhost) |
| Card payment | `CHARGILY_SECRET_KEY`, `CHARGILY_BASE_URL`, `ATLASNA_PAYMENT_WEBHOOK_URL`, `ATLASNA_STOREFRONT_URL` | no key = card not offered; sandbox base URL |
| Media | `ATLASNA_MEDIA_DIR`, `ATLASNA_PUBLIC_API_URL` | `var/media`, `http://localhost:8080` |
| `atlasna.audit.retention` / `atlasna.inventory.expiring-soon-days` | — | `365d` / `3` |

## Design decisions

See [`docs/adr/`](docs/adr/).
