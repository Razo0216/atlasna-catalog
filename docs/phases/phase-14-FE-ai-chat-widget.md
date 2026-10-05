# Phase 14 (Frontend) — AI Chat Widget

| | |
|---|---|
| **Repo** | `atlasna_front` |
| **Prerequisite** | Phase 13 assistant live on staging (`POST /api/assistant/chat` SSE, `/feedback`); Phase 12 same-origin delivery |
| **Read first** | `00-MASTER-PLAN.md` (gate G5), `01-CONVENTIONS.md` §5–6, Phase 8 (`src/lib/sse.ts`), Phase 13 §3 (SSE event contract) |
| **Estimate** | 2 weeks |

## 1. Goal
A friendly, fast, **bilingual chat assistant** reachable from every page: customers ask about orders, prices, stock, delivery or products and get **streaming answers with real product/order cards**, in French or Arabic (RTL), with a clear way to reach a human. It must **never render model output as HTML**, work on weak phones, and not slow down the store.

## 2. Scope
**In:** launcher + chat panel · streaming over the Phase 8 fetch-SSE client · message list with structured cards · input (multi-line, RTL-aware) · quick replies · login-aware behaviour · feedback · human hand-off · privacy notice/consent · error/offline/rate-limit states · accessibility · code-splitting · tests with mocked streams.
**Out:** voice input/output (backlog), file/image upload, write actions inside chat (add-to-cart from chat is a stretch behind a confirm button), push notifications from chat.

## 3. Steps

### Step 0 — Preflight
Branch `phase-14-chat`. Capture **real** SSE transcripts from staging for: a price question, an in-stock check, a delivery-fee question, an order-status question (logged in), the same as a guest (asked to log in), an unanswerable question (hand-off), a rate-limited call, and a model-down error. These define the MSW stream fixtures — **don't invent event shapes**.

### Step 1 — Loading strategy (protect the storefront's performance)
The widget is a **lazy chunk**: only a tiny launcher button is in the main bundle; the panel code loads on first open (or on idle after first paint with `requestIdleCallback`). Verify with the bundle-size budget (Phase 10) that the main chunk grows by only a few KB, and Lighthouse numbers don't regress.

### Step 2 — Chat state & streaming
- `useChat()` hook: messages `{id, role: 'user'|'assistant'|'system', parts: [text | card], status: 'sending'|'streaming'|'done'|'error'}`, `sessionId` (from the server, kept in `sessionStorage`, cleared on logout), abort controller per request.
- Reuse the Phase 8 fetch-based SSE client (auth header, backoff **only for connection setup — never auto-resend a user message**). Handle events `token` (append), `card` (insert a typed card part), `done`, `error`. Render tokens incrementally without re-rendering the whole list (batch with `requestAnimationFrame`).
- **Stop** button aborts the stream; closing the panel mid-stream aborts it; unmounting leaves no listeners. Retry button re-sends the **same** message only on user action.
- Input rules: max 1 000 chars (mirror the server), counter near the limit, Enter to send / Shift+Enter newline (and the correct behaviour with Arabic IME composition — don't send while composing), disabled while streaming, trims whitespace.
- Mask obvious personal data *client-side as a courtesy* (phone-like patterns) with a hint — the server masks again (never rely on the client).

### Step 3 — Safe rendering (the security-critical part)
- Assistant text is rendered as **plain text with minimal formatting** (line breaks, and optionally a tiny allow-listed set: bold, lists) produced by **your own parser**, or a well-maintained Markdown renderer configured with **raw HTML disabled** and links restricted to `https:` / same-origin / `tel:`. **No `dangerouslySetInnerHTML`.** Links open with `rel="noopener noreferrer"`. Test with `<img src=x onerror=alert(1)>`, `javascript:` links, `data:` URLs and Markdown-image tracking pixels — all must be inert.
- Structured **cards** (from `card` events) are rendered by **your own React components** from validated data (Zod): `ProductCard` (name via `pickText`, price with unit, availability, "Voir" link to `/products/:slug`, "Ajouter" using the normal cart — **price shown comes from the card's server data**), `OrderCard` (number, status chip, slot, total with estimate flag, link to `/orders/:n`), `DeliveryInfoCard`. Unknown card types are ignored safely.
- Each assistant message shows a small, honest label ("Assistant IA — vérifiez les informations importantes") — also a transparency/legal good practice.

### Step 4 — UX
- **Launcher:** bottom-end corner (`end-4`, logical CSS), 56 px, doesn't cover primary actions (checkout button, sticky bars), hidden on the checkout confirm step if it blocks the CTA; keyboard shortcut optional.
- **Panel:** bottom sheet on phones (full height with safe-area insets, `dvh` units, handles the on-screen keyboard), floating card on desktop; focus trap, `Esc` closes, focus returns to the launcher.
- **Welcome state:** short greeting + **quick replies** ("Où est ma commande ?", "Livraison à El Bouni ?", "Des produits pour peau sèche ?", "Parler au magasin") in the user's language; logged-in users see order shortcuts. Guests asking about orders get a friendly **"Connectez-vous pour voir vos commandes"** with a link (the server enforces this regardless).
- **Language:** follows the UI language; user may write in any language; message bubbles use `dir="auto"` per message so mixed Arabic/Latin text lays out correctly; bubbles mirror in RTL.
- **Typing indicator**, time-to-first-token skeleton, smooth scroll-to-bottom that **doesn't fight the user** when they scroll up (show a "nouveaux messages ↓" pill).
- **States:** rate-limited (`429` → countdown from `Retry-After`), assistant unavailable (`503` → "Réessayez plus tard" + hand-off), offline (disabled input + explanation), session expired (re-auth then continue), budget exhausted (friendly daily-limit message), empty/long conversation handling.
- **Hand-off:** persistent "Parler au magasin" action → `tel:` and WhatsApp link (numbers from `config.json`/settings); the assistant's own hand-off messages show the same buttons.
- **Feedback:** 👍/👎 per assistant message (optional reason on 👎) → `POST /api/assistant/feedback`; optimistic, idempotent.
- **Privacy:** first-open notice (localised): conversations are processed by an AI service, don't share sensitive data, retention period, link to the privacy policy (Phase 16); "Effacer la conversation" clears local state and asks the server to delete history. Don't open the chat or store anything before the user interacts.

### Step 5 — Accessibility
`role="dialog"` with label, a `role="log"` region with `aria-live="polite"` for new assistant text (announce completed messages, not every token), labelled input and buttons, visible focus, ≥ 44 px targets, colour contrast, reduced-motion respected, screen-reader pass (VoiceOver) recorded, works at 200 % zoom and with large Arabic text.

### Step 6 — Mocks & tests
MSW **streaming** handlers (`ReadableStream` with timed chunks) built from the Step 0 transcripts, including: chunks split mid-event, heartbeats, `card` events, `error` mid-stream, abrupt connection drop, `429`, `503`.
Required tests:
- [ ] SSE parsing of the real transcripts; tokens appended in order; card parts inserted at the right position.
- [ ] Abort: Stop, close-panel and unmount all cancel the request and leave no pending state; **no automatic re-send** of a user message after a failure.
- [ ] Safe rendering suite: HTML injection, `javascript:`/`data:` links, Markdown images, very long words/URLs, RTL+LTR mixed content, 5 000-char responses — nothing executes, nothing breaks layout.
- [ ] Card validation: invalid card payload ignored; product card price equals payload price; unknown type ignored.
- [ ] IME composition doesn't send; Shift+Enter newline; max length; trimming.
- [ ] States: 429 countdown, 503 fallback, offline, expired session, guest-asks-order flow.
- [ ] i18n: all strings fr/ar, `dir="auto"` bubbles, mirrored layout; `i18n:check`, `lint:rtl` pass.
- [ ] Performance: main-chunk delta within budget; chat chunk lazy-loaded; no long tasks on token streaming (profile 500 tokens).
- [ ] Accessibility: axe zero serious/critical; keyboard flow (open → type → send → Esc) via Playwright (add to the E2E suite with the stubbed stream or the real staging assistant behind a flag).

## 4. Acceptance criteria
- [ ] On staging: ask 5 realistic questions in French, 5 in Arabic, 3 in Darija/Arabizi — answers stream, cards render, prices/status match the real data, unknown questions hand off.
- [ ] Logged-in order-status question shows an `OrderCard`; the same as a guest asks to log in; another customer's order number returns "introuvable" (verified manually with two accounts).
- [ ] All injection/XSS samples are inert; CSP (Phase 12) shows **no violations** with the widget active.
- [ ] Storefront Lighthouse mobile scores unchanged within tolerance; widget chunk loads only on interaction/idle.
- [ ] Phone, 360 px, on-screen keyboard open: input never hidden; RTL verified.
- [ ] `npm ci && npm run build && npm run lint && npm test && npm run i18n:check && npm run lint:rtl` green; Playwright chat journey green.

## 5. Out of scope
Voice · attachments · write actions from chat (stretch only) · multi-assistant personas · analytics beyond feedback.

## 6. Commits & tag
`feat(chat): lazy launcher and panel shell` → `feat(chat): streaming state with abort` → `feat(chat): safe rendering and typed cards` → `feat(chat): quick replies, handoff, feedback, privacy notice` → `test(chat): streaming mocks from real transcripts, xss suite` → `test(e2e): chat journey`. Tag `phase-14-done`.

## 7. Concepts & interview questions (for the developer)
**Concepts:** streaming UIs & backpressure · fetch streams vs EventSource · treating model output as **untrusted input** · XSS via Markdown/links · structured outputs (cards) vs free text · code splitting for secondary features · IME composition events · `dir="auto"` & bidirectional text · mobile keyboard/viewport handling · accessible live regions · transparency labels for AI content.
1. Why is rendering model output as HTML dangerous even if *you* wrote the prompt?
2. How do structured cards make the assistant safer and more accurate than model-formatted text?
3. Why must the client **never auto-retry** a sent message, while the SSE client may retry its connection?
4. What problem does `dir="auto"` solve in a bilingual chat?
5. How do you keep a feature like chat from hurting Core Web Vitals?
6. How does the UI announce streaming text to a screen-reader user without being noisy?
