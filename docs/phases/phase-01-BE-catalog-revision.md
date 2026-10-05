# Phase 1 (Backend) — Grocery & Beauty Catalog: REVISION of the existing build

| | |
|---|---|
| **Repo** | `atlasna-catalog` |
| **Type** | Backend · **revision**, not rewrite |
| **Prerequisite** | The earlier Phase 1 build is running (Spring Boot 4.1, JWT auth, Flyway, login rate limiting, tests) |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md`, `02-DOMAIN-AND-DATA-MODEL.md`, `03-API-CONTRACT.md` §0–3 |
| **Estimate** | 1–2 weeks |

---

## 1. Why a revision (and what that means in practice)

The existing Phase 1 was built for a *generic marketplace*: a closed `Category` enum (`ELECTRONICS, FASHION, SPORTS, BOOKS…`),
integer stock, no units, no bilingual names, electronics demo data. Atlasna is a **grocery + beauty mini-mart in Annaba**.

**What stays (do not touch unless a step below says so):**
JWT issuing/validation · `JwtAuthFilter` · BCrypt · stateless `SecurityConfig` skeleton · login rate limiting · `GlobalExceptionHandler`
structure · DTO-as-record pattern · Flyway setup · Docker Compose Postgres · admin bootstrap · `via-dto` page serialization.

**What changes:** the `product` package (entity, DTOs, service, controller, repository), the `Category` enum → a table, the seeder,
the tests that mention old categories, the error shape (adds `code`), CORS config, admin routes (`/api/products` writes → `/api/admin/products`).

**What's new:** `categories` table + endpoints, bilingual fields, units & weights (heavy-item rule), JSONB attributes, product images table,
`users.phone`, `GET /api/auth/me`, Testcontainers-based integration tests, whitelisted sorting, combinable filters.

> This is a **breaking API change** (admin write routes move, product JSON changes). That is fine: nothing consumes it yet. After this
> phase the contract in `03-API-CONTRACT.md` is frozen for the frontend.

## 2. Outcome

A running API where: anyone can browse a bilingual grocery/beauty catalog with combinable filters; an ADMIN can manage categories and products
(soft delete + restore, optimistic locking, heavy items rejected); customers register with an Algerian phone number; every error has a stable `code`;
all of it proven by tests that run **real Postgres**.

## 3. Step 0 — Preflight (do this before changing anything; report findings)

1. `git status` must be clean. Create branch `phase-1-grocery-catalog`. Tag the current commit `phase-1-generic-catalog` (rollback point).
2. Run the existing build and tests; report pass/fail counts.
3. Read and summarise: `src/main/resources/db/migration/*` (list the files and **the exact V1 column names/types** of `users` and `products`),
   `application.yml`, `SecurityConfig`, `AuthService`, `ProductService`, `ProductController`, `DataSeeder`, the existing tests.
4. Confirm `spring.jpa.hibernate.ddl-auto` is `validate`. If it is `update`/`create`, change it to `validate` as the first commit (the migration is now the only schema authority).
5. Confirm Docker is running (Testcontainers needs it): `docker info`.
6. **Report any difference** between the code and the assumptions in this document before proceeding. The SQL below assumes the original V1 columns
   (`products`: `id, name, description, price, category, stock_quantity, active, created_at, updated_at`). Adapt names to reality; keep the intent.

## 4. Steps

### Step 1 — Dependencies (`pom.xml`)
Add (versions are managed by the Boot BOM — do not pin):
```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>   <!-- if absent -->
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><scope>test</scope></dependency>
```
Verify `spring-boot-starter-flyway` and `org.flywaydb:flyway-database-postgresql` are already present (they should be). Remove `h2` if nothing else needs it.
`./mvnw -q -DskipTests compile` must pass.

### Step 2 — Migration `V<next>__grocery_beauty_catalog.sql`
Create the next Flyway version (check the folder; the file below is named `V2` assuming V1 is the baseline). **Never edit an already-applied migration.**
The SQL was **executed against PostgreSQL 16** on top of the old schema with old rows present: it applies cleanly, and 12 constraint/index rules were
verified to reject bad data (price ≤ 0, negative stock, bad `sold_by`, duplicate slug/sku/barcode/phone, `compare_at_price ≤ price`, missing category;
two users with no phone are allowed; image rows cascade).

⚠️ It **deletes all existing products**. Acceptable only because they are electronics/fashion demo rows and no real data exists. Keep the warning comment.

```sql
-- =====================================================================
-- V2: re-shape the generic catalog into a GROCERY + BEAUTY mini-mart catalog
--
-- DESTRUCTIVE for `products`: removes the old demo rows (electronics, fashion,
-- ...). That is only acceptable because nothing real exists yet. Once real
-- data exists, never write a migration like this - migrate the data instead.
-- =====================================================================

DELETE FROM products;

-- ---------- users: contact phone (needed for delivery) ----------
-- Stored normalised as +213XXXXXXXXX. Nullable at DB level so legacy/admin rows
-- stay valid; the API makes it mandatory for customers.
ALTER TABLE users ADD COLUMN phone VARCHAR(16);
CREATE UNIQUE INDEX ux_users_phone ON users (phone) WHERE phone IS NOT NULL;

-- ---------- categories: an admin-editable two-level tree ----------
CREATE TABLE categories (
  id          BIGSERIAL PRIMARY KEY,
  parent_id   BIGINT REFERENCES categories (id),
  slug        VARCHAR(100) NOT NULL,
  type        VARCHAR(10)  NOT NULL,
  name_fr     VARCHAR(150) NOT NULL,
  name_ar     VARCHAR(150),
  sort_order  INTEGER      NOT NULL DEFAULT 0,
  active      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  CONSTRAINT ck_categories_type CHECK (type IN ('GROCERY', 'BEAUTY'))
);
CREATE UNIQUE INDEX ux_categories_slug ON categories (slug);
CREATE INDEX ix_categories_parent ON categories (parent_id);

INSERT INTO categories (parent_id, slug, type, name_fr, name_ar, sort_order) VALUES
  (NULL, 'epicerie', 'GROCERY', 'Épicerie',  'البقالة', 1),
  (NULL, 'beaute',   'BEAUTY',  'Beauté',    'الجمال',  2);

INSERT INTO categories (parent_id, slug, type, name_fr, name_ar, sort_order)
SELECT p.id, v.slug, p.type, v.name_fr, v.name_ar, v.sort_order
FROM (VALUES
  ('epicerie', 'fruits-legumes',          'Fruits & légumes',                  'فواكه وخضروات',               1),
  ('epicerie', 'produits-laitiers-oeufs', 'Produits laitiers & œufs',          'منتجات الألبان والبيض',        2),
  ('epicerie', 'boulangerie-patisserie',  'Boulangerie & pâtisserie',          'مخبوزات وحلويات',              3),
  ('epicerie', 'epicerie-salee',          'Épicerie salée (pâtes, semoule, riz, conserves)', 'مواد غذائية أساسية', 4),
  ('epicerie', 'epicerie-sucree',         'Épicerie sucrée & petit-déjeuner',  'مواد سكرية وفطور',             5),
  ('epicerie', 'huiles-condiments-epices','Huiles, condiments & épices',       'زيوت وتوابل وبهارات',          6),
  ('epicerie', 'boissons',                'Boissons (jus, café, thé, eau)',    'مشروبات',                      7),
  ('epicerie', 'snacks-confiseries',      'Snacks & confiseries',              'وجبات خفيفة وحلويات',          8),
  ('beaute',   'soins-visage',            'Soins du visage',                   'العناية بالوجه',               1),
  ('beaute',   'soins-corps',             'Soins du corps',                    'العناية بالجسم',               2),
  ('beaute',   'cheveux',                 'Cheveux',                           'الشعر',                        3),
  ('beaute',   'maquillage',              'Maquillage',                        'المكياج',                      4),
  ('beaute',   'parfums-deodorants',      'Parfums & déodorants',              'العطور ومزيلات العرق',         5),
  ('beaute',   'hygiene-personnelle',     'Hygiène personnelle',               'النظافة الشخصية',              6),
  ('beaute',   'naturel-traditionnel',    'Soins naturels & traditionnels (argan, henné, rose)', 'العناية الطبيعية والتقليدية', 7)
) AS v (parent_slug, slug, name_fr, name_ar, sort_order)
JOIN categories p ON p.slug = v.parent_slug;

-- ---------- products ----------
ALTER TABLE products RENAME COLUMN name        TO name_fr;
ALTER TABLE products RENAME COLUMN description TO description_fr;
ALTER TABLE products DROP COLUMN category;                       -- enum string -> category_id
ALTER TABLE products ALTER COLUMN stock_quantity TYPE NUMERIC(10,3); -- kg can be fractional
ALTER TABLE products ALTER COLUMN stock_quantity SET DEFAULT 0;

ALTER TABLE products
  ADD COLUMN name_ar             VARCHAR(255),
  ADD COLUMN description_ar      VARCHAR(2000),
  ADD COLUMN slug                VARCHAR(220)  NOT NULL,
  ADD COLUMN sku                 VARCHAR(40)   NOT NULL,
  ADD COLUMN barcode             VARCHAR(14),
  ADD COLUMN brand               VARCHAR(100),
  ADD COLUMN category_id         BIGINT        NOT NULL REFERENCES categories (id),
  ADD COLUMN sold_by             VARCHAR(10)   NOT NULL,           -- UNIT | WEIGHT (price is per piece, or per kg)
  ADD COLUMN quantity_step       NUMERIC(6,3)  NOT NULL DEFAULT 1, -- 1 for pieces, 0.5 for loose produce
  ADD COLUMN unit_weight_grams   INTEGER       NOT NULL,           -- weight of ONE sold unit (1000 for per-kg items)
  ADD COLUMN low_stock_threshold NUMERIC(10,3) NOT NULL DEFAULT 5,
  ADD COLUMN perishable          BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN featured            BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN compare_at_price    NUMERIC(12,2),                    -- "was" price for promotions
  ADD COLUMN attributes          JSONB         NOT NULL DEFAULT '{}'::jsonb, -- halal, origin, skinType, ingredients...
  ADD COLUMN version             BIGINT        NOT NULL DEFAULT 0; -- optimistic locking (stock concurrency, Phase 3)

ALTER TABLE products
  ADD CONSTRAINT ck_products_price_positive  CHECK (price > 0),
  ADD CONSTRAINT ck_products_stock_non_neg   CHECK (stock_quantity >= 0),
  ADD CONSTRAINT ck_products_sold_by         CHECK (sold_by IN ('UNIT', 'WEIGHT')),
  ADD CONSTRAINT ck_products_step_positive   CHECK (quantity_step > 0),
  ADD CONSTRAINT ck_products_weight_positive CHECK (unit_weight_grams > 0),
  ADD CONSTRAINT ck_products_compare_price   CHECK (compare_at_price IS NULL OR compare_at_price > price);

CREATE UNIQUE INDEX ux_products_slug    ON products (slug);
CREATE UNIQUE INDEX ux_products_sku     ON products (sku);
CREATE UNIQUE INDEX ux_products_barcode ON products (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_products_category_active ON products (category_id) WHERE active;
CREATE INDEX ix_products_featured        ON products (id) WHERE active AND featured;

CREATE TABLE product_images (
  id          BIGSERIAL PRIMARY KEY,
  product_id  BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  storage_key VARCHAR(300) NOT NULL,   -- object key (Phase 5 -> Cloud Storage); public URL is derived, never stored
  alt_fr      VARCHAR(200),
  alt_ar      VARCHAR(200),
  sort_order  INTEGER      NOT NULL DEFAULT 0,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_product_images_product ON product_images (product_id, sort_order);
```
Then reset the *dev* database so Flyway starts clean and you test the real path: `docker compose down -v && docker compose up -d`.
(If the dev DB must be kept, `./mvnw spring-boot:run` applies V2 on top of V1 — also valid, and the Testcontainers test below covers both paths.)

### Step 3 — Domain code (package `com.atlasna.catalog.product`, new `…catalog.common` helpers)

**3a. Helpers — already verified (16 assertions pass). Add them as-is, with their tests.**
```java
package com.atlasna.catalog.common;

import java.util.Optional;
import java.util.regex.Pattern;

/** Algerian mobile numbers only (05/06/07). Canonical storage form: +213 + 9 digits. */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");
    private static final Pattern ALGERIAN_MOBILE = Pattern.compile("^(?:\\+213|00213|0)([567]\\d{8})$");

    private PhoneNumbers() {}

    /** @return the canonical "+213XXXXXXXXX" form, or empty if the input is not a valid Algerian mobile number. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        var matcher = ALGERIAN_MOBILE.matcher(SEPARATORS.matcher(raw).replaceAll(""));
        return matcher.matches() ? Optional.of("+213" + matcher.group(1)) : Optional.empty();
    }

    public static boolean isValid(String raw) {
        return normalize(raw).isPresent();
    }
}
```
```java
package com.atlasna.catalog.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** URL-safe slugs from French product names ("Œufs ×6" -> "oeufs-6"). */
public final class Slugs {

    private static final int MAX_LENGTH = 200; // leaves room for a "-NN" uniqueness suffix inside VARCHAR(220)

    private Slugs() {}

    public static String slugify(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT)
                .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss");   // ligatures NFD does not decompose
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", ""); // strip accents
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        return s.isEmpty() ? "produit" : s;
    }

    /** Appends -2, -3, … until {@code exists} says the slug is free. */
    public static String unique(String base, Predicate<String> exists) {
        String candidate = base;
        for (int i = 2; exists.test(candidate); i++) candidate = base + "-" + i;
        return candidate;
    }
}
```

**3b. Config** — `StoreProperties` (`@ConfigurationProperties("atlasna.store")` record): `maxItemWeightGrams=5000`, `maxLineQuantityUnit=20`,
`maxLineQuantityWeight=10`, `lowStockRatio` unused for now. Add to `application.yml`; document each as an owner-confirmable default. (Moves into DB `store_settings` in Phase 3.)

**3c. Enums:** `SoldBy {UNIT, WEIGHT}`, `CategoryType {GROCERY, BEAUTY}`, `Availability {IN_STOCK, LOW_STOCK, OUT_OF_STOCK}`. (Enums are fine for *closed, code-driven* sets;
categories are *data*, hence a table.)

**3d. `Category` entity** → table `categories` (self-reference `parent`). Repository methods: `findByActiveTrueOrderBySortOrder()`, `findBySlug(String)`, `existsByParentId(Long)`.
Build the tree in memory (one query, group by parent) — do not lazy-load children per node.

**3e. `Product` entity** — remap to the new columns. Key points:
- `nameFr`, `nameAr`, `descriptionFr`, `descriptionAr`, `slug`, `sku`, `barcode`, `brand`, `price`, `compareAtPrice`, `soldBy` (`@Enumerated(STRING)`), `quantityStep`,
  `unitWeightGrams`, `stockQuantity` (**`BigDecimal`**, `NUMERIC(10,3)`), `lowStockThreshold`, `perishable`, `featured`, `active`.
- `@ManyToOne(fetch = LAZY) Category category`.
- `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") Map<String,Object> attributes` (Hibernate 7 maps JSON → `jsonb` on PostgreSQL; with `ddl-auto=validate` a mismatch fails at startup — good).
  **Verify:** Boot 4 ships Jackson 3 (`tools.jackson`); check which JSON `FormatMapper` the resolved Hibernate 7 uses and that a JSONB round-trip works (test 1 + a repository test that saves and reloads `attributes`).
- `@Version Long version` (optimistic locking). `@OneToMany(mappedBy="product", cascade=ALL, orphanRemoval=true) @OrderBy("sortOrder") List<ProductImage> images`.
- No `@Data`, no Lombok `equals/hashCode` on entities.

**3f. DTOs (records):** `LocalizedText(fr, ar)`; `CategoryNode`; public `ProductResponse` and `AdminProductResponse` exactly as in `03-API-CONTRACT.md` §2–3 (**different types**);
`ProductRequest`. Bean Validation messages in English, with `code`s mapped in Step 7.
`availability`: `OUT_OF_STOCK` if stock ≤ 0; `LOW_STOCK` if stock ≤ `lowStockThreshold`; else `IN_STOCK`.
`maxOrderQuantity = min(stockQuantity, limit)` where `limit` depends on `soldBy`, rounded **down** to a multiple of `quantityStep`.

### Step 4 — Services
**`CategoryService`**: `tree()` (public, active only), `adminTree()`, create/update/delete. Rules: child `type` == parent `type`; max depth 2; cannot delete a category that has
children or products (`409`); slug unique (`SLUG_CONFLICT`).
**`ProductService`**:
- Public `search(filter, pageable)` — build a **JPA `Specification`** from `{categorySlug (root slug ⇒ include children), type, q, inStock, featured}`; always `active = true`; **all filters AND-combined**.
  `q`: case-insensitive `LIKE` on `nameFr`, `nameAr`, `brand`, `sku` with `%`/`_`/`\` **escaped**. Sorting: map the four whitelisted `sort` values to entity fields (`name,asc` → `nameFr`); anything else → `400 INVALID_SORT`
  *before* touching the repository. Avoid N+1: fetch `category` and `images` with `@EntityGraph`/fetch join or a batch size; **a test asserts the query count for a list call is constant** (use Hibernate statistics or `datasource-proxy`).
- `getPublic(id)` / `getPublicBySlug(slug)` → `404 PRODUCT_NOT_FOUND` if missing **or inactive**.
- Admin: `create` / `update` / `softDelete` / `restore` / `adminSearch` (adds `active=true|false|all`, `lowStock`).
  Rules on create/update: category must exist and be a **leaf** (`CATEGORY_NOT_FOUND`/`422`), **`unitWeightGrams ≤ store.maxItemWeightGrams` else `422 PRODUCT_TOO_HEAVY`**,
  `soldBy=UNIT` ⇒ whole-number `quantityStep`, slug generated **once** from `name.fr` via `Slugs` + `Slugs.unique` (stable on update), sku auto-generated if blank (`GRC-`/`BTY-` + 4-digit sequence by category type; unique, `SKU_CONFLICT` if supplied and taken).
  `update` compares the request's `version` with the entity's and returns `409 CONCURRENT_MODIFICATION` (also catch `ObjectOptimisticLockingFailureException`).

### Step 5 — Controllers & routes
| Route | Who | Notes |
|---|---|---|
| `GET /api/categories` | public | active tree |
| `GET /api/products`, `GET /api/products/{id}`, `GET /api/products/by-slug/{slug}` | public | **no write routes remain under `/api/products`** |
| `/api/admin/categories`, `/api/admin/products` (+ `/{id}/restore`) | `ADMIN` | `@PreAuthorize("hasRole('ADMIN')")` on the controller class **and** `/api/admin/**` in the filter chain (defence in depth) |
| `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me` | public / public / authenticated | see Step 6 |

### Step 6 — Identity changes
- `User.phone` (`String`, nullable in DB, mandatory in `RegisterRequest`). Validate with a custom constraint (`@AlgerianMobile`) that delegates to `PhoneNumbers.isValid`; the service stores `PhoneNumbers.normalize(...)`.
  Duplicate email → `409 EMAIL_ALREADY_IN_USE`; duplicate phone → `409 PHONE_ALREADY_IN_USE` (check first **and** map the unique-index violation, because two requests can race).
- `UserResponse` gains `phone`. `GET /api/auth/me` returns the current `UserResponse` (re-reads the DB, so role changes show up).
- Login: a **disabled** account currently falls through to a generic 401. Catch `DisabledException` → `401 ACCOUNT_DISABLED` (only after the password matched).
  ⚠️ By default `DaoAuthenticationProvider` runs the enabled/locked checks in its **pre**-authentication checks — *before* comparing the password —
  so a naive catch reports `ACCOUNT_DISABLED` to someone with the wrong password (account-state leak; test 16 fails). Move the account-status
  checks to run after the password check (e.g. `setPreAuthenticationChecks(user -> {})` and a `setPostAuthenticationChecks(...)` that throws
  `DisabledException`/`LockedException`); verify the setter names against the installed Spring Security 7 jar.
- Give `LoginRequest` explicit validation messages like `RegisterRequest`.

### Step 7 — Errors with stable codes
Add `code` to `ApiError` (keep all existing fields so nothing breaks). Map every exception to a code per `02-DOMAIN-AND-DATA-MODEL.md` §7. Also handle:
`MethodArgumentTypeMismatchException` → `400 VALIDATION_FAILED` (e.g. `/api/products/abc`, `?type=FOO`), `HttpMessageNotReadableException` → `400`, invalid sort → `400 INVALID_SORT`,
`ObjectOptimisticLockingFailureException` → `409 CONCURRENT_MODIFICATION`, `DataIntegrityViolationException` on slug/sku/phone/email → the matching `409`. The catch-all returns `500 INTERNAL_ERROR` with a generic message and logs the stack trace server-side only.

### Step 8 — Security & CORS (carry-over fixes from the frontend review)
- Public: `/api/auth/register`, `/api/auth/login`, `GET /api/categories/**`, `GET /api/products/**`, `/actuator/health`. `/api/admin/**` → `ADMIN`. `anyRequest().authenticated()` **last**. Use `HttpMethod.GET` objects, not strings.
- CORS: origins from config (`atlasna.cors.allowed-origins`, default `http://localhost:5173,http://localhost:3000`), methods GET/POST/PUT/PATCH/DELETE/OPTIONS, headers `Authorization, Content-Type, Idempotency-Key`,
  **`exposedHeaders: Retry-After`**, and **`allowCredentials(false)`** (we use bearer tokens, not cookies; revisit only if cookies are introduced).
- Keep the stateless chain, CSRF disabled (documented why), BCrypt, rate limits.
- Do not log tokens or passwords; check the existing logging config.

### Step 9 — Seeder (dev only)
`@Profile("dev")`. Keep the existing admin bootstrap behaviour. Seed demo products from `02-DOMAIN-AND-DATA-MODEL.md` §8 (look up categories **by slug**; images empty). Idempotent (skip if products exist).
Categories are reference data and already come from the migration. Make sure the `dev` profile is active for `spring-boot:run` (via `application-dev.yml` / `spring.profiles.default`).

### Step 10 — Tests (real Postgres)
Create an abstract `PostgresIntegrationTest` base (singleton `PostgreSQLContainer` `postgres:16-alpine` with `@ServiceConnection`; in Testcontainers 2.x the class lives in `org.testcontainers.postgresql` — verify against the resolved jar).
Remove H2 usage for anything that touches these entities. **Required tests (each must be able to fail — break the code once to see it go red):**

| # | Test | Proves |
|---|---|---|
| 1 | Context loads on Postgres with `ddl-auto=validate` | Entities ⇄ migration agree (catches JSONB/NUMERIC mismatches) |
| 2 | Migration test: apply V1 only, insert old-style rows, apply V2 → succeeds, products empty, 2 root + 15 child categories | The destructive migration path works |
| 3 | Category tree endpoint returns 2 roots / 15 children, ordered, inactive excluded | |
| 4 | Product list filters: root category includes children · `type` · `q` (accent/case, `%` escaped) · `inStock` · `featured` · **combined filters** | Fixes the old "search ignores category" gap |
| 5 | Sort whitelist: 4 valid values OK, `?sort=nope` → `400 INVALID_SORT` (not 500) | |
| 6 | List endpoint query count is constant for 1 vs 20 products | No N+1 |
| 7 | Admin create: heavy item (6000 g) → `422 PRODUCT_TOO_HEAVY`; 5000 g OK | The business rule |
| 8 | Admin create: UNIT with step 0.5 → 400; non-leaf category → rejected; unknown category → `CATEGORY_NOT_FOUND` | |
| 9 | Slug: generated from French name, stable after rename, collision → `-2` | |
| 10 | Soft delete → 404 publicly, visible with `active=false` in admin, restore → visible again | |
| 11 | Optimistic locking: two PUTs with the same `version` → second gets `409 CONCURRENT_MODIFICATION` | |
| 12 | **Authorization matrix** for every `/api/admin/**` route: anonymous 401 · CUSTOMER 403 · ADMIN 2xx | The most important security test |
| 13 | Public product JSON has **no** `stockQuantity`, `active`, `version`; admin JSON has them | DTO split |
| 14 | Register: valid phone variants normalise to `+213…`; invalid → 400 with `fieldErrors.phone`; duplicate email/phone → 409 with correct `code`; phone race (two parallel registers) → one 201, one 409 | |
| 15 | `/api/auth/me` returns the user incl. phone; without token → 401 | |
| 16 | Disabled user with correct password → `401 ACCOUNT_DISABLED`; wrong password → `INVALID_CREDENTIALS` | No account-state leak |
| 17 | Every error response has `code`; validation errors have `fieldErrors` | Contract |
| 18 | `PhoneNumbers` and `Slugs` unit tests (reuse the verified assertions) | |

### Step 11 — Docs
Update `README.md` (run instructions: `docker compose up -d`, `./mvnw spring-boot:run`, dev logins). Copy/refresh `03-API-CONTRACT.md` into `docs/`.
Write ADRs: **0001** categories as a table not an enum · **0002** separate public/admin DTOs (don't leak stock) · **0003** Testcontainers instead of H2 · **0004** `code` field on errors.

## 5. Acceptance criteria

- [ ] `./mvnw clean verify` green on a clean checkout; the integration tests ran against **Testcontainers Postgres** (Docker required).
- [ ] `docker compose down -v && docker compose up -d && ./mvnw spring-boot:run` starts cleanly with Flyway applying V1+V2 and Hibernate `validate` passing.
- [ ] No reference to electronics/fashion/sports/books/headphones/sneakers remains anywhere (code, tests, seed, README): `grep -rniE "electronics|fashion|sports|books|headphone|sneaker" src README.md` is empty.
- [ ] Contract examples in `03-API-CONTRACT.md` match real responses (capture one list and one detail response with `curl`; paste them into the frontend fixtures later).
- [ ] The 18 tests above exist and pass; test 12 (authorization matrix) covers **every** admin route.
- [ ] Security baseline (`01-CONVENTIONS.md` §6) ticked.

## 6. Verification (run and paste the outputs in the PR)

```bash
docker compose down -v && docker compose up -d && ./mvnw spring-boot:run &   # wait for "Started"

curl -s localhost:8080/api/categories | jq '.[].slug'                       # "epicerie", "beaute"
curl -s "localhost:8080/api/products?category=epicerie&inStock=true&sort=price,asc&size=5" | jq '.page, (.content[0] | {slug,price,availability})'
curl -s "localhost:8080/api/products?q=argan" | jq '.content[].name.fr'
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8080/api/products?sort=nope"         # 400
curl -s localhost:8080/api/products/by-slug/tomates | jq 'has("stockQuantity")'          # false  (public view hides stock)

curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"fullName":"Test","email":"t@example.com","phone":"0551 23 45 67","password":"SuperSecret1"}' | jq '.user'   # phone: +213551234567
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/api/admin/products -H 'Content-Type: application/json' -d '{}'   # 401
# customer token → 403 on /api/admin/products ; admin token → 201, then try unitWeightGrams=6000 → 422 PRODUCT_TOO_HEAVY
```

## 7. Out of scope (do not build now)
Orders, cart, delivery, stock ledger (Phase 3) · Redis, Mongo, image upload (Phase 5) · refresh tokens (Phase 5) · full-text/accent-insensitive search (Phase 5) · OTP / phone login (backlog).

## 8. Commit plan
`db: add grocery/beauty catalog migration` → `feat: categories tree + admin API` → `feat: bilingual product model with units, weights and JSONB attributes` →
`feat: heavy-item rule, slug/sku generation, optimistic locking` → `feat: algerian phone + /me + account-disabled` → `refactor: stable error codes` →
`fix(security): configurable CORS, expose Retry-After, no credentials` → `test: testcontainers integration suite` → `docs: contract, ADRs, README`. Tag `phase-1-done`.

---

## 9. Concepts & interview questions (for the developer — Claude Code may ignore this section)

**Concepts you should now be able to explain**
- **Schema migrations:** why Flyway, why `validate` not `update`, why applied migrations are immutable, forward-only changes, and when a destructive migration is (not) acceptable.
- **Constraints in the database vs validation in the app:** the DB is the last line of defence against races and bugs; the app gives good error messages.
- **Partial unique indexes** (`WHERE phone IS NOT NULL`) and why a plain `UNIQUE` on a nullable column behaves differently across databases.
- **Optimistic locking** (`@Version`) vs pessimistic locking; what a lost update is.
- **DTO separation:** why the public view hides stock and the entity never leaves the service layer.
- **Why Testcontainers over H2:** same engine in tests and production (JSONB, partial indexes, locking semantics).
- **N+1 queries** and how to detect them in a test.
- **Stable error codes** vs human messages — what each is for.

**Interview questions**
1. You need to add a NOT NULL column to a 50-million-row table in production with zero downtime. How?
2. Why is `DELETE FROM products` in a migration acceptable here and a firing offence later?
3. Explain optimistic locking with an example from this catalog. What does the client see on conflict?
4. A customer calls `GET /api/admin/products` with a valid JWT. Walk through every layer that stops them.
5. Why return `404` instead of `403` when a customer requests another customer's resource? (Preview of Phase 3.)
6. What's the difference between `@WebMvcTest`, `@DataJpaTest` and `@SpringBootTest`, and which did you use for which test?
7. How would you add a third language later? What changes in DB, API, and frontend?
