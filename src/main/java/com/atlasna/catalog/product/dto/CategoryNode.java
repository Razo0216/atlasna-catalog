package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.domain.CategoryType;

import java.util.List;

/** Public category tree node ({@code GET /api/categories}); only active categories appear. */
public record CategoryNode(Long id, String slug, CategoryType type, LocalizedText name, int sortOrder,
                           List<CategoryNode> children) {}
