# ADR 0007 — Idempotent order placement

**Status:** accepted (Phase 3) · **Date:** 2026-11-13

## Context
On a weak mobile connection the app may time out and retry `POST /api/orders`. Without protection the customer gets
two orders (and two deliveries of cash-on-delivery goods).

## Decision
- The client sends `Idempotency-Key: <uuid>` (required) — one new key per checkout attempt, reused for retries.
- At the start of the transaction the server claims `(user_id, key)` in `idempotency_records` with
  `INSERT … ON CONFLICT DO NOTHING RETURNING id`. A concurrent duplicate waits on the unique index until the first
  transaction commits, then finds the record: same request hash → it returns the **original order with 200**;
  different body → `409 IDEMPOTENCY_KEY_REUSED`. `orders(user_id, idempotency_key)` is unique as a second guard.
- The record stores the order id (not a frozen response body), so a replay shows the order's current state.
- If placement fails (no stock, slot full), the claim rolls back with everything else: retrying the same key tries again.

## Consequences
- Tested: the same key twice → one order, `200` with the same number; 10 parallel identical requests → one order.
- `ON CONFLICT DO NOTHING` (not catching a unique violation) keeps the Postgres transaction usable.
