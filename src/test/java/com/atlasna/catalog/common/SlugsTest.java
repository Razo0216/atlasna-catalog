package com.atlasna.catalog.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Œufs ×6                        | oeufs-6
            Huile d'argan pure 50 ml       | huile-d-argan-pure-50-ml
            Crème hydratante visage 50 ml  | creme-hydratante-visage-50-ml
            Fruits & légumes               | fruits-legumes
            Thé vert 100 g                 | the-vert-100-g
            '  --Café moulu--  '           | cafe-moulu
            طماطم                          | produit
            '   '                          | produit
            """)
    void slugifiesFrenchNames(String name, String expected) {
        assertThat(Slugs.slugify(name)).isEqualTo(expected);
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(Slugs.slugify(null)).isEmpty();
    }

    @Test
    void longNamesAreCutWithoutATrailingDash() {
        String slug = Slugs.slugify("ab ".repeat(150));
        assertThat(slug).hasSizeLessThanOrEqualTo(200).doesNotEndWith("-").startsWith("ab-ab");
    }

    @Test
    void uniqueAppendsTheFirstFreeSuffix() {
        Set<String> taken = Set.of("tomates", "tomates-2");
        assertThat(Slugs.unique("tomates", taken::contains)).isEqualTo("tomates-3");
        assertThat(Slugs.unique("oranges", taken::contains)).isEqualTo("oranges");
    }
}
