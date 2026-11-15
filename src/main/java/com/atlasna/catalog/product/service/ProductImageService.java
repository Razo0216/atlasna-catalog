package com.atlasna.catalog.product.service;

import com.atlasna.catalog.common.Actor;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.media.ImageInspector;
import com.atlasna.catalog.media.MediaProperties;
import com.atlasna.catalog.media.ObjectStorage;
import com.atlasna.catalog.product.CatalogChanged;
import com.atlasna.catalog.product.domain.Product;
import com.atlasna.catalog.product.domain.ProductImage;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.product.dto.AdminProductResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Product photos (ADR-0014): 1) the back-office asks for a signed upload link, 2) the browser uploads straight to
 * storage, 3) the back-office attaches the uploaded keys to the product. Step 3 re-checks every new object by its
 * CONTENT (magic bytes, size, dimensions) — in the cloud nothing else has looked at the bytes — and only accepts keys
 * under {@code products/{id}/}. Uploads never attached are deleted by {@link #deleteOrphans}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    public static final int MAX_IMAGES = 8;

    private final ProductRepository products;
    private final ObjectStorage storage;
    private final MediaProperties media;
    private final ProductMapper mapper;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record UploadRequest(long productId, String contentType, long sizeBytes) {}

    public record ImageRef(String key, String altFr, String altAr) {}

    @Transactional(readOnly = true)
    public ObjectStorage.UploadTarget requestUpload(UploadRequest r) {
        if (!products.existsById(r.productId())) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + r.productId() + " not found");
        ImageInspector.Type type = ImageInspector.Type.ofContentType(r.contentType()).orElseThrow(() ->
                ApiException.invalidField("contentType", "Only image/jpeg, image/png and image/webp are accepted"));
        if (r.sizeBytes() < 1 || r.sizeBytes() > media.maxBytes()) {
            throw ApiException.invalidField("sizeBytes", "Images must be at most " + media.maxBytes() / (1024 * 1024) + " MB");
        }
        // The key is ours: random, under the product's prefix. The client's file name is never used.
        String key = "products/" + r.productId() + "/" + UUID.randomUUID() + "." + type.extension;
        return storage.createUploadUrl(key, type.contentType, media.maxBytes());
    }

    /** Replaces the product's images with {@code images}, in that order (first = main photo). */
    @Transactional
    public AdminProductResponse replaceImages(long productId, List<ImageRef> images) {
        Product product = products.findWithCategoryById(productId)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + productId + " not found"));
        if (images.size() > MAX_IMAGES) throw ApiException.invalidField("images", "At most " + MAX_IMAGES + " images");
        Set<String> current = new HashSet<>(product.getImages().stream().map(ProductImage::getStorageKey).toList());
        Set<String> seen = new HashSet<>();
        for (ImageRef ref : images) {
            if (ref.key() == null || !seen.add(ref.key())) throw ApiException.invalidField("images", "Each image key must be given once");
            if (current.contains(ref.key())) continue; // kept as it is
            if (!ref.key().startsWith("products/" + productId + "/")) {
                throw ApiException.invalidField("images", "Image " + ref.key() + " doesn't belong to this product");
            }
            verify(ref.key());
        }
        List<String> before = product.getImages().stream().map(ProductImage::getStorageKey).toList();
        product.getImages().clear();
        int order = 0;
        for (ImageRef ref : images) {
            product.getImages().add(ProductImage.builder().product(product).storageKey(ref.key())
                    .altFr(blank(ref.altFr())).altAr(blank(ref.altAr())).sortOrder(order++).build());
        }
        Product saved = products.saveAndFlush(product);
        List<String> after = images.stream().map(ImageRef::key).toList();
        if (!before.equals(after)) {
            events.publishEvent(new CatalogChanged("PRODUCT", productId, "PRODUCT_IMAGES_UPDATED",
                    Map.of("images", String.join(",", before)), Map.of("images", String.join(",", after)), Actor.current()));
        }
        return mapper.toAdmin(saved);
    }

    /** Deletes uploads older than {@code age} that no product uses. Returns how many were deleted. */
    public int deleteOrphans(Duration age) {
        Set<String> used = new HashSet<>(jdbc.queryForList("SELECT storage_key FROM product_images", String.class));
        var cutoff = clock.instant().minus(age);
        int deleted = 0;
        for (ObjectStorage.StoredObject o : storage.list("products")) {
            if (o.lastModified().isBefore(cutoff) && !used.contains(o.key())) {
                storage.delete(o.key());
                deleted++;
            }
        }
        if (deleted > 0) log.info("Deleted {} orphaned upload(s)", deleted);
        return deleted;
    }

    private void verify(String key) {
        byte[] bytes;
        try {
            bytes = storage.read(key).orElseThrow(() -> ApiException.businessRule("IMAGE_NOT_UPLOADED", "Image " + key + " was not uploaded"));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("images", "Invalid image key");
        }
        if (bytes.length > media.maxBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "Image too large");
        ImageInspector.Info info = ImageInspector.inspect(bytes).orElseThrow(() ->
                ApiException.businessRule("IMAGE_INVALID", "The file is not a JPEG, PNG or WebP image"));
        if (!key.endsWith("." + info.type().extension)) {
            throw ApiException.businessRule("IMAGE_INVALID", "The file content doesn't match its type");
        }
        if (info.width() > media.maxDimension() || info.height() > media.maxDimension()) {
            throw ApiException.businessRule("IMAGE_INVALID", "Image dimensions must be at most " + media.maxDimension() + " px");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
