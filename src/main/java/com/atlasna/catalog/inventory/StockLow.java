package com.atlasna.catalog.inventory;

import java.math.BigDecimal;

/** Published (in the same transaction) when a sale takes a product to or below its low-stock threshold. */
public record StockLow(long productId, BigDecimal stockQuantity, BigDecimal lowStockThreshold) {}
