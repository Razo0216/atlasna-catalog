# ADR 0013 — Card payments: gateway port, webhook as the only source of truth, resilience on every call

**Status:** accepted (Phase 5, step 5 — sandbox only, gate G2) · **Date:** 2026-11-15

## Context
Most customers pay cash on delivery, but CIB / Edahabia card payment is expected. Domestic cards go through SATIM;
Chargily Pay V2 is a developer-friendly gateway on those rails. Merchant onboarding needs a registered business, so we
build and test against the sandbox. The provider is an outside system that can be slow, down, or send the same webhook
several times — and a payment page "success" redirect can be opened by anyone.

## Decision
- **Port and adapter.** `payment.PaymentGateway` (createCheckout, checkoutStatus, parseAndVerifyWebhook, refund stub);
  `ChargilyGateway` is the only adapter. Verified against dev.chargily.com (2026-11-15): base URL
  `https://pay.chargily.net/test/api/v2`, `Authorization: Bearer <secret>`, `POST /checkouts {amount (DZD integer),
  currency "dzd", success_url, failure_url, webhook_endpoint, locale, metadata}` → `{id, checkout_url}`; webhooks signed
  with header `signature` = hex HMAC-SHA256(raw body, secret). The key comes from `CHARGILY_SECRET_KEY`; without it card
  payment is not offered (`422 CARD_PAYMENT_UNAVAILABLE {reason: NOT_CONFIGURED}`).
- **Flow.** Place the order with `paymentMethod: CARD_ONLINE` (stock and slot reserved as usual, payment PENDING) →
  `POST /api/orders/{n}/pay` → `{checkoutUrl}` → customer pays on the provider's page → **webhook** →
  PAID / FAILED. `POST /api/orders/{n}/pay-on-delivery` switches to cash at any time before payment and confirmation.
- **Card only when the total is final.** Orders with per-kg lines (weighed at packing) can't be paid by card
  (`CARD_PAYMENT_UNAVAILABLE {reason: ESTIMATED_TOTAL}`) — charging an estimate would need partial refunds or top-ups.
- **The webhook is the only source of truth.** `POST /api/webhooks/payments` (public): signature verified over the
  RAW bytes with a constant-time compare (`401 WEBHOOK_SIGNATURE_INVALID`, nothing changes); events older than 72 h are
  refused (`400 WEBHOOK_STALE`); each provider event id is claimed once in `webhook_events` in the same transaction as
  the payment update (re-delivery → `DUPLICATE`, no change); the paid amount must equal what we asked the gateway to
  charge (`charged_amount`, whole dinars) — otherwise the payment is flagged `amount_mismatch` and stays PENDING. The
  browser's success redirect changes nothing.
- **Resilience4j, in this order:** HTTP connect/read **timeouts** (2 s / 5 s) → **bulkhead** (≤ 10 concurrent gateway
  calls, no waiting) → **circuit breaker** (opens at 50 % failures/slow calls over 10, 30 s open, 2 trial calls).
  **Retry only idempotent reads** (checkout status: 3 attempts, exponential backoff with jitter). **Creating a checkout
  is never retried**: Chargily has no idempotency key, so a retry after a timeout could create a second checkout. Any
  failure → `503 PAYMENT_UNAVAILABLE` within the timeout, and the UI offers cash on delivery. The gateway call runs
  outside any DB transaction (a slow provider never holds a connection). Breaker state is on `/actuator/health`.
- **Unpaid card orders expire** after 30 min (Quartz job every minute, idempotent): cancelled by the system, stock back,
  slot released. A payment that arrives after that is marked `REFUND_PENDING`.
- **Cancel after payment** → `REFUND_PENDING` (refunds by hand until merchant onboarding). A card-paid order is delivered
  without collecting cash (`cashCollected` optional for it).

## Consequences
- Locally, the provider can't reach `localhost` webhooks: use a tunnel (e.g. `cloudflared`) and set
  `ATLASNA_PAYMENT_WEBHOOK_URL`, or simulate webhooks with a signed `curl` (README).
- Out-of-stock lines or substitutions on a card order change the total after payment: staff refund the difference by hand
  (Phase 6 back-office shows `REFUND_PENDING` / `amount_mismatch`).
- Production needs a registered business and an Algerian bank / Algérie Poste account (law 18-05), and the live key in a
  secret manager.
