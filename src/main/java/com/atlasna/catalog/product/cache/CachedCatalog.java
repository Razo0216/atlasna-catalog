package com.atlasna.catalog.product.cache;

import com.atlasna.catalog.product.dto.CategoryNode;
import com.atlasna.catalog.product.dto.ProductResponse;
import com.atlasna.catalog.product.service.CategoryService;
import com.atlasna.catalog.product.service.ProductFilter;
import com.atlasna.catalog.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The public catalog endpoints read through here. Same answers as {@link ProductService} / {@link CategoryService}
 * (they are the loaders), just cached. Order placement and carts never use this: they read Postgres directly
 * through CatalogApi, because an order must never be decided on a cached stock number.
 */
@Service
@RequiredArgsConstructor
public class CachedCatalog {

    private final CatalogCache cache;
    private final CatalogCacheProperties properties;
    private final ProductService products;
    private final CategoryService categories;

    public List<CategoryNode> categoryTree() {
        return cache.get("categories", properties.categoriesTtl(), cache.listOf(CategoryNode.class), categories::publicTree);
    }

    public ProductResponse product(long id) {
        return cache.get("product:id:" + id, properties.productTtl(), cache.type(ProductResponse.class), () -> products.getPublic(id));
    }

    public ProductResponse productBySlug(String slug) {
        return cache.get("product:slug:" + digest(slug), properties.productTtl(), cache.type(ProductResponse.class),
                () -> products.getPublicBySlug(slug));
    }

    public Page<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        CachedPage<ProductResponse> page = cache.get("products:" + digest(listKey(filter, pageable)), properties.listTtl(),
                cache.type(CachedPage.class, ProductResponse.class),
                () -> CachedPage.of(products.search(filter, pageable)));
        return page.toPage(pageable);
    }

    /**
     * Canonical form of a listing request: equivalent requests ({@code q=" Tomates"} vs {@code q="tomates"}) share
     * an entry. The search is case-insensitive and trims {@code q}, so normalising it here can't change an answer.
     */
    public static String listKey(ProductFilter f, Pageable p) {
        String q = f.q() == null ? "" : f.q().trim().toLowerCase(Locale.ROOT);
        return String.join("|", "p=" + p.getPageNumber(), "s=" + p.getPageSize(), "o=" + p.getSort(),
                "c=" + nullToEmpty(f.category()), "t=" + (f.type() == null ? "" : f.type().name()), "q=" + q,
                "i=" + f.inStock(), "f=" + f.featured());
    }

    /** Fixed-length key part: user input (search text, slugs) never ends up raw in a Redis key. */
    static String digest(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
