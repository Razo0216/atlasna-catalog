# ADR 0006 — Stock is a ledger, changed by atomic conditional updates

**Status:** accepted (Phase 3) · **Date:** 2026-11-13

## Context
Two customers can order the last crate of tomatoes in the same millisecond. A read-check-write
(`if stock >= q then stock -= q`) lets both pass under READ COMMITTED: the shop oversells. We also need to know *why*
stock changed (sale, cancellation, restock, write-off) for audits and, later, expiry tracking.

## Decision
- Every stock change appends a row to `stock_movements` (`OPENING, SALE, CANCEL_RESTORE, RESTOCK, ADJUSTMENT, EXPIRED`).
  `products.stock_quantity` is the cached sum. Migration V3 writes an `OPENING` row for existing stock; the catalog
  writes `OPENING`/`ADJUSTMENT` rows when a product is created or its stock edited. A trigger rejects UPDATEs on the ledger.
- Decrements are ONE statement: `UPDATE products SET stock_quantity = stock_quantity - :q … WHERE id = :id AND active
  AND stock_quantity >= :q`. The row lock serializes concurrent buyers; whoever finds too little gets 0 rows →
  `409 INSUFFICIENT_STOCK` with `details:[{productId, available}]`, and the whole order rolls back.
- An order's lines are processed in **ascending product id**, so two orders never lock the same rows in opposite order.
- Each stock update bumps `products.version`: an admin saving a stale product form gets 409, not a lost sale.

## Consequences
- Tested with real threads: 20 buyers for 1 unit → exactly 1 order; opposite-order carts never deadlock;
  `stock_quantity = Σ delta` for every product after every scenario.
- Packing follows the scale: weighing heavier takes more stock (conditional), lighter or out-of-stock gives it back.
