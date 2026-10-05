# Phase 16 (Frontend) — Launch Readiness

| | |
|---|---|
| **Repo** | `atlasna_front` (+ small settings/content endpoints in the backend) |
| **Prerequisite** | Phase 15 done (or the Cloud Run staging from Phase 11/12 — both are valid targets); Phase 14 chat widget done |
| **Read first** | `00-MASTER-PLAN.md` (**gates G1 and G6**), `01-CONVENTIONS.md` §5, `02-…` §2 |
| **Estimate** | 2 weeks (plus real-world admin work that code can't do) |

> This phase is about everything between "it works" and "a stranger in Annaba trusts it with their money". Some of it is legal/business work **only you can do** (registration, tax id, domain, lawyer). This document tells Claude Code what to *build* and tells you what to *obtain*. **Not legal advice** — confirm requirements with a local professional.

## 1. Goal
The storefront is **complete, honest and compliant-by-design**: every legally expected disclosure is present before purchase, real customers can use it without help, the store can be "closed" or on Ramadan hours without a deploy, content reads naturally in French and Arabic, and a small group of real Annaba customers have tested it.

## 2. Scope
**In:** legal & trust pages · pre-purchase disclosures · store-status/hours/holiday handling · content & copy pass (fr/ar) · cookie/consent only if needed · SEO finishing · error/maintenance pages · accessibility & performance sign-off · real-user testing · analytics funnel review · support entry points · launch checklist.
**Out:** marketing campaigns, payment onboarding paperwork, new features.

## 3. What you (the owner) must obtain — track in `docs/launch-checklist.md`
- [ ] Commercial registration (RC) and tax id (NIF) for e-commerce activity; check the current category/requirements with the CNRC or your accountant.
- [ ] **Hosting & domain decision (gate G1)**: Law 18-05 describes an Algerian-hosted `.com.dz` site — get a professional opinion; ADR-0026 must be resolved and the production environment chosen accordingly (the portable image from Phase 12 helps).
- [ ] Payment onboarding if offering cards (gate G2): merchant account with the chosen gateway; proceeds into an Algerian bank/Algérie Poste account as the law expects.
- [ ] Business facts for the footer and CGV: legal name, RC, NIF, address, phone, email, opening hours, delivery area, return/cancellation policy.
- [ ] Insurance/health-and-safety/food-handling requirements for selling groceries and cosmetics (labels, expiry, storage) per local regulation — ask the relevant authority.
- [ ] A native Arabic **and** French reader to review all copy.

## 4. Steps (what Claude Code builds)

### Step 0 — Preflight
Branch `phase-16-launch`. Inventory every user-facing string and page. Create `docs/launch-checklist.md` from §3 and the acceptance list below.

### Step 1 — Legal & trust pages (content in fr + ar; text supplied by the owner, structure by you)
Routes under `/legal/*`, linked from the footer on **every** page: **Mentions légales / معلومات قانونية** (legal name, RC, NIF, address, contact, hosting provider), **Conditions générales de vente (CGV)** (ordering, prices incl. taxes, delivery areas & slots, payment, per-kg final-price rule, substitutions, cancellation, failed delivery, returns/refunds for food and cosmetics, liability, complaints, applicable law), **Politique de confidentialité** (what data, why, retention, processors incl. the AI service and payment gateway, rights of access/rectification/deletion under Law 18-07 and how to exercise them, cookies/storage), **Politique de retour & remboursement**, **Livraison** (zones, fees, hours, slots), **FAQ**, **Contact**.
Content lives in versioned markdown/JSON (not hard-coded in components), rendered with the safe renderer from Phase 14. Show **effective date and version** on each page. A **placeholder detector** test fails the build if any `TODO`, `[À COMPLÉTER]` or lorem text remains in a production build.

### Step 2 — Pre-purchase disclosures in the flow
At checkout (Phase 4), before the confirm button: store identity line (name, RC/NIF), **total price including delivery and any taxes**, the per-kg "estimated, final price after weighing" notice, delivery window, payment method, link to CGV/returns with an explicit acknowledgement checkbox (**record acceptance**: version + timestamp sent with the order and stored server-side). Product pages show the **full price** and unit price; no hidden fees; delivery fee visible from the cart onward (given a zone).
Order confirmation page and email/SMS carry the order summary and cancellation instructions.

### Step 3 — Store status, hours & Ramadan mode (data-driven)
Backend `GET /api/store/status` (public, cached briefly): `{open, nextOpening, closesAt, banner{fr,ar}?, deliveryPaused?, holidayHours}` driven by `store_settings` + a **holiday/special-hours table** (admin-editable). Frontend: a header status chip ("Ouvert jusqu'à 21:00"), a dismissible banner for announcements (Ramadan hours, Eid closures, bad weather), **"store closed" mode**: browsing allowed, checkout disabled with the next available slot, or ordering allowed for later slots depending on settings. Slot availability already respects hours (Phase 3) — verify the UI explains why slots are unavailable. Admin UI (Phase 6) gets a simple page to edit this. Test with a fake clock around opening/closing and midnight in `Africa/Algiers`.

### Step 4 — Content & copy pass
Every screen reviewed in both languages: natural Algerian-appropriate French and Modern Standard Arabic (avoid machine-translated stiffness; keep brand tone friendly and short), consistent terminology (panier/سلة، commande/طلب، créneau/موعد التسليم، repère/نقطة مرجعية), units (kg/كغ، g/غ، ml/مل، L/ل), currency via `Intl`. Empty states, error messages, success messages, email/SMS templates, product attribute labels, category names. Run the **pseudo-localisation** and **long-string** tests again. Record the reviewer and date in the PR.

### Step 5 — Consent & storage
Inventory what's stored in the browser (session/refresh cookie, language, cart, PWA caches) and which cookies/trackers exist. If you only use strictly necessary storage and cookieless first-party analytics, a full consent banner may not be required — **document that reasoning in the privacy policy and ADR-0047 (confirm with your lawyer)**. If any non-essential tracking exists, implement a real consent UI (granular, no pre-ticked boxes, remembered, revocable) and make scripts load only after consent.

### Step 6 — Support & failure UX
`/contact` with tap-to-call, WhatsApp link, email, hours, address and a simple form (rate-limited, spam-protected without third-party trackers — honeypot + server rate limit). Friendly **404/500/offline/maintenance** pages (localised); a **maintenance mode** switch (config) that serves a static page while the API is down for upgrades; order-problem help ("Un souci avec ma commande ?") on order detail with the order number prefilled.

### Step 7 — SEO & sharing finish
Per-page localised titles/descriptions, canonical URLs, `hreflang` decision implemented, product/category **sitemap** generated from the catalog in CI/CD, `robots.txt`, JSON-LD `Product/Offer` and `LocalBusiness`/`Store` (name, address, opening hours, geo, phone) on the home/contact page, Open Graph images, favicon set, `manifest` polish. Submit sitemaps to search consoles **after** the domain decision (G1).

### Step 8 — Sign-off audits
Run and record: full Playwright suite (fr/ar), axe on **all** routes, Lighthouse mobile on key pages (targets from Phase 10), security-header scan, CSP enforcement clean, dependency audit, SBOM, broken-link check, the placeholder detector, a **keyboard-only** and **screen-reader** pass, test on at least **three real phones** (an older Android, a mid-range Android, an iPhone) over a real mobile connection.

### Step 9 — Real-user test (do this, it finds what tests can't)
Recruit **5–8 real customers in Annaba** (family/neighbours count) with mixed ages and languages. Give each realistic tasks without help: find couscous and tomatoes (kg), add to cart, register, place a COD order for tomorrow afternoon, track it, ask the chatbot about delivery to their neighbourhood, call the store. Observe silently; note every hesitation. Capture: task success, time, errors, quotes, language used. Fix the top issues; log the rest in `docs/BACKLOG.md`. Re-test the fixes with 2 people.

## 5. Acceptance criteria
- [ ] No placeholder text anywhere (detector green); both languages reviewed by a native reader; terminology consistent.
- [ ] Legal pages present and linked everywhere with version/date; checkout shows the required disclosures and **records acceptance** (verify in the DB/API).
- [ ] Store status/holiday/Ramadan hours editable by admin and reflected live without a deploy; closed-store behaviour tested.
- [ ] Contact, tap-to-call and WhatsApp work on phones; maintenance/404/500 pages exist and are localised.
- [ ] Consent approach documented (and implemented if tracking exists).
- [ ] All Phase 10 quality gates pass on the production build; manual device matrix recorded.
- [ ] **User-test report** (`docs/user-test-report.md`) with findings and the fixes made; ≥ 80 % unaided task success on the core COD order task before launch.
- [ ] `docs/launch-checklist.md`: every owner item has a status (done / owner / date); **gates G1, G2, G6 each have a written decision.**

## 6. Out of scope
Marketing, SEO campaigns, loyalty/promotions engine, new payment methods, native apps.

## 7. Commits & tag
`feat(legal): legal pages from versioned content, placeholder detector` → `feat(checkout): disclosures and recorded acceptance` → `feat(store): status, hours, holiday banner, closed mode` → `chore(content): fr/ar copy pass` →
`feat(support): contact, whatsapp, error and maintenance pages` → `feat(seo): jsonld, sitemap, meta` → `test: final audits and device matrix` → `docs: launch checklist, user-test report`. Tag `phase-16-done`.

## 8. Concepts & interview questions (for the developer)
**Concepts:** compliance-by-design · data protection basics (purpose limitation, minimisation, retention, data-subject rights) · recording consent/acceptance with versions · content as data · time-zone-correct business hours · localisation quality vs translation · usability testing methods (think-aloud) · launch readiness & rollback planning · structured data for local businesses.
1. Why record *which version* of the CGV a customer accepted, and where do you store it?
2. How would you let the owner switch to Ramadan hours with no deploy — and test that the UI and slot logic agree?
3. What's the difference between translation and localisation for an Arabic/French store?
4. What did the user test reveal that your automated tests could not?
5. Which personal data do you hold, why, for how long, and how does a customer ask you to delete it?
6. What must be true before you flip DNS to production?
