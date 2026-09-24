package com.atlasna.catalog.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for {@link Product}. Spring Data derives each query from its method name,
 * e.g. {@code findByActiveTrueAndCategory} becomes {@code WHERE active = true AND category = ?}.
 * The {@link Pageable} argument adds LIMIT/OFFSET and ORDER BY from the request's page, size and sort.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByActiveTrue(Pageable pageable);
    Page<Product> findByActiveTrueAndCategory(Category category, Pageable pageable);
    Page<Product> findByActiveTrueAndNameContainingIgnoreCase(String name, Pageable pageable);
}
