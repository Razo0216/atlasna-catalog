package com.atlasna.catalog.product;

/**
 * Product categories. Stored by name in the {@code products.category} column.
 * The column has a CHECK constraint listing these values (Flyway V1), so adding a category
 * also needs a new migration that updates that constraint.
 */
public enum Category {
    ELECTRONICS, FASHION, HOME_AND_KITCHEN, BEAUTY, GROCERIES, SPORTS, BOOKS
}
