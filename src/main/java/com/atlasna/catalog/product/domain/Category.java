package com.atlasna.catalog.product.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A node of the two-level category tree (table {@code categories}, Flyway V2): roots are the departments
 * (Épicerie, Beauté), children are where products live. Categories are admin-editable data, not an enum,
 * so adding one needs no deploy.
 */
@Entity
@Table(name = "categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a root. The tree is at most two levels deep (enforced in CategoryService). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = 100)
    private String slug;

    /** Always equal to the parent's type for children. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CategoryType type;

    @Column(nullable = false, length = 150)
    private String nameFr;

    @Column(length = 150)
    private String nameAr;

    @Column(nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isRoot() {
        return parent == null;
    }

    /**
     * Products may only be placed in a leaf. With a fixed two-level tree, the leaves are exactly the children,
     * so a root never holds products even before it has children.
     */
    public boolean isLeaf() {
        return parent != null;
    }

    /** Visible to the public only if it and its parent (if any) are active. */
    public boolean isPubliclyVisible() {
        return active && (parent == null || parent.isActive());
    }
}
