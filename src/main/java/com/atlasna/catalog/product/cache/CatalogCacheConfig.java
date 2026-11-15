package com.atlasna.catalog.product.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds atlasna.cache.* inside the product module. */
@Configuration
@EnableConfigurationProperties(CatalogCacheProperties.class)
class CatalogCacheConfig {
}
