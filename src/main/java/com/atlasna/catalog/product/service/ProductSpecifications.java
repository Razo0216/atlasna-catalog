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
            // Same normalisation on both sides (atlasna_search_norm, V7): accents, Arabic diacritics and letter variants
            // don't matter. Match = the text contains the query, OR is similar enough to it (typos), via the trigram index.
            // Typo tolerance only for words (≥ 3 letters): "50%" or "GRC-" are matched exactly, not "something like 50".
            String escaped = escapeLike(f.q().trim());
            boolean fuzzy = f.q().codePoints().filter(Character::isLetter).count() >= 3;
            specs.add((root, q, cb) -> {
                var text = root.<String>get("searchText");
                var normalised = cb.function("atlasna_search_norm", String.class, cb.literal(escaped));
                var contains = cb.like(text, cb.concat(cb.concat("%", normalised), "%"), LIKE_ESCAPE);
                if (!fuzzy) return contains;
                var fuzzyQuery = cb.function("atlasna_search_norm", String.class, cb.literal(f.q().trim()));
                return cb.or(contains, cb.isTrue(cb.function("atlasna_fuzzy_match", Boolean.class, fuzzyQuery, text)));
            });
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
     * Search ranking when there is a query: starts with it (2) › contains it (1) › only similar (0), then by trigram
     * word similarity. Applied before the requested sort, so "newest" or "price" order products of equal relevance.
     */
    static Specification<Product> rankedBy(String query, org.springframework.data.domain.Sort sort) {
        return (root, q, cb) -> {
            if (q.getResultType() != Long.class && q.getResultType() != long.class) { // not for the count query
                var text = root.<String>get("searchText");
                var normalised = cb.function("atlasna_search_norm", String.class, cb.literal(escapeLike(query.trim())));
                var plain = cb.function("atlasna_search_norm", String.class, cb.literal(query.trim()));
                var tier = cb.<Integer>selectCase()
                        .when(cb.like(text, cb.concat(normalised, "%"), LIKE_ESCAPE), 2)
                        .when(cb.like(text, cb.concat(cb.concat("%", normalised), "%"), LIKE_ESCAPE), 1)
                        .otherwise(0);
                var similarity = cb.function("word_similarity", Double.class, plain, text);
                List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
                orders.add(cb.desc(tier));
                orders.add(cb.desc(similarity));
                orders.addAll(org.springframework.data.jpa.repository.query.QueryUtils.toOrders(sort, root, cb));
                q.orderBy(orders);
            }
            return null;
        };
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
