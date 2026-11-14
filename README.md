# Atlasna Catalog (backend)

The backend for **Atlasna**, a single mini-mart in Annaba, Algeria, selling groceries and beauty / personal-care
products online. This service holds the **catalog** (categories and products, in French and Arabic) and
**accounts** (register, login, JWT).

Spring Boot 4.1 · Java 21 · PostgreSQL 16 · Flyway · Spring Security (stateless JWT) · Testcontainers.

Start with [`docs/README.md`](docs/README.md) and [`docs/00-MASTER-PLAN.md`](docs/00-MASTER-PLAN.md).
The REST contract the frontend codes against is [`docs/03-API-CONTRACT.md`](docs/03-API-CONTRACT.md).

## Requirements

- JDK 21 or newer (the code targets 21)
- Docker Desktop running (for the local database **and** for the tests)

## Run it locally

```bash
docker compose up -d          # PostgreSQL 16 on localhost:5432 (dev credentials, not used anywhere real)
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

Integration tests run against a real PostgreSQL in a Testcontainers container, so Docker must be running.

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

## Configuration

| Property | Env var | Default |
|---|---|---|
| `atlasna.jwt.secret` | `ATLASNA_JWT_SECRET` | none outside `dev` (startup fails without it) |
| `atlasna.cors.allowed-origins` | `ATLASNA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` |
| Datasource | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | none outside `dev` |
| First admin (any env) | `ATLASNA_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | dev values in `application-dev.yml` |

## Design decisions

See [`docs/adr/`](docs/adr/).
