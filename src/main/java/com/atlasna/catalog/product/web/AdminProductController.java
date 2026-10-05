package com.atlasna.catalog.product.web;

import com.atlasna.catalog.product.domain.CategoryType;
import com.atlasna.catalog.product.dto.AdminProductResponse;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.service.ActiveFilter;
import com.atlasna.catalog.product.service.PageParams;
import com.atlasna.catalog.product.service.ProductFilter;
import com.atlasna.catalog.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Catalog management. ADMIN only, enforced twice (defence in depth): the {@code /api/admin/**} URL rule in
 * SecurityConfig and {@code @PreAuthorize} here. Anonymous gets 401, any other role 403.
 */
@RestController
@RequestMapping("/api/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    /** Public filters plus {@code active=true|false|all} (default true) and {@code lowStock=true}. */
    @GetMapping
    public Page<AdminProductResponse> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(defaultValue = PageParams.DEFAULT_SORT) String sort,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(required = false) CategoryType type,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean inStock,
                                           @RequestParam(defaultValue = "false") boolean featured,
                                           @RequestParam(required = false) String active,
                                           @RequestParam(defaultValue = "false") boolean lowStock) {
        var filter = new ProductFilter(category, type, q, inStock, featured, ActiveFilter.parse(active), lowStock);
        return productService.adminSearch(filter, PageParams.of(page, size, sort));
    }

    @GetMapping("/{id}")
    public AdminProductResponse get(@PathVariable Long id) {
        return productService.adminGet(id);
    }

    @PostMapping
    public ResponseEntity<AdminProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    /** Full replace; the body must include the {@code version} last read. */
    @PutMapping("/{id}")
    public AdminProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    /** Soft delete; 204 No Content. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public AdminProductResponse restore(@PathVariable Long id) {
        return productService.restore(id);
    }
}
