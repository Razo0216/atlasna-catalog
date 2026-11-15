# ADR 0010 — Catalog cache policy (Redis, cache-aside, invalidation by generation)

**Status:** accepted (Phase 5, step 1) · **Date:** 2026-11-15

## Context
The storefront's busiest requests are public and identical for everyone: the category tree, product pages, listing
and search pages. Each is a Postgres query plus mapping (images, category, settings). Customers are mostly on phones
on mediocre connections, so shaving server time matters; and in Phase 7+ several instances will share one cache.
What must never happen: an order or cart decided on a stale stock number, or personal data sitting in a cache.

## Decision
- **What is cached:** only `GET /api/categories`, `GET /api/products` (listing/search), `GET /api/products/{id}`,
  `GET /api/products/by-slug/{slug}` — through `CachedCatalog`. Nothing per-user (cart, orders, addresses, `/me`),
  nothing admin, never exact stock. Carts and order placement read Postgres via `CatalogApi`, always.
- **Pattern:** cache-aside. Miss → load from Postgres → store JSON (the app's `JsonMapper`, never Java serialisation).
- **Keys:** `atlasna:v1:catalog:{generation}:{name}`. `v1` lets a future incompatible format start clean. User input
  (search text, slugs) is SHA-256-hashed into the key, so keys are bounded and never contain raw input. Equivalent
  listing requests (`q=" Tomates"` / `q=tomates`) share one key because the search itself is trimmed and case-insensitive.
- **TTLs:** categories 5 min · product 2 min · listings 1 min (`atlasna.cache.*`). They bound staleness if an
  invalidation is ever missed (e.g. Redis unreachable at that moment).
- **Invalidation by generation, after commit:** a product/category edit (`CatalogChanged`), any stock movement —
  including every order, cancellation and restock (`StockChanged`) — and a settings save (`StoreSettingsChanged`)
  increment `atlasna:v1:catalog:generation` once per transaction, after commit. All old keys become unreachable at once
  (listing pages can't be enumerated, so per-key eviction would miss them) and expire by TTL. Readers read the
  generation *before* loading, so a read racing a write files its result under the old generation — never served.
  The app also bumps the generation at startup (a reset dev database must not meet old entries).
  - Why bump on every order? At one shop's order rate the hit ratio stays high, and customers never see "En stock"
    for something that just sold out. If order volume grows, switch to bumping only when a product's availability
    bucket changes (IN/LOW/OUT).
  - The listener is a plain `@EventListener` that registers an after-commit callback, not a
    `@TransactionalEventListener`: Spring Modulith's publication registry needs a second DB connection per
    transactional listener, which exhausted the pool under concurrent orders. A cache bump needs no durable record.
- **Stampede protection:** per instance, only one request per key loads from Postgres; concurrent requests for the
  same key wait for it (max 5 s, then load themselves). Cross-instance stampedes are bounded by the instance count.
- **Redis is optional:** 250 ms command timeout, 500 ms connect timeout, Resilience4j circuit breaker `catalogCache`
  (opens at 50 % failures/slow calls over the last 20, stays open 30 s, 3 trial calls). Any failure → serve from
  Postgres. Metric `atlasna.cache.requests{cache=catalog,result=hit|miss|error|bypass}`.
- **Tests:** the test profile disables the cache (tests edit rows in SQL); `CatalogCacheTests` enables it with real
  Redis: served from cache, every write path invalidates, single load under 20 concurrent misses, only catalog keys
  and no personal data in Redis, and a paused Redis container → still 200, breaker OPEN, no timeout per request.

## Consequences
- Admin edits show up on the next request; a sold-out product shows out of stock on the next request.
- If Redis is unreachable *during* a write, the bump is lost and customers may see the old data for up to one TTL.
- Two Redis round trips per cached read (generation + entry): sub-millisecond on the same network.
- No HTTP cache headers yet: browser/CDN caching of these endpoints is Phase 12.
