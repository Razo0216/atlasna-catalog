package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.common.LocalizedText;

import com.atlasna.catalog.product.domain.Availability;
import com.atlasna.catalog.product.domain.SoldBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin view: everything in {@link ProductResponse} plus the internal fields an editor needs.
 * {@code version} must be sent back on update (optimistic locking).
 */
public record AdminProductResponse(
        Long id,
        String slug,
        String sku,
        String barcode,
        LocalizedText name,
        LocalizedText description,
        String brand,
        CategorySummary category,
        BigDecimal price,
        BigDecimal compareAtPrice,
        SoldBy soldBy,
        BigDecimal quantityStep,
        Integer unitWeightGrams,
        Availability availability,
        BigDecimal maxOrderQuantity,
        boolean perishable,
        boolean featured,
        Map<String, Object> attributes,
        List<ProductImageResponse> images,
        Instant createdAt,
        BigDecimal stockQuantity,
        BigDecimal lowStockThreshold,
        boolean active,
        Instant updatedAt,
        Long version
) {}
