package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.domain.CategoryType;

/** The category embedded in a product response. */
public record CategorySummary(Long id, String slug, CategoryType type, LocalizedText name) {}
