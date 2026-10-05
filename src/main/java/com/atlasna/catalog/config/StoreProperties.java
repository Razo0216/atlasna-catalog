package com.atlasna.catalog.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Store rules the owner must confirm (atlasna.store.*). They are configuration, never constants in code;
 * Phase 3 moves them into the {@code store_settings} table so the admin can edit them.
 *
 * @param maxItemWeightGrams    heaviest single sold unit allowed in the catalog (the "no heavy items" rule); default 5000 g
 * @param maxLineQuantityUnit   most pieces of one product per order line; default 20
 * @param maxLineQuantityWeight most kilograms of one per-kg product per order line; default 10 kg
 */
@ConfigurationProperties(prefix = "atlasna.store")
@Validated
public record StoreProperties(
        @Positive Integer maxItemWeightGrams,
        @Positive Integer maxLineQuantityUnit,
        @Positive BigDecimal maxLineQuantityWeight
) {
    public StoreProperties {
        if (maxItemWeightGrams == null) maxItemWeightGrams = 5000;
        if (maxLineQuantityUnit == null) maxLineQuantityUnit = 20;
        if (maxLineQuantityWeight == null) maxLineQuantityWeight = new BigDecimal("10");
    }
}
