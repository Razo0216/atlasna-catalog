package com.atlasna.catalog.inventory;

import java.math.BigDecimal;

/** One product and a quantity (units or kg) to take from, or give back to, stock. */
public record StockLine(long productId, BigDecimal quantity) {}
