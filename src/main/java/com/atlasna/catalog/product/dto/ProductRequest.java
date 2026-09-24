package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.Category;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Request body for creating or updating a product (admin only).
 * Constraint violations are returned as 400 with per-field messages; an unknown category value
 * fails JSON parsing and is also returned as 400.
 */
public record ProductRequest(
        @NotBlank(message = "Name is required") @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull(message = "Price is required") @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0") BigDecimal price,
        @NotNull(message = "Category is required") Category category,
        @NotNull(message = "Stock quantity is required") @Min(value = 0, message = "Stock quantity cannot be negative") Integer stockQuantity
) {}
