package com.atlasna.catalog.media;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves images at /media/**: uploaded files from the storage directory first, then the demo photos bundled in the jar
 * (classpath:/static/media). In the cloud a CDN in front of the bucket replaces this (Phase 12).
 */
@Configuration
class MediaWebConfig implements WebMvcConfigurer {

    private final LocalFsObjectStorage storage;

    MediaWebConfig(LocalFsObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(storage.root().toUri().toString(), "classpath:/static/media/")
                .setCachePeriod(7 * 24 * 3600);
    }
}
