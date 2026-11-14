package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.common.LocalizedText;

import com.atlasna.catalog.product.domain.CategoryType;

import java.util.List;

/** Admin category tree node: includes inactive categories and the fields an editor needs. */
public record AdminCategoryNode(Long id, Long parentId, String slug, CategoryType type, LocalizedText name,
                                int sortOrder, boolean active, List<AdminCategoryNode> children) {}
