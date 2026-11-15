package com.atlasna.catalog.product.service;

import com.atlasna.catalog.common.Slugs;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.product.domain.Category;
import com.atlasna.catalog.product.domain.CategoryRepository;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.product.dto.AdminCategoryNode;
import com.atlasna.catalog.product.dto.CategoryNode;
import com.atlasna.catalog.product.dto.CategoryRequest;
import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.common.Actor;
import com.atlasna.catalog.common.FieldChanges;
import com.atlasna.catalog.product.CatalogChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The category tree: public read, admin CRUD. Rules: at most two levels; a child has its parent's type;
 * slugs are unique; a category that still has children or products can't be deleted.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher events;

    /** Active categories only; children of an inactive root are hidden with it. One query, built in memory. */
    @Transactional(readOnly = true)
    public List<CategoryNode> publicTree() {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> children = childrenByParentId(all);
        return all.stream()
                .filter(c -> c.isRoot() && c.isActive())
                .map(root -> new CategoryNode(root.getId(), root.getSlug(), root.getType(), name(root), root.getSortOrder(),
                        children.getOrDefault(root.getId(), List.of()).stream()
                                .filter(Category::isActive)
                                .map(c -> new CategoryNode(c.getId(), c.getSlug(), c.getType(), name(c), c.getSortOrder(), List.of()))
                                .toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryNode> adminTree() {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> children = childrenByParentId(all);
        return all.stream()
                .filter(Category::isRoot)
                .map(root -> toAdminNode(root, children.getOrDefault(root.getId(), List.of()).stream()
                        .map(c -> toAdminNode(c, List.of())).toList()))
                .toList();
    }

    @Transactional
    public AdminCategoryNode create(CategoryRequest request) {
        Category parent = resolveParent(request, null);
        String slug = request.slug() == null || request.slug().isBlank()
                ? Slugs.unique(Slugs.slugify(request.name().fr()), categoryRepository::existsBySlug)
                : requireFreeSlug(request.slug(), null);
        Category category = Category.builder()
                .parent(parent)
                .slug(slug)
                .type(request.type())
                .nameFr(request.name().fr().trim())
                .nameAr(LocalizedText.blankToNull(request.name().ar()))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .active(request.active() == null || request.active())
                .build();
        Category saved = categoryRepository.saveAndFlush(category);
        changed(saved.getId(), "CATEGORY_CREATED", Map.of(), snapshot(saved));
        return toAdminNode(saved, List.of());
    }

    @Transactional
    public AdminCategoryNode update(Long id, CategoryRequest request) {
        Category category = getOrThrow(id);
        boolean hasChildren = categoryRepository.existsByParentId(id);
        if (hasChildren && request.parentId() != null) {
            throw ApiException.invalidField("parentId", "A category with children must stay a root");
        }
        if (hasChildren && request.type() != category.getType()) {
            throw ApiException.invalidField("type", "Change the type of a root only when it has no children");
        }
        Map<String, Object> before = snapshot(category);
        Category parent = resolveParent(request, id);
        if (parent == null && productRepository.existsByCategoryId(id)) {
            throw ApiException.conflict("CATEGORY_IN_USE", "A category that holds products must stay a child category");
        }
        if (request.slug() != null && !request.slug().isBlank()) {
            category.setSlug(requireFreeSlug(request.slug(), id));
        }
        category.setParent(parent);
        category.setType(request.type());
        category.setNameFr(request.name().fr().trim());
        category.setNameAr(LocalizedText.blankToNull(request.name().ar()));
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        if (request.active() != null) category.setActive(request.active());
        categoryRepository.flush();
        changed(id, "CATEGORY_UPDATED", before, snapshot(category));
        List<AdminCategoryNode> childNodes = hasChildren
                ? categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                        .filter(c -> c.getParent() != null && Objects.equals(c.getParent().getId(), id))
                        .map(c -> toAdminNode(c, List.of())).toList()
                : List.of();
        return toAdminNode(category, childNodes);
    }

    /** Hard delete, allowed only for an empty category (no children, no products — even soft-deleted ones). */
    @Transactional
    public void delete(Long id) {
        Category category = getOrThrow(id);
        if (categoryRepository.existsByParentId(id)) {
            throw ApiException.conflict("CATEGORY_IN_USE", "Delete or move the child categories first");
        }
        if (productRepository.existsByCategoryId(id)) {
            throw ApiException.conflict("CATEGORY_IN_USE", "The category still has products (including deleted ones)");
        }
        Map<String, Object> before = snapshot(category);
        categoryRepository.delete(category);
        changed(id, "CATEGORY_DELETED", before, Map.of());
    }

    /** Cache invalidation + audit log (only the fields that changed). */
    private void changed(long id, String action, Map<String, Object> before, Map<String, Object> after) {
        FieldChanges.Diff diff = FieldChanges.diff(before, after);
        events.publishEvent(new CatalogChanged("CATEGORY", id, action, diff.before(), diff.after(), Actor.current()));
    }

    private static Map<String, Object> snapshot(Category c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("slug", c.getSlug());
        m.put("nameFr", c.getNameFr());
        m.put("nameAr", c.getNameAr());
        m.put("type", c.getType());
        m.put("parentId", c.getParent() == null ? null : c.getParent().getId());
        m.put("sortOrder", c.getSortOrder());
        m.put("active", c.isActive());
        return m;
    }

    /** For product create/update: the category must exist and be a leaf (a child), because only leaves hold products. */
    @Transactional(readOnly = true)
    public Category requireLeaf(Long id) {
        Category category = getOrThrow(id);
        if (!category.isLeaf()) {
            throw ApiException.businessRule("CATEGORY_NOT_LEAF", "Products must be placed in a sub-category, not a department");
        }
        return category;
    }

    private Category resolveParent(CategoryRequest request, Long selfId) {
        if (request.parentId() == null) return null;
        if (request.parentId().equals(selfId)) {
            throw ApiException.invalidField("parentId", "A category can't be its own parent");
        }
        Category parent = getOrThrow(request.parentId());
        if (!parent.isRoot()) {
            throw ApiException.invalidField("parentId", "Categories can only be two levels deep");
        }
        if (parent.getType() != request.type()) {
            throw ApiException.invalidField("type", "A sub-category must have the same type as its parent");
        }
        return parent;
    }

    private String requireFreeSlug(String slug, Long selfId) {
        boolean taken = selfId == null ? categoryRepository.existsBySlug(slug) : categoryRepository.existsBySlugAndIdNot(slug, selfId);
        if (taken) throw ApiException.conflict("SLUG_CONFLICT", "Slug '" + slug + "' is already used by another category");
        return slug;
    }

    private Category getOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category " + id + " not found"));
    }

    private static Map<Long, List<Category>> childrenByParentId(List<Category> all) {
        // getParent().getId() reads the id from the lazy proxy without loading the parent.
        return all.stream().filter(c -> !c.isRoot())
                .collect(Collectors.groupingBy(c -> c.getParent().getId()));
    }

    private static LocalizedText name(Category c) {
        return LocalizedText.of(c.getNameFr(), c.getNameAr());
    }

    private static AdminCategoryNode toAdminNode(Category c, List<AdminCategoryNode> children) {
        return new AdminCategoryNode(c.getId(), c.isRoot() ? null : c.getParent().getId(), c.getSlug(), c.getType(),
                name(c), c.getSortOrder(), c.isActive(), children);
    }
}
