package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.common.LocalizedText;

/**
 * @param url the public image URL (CDN later)
 * @param key the storage key — what the back-office sends back to PUT /api/admin/products/{id}/images to keep an image
 */
public record ProductImageResponse(String url, LocalizedText alt, String key) {}
