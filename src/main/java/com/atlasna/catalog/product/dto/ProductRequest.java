package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.domain.SoldBy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Body of {@code POST /api/admin/products} and {@code PUT /api/admin/products/{id}} (full replace).
 * Single-field rules are Bean Validation here; rules that need other fields or the database
 * (leaf category, heavy items, step vs soldBy, compare-at price, uniqueness, version) live in ProductService.
 */
public record ProductRequest(
        @NotNull(message = "Name is required") @Valid Name name,
        @Valid Description description,
        @Size(max = 100, message = "Brand must be at most 100 characters") String brand,
        @Size(max = 40, message = "SKU must be at most 40 characters")
        @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "SKU may contain only letters, digits and dashes") String sku,
        @Pattern(regexp = "^(\\d{8}|\\d{12,14})?$", message = "Barcode must be 8, 12, 13 or 14 digits") String barcode,
        @NotNull(message = "Category is required") Long categoryId,
        @NotNull(message = "Price is required")
        @DecimalMin(value = "0", inclusive = false, message = "Price must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 10 digits and 2 decimals") BigDecimal price,
        @DecimalMin(value = "0", inclusive = false, message = "Compare-at price must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "Compare-at price must have at most 10 digits and 2 decimals")
        BigDecimal compareAtPrice,
        @NotNull(message = "Sold-by is required") SoldBy soldBy,
        @NotNull(message = "Quantity step is required")
        @DecimalMin(value = "0", inclusive = false, message = "Quantity step must be greater than 0")
        @Digits(integer = 3, fraction = 3, message = "Quantity step must have at most 3 decimals") BigDecimal quantityStep,
        @NotNull(message = "Unit weight is required")
        @Positive(message = "Unit weight must be greater than 0") Integer unitWeightGrams,
        @NotNull(message = "Stock quantity is required")
        @DecimalMin(value = "0", message = "Stock quantity cannot be negative")
        @Digits(integer = 7, fraction = 3, message = "Stock quantity must have at most 3 decimals") BigDecimal stockQuantity,
        @DecimalMin(value = "0", message = "Low-stock threshold cannot be negative")
        @Digits(integer = 7, fraction = 3, message = "Low-stock threshold must have at most 3 decimals")
        BigDecimal lowStockThreshold,
        Boolean perishable,
        Boolean featured,
        @Size(max = 30, message = "At most 30 attributes") Map<String, Object> attributes,
        /** Required on PUT: the version the client last read. */
        Long version
) {
    public record Name(
            @NotBlank(message = "French name is required")
            @Size(max = 200, message = "Name must be at most 200 characters") String fr,
            @Size(max = 200, message = "Arabic name must be at most 200 characters") String ar) {}

    public record Description(
            @Size(max = 2000, message = "Description must be at most 2000 characters") String fr,
            @Size(max = 2000, message = "Arabic description must be at most 2000 characters") String ar) {}
}
