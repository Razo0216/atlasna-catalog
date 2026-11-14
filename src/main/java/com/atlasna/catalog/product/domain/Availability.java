package com.atlasna.catalog.product.domain;

import org.springframework.modulith.NamedInterface;

/** What the public API shows instead of the exact stock quantity. */
@NamedInterface("types") // shared with other modules (orders snapshot it)
public enum Availability {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK
}
