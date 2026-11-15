# ADR 0011 — Rate limiting: Redis token buckets, explicit fail-open/closed, trusted client IP

**Status:** accepted (Phase 5, step 2) · **Date:** 2026-11-15

## Context
Phase 1 throttled logins with an in-memory fixed-window counter. Two flaws once we run more than one instance (Phase 7+):
each instance counts separately (two instances = twice the password guesses) and a restart forgets every lock. Sign-ups
and order placement weren't limited at all. Per-IP limits also depend on knowing the client's IP, and behind a load
balancer the naive answer (`X-Forwarded-For`) is a header any client can forge.

## Decision
- **Token bucket in Redis, one atomic Lua script** (`ratelimit/token-bucket.lua`). A bucket holds `capacity` tokens and
  refills continuously (`capacity / period`). Each check reads, refills, takes and writes in one script, which Redis
  runs without interleaving — 50 parallel requests can't share the last tokens (tested). Time comes from the Redis
  server (`TIME`), so all instances agree. A bucket's key expires when it would be full again: idle subjects cost nothing.
  - *Why token bucket over fixed window:* no "double burst" at window edges; after a lock, access comes back
    gradually (one try every `period / capacity`) instead of all at once.
- **Policies** (`atlasna.rate-limit.*`, `RateLimitPolicies`):

  | Policy | Limit (default) | Subject | If Redis is down |
  |---|---|---|---|
  | `login-email` | 5 wrong passwords / 15 min, then 1 per 3 min | normalised email | **deny** (503) |
  | `login-ip` | 20 attempts / min | client IP | **deny** (503) |
  | `register-ip` | 10 sign-ups / hour | client IP | allow |
  | `orders` | 10 placement attempts / hour | user id | allow |

  Exceeded → `429 TOO_MANY_REQUESTS` + `Retry-After` (seconds, rounded up). The register default is 10, not 5:
  Algerian mobile carriers put many subscribers behind one public IP (CGNAT), so a strict per-IP limit would block real
  neighbours. The dev profile raises it for the Postman run; the test profile lifts register/orders limits (all MockMvc
  requests come from 127.0.0.1) and `RateLimitTests` exercises small real limits.
- **Fail open or closed — explicitly, per policy.** Login throttling is a security control: if Redis can't be asked,
  login answers `503 RATE_LIMIT_UNAVAILABLE` + `Retry-After: 30` rather than allowing unlimited guessing. Sign-ups and
  orders let the request through (a Redis outage must not stop the shop; idempotency keys already prevent accidental
  duplicate orders). Redis calls use the `rateLimiter` circuit breaker and the 250 ms command timeout, so an outage
  costs milliseconds, not seconds. Metric: `atlasna.ratelimit.events{policy, outcome=limited|redis-error}`.
- **No personal data in Redis:** subjects (emails, IPs, user ids) are SHA-256-hashed into the key.
- **Client IP:** `ClientIpResolver`. With no trusted proxies configured (default, dev): the TCP peer address;
  `X-Forwarded-For` is ignored. With `atlasna.http.trusted-proxies` (IPs/CIDRs of our own load balancer, Phase 11):
  only when the request comes *from* a trusted proxy, walk the header from the right and take the first address that is
  not ours. The client-controlled left end is never used; non-IP content is never resolved via DNS.
- Every order placement attempt counts, including replays of the same Idempotency-Key: the limit is generous enough
  that a retrying phone never meets it, and it keeps the logic in one place (the controller).

## Consequences
- Limits hold across instances and restarts. The in-memory `FixedWindowRateLimiter` is deleted.
- Login now depends on Redis being reachable (by design). Redis availability becomes part of login availability:
  Memorystore (Phase 11) has an SLA; the breaker keeps the failure fast and visible.
- The lockout after 5 wrong passwords is no longer a flat 15 minutes: one more attempt is allowed every 3 minutes
  (still at most ~25 guesses per hour per account, plus the per-IP cap).
- Assistant (Phase 13) per-user daily budgets reuse the same `RateLimiter` with a new policy.
