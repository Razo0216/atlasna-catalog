package com.atlasna.catalog.product.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * Database access for {@link Product}. Filtered listing uses JPA Specifications (see ProductSpecifications),
 * so filters combine freely. The {@code @EntityGraph} fetches each product's category in the same query.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findWithCategoryById(Long id);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findBySlug(String slug);

    boolean existsBySlug(String slug);
    boolean existsBySku(String sku);
    boolean existsBySkuAndIdNot(String sku, Long id);
    boolean existsByBarcode(String barcode);
    boolean existsByBarcodeAndIdNot(String barcode, Long id);
    boolean existsByCategoryId(Long categoryId);

    /** Highest number used by generated SKUs of the form PREFIX-0001 (0 if none). */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(sku FROM 5) AS INTEGER)), 0) FROM products "
            + "WHERE sku ~ CONCAT('^', :prefix, '-[0-9]{4,9}$')", nativeQuery = true)
    int maxGeneratedSkuNumber(String prefix);

    /** Products by id with their category (one query) — used to price carts and orders. */
    @EntityGraph(attributePaths = "category")
    java.util.List<Product> findByIdIn(java.util.Collection<Long> ids);
}
