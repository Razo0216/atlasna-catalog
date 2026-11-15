package com.atlasna.catalog.inventory;

import com.atlasna.catalog.common.Actor;

import java.math.BigDecimal;

/**
 * Published for stock changes made by people or jobs (opening stock, restock, count adjustment, expiry) — not for
 * sales and cancellations, which the order history already records. The audit log listens to it.
 */
public record StockAdjusted(long productId, BigDecimal delta, StockReason reason, String note, Actor actor) {}
