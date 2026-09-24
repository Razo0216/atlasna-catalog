package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(Long id, String name, String description, BigDecimal price, Category category, Integer stockQuantity, Instant createdAt) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCategory(), product.getStockQuantity(), product.getCreatedAt());
    }
}
