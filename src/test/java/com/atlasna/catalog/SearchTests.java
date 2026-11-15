package com.atlasna.catalog;

import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Search quality (ADR-0016) against a fixture list of real-world queries: accents and their absence, Arabic with or
 * without diacritics and with letter variants, typos, upper case, ranking (starts with › contains › similar), filters
 * still combinable, wildcards matched literally, and the trigram index actually usable on thousands of rows.
 */
class SearchTests extends OrderTestSupport {

    Map<String, Long> ids;

    @BeforeEach
    void catalog() {
        ids = new java.util.LinkedHashMap<>();
        add("dates", "Dattes Deglet Nour 500 g", "تمر دقلة نور 500 غ", "epicerie-salee");
        add("couscous", "Couscous fin 1 kg", "كسكس رفيع 1 كغ", "epicerie-salee");
        add("creme", "Crème hydratante visage 50 ml", "كريم مرطب للوجه 50 مل", "soins-visage");
        add("oeufs", "Œufs ×6", "بيض ×6", "epicerie-salee");
        add("henne", "Henné naturel 100 g", "حناء طبيعية 100 غ", "soins-visage");
        add("olive", "Huile d'olive extra vierge 500 ml", "زيت الزيتون البكر الممتاز 500 مل", "epicerie-salee");
        add("argan", "Huile d'argan pure 50 ml", "زيت الأركان الصافي 50 مل", "soins-visage");
        add("rose", "Eau de rose 200 ml", "ماء الورد 200 مل", "soins-visage");
        add("gateau", "Gâteau au miel", "كعكة بالعسل", "epicerie-salee");
    }

    @ParameterizedTest(name = "\"{0}\" finds {1}")
    @CsvSource({
            "dattes deglet, dates",
            "deglet nour, dates",
            "Deglet, dates",
            "تمر, dates",
            "تمر دقلة, dates",
            "تمر دقله, dates",        // ة written as ه
            "تَمْر, dates",            // with diacritics
            "dates, dates",           // the usual misspelling of "dattes"
            "couscouss, couscous",    // typo
            "COUSCOUS, couscous",
            "كسكس, couscous",
            "creme, creme",           // no accent typed
            "crème, creme",
            "oeufs, oeufs",           // œ typed as oe
            "henne, henne",
            "huile d'olive, olive",
            "argan, argan",
            "الاركان, argan",         // plain alef for أ
            "eau, rose",              // starts with "eau" beats "Gâteau" (contains it)
    })
    void findsTheRightProductFirst(String query, String expected) throws Exception {
        assertThat(search(query, null).getFirst()).isEqualTo(ids.get(expected));
    }

    @Test
    void bothOilsForZeit_andRankingPutsPrefixMatchesFirst() throws Exception {
        assertThat(search("زيت", null)).containsExactlyInAnyOrder(ids.get("olive"), ids.get("argan")); // equal relevance
        List<Long> huile = search("huile", null);
        assertThat(huile.subList(0, 2)).containsExactlyInAnyOrder(ids.get("olive"), ids.get("argan"));
    }

    @Test
    void stillCombinesWithTheOtherFilters() throws Exception {
        assertThat(search("huile", "BEAUTY")).containsExactly(ids.get("argan"));
    }

    @Test
    void wildcardsAreLiteral_andNonsenseFindsNothing() throws Exception {
        // A bare wildcard must not mean "everything" (it's escaped for LIKE; trigrams ignore punctuation).
        assertThat(search("%", null)).isEmpty();
        assertThat(search("_", null)).isEmpty();
        // Without letters there's no typo tolerance: "50%" is matched exactly (no product here contains "50%").
        assertThat(search("50%", null)).isEmpty();
        assertThat(search("500", null)).containsExactlyInAnyOrder(ids.get("dates"), ids.get("olive"));
        assertThat(search("zzzzqqq", null)).isEmpty();
        mockMvc.perform(get("/api/products").param("q", "x".repeat(101))).andExpect(status().isBadRequest());
    }

    @Test
    void theTrigramIndexIsUsedOnThousandsOfRows() {
        long template = ids.get("couscous");
        jdbc.update("""
                INSERT INTO products (name_fr, name_ar, price, stock_quantity, active, created_at, updated_at, slug, sku,
                                      category_id, sold_by, quantity_step, unit_weight_grams, low_stock_threshold)
                SELECT 'Produit généré ' || g, 'منتج ' || g, price, 0, TRUE, now(), now(), 'gen-' || g, 'GEN-' || g,
                       category_id, sold_by, quantity_step, unit_weight_grams, low_stock_threshold
                FROM products, generate_series(1, 4000) g WHERE id = ?""", template);
        jdbc.execute("ANALYZE products");
        // With only thousands of rows Postgres may rightly prefer a sequential scan; switching that off for this one
        // statement proves the index CAN serve both the "contains" and the fuzzy condition (the function is inlined).
        String plan = jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                var rs = st.executeQuery("""
                        EXPLAIN SELECT id FROM products
                        WHERE search_text LIKE '%' || atlasna_search_norm('couscous') || '%'
                           OR atlasna_fuzzy_match(atlasna_search_norm('couscous'), search_text)""");
                StringBuilder sb = new StringBuilder();
                while (rs.next()) sb.append(rs.getString(1)).append('\n');
                st.execute("RESET enable_seqscan");
                return sb.toString();
            }
        });
        assertThat(plan).contains("ix_products_search_trgm");
    }

    // ---------- helpers ----------

    private void add(String key, String fr, String ar, String categorySlug) {
        long category = categories.findBySlug(categorySlug).orElseThrow().getId();
        ids.put(key, productService.create(new ProductRequest(new ProductRequest.Name(fr, ar), null, null, null, null, category,
                new BigDecimal("100.00"), null, SoldBy.UNIT, BigDecimal.ONE, 300, new BigDecimal("10"), BigDecimal.ZERO,
                false, false, Map.of(), null)).id());
    }

    private List<Long> search(String q, String type) throws Exception {
        var req = get("/api/products").param("q", q).param("size", "50");
        if (type != null) req.param("type", type);
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> found = JsonPath.read(body, "$.content[*].id");
        return found.stream().map(Number::longValue).toList();
    }
}
