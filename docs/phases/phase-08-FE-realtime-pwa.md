# Phase 8 (Frontend) — Real-time Tracking, Notifications & PWA

| | |
|---|---|
| **Repo** | `atlasna_front` |
| **Prerequisite** | Phase 7 backend running through the gateway (events, notification service, `GET /api/orders/{n}/events`, `/api/me/notifications`) |
| **Read first** | `00-MASTER-PLAN.md`, `01-CONVENTIONS.md` §5, `03-API-CONTRACT.md` §5 (Phase 7–8 row) |
| **Estimate** | 3 weeks |

## 1. Goal
Customers **see their order move in real time**, get a **notification centre**, and can **install Atlasna on their phone**; browsing the catalog keeps working on a weak or dropped connection. Drivers' and staff views also go live (no more polling).

## 2. Scope
**In:** authenticated **SSE** streaming with reconnect/backoff and polling fallback · live order tracking · notification centre (in-app, unread badge) · **PWA** (manifest, service worker, offline catalog, install prompt, update flow) · optional Web Push · admin/driver live updates · i18n completeness audit.
**Out:** native apps, background sync of orders, offline *checkout* (an order is never queued offline — money and stock need the server), e2e suite (Phase 10).

## 3. Steps

### Step 0 — Preflight
Branch `phase-8-realtime`. Capture a real SSE stream with `curl -N` (event names, ids, heartbeat) for an order going through several statuses; capture notification payloads (fr + ar). These define the fixtures — **do not guess the event format**.

### Step 1 — Streaming client (`src/lib/sse.ts`)
**The browser's `EventSource` cannot send an `Authorization` header** — so don't use it for authenticated streams. Implement SSE over `fetch` + `ReadableStream` (or a small, well-maintained library; verify it against Vite 8 / TS 6 before adding): parse `id:`, `event:`, `data:`, comments (heartbeats); `Last-Event-ID` on reconnect; **exponential backoff with jitter** (cap ~30 s); stop when terminal; pause when the tab is hidden (and resume + resync on visibility); abort on unmount; surface a connection state (`connecting | live | reconnecting | offline`).
**Fallback:** if streaming fails repeatedly or isn't supported, fall back to the Phase 4 polling transparently. Always **resync from the REST endpoint** after a reconnect — the stream is a hint, REST is the truth.
Handle token expiry mid-stream (refresh → reconnect). Never log tokens or payloads containing personal data.

### Step 2 — Live order tracking
Order detail subscribes to `GET /api/orders/{n}/events`. Events update the TanStack Query cache for that order (`setQueryData`) so every screen showing it updates at once. Timeline animates new steps (respect `prefers-reduced-motion`), announces changes to screen readers (`aria-live="polite"`), and shows contextual messages: "Votre livreur est en route", weighed-total changes ("Total final: …"), `OUT_OF_STOCK` substitution requests with a "Appeler le magasin" action, `DELIVERY_FAILED` with reasons and next steps.
A small "Live / Reconnecting…" indicator (not alarming). Order history list also refreshes statuses via the notification stream.

### Step 3 — Notification centre
`GET /api/me/notifications` (paged), unread count in the header badge (updates live), list grouped by day, click → deep link (order detail), mark read / mark all read, empty state. Content is **plain text from the server** (already localised) — never HTML. Respect the user's language (`fr`/`ar`) and RTL. Optional toast for new notifications while the app is open (dismissible, not stacked forever).
Add a **language preference** setting (stored server-side via the account API) so email/SMS/notification language follows the UI choice.

### Step 4 — Admin & driver live updates
Orders board (Phase 6) subscribes to a staff stream (new orders, status changes) instead of polling; "new order" indicator + optional sound toggle (user gesture required by browsers). Driver view gets assignment/changes live. Keep polling as the fallback and the resync mechanism.

### Step 5 — PWA with `vite-plugin-pwa` (verify the plugin works with Vite 8; if not, hand-write the service worker with Workbox)
- **Manifest:** names in fr/ar (`name`, `short_name`, `lang`, `dir`), icons (192/512, maskable), `theme_color`, `background_color`, `display: standalone`, `start_url`, `scope`.
- **Service worker strategy (be conservative):**
  - Precache the **app shell** (hashed JS/CSS/fonts/icons).
  - Catalog **GET** API responses (`/api/categories`, `/api/products*`): **stale-while-revalidate**, short max-age, bounded entries — so browsing works offline and feels instant.
  - Product images: **cache-first** with an expiry and a maximum count.
  - **Never cache** authenticated responses (cart, orders, account, admin, notifications), any `POST/PUT/PATCH/DELETE`, `/api/auth/*`, or SSE. A cache must never leak one user's data to another on a shared phone.
  - Navigation fallback to the app shell; a friendly **offline page**; clear offline banner in the UI; actions that need the server are disabled with an explanation (**checkout is never queued offline**).
- **Update flow:** `registerType: 'prompt'` — show "Nouvelle version disponible — Actualiser" rather than silently swapping code under a user mid-checkout.
- **Install:** capture `beforeinstallprompt`, show a gentle, dismissible, localised "Installer l'application" entry in the account menu (never a nag); iOS Safari hint ("Ajouter à l'écran d'accueil").
- On logout, clear any user-specific runtime cache and IndexedDB/localStorage keys belonging to the user.

### Step 6 — Web Push (stretch; needs backend support from the notification service)
VAPID keys server-side; `POST /api/me/push-subscriptions`; opt-in only after a clear value explanation (not on first load); handle permission states; notification text localised; click opens the order. Unsubscribe on logout/disable. If the backend piece isn't ready, ship Steps 1–5 and log Step 6 in `docs/BACKLOG.md`.

### Step 7 — Internationalisation completeness
Audit: every string, every `aria-label`, error codes, date/time (slot times in `Africa/Algiers`), plurals (six Arabic forms), RTL in new components (timeline, notification list, banners). `i18n:check` and `lint:rtl` green. Add a pseudo-localisation test run (long strings) to catch layout breakage.

## 4. Required tests
- [ ] SSE parser: multi-line `data:`, ids, comments/heartbeats, partial chunks split mid-line, unknown event types ignored, malformed JSON handled without crashing.
- [ ] Reconnect: backoff sequence with jitter bounds (fake timers), `Last-Event-ID` sent, resync call after reconnect, stops at terminal status, pauses when hidden, aborts on unmount (no leaks).
- [ ] Fallback to polling after N failures; token refresh mid-stream reconnects with the new token.
- [ ] Cache update: an event updates the order everywhere (detail + history) without refetch storms.
- [ ] Notification centre: unread count live, mark-read optimistic with rollback, deep link, RTL, no HTML rendering (inject `<img onerror>` text → rendered as text).
- [ ] Service worker: **a test that proves authenticated URLs and non-GET requests are never cached** (inspect the Workbox route config or run against the built SW); logout clears user caches.
- [ ] Offline behaviour (simulated): catalog pages render from cache; checkout shows the offline explanation and cannot submit; coming back online resumes.
- [ ] Update prompt appears when a new SW is waiting; accepting reloads once.
- [ ] Accessibility: live-region announcements, focus management on banners/dialogs, contrast.

## 5. Acceptance criteria
- [ ] With the real backend: place an order, watch the status change live as staff/driver act in another browser; kill the network for a minute → "Reconnecting…" then state resyncs correctly on return.
- [ ] Lighthouse **PWA** checks pass on the production build (installable, offline-capable shell); the app installs on an Android phone and the iOS "Add to Home Screen" path works.
- [ ] Airplane-mode: previously browsed categories/products still load; cart/checkout clearly explain they need a connection.
- [ ] Two users share a device: after user A logs out, user B never sees A's data from any cache (manual check recorded).
- [ ] Security: no tokens in logs/URLs; notification text never rendered as HTML; SW scope limited to the app.
- [ ] `npm ci && npm run build && npm run lint && npm test && npm run i18n:check && npm run lint:rtl` green.

## 6. Out of scope
Offline checkout/queued orders · native app stores · background sync · analytics.

## 7. Commits & tag
`feat(sse): authenticated fetch-based event stream with backoff` → `feat(orders): live tracking` → `feat(notifications): centre, badge, language preference` → `feat(admin): live board and driver updates` →
`feat(pwa): manifest, service worker, offline catalog, update prompt` → `feat(push): web push (optional)` → `chore(i18n): completeness audit`. Tag `phase-8-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** SSE vs WebSocket vs polling · why `EventSource` can't carry auth headers · reconnection with backoff + jitter · "stream as hint, REST as truth" · service workers & Cache Storage · cache strategies (cache-first, network-first, stale-while-revalidate) · what must never be cached · PWA installability criteria · push notifications & VAPID.
1. SSE or WebSockets for order tracking — and why?
2. How do you authenticate an SSE connection if `EventSource` can't set headers? What are the options and their risks?
3. Describe stale-while-revalidate and where it's appropriate and dangerous in this app.
4. A shared family phone: how do you make sure one user's orders never appear for another via the service worker?
5. Why doesn't Atlasna allow offline checkout?
6. What happens to the stream when the access token expires?
