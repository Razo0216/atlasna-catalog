package com.atlasna.catalog.product.web;

import com.atlasna.catalog.product.domain.CategoryType;
import com.atlasna.catalog.product.dto.ProductResponse;
import com.atlasna.catalog.product.service.PageParams;
import com.atlasna.catalog.product.service.ProductFilter;
import com.atlasna.catalog.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Public, read-only catalog (permitted for everyone in SecurityConfig). Writes live under /api/admin/products.
 * Filters combine (AND); {@code sort} accepts only the values in {@link PageParams}.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = PageParams.DEFAULT_SORT) String sort,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(required = false) CategoryType type,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "false") boolean inStock,
                                      @RequestParam(defaultValue = "false") boolean featured) {
        return productService.search(ProductFilter.publicFilter(category, type, q, inStock, featured),
                PageParams.of(page, size, sort));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.getPublic(id);
    }

    @GetMapping("/by-slug/{slug}")
    public ProductResponse getBySlug(@PathVariable String slug) {
        return productService.getPublicBySlug(slug);
    }
}
