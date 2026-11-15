package com.atlasna.catalog.product.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code atlasna.cache.*}: the public catalog cache in Redis (ADR-0010).
 *
 * @param enabled         off = every read goes to Postgres (the test profile turns it off except in cache tests)
 * @param categoriesTtl   the category tree changes rarely
 * @param productTtl      a product page; also bounds how stale its availability can look (writes invalidate earlier)
 * @param listTtl         listing/search pages: many distinct keys, so the shortest lifetime
 */
@ConfigurationProperties(prefix = "atlasna.cache")
public record CatalogCacheProperties(
        boolean enabled,
        Duration categoriesTtl,
        Duration productTtl,
        Duration listTtl) {

    public CatalogCacheProperties {
        if (categoriesTtl == null) categoriesTtl = Duration.ofMinutes(5);
        if (productTtl == null) productTtl = Duration.ofMinutes(2);
        if (listTtl == null) listTtl = Duration.ofMinutes(1);
    }
}
