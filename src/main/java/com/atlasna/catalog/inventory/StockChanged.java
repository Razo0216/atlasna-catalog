package com.atlasna.catalog.inventory;

/**
 * Published (inside the transaction) whenever a product's stock moves: sale, cancellation, restock, adjustment.
 * The catalog cache listens to it after commit, so availability shown to customers follows the real stock.
 */
public record StockChanged(long productId) {}
