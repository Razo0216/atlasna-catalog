# ADR 0003 — Integration tests run on PostgreSQL (Testcontainers), not H2

**Status:** accepted (Phase 1) · **Date:** 2026-11-10

## Context
The schema uses PostgreSQL features H2 does not reproduce faithfully: `JSONB` attributes, `NUMERIC(10,3)` quantities,
partial unique indexes (`ux_users_phone … WHERE phone IS NOT NULL`), and Flyway migrations written in PostgreSQL SQL.
Tests passing on H2 would not prove the real schema works.

## Decision
`PostgresIntegrationTest` starts one `postgres:16-alpine` container per test JVM (`@ServiceConnection`) and every
integration test extends it. Flyway builds the schema and Hibernate runs with `ddl-auto=validate`.

## Consequences
- Docker must be running for `./mvnw verify` (locally and in CI).
- Tests are slower to start (~15 s once), but they catch entity ⇄ migration mismatches and real constraint behaviour
  (e.g. the phone-registration race test relies on the real unique index).
