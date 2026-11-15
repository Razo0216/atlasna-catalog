package com.atlasna.catalog.product.cache;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** The JSON-friendly part of a {@link Page} (PageImpl itself doesn't round-trip through JSON). */
public record CachedPage<T>(List<T> content, long totalElements) {

    public static <T> CachedPage<T> of(Page<T> page) {
        return new CachedPage<>(page.getContent(), page.getTotalElements());
    }

    public Page<T> toPage(Pageable pageable) {
        return new PageImpl<>(content, pageable, totalElements);
    }
}
