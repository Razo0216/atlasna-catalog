package com.atlasna.catalog.product.web;

import com.atlasna.catalog.product.dto.AdminCategoryNode;
import com.atlasna.catalog.product.dto.CategoryRequest;
import com.atlasna.catalog.product.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Category management (ADMIN only, enforced by URL rule and {@code @PreAuthorize}). */
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    /** The full tree including inactive categories. */
    @GetMapping
    public List<AdminCategoryNode> tree() {
        return categoryService.adminTree();
    }

    @PostMapping
    public ResponseEntity<AdminCategoryNode> create(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request));
    }

    @PutMapping("/{id}")
    public AdminCategoryNode update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    /** Only an empty category can be deleted (409 CATEGORY_IN_USE otherwise). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
