# ADR 0004 — Every error response carries a stable `code`

**Status:** accepted (Phase 1) · **Date:** 2026-11-10

## Context
The UI is French and Arabic. HTTP status alone is ambiguous (`409` can mean email taken, phone taken, SKU taken or a
concurrent edit), and English `message` text is for developers and may change. The frontend needs something stable to
translate and branch on.

## Decision
`ApiError` = `{timestamp, status, error, code, message, fieldErrors}`. `code` is an UPPER_SNAKE string from the list in
`docs/02-DOMAIN-AND-DATA-MODEL.md` §7 (e.g. `VALIDATION_FAILED`, `INVALID_CREDENTIALS`, `ACCOUNT_DISABLED`,
`PHONE_ALREADY_IN_USE`, `PRODUCT_TOO_HEAVY`, `CONCURRENT_MODIFICATION`). Services throw `ApiException(status, code, message)`;
`GlobalExceptionHandler` maps everything else (security, Spring MVC, unique-index violations) to a code. Framework
errors without a domain code use the HTTP status name (`NOT_FOUND`, `METHOD_NOT_ALLOWED`); unexpected errors are
`500 INTERNAL_ERROR` with a generic message and the stack trace logged server-side only.

## Consequences
- Codes are part of the public contract: **add new ones freely, never rename or reuse one.**
- Tests assert the `code` of every error path they cover.
- `fieldErrors` stays the per-field map for `400 VALIDATION_FAILED`.
