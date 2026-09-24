package com.atlasna.catalog.product;

import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.dto.ProductResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Product catalog endpoints.
 *
 * <p>Access: GET endpoints are public (SecurityConfig permits GET /api/products and everything under it).
 * Write endpoints need a logged-in user (URL rule) <em>and</em> the ADMIN role ({@code @PreAuthorize},
 * enabled by {@code @EnableMethodSecurity}). A CUSTOMER token on a write endpoint gets 403; no token gets 401.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * Lists active products. Spring binds {@code ?page=0&size=20&sort=price,desc} into {@link Pageable};
     * {@code category} and {@code search} are optional filters (search wins if both are given).
     */
    @GetMapping
    public Page<ProductResponse> list(@RequestParam(required = false) Category category,
                                       @RequestParam(required = false) String search,
                                       Pageable pageable) {
        return productService.list(category, search, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    /** Full replacement: the request must contain every field. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    /** Soft delete; returns 204 No Content. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
