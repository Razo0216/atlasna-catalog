package com.atlasna.catalog.product.service;

import com.atlasna.catalog.common.Slugs;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.config.StoreProperties;
import com.atlasna.catalog.product.domain.Category;
import com.atlasna.catalog.product.domain.CategoryType;
import com.atlasna.catalog.product.domain.Product;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.product.dto.AdminProductResponse;
import com.atlasna.catalog.product.dto.LocalizedText;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;

/**
 * Catalog business logic. Role checks happen in the controllers ({@code @PreAuthorize}); this class enforces
 * the business rules: leaf categories only, the heavy-item cap, whole steps for unit products, compare-at price
 * above price, unique slug/SKU/barcode, and optimistic locking on update.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int MAX_QUERY_LENGTH = 100;

    private final ProductRepository productRepository;
    private final CategoryService categoryService;
    private final ProductMapper mapper;
    private final StoreProperties store;

    // ---------- public ----------

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        validateQuery(filter.q());
        var spec = ProductSpecifications.publiclyVisible().and(ProductSpecifications.matching(filter));
        return productRepository.findAll(spec, pageable).map(mapper::toPublic);
    }

    /** Missing and hidden (inactive product or category) look the same: 404, so nothing leaks. */
    @Transactional(readOnly = true)
    public ProductResponse getPublic(Long id) {
        return productRepository.findWithCategoryById(id)
                .filter(Product::isPubliclyVisible)
                .map(mapper::toPublic)
                .orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public ProductResponse getPublicBySlug(String slug) {
        return productRepository.findBySlug(slug)
                .filter(Product::isPubliclyVisible)
                .map(mapper::toPublic)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product '" + slug + "' not found"));
    }

    // ---------- admin ----------

    @Transactional(readOnly = true)
    public Page<AdminProductResponse> adminSearch(ProductFilter filter, Pageable pageable) {
        validateQuery(filter.q());
        return productRepository.findAll(ProductSpecifications.matching(filter), pageable).map(mapper::toAdmin);
    }

    @Transactional(readOnly = true)
    public AdminProductResponse adminGet(Long id) {
        return mapper.toAdmin(getOrThrow(id));
    }

    @Transactional
    public AdminProductResponse create(ProductRequest request) {
        Category category = categoryService.requireLeaf(request.categoryId());
        validateRules(request);
        Product product = new Product();
        product.setSlug(Slugs.unique(Slugs.slugify(request.name().fr()), productRepository::existsBySlug));
        product.setSku(resolveSku(request.sku(), category.getType(), null));
        apply(product, request, category);
        return mapper.toAdmin(productRepository.saveAndFlush(product));
    }

    /**
     * Full replace. The client must send the {@code version} it read: if someone saved in between, the versions
     * differ and this is refused with 409 instead of silently overwriting their change (a lost update).
     * Hibernate's own version check at flush covers the race where both requests read the same version.
     */
    @Transactional
    public AdminProductResponse update(Long id, ProductRequest request) {
        if (request.version() == null) throw ApiException.invalidField("version", "Version is required");
        Product product = getOrThrow(id);
        if (!Objects.equals(product.getVersion(), request.version())) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION",
                    "Product " + id + " was changed by someone else (version " + product.getVersion() + "). Reload and retry.");
        }
        Category category = categoryService.requireLeaf(request.categoryId());
        validateRules(request);
        product.setSku(resolveSku(request.sku(), category.getType(), id));
        apply(product, request, category); // slug deliberately untouched: URLs stay stable after a rename
        // Flush now so the response carries the incremented version and new updatedAt.
        return mapper.toAdmin(productRepository.saveAndFlush(product));
    }

    /** Soft delete: hidden from the public, kept for order history, restorable. Idempotent. */
    @Transactional
    public void softDelete(Long id) {
        getOrThrow(id).setActive(false);
    }

    @Transactional
    public AdminProductResponse restore(Long id) {
        Product product = getOrThrow(id);
        product.setActive(true);
        return mapper.toAdmin(productRepository.saveAndFlush(product));
    }

    // ---------- rules ----------

    private void validateRules(ProductRequest r) {
        // The store doesn't sell heavy or bulky items (25 kg flour, water packs): a real rule, not a convention.
        if (r.unitWeightGrams() > store.maxItemWeightGrams()) {
            throw ApiException.businessRule("PRODUCT_TOO_HEAVY",
                    "Items heavier than " + store.maxItemWeightGrams() + " g per unit are not sold");
        }
        if (r.soldBy() == SoldBy.UNIT && !isWholeNumber(r.quantityStep())) {
            throw ApiException.invalidField("quantityStep", "Products sold by unit need a whole-number quantity step");
        }
        if (r.compareAtPrice() != null && r.compareAtPrice().compareTo(r.price()) <= 0) {
            throw ApiException.invalidField("compareAtPrice", "Compare-at price must be greater than the price");
        }
    }

    private void apply(Product p, ProductRequest r, Category category) {
        p.setNameFr(r.name().fr().trim());
        p.setNameAr(LocalizedText.blankToNull(r.name().ar()));
        p.setDescriptionFr(r.description() == null ? null : LocalizedText.blankToNull(r.description().fr()));
        p.setDescriptionAr(r.description() == null ? null : LocalizedText.blankToNull(r.description().ar()));
        p.setBrand(LocalizedText.blankToNull(r.brand()));
        p.setBarcode(resolveBarcode(r.barcode(), p.getId()));
        p.setCategory(category);
        p.setPrice(r.price().setScale(2, RoundingMode.UNNECESSARY));
        p.setCompareAtPrice(r.compareAtPrice() == null ? null : r.compareAtPrice().setScale(2, RoundingMode.UNNECESSARY));
        p.setSoldBy(r.soldBy());
        p.setQuantityStep(r.quantityStep().setScale(3, RoundingMode.UNNECESSARY));
        p.setUnitWeightGrams(r.unitWeightGrams());
        p.setStockQuantity(r.stockQuantity().setScale(3, RoundingMode.UNNECESSARY));
        p.setLowStockThreshold(r.lowStockThreshold() == null
                ? new BigDecimal("5.000") : r.lowStockThreshold().setScale(3, RoundingMode.UNNECESSARY));
        p.setPerishable(Boolean.TRUE.equals(r.perishable()));
        p.setFeatured(Boolean.TRUE.equals(r.featured()));
        p.setAttributes(r.attributes() == null ? new HashMap<>() : new HashMap<>(r.attributes()));
    }

    /**
     * A supplied SKU is normalised and must be unique. A blank one is generated as GRC-0001 / BTY-0001 by
     * department. Two concurrent creates can compute the same number; the unique index then rejects one of them.
     */
    private String resolveSku(String requested, CategoryType type, Long selfId) {
        if (requested == null || requested.isBlank()) {
            String prefix = type == CategoryType.GROCERY ? "GRC" : "BTY";
            return "%s-%04d".formatted(prefix, productRepository.maxGeneratedSkuNumber(prefix) + 1);
        }
        String sku = requested.trim().toUpperCase(Locale.ROOT);
        boolean taken = selfId == null ? productRepository.existsBySku(sku) : productRepository.existsBySkuAndIdNot(sku, selfId);
        if (taken) throw ApiException.conflict("SKU_CONFLICT", "SKU " + sku + " is already used by another product");
        return sku;
    }

    private String resolveBarcode(String requested, Long selfId) {
        String barcode = LocalizedText.blankToNull(requested);
        if (barcode == null) return null;
        boolean taken = selfId == null ? productRepository.existsByBarcode(barcode) : productRepository.existsByBarcodeAndIdNot(barcode, selfId);
        if (taken) throw ApiException.conflict("BARCODE_CONFLICT", "Barcode " + barcode + " is already used by another product");
        return barcode;
    }

    private static void validateQuery(String q) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalidField("q", "Search text must be at most " + MAX_QUERY_LENGTH + " characters");
        }
    }

    private static boolean isWholeNumber(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    private Product getOrThrow(Long id) {
        return productRepository.findWithCategoryById(id).orElseThrow(() -> notFound(id));
    }

    private static ApiException notFound(Long id) {
        return ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + id + " not found");
    }
}
