# ADR 0009 — Token strategy: short JWT in memory + rotating refresh token in an httpOnly cookie

**Status:** accepted and implemented (Phase 5, step 3; written before coding) · **Date:** 2026-11-15

## Context
Today (Phases 1–4) login returns one JWT valid 60 minutes; the storefront keeps it in `localStorage` and the customer
logs in again every hour. Two problems:
- **Theft:** any XSS on the storefront can read `localStorage` and replay the token from anywhere until it expires.
- **No real logout:** a JWT can't be revoked; "log out" only forgets it in this browser.
Customers in Annaba shop on their phone over days ("I'll finish the order tonight"); an hourly re-login loses carts
and orders, so sessions must last weeks — which makes theft worse if we simply lengthen the JWT.

## Options
| | A. Both tokens in `localStorage` | B. Access in memory, refresh in httpOnly cookie (**chosen**) |
|---|---|---|
| XSS can steal a long-lived credential | **Yes** (the refresh token) | No: JavaScript can't read an `HttpOnly` cookie |
| XSS can act *while the page is open* | Yes | Yes (no storage choice fixes XSS itself — CSP and escaping do) |
| CSRF | Not possible (nothing sent automatically) | Possible on the refresh route → needs `SameSite=Strict` + a custom header |
| Survives a page reload | Yes | Yes, via one silent `POST /api/auth/refresh` at start-up |
| Needs same site for API and storefront | No | **Yes** (Phase 12 serves both behind one load balancer) |
| Complexity | Low | Medium |

## Decision
- **Access token:** JWT, **15 minutes**, sent as `Authorization: Bearer`. The storefront keeps it **in memory only**
  (a page reload gets a new one from the refresh endpoint). Server side unchanged: stateless check, role re-read from
  the database (as today).
- **Refresh token:** opaque random value, **256 bits** from `SecureRandom`, base64url. Never a JWT (it must be revocable).
  - Stored in **Redis, hashed with SHA-256** (a Redis dump never contains usable tokens):
    `atlasna:v1:refresh:{hash} → userId|familyId`, TTL = remaining lifetime (sliding: each rotation gets a fresh 30 days).
    No user agent or IP is stored (not needed for the logic; less personal data).
  - Lifetime **30 days** (configurable). Each user/device login starts a new **family**.
  - Sent as cookie `atlasna_refresh`: `HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=2592000`.
    `Path=/api/auth` keeps it off every other request.
- **Rotation on every use:** `POST /api/auth/refresh` consumes the presented token (single use, atomic `GETDEL`), issues a
  new refresh token in the same family + a new access token.
- **Reuse detection:** a consumed token is remembered (`atlasna:v1:refresh-used:{hash} → familyId`) until its original
  expiry. If it is ever presented again, someone has a copy → **revoke the whole family** (both the attacker and the
  real user must log in again) and log a security event.
- **CSRF on refresh/logout:** `SameSite=Strict` **and** a required header `X-Atlasna-Refresh: 1` (a cross-site form
  cannot set custom headers; a cross-site `fetch` with it is blocked by CORS preflight).
- **Logout** `POST /api/auth/logout`: revokes the family, clears the cookie (`Max-Age=0`). **Disabling a user** or
  **changing the password** revokes all of that user's families.
- **Redis down:** refresh fails **closed** (`503`, the user logs in again later); existing access tokens keep working
  until they expire. Login itself does not depend on Redis being up for the access token, only for the refresh cookie.
- **Dev on http://localhost:** browsers accept `Secure` cookies on `localhost`; the frontend (5173) and API (8080) are
  the same *site* (`localhost`), so `SameSite=Strict` still sends the cookie with `credentials: 'include'`.
  CORS must allow credentials for the listed origins only (never `*`).

## Consequences
- Phase 5 builds the endpoints and tests (rotation, reuse revokes the family, logout, expired/forged rejected, cookie
  flags, CSRF header required). **The frontend adopts it in Phase 6**; until then the 60-minute token keeps working
  (login response unchanged, the cookie is an addition), so nothing breaks in between.
- Production needs storefront and API on the same registrable domain (e.g. `atlasna.com.dz` + `/api` behind one load
  balancer, Phase 12) — otherwise third-party-cookie rules would drop the cookie.
- One Redis lookup per refresh (every ~15 min per active user) — negligible.
- XSS still lets an attacker act while the victim's page is open; the defences for that are output escaping (React),
  no `dangerouslySetInnerHTML`, and a strict CSP (Phase 10).

## Implementation notes (step 3)
- Consume + remember-as-used is one Lua script (`auth/refresh-consume.lua`), so two parallel refreshes with the same
  token can't both succeed. Families: `atlasna:v1:refresh-family:{id}` → current token hash; `refresh-user:{userId}` →
  set of families (for "log out everywhere").
- A browser with two tabs refreshing at the same moment would trip reuse detection and log the user out: the storefront
  (Phase 6) must run refreshes single-flight and share the result between tabs.
- The access token stayed at 60 minutes until the storefront adopted refresh; since Phase 6 it is **15 minutes** (`atlasna.jwt.expiration-minutes`).
- If Redis is down at login/sign-up the user is still logged in, just without a refresh cookie (logged warning).
- Dev profile: `cookie-secure: false`, because Safari refuses `Secure` cookies on `http://localhost`.

## Grace window (Phase 6, 2026-11-15)
Found while testing the back-office: if the page reloads (or the phone loses signal) while `POST /api/auth/refresh` is
in flight, the server has already rotated the token but the browser never stores the successor cookie. The next refresh
sends the old token and strict reuse detection revoked the whole session — an honest user logged out.

**Decision:** a just-consumed token may be presented **once** more within `atlasna.refresh-token.reuse-grace`
(default 30 s). The Lua script keeps `atlasna:v1:refresh-grace:{hash}` for that window and deletes it on its one use;
the store then revokes the orphaned successor and issues a new token, so the family keeps exactly one live token.
A third presentation, or any presentation after the window, is reuse as before (family revoked). No grace if the
family was revoked meanwhile (logout, password change, disabled account).

**Trade-off:** a thief replaying a stolen token within those 30 s gets one fresh token and the real user's next
refresh fails (they log in again, which starts a new family). This is the usual "reuse interval" compromise; the
window is short and single-use. Tests: `RefreshTokenTests.lostResponse_*`, `theGraceIsOneExtraUse_*`, `noGraceAfterLogout`.
