package com.atlasna.catalog.product.web;

import com.atlasna.catalog.product.dto.CategoryNode;
import com.atlasna.catalog.product.cache.CachedCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public category tree: departments (roots) with their sub-categories, active only, in display order. */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CachedCatalog catalog;

    @GetMapping
    public List<CategoryNode> tree() {
        return catalog.categoryTree();
    }
}
