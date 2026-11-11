package com.atlasna.catalog.product.service;

import com.atlasna.catalog.common.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Builds the Pageable for product listings from raw query parameters.
 *
 * <p>Sorting is a fixed allow-list: a client may only ask for these four orders. Anything else is
 * {@code 400 INVALID_SORT} before any query runs, so a crafted {@code sort} can't reach the database
 * (unknown properties used to surface as a 500). Each order ends with {@code id} so paging is stable
 * when several products share a price or date.
 */
public final class PageParams {

    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "createdAt,desc";

    private static final Map<String, Sort> SORTS = Map.of(
            "createdAt,desc", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
            "price,asc", Sort.by(Sort.Order.asc("price"), Sort.Order.asc("id")),
            "price,desc", Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id")),
            "name,asc", Sort.by(Sort.Order.asc("nameFr"), Sort.Order.asc("id")));

    private PageParams() {}

    public static Pageable of(int page, int size, String sort) {
        if (page < 0) throw ApiException.invalidField("page", "Page must be 0 or greater");
        if (size < 1 || size > MAX_SIZE) throw ApiException.invalidField("size", "Size must be between 1 and " + MAX_SIZE);
        Sort resolved = SORTS.get(sort == null ? DEFAULT_SORT : sort);
        if (resolved == null) {
            throw ApiException.badRequest("INVALID_SORT",
                    "Unsupported sort. Allowed: createdAt,desc | price,asc | price,desc | name,asc");
        }
        return PageRequest.of(page, size, resolved);
    }
}
