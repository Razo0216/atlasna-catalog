# ADR 0005 — A modular monolith with enforced boundaries (Spring Modulith)

**Status:** accepted (Phase 3) · **Date:** 2026-11-13

## Context
One shop does not need microservices: one deployable is cheaper to run, test and change. But a monolith without
boundaries turns into a ball of mud, and Phase 7 will split it into services — which is only possible if the
modules are already independent.

## Decision
Each top-level package under `com.atlasna.catalog` is an application module: `user` (identity, incl. JWT security),
`product` (catalog), `inventory` (stock ledger), `store` (settings), `delivery` (zones, slots, addresses),
`order` (cart, orders, payments), plus `config` (wiring) and `common` (helpers, error handling).
A module may use another only through its **public API** — its root package (e.g. `DeliveryApi`, `CatalogApi`,
`InventoryService`) or an explicit `@NamedInterface` (`product.dto`, `common.exception`, the `SoldBy`/`Availability`
enums, the JWT filter). `ModularityTests` runs `ApplicationModules.verify()` (no illegal access, no cycles) and writes
the diagrams in `docs/modules/`. Modules talk asynchronously through application events (`OrderPlaced`,
`OrderStatusChanged`, `StockLow`) recorded by the JPA event publication registry — the outbox Phase 7 forwards to Kafka.

## Consequences
- A boundary violation fails the build (proven once by adding an illegal import of `delivery.domain.Address`).
- Cross-module data goes through small records (`ProductInfo`, `ZoneInfo`, `AddressSnapshot`, `SlotInfo`), never entities.
- `security` moved under `user` (they depended on each other — one "identity" module), and the dev seeders moved into `product`.
