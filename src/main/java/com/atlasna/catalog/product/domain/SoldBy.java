package com.atlasna.catalog.product.domain;

import org.springframework.modulith.NamedInterface;

/** How a product is priced: per piece/pack (whole quantities) or per kilogram (decimal quantities, weighed at packing). */
@NamedInterface("types") // shared with other modules (orders snapshot it)
public enum SoldBy {
    UNIT,
    WEIGHT
}
