package com.atlasna.catalog.product;

import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.product.domain.Availability;
import com.atlasna.catalog.product.domain.Product;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.product.dto.ProductResponse;
import com.atlasna.catalog.product.service.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** What other modules (cart, orders) may ask the catalog. Everything else in this module is internal. */
@Service
@RequiredArgsConstructor
public class CatalogApi {

    private final ProductRepository products;
    private final ProductMapper mapper;

    /** Products by id (missing ids are simply absent), with live price, stock limits and visibility. */
    @Transactional(readOnly = true)
    public Map<Long, ProductInfo> find(Collection<Long> ids) {
        Map<Long, ProductInfo> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (Product p : products.findByIdIn(ids)) out.put(p.getId(), info(p));
        return out;
    }

    /** The public view of each publicly visible product (what the storefront shows in the cart). */
    @Transactional(readOnly = true)
    public Map<Long, ProductResponse> publicViews(Collection<Long> ids) {
        Map<Long, ProductResponse> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (Product p : products.findByIdIn(ids)) {
            if (p.isPubliclyVisible()) out.put(p.getId(), mapper.toPublic(p));
        }
        return out;
    }

    private ProductInfo info(Product p) {
        Availability availability = p.availability();
        return new ProductInfo(p.getId(), p.getSlug(), p.getSku(), LocalizedText.of(p.getNameFr(), p.getNameAr()),
                p.getSoldBy(), p.getPrice(), p.getQuantityStep(), p.getUnitWeightGrams(), mapper.maxOrderQuantity(p),
                availability, p.isPubliclyVisible(), p.isPubliclyVisible() && availability != Availability.OUT_OF_STOCK);
    }
}
