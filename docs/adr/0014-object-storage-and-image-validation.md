# ADR 0014 — Object storage port, signed uploads, image validation by content

**Status:** accepted (Phase 5, step 6) · **Date:** 2026-11-15

## Context
Product photos were bundled in the jar (demo data). The back-office needs real uploads: phones on mediocre connections,
files up to a few MB, stored outside the database, served fast (CDN later). Uploads are a classic attack surface: an
"image" that is really an HTML page served from our domain is stored XSS; a client-chosen file name can escape a folder.

## Decision
- **Port:** `media.ObjectStorage` (`createUploadUrl`, `publicUrl`, `read`, `delete`, `list`). Adapters:
  `LocalFsObjectStorage` now (dev/tests, `atlasna.media.storage-dir`, served at `/media/**` together with the bundled demo
  photos), a GCS adapter with the plain Google Cloud Storage client in Phase 11. Only the **storage key** is stored in
  `product_images`; URLs are derived (`atlasna.media.base-url`, a CDN base later), so moving storage never rewrites rows.
- **Three-step flow:** `POST /api/admin/uploads/product-image {productId, contentType, sizeBytes}` → signed link →
  browser `PUT`s the bytes directly → `PUT /api/admin/products/{id}/images {images:[{key, alt{fr,ar}}]}` (order = display
  order, max 8; existing images are kept by sending their key back). ADMIN and STAFF.
- **Keys are ours:** `products/{productId}/{uuid}.{jpg|png|webp}` — random, under the product's prefix, lower-case,
  never the client's file name. Attaching a key outside `products/{id}/`, with `..`, or not matching the pattern is 400.
- **Signed links:** HMAC over (key, content type, size cap, expiry), valid 10 minutes, constant-time check; changing any
  of them → 403 `UPLOAD_URL_INVALID`; the `Content-Type` must be the signed one. Locally the signing key is random per
  start; with GCS, V4 signed URLs replace this.
- **Validation by content, twice:** at upload (local) and again at attach (because with a bucket nothing else sees the
  bytes): magic bytes must be JPEG / PNG / WebP **and** match the key's extension; size ≤ 5 MB (body read stops at the cap,
  413); width and height ≤ 4000 px read from the header (no full decode — a "decompression bomb" is caught by its
  declared size). GIF, SVG (scriptable), HTML-as-JPEG → 422 `IMAGE_INVALID`.
- **Abandoned uploads:** an hourly Quartz job deletes objects under `products/` older than 24 h that no product uses.
- Attaching or changing photos publishes `CatalogChanged(PRODUCT_IMAGES_UPDATED)`: cache invalidated, audit entry written.

## Consequences
- `ProductImageResponse` gains `key` (additive) so the back-office can keep images when it reorders them.
- Images are served with a 7-day cache: a new photo always gets a new key, so no stale-image problem.
- Re-encoding (strip EXIF/GPS, resize to standard sizes, AVIF) is a later improvement (Cloud Function in Phase 11).
