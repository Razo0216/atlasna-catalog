package com.atlasna.catalog.product.web;

import com.atlasna.catalog.product.cache.CachedCatalog;
import com.atlasna.catalog.product.domain.CategoryType;
import com.atlasna.catalog.product.dto.ProductResponse;
import com.atlasna.catalog.product.service.PageParams;
import com.atlasna.catalog.product.service.ProductFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Public, read-only catalog (permitted for everyone in SecurityConfig). Writes live under /api/admin/products.
 * Filters combine (AND); {@code sort} accepts only the values in {@link PageParams}. Reads go through the Redis
 * cache ({@link CachedCatalog}); answers are identical to the database's, at most a TTL old for stock-driven fields.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final CachedCatalog catalog;

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = PageParams.DEFAULT_SORT) String sort,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) CategoryType type,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "false") boolean inStock,
                                      @RequestParam(defaultValue = "false") boolean featured) {
        return catalog.search(ProductFilter.publicFilter(category, type, q, inStock, featured),
                PageParams.of(page, size, sort));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return catalog.product(id);
    }

    @GetMapping("/by-slug/{slug}")
    public ProductResponse getBySlug(@PathVariable String slug) {
        return catalog.productBySlug(slug);
    }
}
