# Review Checklist — Phase 1 Backend (Auth + Catalog)

Run through this **after** Claude Code says the task is done. The goal is
to verify with your own eyes and your own terminal commands — not just
trust a "done, tests passed" message. Each check tells you what to run and
what a correct result looks like.

---

## 1. It actually compiles from a clean state

```bash
./mvnw clean compile
```
**Expect:** no errors. If Claude Code only ran `compile` (not `clean compile`)
during development, stale class files can hide a real problem — always
verify clean.

## 2. Tests actually run and actually test something real

```bash
./mvnw test
```
**Expect:** all tests pass, and the summary shows a number **greater than
1** (a single `contextLoads` test passing means almost nothing — it just
confirms the app starts, not that any logic is correct).

**Then open the test files yourself** and check:
- Is there a test that asserts a **customer token gets rejected** (403) when
  trying to create a product? This is the single most important security
  test in this phase — if it's missing, ask Claude Code to add it.
- Do tests use real assertions (`assertThat(x).isEqualTo(y)`), not just
  "doesn't throw an exception"?

## 3. The security rule is actually correct, not just present

Open `SecurityConfig.java` and manually check:
- [ ] `requestMatchers(HttpMethod.GET, "/api/products/**")` — the method is
      `HttpMethod.GET` (an actual object), **not** the string `"GET"`. If
      it's a string, this is the exact bug I warned about — it silently
      does the wrong thing instead of erroring.
- [ ] `.anyRequest().authenticated()` is the **last** rule in the chain —
      Spring Security checks rules top-to-bottom, first match wins. If a
      broad `.anyRequest()` rule appears before your specific ones, all
      your specific rules become dead code.
- [ ] `.csrf(csrf -> csrf.disable())` has a comment or your own understanding
      of *why* — CSRF protection is only safe to disable for a stateless,
      token-based API. If this project ever adds cookie-based sessions,
      this line needs revisiting.

## 4. Prove the security actually works — don't just read the code, run it

```bash
docker compose up -d
./mvnw spring-boot:run
```

In another terminal:
```bash
# Should work with no auth at all
curl -i http://localhost:8080/api/products

# Register a customer, save the token
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test","email":"test@example.com","password":"SuperSecret1"}'

# Try to create a product AS THAT CUSTOMER — must fail
curl -i -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer <paste customer token here>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Hack","price":1,"category":"ELECTRONICS","stockQuantity":1}'
```
**Expect:** `403 Forbidden` on that last one. **If you get `201 Created`
instead, the security is broken** — regardless of what Claude Code or the
tests claimed. This single curl command is the real source of truth.

Then confirm the admin path works:
```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@atlasna.dz","password":"ChangeMe123!"}'
# use that token to create a product — should succeed with 201
```

## 5. Passwords are actually hashed, not stored in plain text

```bash
docker exec -it atlasna-postgres psql -U atlasna -d atlasna -c "SELECT email, password_hash FROM users LIMIT 3;"
```
**Expect:** `password_hash` starts with `$2a$` or `$2b$` (BCrypt's format).
**If you see the actual plaintext password** — that's a critical bug, stop
and fix it before doing anything else.

## 6. No secrets committed to git

```bash
git log -p | grep -i "password\|secret" | head -20
```
**Expect:** only the placeholder dev values from `application.yml`
(`atlasna_dev_password`, the dev-only JWT secret) — never a real credential.
Also check:
```bash
git show HEAD --stat
```
to confirm no `.env` file or IDE config with real secrets slipped in.

## 7. Validation actually rejects bad input

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"","email":"not-an-email","password":"short"}'
```
**Expect:** `400 Bad Request` with field-level error messages, not a 500 or
a silent success.

## 8. Read every file once, even the "boring" ones

Specifically skim:
- `GlobalExceptionHandler.java` — does it leak internal exception messages
  or stack traces to the client anywhere? It shouldn't.
- `DataSeeder.java` — is the seeded admin password (`ChangeMe123!`) only
  ever used for local dev, with a comment saying so? It should never look
  like something meant for a real environment.

## 9. Git history is honest

```bash
git log --oneline
```
**Expect:** commit messages that describe what was actually done, and a
reasonable number of commits (not one giant "everything" commit hiding 19
files' worth of changes with no way to tell what happened when).

---

## If anything fails

Don't ask Claude Code to "just fix it" blindly — paste it the **exact**
curl output or error, and the specific checklist item that failed. Vague
prompts get vague (or worse, superficially patched) fixes. Precise repro
steps get real fixes.
