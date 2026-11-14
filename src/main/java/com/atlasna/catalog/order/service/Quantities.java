package com.atlasna.catalog.order.service;

import com.atlasna.catalog.product.ProductInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Quantity and money arithmetic, always BigDecimal (never double): scale 3 for quantities, scale 2 HALF_UP for money. */
final class Quantities {

    private Quantities() {}

    /** Rounds DOWN to a whole number of steps (1.7 kg in 0.5 kg steps → 1.5 kg). */
    static BigDecimal snapDown(BigDecimal q, BigDecimal step) {
        return q.divide(step, 0, RoundingMode.FLOOR).multiply(step).setScale(3, RoundingMode.UNNECESSARY);
    }

    /** Snaps to the step, at least one step, at most the product's current maximum (also snapped). 0 = nothing possible. */
    static BigDecimal clamp(BigDecimal requested, ProductInfo p) {
        BigDecimal max = snapDown(p.maxOrderQuantity(), p.quantityStep());
        if (max.compareTo(p.quantityStep()) < 0) return BigDecimal.ZERO.setScale(3);
        BigDecimal q = snapDown(requested, p.quantityStep());
        if (q.compareTo(p.quantityStep()) < 0) q = p.quantityStep().setScale(3, RoundingMode.UNNECESSARY);
        return q.min(max);
    }

    static BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal quantity) {
        return unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    }

    /** Grams for a line: unit weight × quantity (per-kg products weigh 1000 g per kg), rounded half-up. */
    static int weightGrams(int unitWeightGrams, BigDecimal quantity) {
        return BigDecimal.valueOf(unitWeightGrams).multiply(quantity).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
