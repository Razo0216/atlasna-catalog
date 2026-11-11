package com.atlasna.catalog.product.service;

import com.atlasna.catalog.product.domain.CategoryType;

/**
 * Listing filters. All of them combine with AND; null/false means "don't filter".
 *
 * @param category category slug; a root slug matches products in all its children
 * @param active   admin only: which products by soft-delete state (public listing is always active-only)
 * @param lowStock admin only: stock at or under the product's low-stock threshold
 */
public record ProductFilter(String category, CategoryType type, String q, boolean inStock, boolean featured,
                            ActiveFilter active, boolean lowStock) {

    public static ProductFilter publicFilter(String category, CategoryType type, String q, boolean inStock, boolean featured) {
        return new ProductFilter(category, type, q, inStock, featured, ActiveFilter.TRUE, false);
    }
}
