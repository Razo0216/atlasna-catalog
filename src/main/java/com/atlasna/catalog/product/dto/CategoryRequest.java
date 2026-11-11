package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.domain.CategoryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST/PUT /api/admin/categories}. {@code parentId} null creates a root (department).
 * {@code slug} is optional on create (generated from the French name) and kept unchanged on update if omitted.
 */
public record CategoryRequest(
        @Size(max = 100, message = "Slug must be at most 100 characters")
        @Pattern(regexp = "^([a-z0-9]+(-[a-z0-9]+)*)?$", message = "Slug may contain only a-z, 0-9 and single dashes")
        String slug,
        Long parentId,
        @NotNull(message = "Type is required") CategoryType type,
        @NotNull(message = "Name is required") @Valid Name name,
        Integer sortOrder,
        Boolean active
) {
    public record Name(
            @NotBlank(message = "French name is required")
            @Size(max = 150, message = "Name must be at most 150 characters") String fr,
            @Size(max = 150, message = "Arabic name must be at most 150 characters") String ar) {}
}
