-- Phase 5, step 7: stock in lots with expiry dates, sold first-expired-first-out (FEFO). ADR-0015.
-- Invariant (tested): products.stock_quantity = SUM(inventory_lots.quantity_remaining) = SUM(stock_movements.delta).

CREATE TABLE inventory_lots (
  id                    BIGSERIAL PRIMARY KEY,
  product_id            BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  lot_code              VARCHAR(60)   NOT NULL,
  quantity_received     NUMERIC(10,3) NOT NULL CHECK (quantity_received >= 0),
  quantity_remaining    NUMERIC(10,3) NOT NULL CHECK (quantity_remaining >= 0),
  received_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  -- Last day the goods may be sold; NULL = doesn't expire (non-perishables, or not recorded).
  expiry_date           DATE,
  -- When the "expiring soon" alert was raised for this lot (raised once).
  expiring_notified_at  TIMESTAMP(6) WITH TIME ZONE,
  note                  VARCHAR(300),
  created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
-- FEFO lookup: a product's lots with stock, earliest expiry first.
CREATE INDEX ix_lots_fefo ON inventory_lots (product_id, expiry_date, received_at, id) WHERE quantity_remaining > 0;
CREATE INDEX ix_lots_expiry ON inventory_lots (expiry_date) WHERE quantity_remaining > 0 AND expiry_date IS NOT NULL;

-- Which lots each order took its goods from (recalls: "who received lot X?"). Net quantities: a cancellation or a
-- lighter weighing gives back to the same lots and reduces these rows.
CREATE TABLE lot_allocations (
  id         BIGSERIAL PRIMARY KEY,
  order_id   BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
  product_id BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  lot_id     BIGINT        NOT NULL REFERENCES inventory_lots (id) ON DELETE CASCADE,
  quantity   NUMERIC(10,3) NOT NULL CHECK (quantity >= 0), -- 0 only for an instant: rows are deleted when emptied
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_lot_allocations_order ON lot_allocations (order_id, product_id);
CREATE INDEX ix_lot_allocations_lot ON lot_allocations (lot_id);

-- The ledger can now name the lot a movement concerns (receiving, expiry write-off).
ALTER TABLE stock_movements ADD COLUMN lot_id BIGINT REFERENCES inventory_lots (id) ON DELETE SET NULL;

-- Today's stock becomes one opening lot per product (no expiry recorded yet), so the invariant holds from day one.
INSERT INTO inventory_lots (product_id, lot_code, quantity_received, quantity_remaining, note)
SELECT id, 'OPENING', stock_quantity, stock_quantity, 'Opening lot (migration V6)'
FROM products WHERE stock_quantity > 0;
