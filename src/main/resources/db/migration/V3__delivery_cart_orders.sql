-- Phase 3: delivery (settings, zones, slots, addresses), server cart, orders, payments, stock ledger,
-- idempotency, staff/driver roles, and the Spring Modulith event publication registry (the outbox).
-- Forward-only and non-destructive: existing rows are kept; stock gets an OPENING ledger entry per product.

-- ---------- identity: new roles ----------
ALTER TABLE users DROP CONSTRAINT ck_users_role;
ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN', 'STAFF', 'DRIVER'));
-- Accounts created by the admin (staff, drivers) get a temporary password they must change.
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------- store settings (single row, typed columns) ----------
CREATE TABLE store_settings (
  id                          SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  store_open                  BOOLEAN      NOT NULL DEFAULT TRUE,   -- holidays / emergencies: closes every slot
  opening_time                TIME         NOT NULL,
  closing_time                TIME         NOT NULL,
  same_day_cutoff             TIME         NOT NULL,
  lead_time_minutes           INTEGER      NOT NULL CHECK (lead_time_minutes >= 0),
  max_days_ahead              INTEGER      NOT NULL CHECK (max_days_ahead BETWEEN 0 AND 30),
  max_item_weight_grams       INTEGER      NOT NULL CHECK (max_item_weight_grams > 0),
  max_order_weight_grams      INTEGER      NOT NULL CHECK (max_order_weight_grams > 0),
  max_line_quantity_unit      INTEGER      NOT NULL CHECK (max_line_quantity_unit > 0),
  max_line_quantity_weight    NUMERIC(10,3) NOT NULL CHECK (max_line_quantity_weight > 0),
  weight_tolerance_percent    NUMERIC(5,2) NOT NULL CHECK (weight_tolerance_percent >= 0),
  failed_delivery_flag_count  INTEGER      NOT NULL CHECK (failed_delivery_flag_count > 0),
  failed_delivery_window_days INTEGER      NOT NULL CHECK (failed_delivery_window_days > 0),
  updated_at                  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  version                     BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT ck_store_settings_hours CHECK (opening_time < closing_time)
);
-- Defaults the owner must confirm (docs/02-DOMAIN-AND-DATA-MODEL.md, rules 3-4, 10-14, 17). Editable via /api/admin/settings.
INSERT INTO store_settings (opening_time, closing_time, same_day_cutoff, lead_time_minutes, max_days_ahead,
                            max_item_weight_grams, max_order_weight_grams, max_line_quantity_unit, max_line_quantity_weight,
                            weight_tolerance_percent, failed_delivery_flag_count, failed_delivery_window_days)
VALUES ('09:00', '21:00', '19:00', 90, 3, 5000, 15000, 20, 10, 20, 3, 30);

-- ---------- delivery zones & slots ----------
CREATE TABLE delivery_zones (
  id                      BIGSERIAL PRIMARY KEY,
  slug                    VARCHAR(100) NOT NULL,
  name_fr                 VARCHAR(150) NOT NULL,
  name_ar                 VARCHAR(150),
  fee                     NUMERIC(12,2) NOT NULL CHECK (fee >= 0),
  min_order_amount        NUMERIC(12,2) NOT NULL CHECK (min_order_amount >= 0),
  free_delivery_threshold NUMERIC(12,2) CHECK (free_delivery_threshold > 0),
  eta_minutes             INTEGER       NOT NULL CHECK (eta_minutes > 0),
  sort_order              INTEGER       NOT NULL DEFAULT 0,
  active                  BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  version                 BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_delivery_zones_slug ON delivery_zones (slug);

-- Seed: the plan's service area around the shop (replace with the real zones). Minimum order 2 000 DA (owner's decision),
-- free delivery from 5 000 DA.
INSERT INTO delivery_zones (slug, name_fr, name_ar, fee, min_order_amount, free_delivery_threshold, eta_minutes, sort_order) VALUES
  ('annaba-centre', 'Annaba centre', 'وسط عنابة', 200, 2000, 5000, 60, 1),
  ('el-bouni',      'El Bouni',      'البوني',     250, 2000, 5000, 75, 2),
  ('sidi-amar',     'Sidi Amar',     'سيدي عمار',  300, 2000, 5000, 90, 3),
  ('el-hadjar',     'El Hadjar',     'الحجار',     300, 2000, 5000, 90, 4);

CREATE TABLE delivery_slots (
  id          BIGSERIAL PRIMARY KEY,
  zone_id     BIGINT   REFERENCES delivery_zones (id),   -- NULL = offered in every zone
  day_of_week SMALLINT CHECK (day_of_week BETWEEN 1 AND 7), -- ISO 1 = Monday; NULL = every day
  start_time  TIME     NOT NULL,
  end_time    TIME     NOT NULL,
  capacity    INTEGER  NOT NULL CHECK (capacity > 0),
  active      BOOLEAN  NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  version     BIGINT   NOT NULL DEFAULT 0,
  CONSTRAINT ck_delivery_slots_times CHECK (start_time < end_time)
);
INSERT INTO delivery_slots (start_time, end_time, capacity) VALUES
  ('10:00', '12:00', 8), ('12:00', '14:00', 8), ('14:00', '16:00', 8), ('16:00', '18:00', 8), ('18:00', '20:00', 8);

-- Slot capacity without races: one row per slot and day, incremented by a single conditional upsert.
CREATE TABLE slot_usage (
  slot_id   BIGINT  NOT NULL REFERENCES delivery_slots (id),
  slot_date DATE    NOT NULL,
  booked    INTEGER NOT NULL CHECK (booked >= 0),
  PRIMARY KEY (slot_id, slot_date)
);

-- ---------- addresses ----------
CREATE TABLE addresses (
  id             BIGSERIAL PRIMARY KEY,
  user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  label          VARCHAR(60),
  recipient_name VARCHAR(120) NOT NULL,
  phone          VARCHAR(16)  NOT NULL,
  zone_id        BIGINT       NOT NULL REFERENCES delivery_zones (id),
  street         VARCHAR(255) NOT NULL,
  landmark       VARCHAR(255) NOT NULL,   -- in Annaba the landmark matters more than the street number
  building_notes VARCHAR(500),
  lat            NUMERIC(9,6) CHECK (lat BETWEEN -90 AND 90),
  lng            NUMERIC(9,6) CHECK (lng BETWEEN -180 AND 180),
  is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_addresses_user ON addresses (user_id);
CREATE UNIQUE INDEX ux_addresses_one_default ON addresses (user_id) WHERE is_default;

-- ---------- server cart ----------
CREATE TABLE carts (
  id         BIGSERIAL PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_carts_user ON carts (user_id);

CREATE TABLE cart_items (
  cart_id             BIGINT        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
  product_id          BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  quantity            NUMERIC(10,3) NOT NULL CHECK (quantity > 0),
  substitution_policy VARCHAR(20)   NOT NULL DEFAULT 'CALL_ME'
    CHECK (substitution_policy IN ('ALLOW_SIMILAR', 'REMOVE_ITEM', 'CALL_ME')),
  unit_price_seen     NUMERIC(12,2) NOT NULL,   -- price when added: GET /api/cart warns PRICE_CHANGED when it moved
  added_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  PRIMARY KEY (cart_id, product_id)
);

-- ---------- orders ----------
CREATE TABLE orders (
  id                   BIGSERIAL PRIMARY KEY,
  order_number         VARCHAR(20)   NOT NULL,
  user_id              BIGINT        NOT NULL REFERENCES users (id),
  status               VARCHAR(30)   NOT NULL CHECK (status IN ('PLACED', 'CONFIRMED', 'PREPARING', 'READY_FOR_DELIVERY',
                                                          'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'CANCELLED')),
  zone_id              BIGINT        NOT NULL REFERENCES delivery_zones (id),
  -- address snapshot: editing or deleting the address later never rewrites the order
  recipient_name       VARCHAR(120)  NOT NULL,
  phone                VARCHAR(16)   NOT NULL,
  street               VARCHAR(255)  NOT NULL,
  landmark             VARCHAR(255)  NOT NULL,
  building_notes       VARCHAR(500),
  lat                  NUMERIC(9,6),
  lng                  NUMERIC(9,6),
  slot_id              BIGINT        NOT NULL REFERENCES delivery_slots (id),
  slot_date            DATE          NOT NULL,
  slot_start           TIME          NOT NULL,
  slot_end             TIME          NOT NULL,
  subtotal             NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0),
  delivery_fee         NUMERIC(12,2) NOT NULL CHECK (delivery_fee >= 0),
  total                NUMERIC(12,2) NOT NULL CHECK (total >= 0),
  estimated_total      NUMERIC(12,2) NOT NULL CHECK (estimated_total >= 0),   -- total at placement, before weighing
  total_weight_grams   INTEGER       NOT NULL CHECK (total_weight_grams >= 0),
  total_change_flagged BOOLEAN       NOT NULL DEFAULT FALSE,   -- weighed total moved beyond the tolerance
  payment_method       VARCHAR(30)   NOT NULL CHECK (payment_method IN ('CASH_ON_DELIVERY')),
  customer_note        VARCHAR(500),
  change_for           NUMERIC(12,2) CHECK (change_for > 0),
  assigned_driver_id   BIGINT        REFERENCES users (id),
  idempotency_key      VARCHAR(100)  NOT NULL,
  cancelled_reason     VARCHAR(500),
  failure_reason       VARCHAR(500),
  created_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  version              BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_orders_number ON orders (order_number);
CREATE UNIQUE INDEX ux_orders_user_idempotency ON orders (user_id, idempotency_key);
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX ix_orders_status_slot ON orders (status, slot_date);
CREATE INDEX ix_orders_driver ON orders (assigned_driver_id) WHERE assigned_driver_id IS NOT NULL;
CREATE INDEX ix_orders_phone_failed ON orders (phone, created_at) WHERE status = 'DELIVERY_FAILED';

CREATE TABLE order_items (
  id                    BIGSERIAL PRIMARY KEY,
  order_id              BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
  product_id            BIGINT        NOT NULL REFERENCES products (id),
  -- snapshot: history never depends on the live product row
  name_fr               VARCHAR(200)  NOT NULL,
  name_ar               VARCHAR(200),
  sku                   VARCHAR(40)   NOT NULL,
  sold_by               VARCHAR(10)   NOT NULL CHECK (sold_by IN ('UNIT', 'WEIGHT')),
  unit_price            NUMERIC(12,2) NOT NULL CHECK (unit_price > 0),
  unit_weight_grams     INTEGER       NOT NULL CHECK (unit_weight_grams > 0),
  quantity_ordered      NUMERIC(10,3) NOT NULL CHECK (quantity_ordered > 0),
  quantity_fulfilled    NUMERIC(10,3) CHECK (quantity_fulfilled >= 0),
  line_total            NUMERIC(12,2) NOT NULL CHECK (line_total >= 0),
  substitution_policy   VARCHAR(20)   NOT NULL CHECK (substitution_policy IN ('ALLOW_SIMILAR', 'REMOVE_ITEM', 'CALL_ME')),
  status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'PACKED', 'OUT_OF_STOCK', 'SUBSTITUTED')),
  substitute_product_id BIGINT        REFERENCES products (id)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);

CREATE TABLE order_status_history (
  id          BIGSERIAL PRIMARY KEY,
  order_id    BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
  from_status VARCHAR(30),
  to_status   VARCHAR(30) NOT NULL,
  actor_id    BIGINT      REFERENCES users (id),
  reason      VARCHAR(500),
  at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_history_order ON order_status_history (order_id, at);

CREATE TABLE payments (
  id           BIGSERIAL PRIMARY KEY,
  order_id     BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
  method       VARCHAR(30)   NOT NULL CHECK (method IN ('CASH_ON_DELIVERY')),
  status       VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'COLLECTED', 'NOT_COLLECTED', 'VOID')),
  amount       NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
  collected_by BIGINT        REFERENCES users (id),
  collected_at TIMESTAMP(6) WITH TIME ZONE,
  provider_ref VARCHAR(100),
  note         VARCHAR(500),
  created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_payments_order ON payments (order_id);

-- ---------- idempotency ----------
CREATE TABLE idempotency_records (
  id              BIGSERIAL PRIMARY KEY,
  user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  idem_key        VARCHAR(100) NOT NULL,
  request_hash    VARCHAR(64)  NOT NULL,
  order_id        BIGINT       REFERENCES orders (id) ON DELETE CASCADE,
  created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_idempotency_user_key ON idempotency_records (user_id, idem_key);

-- ---------- stock ledger: append-only; products.stock_quantity is its cached sum ----------
CREATE TABLE stock_movements (
  id         BIGSERIAL PRIMARY KEY,
  product_id BIGINT        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
  delta      NUMERIC(10,3) NOT NULL CHECK (delta <> 0),
  reason     VARCHAR(20)   NOT NULL CHECK (reason IN ('OPENING', 'SALE', 'CANCEL_RESTORE', 'RESTOCK', 'ADJUSTMENT', 'EXPIRED')),
  order_id   BIGINT        REFERENCES orders (id) ON DELETE SET NULL,
  actor_id   BIGINT        REFERENCES users (id) ON DELETE SET NULL,
  note       VARCHAR(300),
  at         TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX ix_stock_movements_product ON stock_movements (product_id, at DESC);

-- The ledger is history: corrections are new rows, never edits. (Deletes stay possible only through the
-- product/order cascades used by tests and data clean-up; the application never deletes movements.)
CREATE FUNCTION reject_stock_movement_update() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'stock_movements is append-only: write a correcting movement instead';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_stock_movements_no_update BEFORE UPDATE ON stock_movements
  FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_update();

-- Opening balance: today's stock becomes the first ledger entry, so stock_quantity = SUM(delta) from day one.
INSERT INTO stock_movements (product_id, delta, reason, note)
SELECT id, stock_quantity, 'OPENING', 'Opening balance (migration V3)' FROM products WHERE stock_quantity <> 0;

-- ---------- Spring Modulith event publication registry (outbox; Phase 7 forwards it to Kafka) ----------
-- Matches spring-modulith-events-jpa 2.1.x (JpaEventPublication); Hibernate validates it at startup.
CREATE TABLE event_publication (
  id                     UUID NOT NULL PRIMARY KEY,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP(6) WITH TIME ZONE,
  status                 VARCHAR(16),
  completion_attempts    INTEGER,
  last_resubmission_date TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX ix_event_publication_incomplete ON event_publication (publication_date) WHERE completion_date IS NULL;

CREATE TABLE event_publication_archive (
  id                     UUID NOT NULL PRIMARY KEY,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP(6) WITH TIME ZONE,
  status                 VARCHAR(16),
  completion_attempts    INTEGER,
  last_resubmission_date TIMESTAMP(6) WITH TIME ZONE
);
