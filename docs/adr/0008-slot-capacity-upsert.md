# ADR 0008 — Slot capacity with one atomic upsert

**Status:** accepted (Phase 3) · **Date:** 2026-11-13

## Context
Each delivery slot (e.g. 14:00–16:00) takes at most `capacity` orders per day (default 8). Counting orders and then
inserting races exactly like stock does.

## Decision
`slot_usage(slot_id, slot_date, booked)` holds the count, changed by one statement:
`INSERT … VALUES (:slot, :date, 1) ON CONFLICT (slot_id, slot_date) DO UPDATE SET booked = slot_usage.booked + 1
WHERE slot_usage.booked < :capacity RETURNING booked`. No row back → `409 SLOT_FULL`. Cancelling an order decrements
it (never below zero). The time rules (lead time, same-day cut-off, opening hours, days ahead, shop closed) live in one
pure function, `SlotRules.check`, unit-tested at every boundary in Africa/Algiers time.

## Consequences
- Tested: 30 parallel bookings for a slot of 8 → exactly 8 orders, `booked = 8`; removing the `WHERE` makes it fail.
- The public slot list shows `remaining` and, when unavailable, the reason (`FULL | PAST_CUTOFF | TOO_SOON | CLOSED`).
