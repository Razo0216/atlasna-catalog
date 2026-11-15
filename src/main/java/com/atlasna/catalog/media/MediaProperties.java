package com.atlasna.catalog.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code atlasna.media.*}.
 *
 * @param baseUrl      public base URL of images (served at /media/** by this app locally; a CDN later)
 * @param uploadBaseUrl public base URL of this API, for the signed upload links
 * @param storageDir   where LocalFsObjectStorage keeps files
 * @param uploadTtl    how long a signed upload link stays valid
 * @param maxBytes     largest accepted image
 * @param maxDimension largest accepted width or height, in pixels
 */
@ConfigurationProperties(prefix = "atlasna.media")
public record MediaProperties(String baseUrl, String uploadBaseUrl, String storageDir, Duration uploadTtl, Long maxBytes,
                              Integer maxDimension) {
    public MediaProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "/media";
        if (uploadBaseUrl == null || uploadBaseUrl.isBlank()) uploadBaseUrl = "http://localhost:8080";
        if (storageDir == null || storageDir.isBlank()) storageDir = "var/media";
        if (uploadTtl == null) uploadTtl = Duration.ofMinutes(10);
        if (maxBytes == null) maxBytes = 5L * 1024 * 1024;
        if (maxDimension == null) maxDimension = 4000;
    }
}
