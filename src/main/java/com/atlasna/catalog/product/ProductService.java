package com.atlasna.catalog.product;

import com.atlasna.catalog.common.exception.ResourceNotFoundException;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Category category, String search, Pageable pageable) {
        Page<Product> page;
        if (search != null && !search.isBlank()) {
            page = productRepository.findByActiveTrueAndNameContainingIgnoreCase(search, pageable);
        } else if (category != null) {
            page = productRepository.findByActiveTrueAndCategory(category, pageable);
        } else {
            page = productRepository.findByActiveTrue(pageable);
        }
        return page.map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(getActiveOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = Product.builder()
                .name(request.name()).description(request.description()).price(request.price())
                .category(request.category()).stockQuantity(request.stockQuantity()).build();
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getOrThrow(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(request.category());
        product.setStockQuantity(request.stockQuantity());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = getOrThrow(id);
        product.setActive(false); // soft delete — preserves order history integrity later
    }

    private Product getOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
    }

    private Product getActiveOrThrow(Long id) {
        Product product = getOrThrow(id);
        if (!product.isActive()) {
            throw new ResourceNotFoundException("Product " + id + " not found");
        }
        return product;
    }
}
