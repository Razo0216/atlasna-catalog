package com.atlasna.catalog.product;

import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.product.domain.Availability;
import com.atlasna.catalog.product.domain.SoldBy;

import java.math.BigDecimal;

/**
 * A product as the order module sees it: enough to validate a cart line, price it and snapshot it into an order.
 *
 * @param publiclyVisible  active and in an active category (a hidden product can't be ordered at all)
 * @param orderable        publicly visible and not out of stock
 * @param maxOrderQuantity most one order line may hold right now (stock and the store's per-line limit)
 */
public record ProductInfo(long id, String slug, String sku, LocalizedText name, SoldBy soldBy, BigDecimal price,
                          BigDecimal quantityStep, int unitWeightGrams, BigDecimal maxOrderQuantity,
                          Availability availability, boolean publiclyVisible, boolean orderable) {}
