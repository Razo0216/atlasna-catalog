package com.atlasna.catalog.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StorePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsApplyWhenUnset() {
        runner.run(ctx -> {
            StoreProperties store = ctx.getBean(StoreProperties.class);
            assertThat(store.maxItemWeightGrams()).isEqualTo(5000);
            assertThat(store.maxLineQuantityUnit()).isEqualTo(20);
            assertThat(store.maxLineQuantityWeight()).isEqualByComparingTo(new BigDecimal("10"));
        });
    }

    @Test
    void valuesBindFromProperties() {
        runner.withPropertyValues("atlasna.store.max-item-weight-grams=3000", "atlasna.store.max-line-quantity-weight=7.5")
                .run(ctx -> {
                    StoreProperties store = ctx.getBean(StoreProperties.class);
                    assertThat(store.maxItemWeightGrams()).isEqualTo(3000);
                    assertThat(store.maxLineQuantityWeight()).isEqualByComparingTo(new BigDecimal("7.5"));
                });
    }

    @Test
    void nonPositiveLimitFailsStartup() {
        runner.withPropertyValues("atlasna.store.max-item-weight-grams=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @EnableConfigurationProperties(StoreProperties.class)
    static class Config {}
}
