# ADR 0016 — Search normalisation (French accents, Arabic variants) and typo tolerance in Postgres

**Status:** accepted (Phase 5, step 8) · **Date:** 2026-11-15

## Context
Phase 1 search was `lower(name) LIKE '%q%'`. Real Annaba customers type "creme" for "Crème", "oeufs" for "Œufs",
"dates" for "Dattes", "couscouss", and Arabic with or without diacritics and with the usual letter variants
(أ / ا, ة / ه, ى / ي). A search engine (Elasticsearch/OpenSearch) would be another system to run for a catalogue of a few
hundred products; Postgres already has `unaccent` and `pg_trgm`.

## Decision
- **One normalisation function**, `atlasna_search_norm(text)` (migration V7, IMMUTABLE), applied to the stored text AND
  to the query, so both sides always agree: lower-case + `unaccent` (é→e, œ→oe); Arabic tashkeel (U+064B–U+065F, U+0670)
  and tatweel removed; أ إ آ ٱ → ا, ى → ي, ة → ه, ؤ → و, ئ → ي; whitespace collapsed.
- **Stored generated column** `products.search_text` = normalised name fr + name ar + brand + SKU (computed by Postgres on
  write; mapped read-only in JPA), with a **GIN trigram index** (`gin_trgm_ops`).
- **Match** = normalised text *contains* the normalised query (wildcards escaped) **or**, for word queries (≥ 3 letters),
  is *similar* to it: `atlasna_fuzzy_match(q, text)` = `q <% text` (pg_trgm word similarity ≥ 0.6). The SQL function is
  inlined by the planner, so the trigram index serves both conditions (verified with EXPLAIN on 4 000 rows). Queries
  without letters ("50%", "GRC-", "500") are matched exactly — "something like 50" is not useful.
- **Ranking** when there is a query: starts with it › contains it › only similar, then `word_similarity`, then the
  requested sort. `q` still combines with every other filter; max 100 characters.
- **Fixture of real queries** (SearchTests): dattes deglet, deglet nour, تمر, تمر دقلة, تمر دقله, تَمْر, dates, couscouss,
  COUSCOUS, كسكس, creme, crème, oeufs, henne, huile d'olive, argan, الاركان, eau (prefix beats "Gâteau").

## Consequences
- Cached listing pages (ADR-0010) key on the trimmed, lower-cased query; equivalent spellings that normalise the same
  but differ in accents get separate cache entries (harmless).
- Arabic morphology (plurals, the article ال as a prefix) is not stemmed: "التمر" finds "تمر" only through similarity.
  Good enough for the catalogue size; revisit with a dedicated search service if it grows to tens of thousands.
- Needs the `unaccent` and `pg_trgm` extensions (available on Cloud SQL for PostgreSQL).
