package com.atlasna.catalog.product.domain;

/** How a product is priced: per piece/pack (whole quantities) or per kilogram (decimal quantities, weighed at packing). */
public enum SoldBy {
    UNIT,
    WEIGHT
}
