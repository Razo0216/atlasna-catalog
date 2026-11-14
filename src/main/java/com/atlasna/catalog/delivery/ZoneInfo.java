package com.atlasna.catalog.delivery;

import com.atlasna.catalog.common.LocalizedText;

import java.math.BigDecimal;

/** A served zone as other modules see it. */
public record ZoneInfo(long id, String slug, LocalizedText name, BigDecimal fee, BigDecimal minOrderAmount,
                       BigDecimal freeDeliveryThreshold, int etaMinutes) {

    /** The zone's fee, or zero once the subtotal reaches the free-delivery threshold. */
    public BigDecimal feeFor(BigDecimal subtotal) {
        return freeDeliveryThreshold != null && subtotal.compareTo(freeDeliveryThreshold) >= 0 ? BigDecimal.ZERO.setScale(2) : fee;
    }
}
