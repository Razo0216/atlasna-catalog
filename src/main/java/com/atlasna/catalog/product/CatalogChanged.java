package com.atlasna.catalog.product;

import com.atlasna.catalog.common.Actor;

import java.util.Map;

/**
 * Published when the admin creates, edits, hides, restores or deletes a product or a category.
 *
 * @param action  e.g. PRODUCT_UPDATED, CATEGORY_DELETED
 * @param before  changed fields only, as they were (empty for a creation)
 * @param after   changed fields only, as they are now (empty for a deletion)
 */
public record CatalogChanged(String entityType, long entityId, String action, Map<String, Object> before,
                             Map<String, Object> after, Actor actor) {}
