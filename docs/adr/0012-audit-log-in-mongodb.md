# ADR 0012 — Audit log in MongoDB, written from application events

**Status:** accepted (Phase 5, step 4) · **Date:** 2026-11-15

## Context
The owner needs to answer "who changed this price / who cancelled this order / who disabled this account, and when?".
Audit entries are append-only, schema-light (each action carries different fields), queried by entity/actor/time,
and expire after a retention period. They must never slow down or break the business operation they describe.

## Decision
- **Store:** MongoDB `audit_logs` (the only other Mongo use will be chat transcripts, Phase 13). Orders and products
  stay in Postgres: they need transactions, joins and constraints; an audit line needs none of that.
- **Shape:** `{_id, at, actorId, actorRole, ip, action, entityType, entityId, before?, after?, details?}`;
  `before/after` contain **only the changed fields** (`FieldChanges.diff`, BigDecimal compared by value).
- **What is logged:** PRODUCT_CREATED/UPDATED/DELETED/RESTORED, CATEGORY_*, STOCK_ADJUSTED (opening, restock,
  adjustment, expiry — not sales, which order history covers), ORDER_PLACED, ORDER_STATUS_CHANGED (with reason),
  SETTINGS_UPDATED, USER_CREATED/ENABLED/DISABLED, PASSWORD_CHANGED, LOGIN_FAILED (email tried + reason).
  Never: passwords, tokens, phone numbers.
- **Written from events, not scattered calls.** Modules publish what happened (`CatalogChanged`, `StockAdjusted`,
  `OrderPlaced`, `OrderStatusChanged`, `StoreSettingsChanged`, `UserAccountChanged`, `LoginFailed`), carrying an
  `Actor(id, role, ip, at)` captured at publish time; the `audit` module decides what to record. Business code doesn't
  know the audit log exists.
- **After commit, asynchronously** (`@Async @TransactionalEventListener`): rolled-back changes are never logged; Mongo
  latency never reaches the request; the listener opens no JDBC transaction, so a Mongo timeout never pins a DB connection.
- **Not lost:** Spring Modulith persists each publication before delivery and completes it only when the listener
  returns. A failed write throws → stays incomplete → re-delivered at startup
  (`spring.modulith.events.republish-outstanding-events-on-restart`). Failures increment
  `atlasna.audit.writes{outcome=failure}` (the alert).
- **Idempotent writes:** `_id` = SHA-256 of the event (including the actor's timestamp). A timed-out insert that MongoDB
  actually completed (found by the chaos test: a paused server stores the write after resuming) plus its re-delivery
  yields one entry, not two — a duplicate key counts as success.
- **Retention:** TTL index on `at` (365 days, `atlasna.audit.retention`), plus indexes for entity, actor and action.
- **Fail fast:** Mongo client server-selection 2 s, socket 5 s.
- **Read:** `GET /api/admin/audit-logs?entityType&entityId&actorId&action&from&to&page&size` (ADMIN only, newest first).

## Consequences
- Every audited event also writes an `event_publication` row (Modulith outbox) — the price of not losing entries.
- Inside tests, Spring attaches a mock request to the test thread, so service calls there look "ANONYMOUS 127.0.0.1";
  in production, scheduled jobs log as SYSTEM.
- Delivery zone/slot edits are not audited yet (add a `DeliveryChanged` event when the back-office edits them, Phase 6).
