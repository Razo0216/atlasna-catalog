package com.atlasna.catalog.product.service;

import com.atlasna.catalog.product.domain.Category;
import com.atlasna.catalog.product.domain.Product;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates a {@link ProductFilter} into a JPA Specification: one WHERE clause per active filter, AND-combined.
 * Joins to category/parent are reused, so combining filters never multiplies joins.
 */
final class ProductSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private ProductSpecifications() {}

    /** Public visibility: the product, its category and the category's parent must all be active. */
    static Specification<Product> publiclyVisible() {
        return (root, query, cb) -> {
            Join<Product, Category> category = category(root);
            Join<Category, Category> parent = parent(category);
            return cb.and(cb.isTrue(root.get("active")), cb.isTrue(category.get("active")),
                    cb.or(cb.isNull(parent.get("id")), cb.isTrue(parent.get("active"))));
        };
    }

    static Specification<Product> matching(ProductFilter f) {
        List<Specification<Product>> specs = new ArrayList<>();
        switch (f.active()) {
            case TRUE -> specs.add((root, q, cb) -> cb.isTrue(root.get("active")));
            case FALSE -> specs.add((root, q, cb) -> cb.isFalse(root.get("active")));
            case ALL -> { }
        }
        if (f.category() != null && !f.category().isBlank()) {
            String slug = f.category().trim().toLowerCase(Locale.ROOT);
            // A root slug ("epicerie") matches products in any of its children.
            specs.add((root, q, cb) -> {
                Join<Product, Category> category = category(root);
                return cb.or(cb.equal(category.get("slug"), slug), cb.equal(parent(category).get("slug"), slug));
            });
        }
        if (f.type() != null) {
            specs.add((root, q, cb) -> cb.equal(category(root).get("type"), f.type()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String pattern = "%" + escapeLike(f.q().trim().toLowerCase(Locale.ROOT)) + "%";
            specs.add((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("nameFr")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("nameAr")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("brand")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("sku")), pattern, LIKE_ESCAPE)));
        }
        if (f.inStock()) {
            // At least one quantity step left (same rule as Availability.OUT_OF_STOCK).
            specs.add((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("stockQuantity"), root.get("quantityStep")));
        }
        if (f.featured()) {
            specs.add((root, q, cb) -> cb.isTrue(root.get("featured")));
        }
        if (f.lowStock()) {
            specs.add((root, q, cb) -> cb.lessThanOrEqualTo(root.get("stockQuantity"), root.get("lowStockThreshold")));
        }
        return Specification.allOf(specs);
    }

    /**
     * Escapes LIKE wildcards so user text is matched literally: searching "50%" must not mean "50 followed by
     * anything". The backslash itself is escaped first.
     */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private static Join<Product, Category> category(Root<Product> root) {
        return (Join<Product, Category>) reuseOrJoin(root, "category", JoinType.INNER);
    }

    @SuppressWarnings("unchecked")
    private static Join<Category, Category> parent(Join<Product, Category> category) {
        return (Join<Category, Category>) reuseOrJoin(category, "parent", JoinType.LEFT);
    }

    /** Reuses a plain (non-fetch) join on the attribute if one exists, so several filters share it. */
    private static Join<?, ?> reuseOrJoin(From<?, ?> from, String attribute, JoinType type) {
        return from.getJoins().stream()
                .filter(j -> j.getAttribute().getName().equals(attribute) && j.getJoinType() == type)
                .findFirst()
                .<Join<?, ?>>map(j -> j)
                .orElseGet(() -> from.join(attribute, type));
    }
}
