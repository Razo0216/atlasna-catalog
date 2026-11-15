-- Phase 5, step 8: search that understands accents, Arabic spelling variants and typos. ADR-0016.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- One normalisation for the stored text AND the customer's query, so both sides always agree:
--   French/Latin: lower-case, accents removed (é→e, œ→oe — "creme" finds "Crème", "oeufs" finds "Œufs");
--   Arabic: diacritics (tashkeel, U+064B–U+065F, U+0670) and tatweel (U+0640) removed, alef forms أ إ آ ٱ → ا,
--   ى → ي, ة → ه, ؤ → و, ئ → ي ("تَمْر" finds "تمر", "دقلة" finds "دقله");
--   whitespace collapsed. IMMUTABLE (unaccent called with an explicit dictionary) so it can feed a stored column.
CREATE FUNCTION atlasna_search_norm(t text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT AS $$
    SELECT btrim(regexp_replace(
             translate(
               regexp_replace(lower(public.unaccent('public.unaccent'::regdictionary, t)),
                              '[\u064B-\u065F\u0670\u0640]', '', 'g'),
               'أإآٱىةؤئ', 'اااايهوي'),
             '\s+', ' ', 'g'))
  $$;

-- Typo tolerance: true when the query is similar enough to some part of the text (pg_trgm word similarity,
-- threshold pg_trgm.word_similarity_threshold = 0.6 by default: "couscouss" → "couscous", "dates" → "dattes").
-- A plain SQL function is inlined by the planner, so the trigram index below is used.
CREATE FUNCTION atlasna_fuzzy_match(q text, t text) RETURNS boolean
  LANGUAGE sql STABLE PARALLEL SAFE STRICT AS $$ SELECT q <% t $$;

-- Name (fr + ar), brand and SKU, normalised once on write. Hibernate maps it read-only.
ALTER TABLE products ADD COLUMN search_text TEXT GENERATED ALWAYS AS (
  atlasna_search_norm(coalesce(name_fr, '') || ' ' || coalesce(name_ar, '') || ' ' || coalesce(brand, '') || ' ' || sku)
) STORED;

-- Trigram GIN index: serves both "contains" (LIKE '%…%') and the fuzzy match.
CREATE INDEX ix_products_search_trgm ON products USING gin (search_text gin_trgm_ops);
