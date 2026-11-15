# ADR 0015 — Inventory lots, FEFO and expiry

**Status:** accepted (Phase 5, step 7) · **Date:** 2026-11-15

## Context
A mini-mart sells yoghurt, milk, cheese, bread: the same product arrives in several deliveries with different expiry
dates. Selling the newest first leaves the oldest to expire; selling an expired item is a health and legal problem;
a recall needs "which customers got lot X". Phase 3 already guarantees no overselling with one conditional
`UPDATE products … WHERE stock_quantity >= q` per line and a ledger.

## Decision
- **Lots are a breakdown of the stock, not a replacement.** `inventory_lots(product, lot_code, quantity_received,
  quantity_remaining, received_at, expiry_date)`. Invariant, checked after every test flow:
  `products.stock_quantity = Σ lots.quantity_remaining = Σ stock_movements.delta`. Migration V6 opens one lot per product
  with today's stock (no expiry recorded).
- **The product row stays the concurrency guard.** Every change first updates the product row (conditional for
  decrements, as before); only then are lots touched, in the same transaction. The product row lock serialises buyers
  of that product, so lot updates never race (`FOR UPDATE` makes it explicit). 20 parallel buyers over 2 lots: exactly
  the stock is sold, nothing negative (tested).
- **FEFO:** a sale takes from the lot with the earliest `expiry_date` (lots without a date last), **never from an expired
  lot** — if the sellable quantity is short, the order fails with `409 INSUFFICIENT_STOCK` (details give the sellable
  quantity) and everything rolls back. Each sale records `lot_allocations(order, product, lot, quantity)` for recalls.
- **Give back to the same lots:** cancellations and lighter weighing return quantities to the lots the order took them
  from (latest allocation first).
- **Receiving goods:** `POST /api/admin/products/{id}/lots {quantity, expiryDate?, lotCode?, note?}` (STAFF/ADMIN) →
  new lot + `RESTOCK` movement naming the lot. Perishables require an expiry date, never in the past. Stock added
  without lot details (count correction, product form) goes to the product's open no-expiry lot; removals take the
  earliest-expiring stock first (expired lots included).
- **Hourly Quartz job (idempotent):** writes off every lot past its expiry date (stock down, `EXPIRED` movement with the
  lot, lot emptied — one transaction per lot) and raises `LotExpiring` once per lot entering the 3-day window
  (`atlasna.inventory.expiring-soon-days`; consumed by notifications in Phase 7).
- **Reports:** `GET /api/admin/inventory/expiring?days=N`, `GET /api/admin/inventory/low-stock`,
  `GET /api/admin/products/{id}/lots` (in FEFO order).

## Consequences
- Between an expiry date passing and the next job run (≤ 1 h), the expired quantity still counts in `stock_quantity` and
  can show as "available", but it cannot be sold (the sale fails with INSUFFICIENT_STOCK).
- The expiry date is "last day it may be sold", compared in Annaba time.
- `stock_movements.lot_id` links receiving and write-offs to their lot; sales link through `lot_allocations`.
