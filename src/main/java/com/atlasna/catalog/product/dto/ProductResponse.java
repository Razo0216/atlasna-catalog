package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.common.LocalizedText;

import com.atlasna.catalog.product.domain.Availability;
import com.atlasna.catalog.product.domain.SoldBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public view of a product. Deliberately has no exact stock, threshold, active flag or version: the public sees
 * {@code availability} and {@code maxOrderQuantity} only. Admins get {@link AdminProductResponse}.
 */
public record ProductResponse(
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
        Instant createdAt
) {}
