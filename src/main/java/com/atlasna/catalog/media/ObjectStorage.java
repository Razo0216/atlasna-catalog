package com.atlasna.catalog.media;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where uploaded files live (ADR-0014). The domain only sees this port: {@code LocalFsObjectStorage} on a developer's
 * disk today, a Google Cloud Storage adapter in Phase 11 (plain GCS client; Spring Cloud GCP isn't offered for Boot 4.1).
 * Keys look like {@code products/12/3f2a….jpg}; they never come from a client-supplied file name.
 */
public interface ObjectStorage {

    /** A short-lived URL the browser uploads to directly (HTTP PUT with exactly these headers). */
    UploadTarget createUploadUrl(String key, String contentType, long maxBytes);

    /** The URL images are served from (CDN base in production). */
    String publicUrl(String key);

    /** The stored bytes, if the object exists (used to verify an upload before accepting it). */
    Optional<byte[]> read(String key);

    void delete(String key);

    /** Objects under a prefix with their last-modified time (orphan clean-up). */
    List<StoredObject> list(String prefix);

    record UploadTarget(String key, String url, String method, Map<String, String> headers, Instant expiresAt) {}

    record StoredObject(String key, long size, Instant lastModified) {}
}
