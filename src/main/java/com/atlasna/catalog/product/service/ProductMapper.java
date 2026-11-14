package com.atlasna.catalog.product.service;

import com.atlasna.catalog.store.StoreSettings;
import com.atlasna.catalog.store.StoreSettingsService;
import com.atlasna.catalog.product.domain.Category;
import com.atlasna.catalog.product.domain.Product;
import com.atlasna.catalog.product.domain.ProductImage;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.product.dto.AdminProductResponse;
import com.atlasna.catalog.product.dto.CategorySummary;
import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.product.dto.ProductImageResponse;
import com.atlasna.catalog.product.dto.ProductResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Entity -> DTO mapping. Must run inside the service transaction: it touches the lazy category and images.
 * The public and admin views are built here side by side so it's obvious which fields each one exposes.
 */
@Component
public class ProductMapper {

    private final StoreSettingsService settings;
    private final String mediaBaseUrl;

    public ProductMapper(StoreSettingsService settings, @Value("${atlasna.media.base-url:/media}") String mediaBaseUrl) {
        this.settings = settings;
        this.mediaBaseUrl = mediaBaseUrl.replaceAll("/+$", "");
    }

    public ProductResponse toPublic(Product p) {
        return new ProductResponse(p.getId(), p.getSlug(), p.getSku(), p.getBarcode(),
                LocalizedText.of(p.getNameFr(), p.getNameAr()), LocalizedText.of(p.getDescriptionFr(), p.getDescriptionAr()),
                p.getBrand(), toSummary(p.getCategory()), p.getPrice(), p.getCompareAtPrice(), p.getSoldBy(),
                plain(p.getQuantityStep()), p.getUnitWeightGrams(), p.availability(), plain(maxOrderQuantity(p)),
                p.isPerishable(), p.isFeatured(), attributes(p), images(p), p.getCreatedAt());
    }

    public AdminProductResponse toAdmin(Product p) {
        return new AdminProductResponse(p.getId(), p.getSlug(), p.getSku(), p.getBarcode(),
                LocalizedText.of(p.getNameFr(), p.getNameAr()), LocalizedText.of(p.getDescriptionFr(), p.getDescriptionAr()),
                p.getBrand(), toSummary(p.getCategory()), p.getPrice(), p.getCompareAtPrice(), p.getSoldBy(),
                plain(p.getQuantityStep()), p.getUnitWeightGrams(), p.availability(), plain(maxOrderQuantity(p)),
                p.isPerishable(), p.isFeatured(), attributes(p), images(p), p.getCreatedAt(),
                plain(p.getStockQuantity()), plain(p.getLowStockThreshold()), p.isActive(), p.getUpdatedAt(), p.getVersion());
    }

    public CategorySummary toSummary(Category c) {
        return new CategorySummary(c.getId(), c.getSlug(), c.getType(), LocalizedText.of(c.getNameFr(), c.getNameAr()));
    }

    private StoreSettings store() {
        return settings.current();
    }

    /** The most one order line may hold: stock, capped by the store's per-line limit. Public so ordering uses the same rule. */
    public BigDecimal maxOrderQuantity(Product p) {
        BigDecimal lineLimit = p.getSoldBy() == SoldBy.UNIT
                ? BigDecimal.valueOf(store().maxLineQuantityUnit())
                : store().maxLineQuantityWeight();
        return p.maxOrderQuantity(lineLimit);
    }

    private List<ProductImageResponse> images(Product p) {
        return p.getImages().stream().map(this::toImage).toList();
    }

    private ProductImageResponse toImage(ProductImage i) {
        return new ProductImageResponse(mediaBaseUrl + "/" + i.getStorageKey(), LocalizedText.of(i.getAltFr(), i.getAltAr()));
    }

    private static Map<String, Object> attributes(Product p) {
        return p.getAttributes() == null ? Map.of() : p.getAttributes();
    }

    /** 1.000 -> 1, 0.500 -> 0.5: quantities read naturally in JSON. Never scientific notation (10, not 1E+1). */
    static BigDecimal plain(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
