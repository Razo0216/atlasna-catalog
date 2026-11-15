-- Phase 5, step 5: online card payment (CIB / Edahabia through a local gateway, Chargily Pay V2 in sandbox).
-- Cash on delivery is unchanged. ADR-0013: only a verified webhook can mark a payment PAID.

ALTER TABLE orders DROP CONSTRAINT orders_payment_method_check;
ALTER TABLE orders ADD CONSTRAINT orders_payment_method_check
  CHECK (payment_method IN ('CASH_ON_DELIVERY', 'CARD_ONLINE'));

ALTER TABLE payments DROP CONSTRAINT payments_method_check;
ALTER TABLE payments ADD CONSTRAINT payments_method_check
  CHECK (method IN ('CASH_ON_DELIVERY', 'CARD_ONLINE'));

-- PAID / FAILED: card outcomes from the webhook. REFUND_PENDING: a paid order was cancelled (refund done by hand for now).
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('PENDING', 'COLLECTED', 'NOT_COLLECTED', 'VOID', 'PAID', 'FAILED', 'REFUND_PENDING'));

ALTER TABLE payments
  ADD COLUMN provider            VARCHAR(30),
  ADD COLUMN checkout_url        VARCHAR(500),
  ADD COLUMN checkout_created_at TIMESTAMP(6) WITH TIME ZONE,
  -- The gateway charges whole dinars: what we asked it to charge, to compare with what the webhook says was paid.
  ADD COLUMN charged_amount      NUMERIC(12,0) CHECK (charged_amount >= 0),
  ADD COLUMN paid_at             TIMESTAMP(6) WITH TIME ZONE,
  -- The webhook reported a different amount than we asked for: never marked PAID, staff must check.
  ADD COLUMN amount_mismatch     BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX ux_payments_provider_ref ON payments (provider, provider_ref) WHERE provider_ref IS NOT NULL;

-- Every webhook event processed, once: the provider retries deliveries, so the event id is the idempotency key.
CREATE TABLE webhook_events (
  id          BIGSERIAL PRIMARY KEY,
  provider    VARCHAR(30)  NOT NULL,
  event_id    VARCHAR(100) NOT NULL,
  event_type  VARCHAR(50)  NOT NULL,
  outcome     VARCHAR(30)  NOT NULL,
  received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
  CONSTRAINT ux_webhook_events UNIQUE (provider, event_id)
);
