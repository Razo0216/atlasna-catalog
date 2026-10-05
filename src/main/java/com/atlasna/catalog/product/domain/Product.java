package com.atlasna.catalog.product.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A catalog product (table {@code products}, reshaped by Flyway V2).
 *
 * <p>Sold either by UNIT (price per piece, whole quantities) or by WEIGHT (price per kg, quantities in steps such
 * as 0.5 kg). Stock is {@code NUMERIC(10,3)} because loose produce is counted in kilograms.
 *
 * <p>Products are never physically deleted: {@code active = false} hides them from the public API while keeping
 * the row for order history. {@code @Version} gives optimistic locking: a write based on a stale read fails
 * instead of silently overwriting someone else's change (lost update).
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nameFr;

    private String nameAr;

    @Column(length = 2000)
    private String descriptionFr;

    @Column(length = 2000)
    private String descriptionAr;

    /** Generated once from the French name and never changed, so URLs stay stable. */
    @Column(nullable = false, length = 220)
    private String slug;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(length = 14)
    private String barcode;

    @Column(length = 100)
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** BigDecimal, never double: money needs exact decimal arithmetic. Per piece, or per kg for WEIGHT. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** The "was" price shown struck through; must be greater than {@code price} (DB check too). */
    @Column(precision = 12, scale = 2)
    private BigDecimal compareAtPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SoldBy soldBy;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal quantityStep;

    /** Weight of ONE sold unit (1000 for a per-kg item). Drives the heavy-item rule. */
    @Column(nullable = false)
    private Integer unitWeightGrams;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal stockQuantity;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal lowStockThreshold;

    @Column(nullable = false)
    private boolean perishable;

    @Column(nullable = false)
    private boolean featured;

    /** Free-form but documented keys (halal, origin, skinType, ...); stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    /** Soft-delete flag: false means deleted (hidden from public endpoints, restorable by an admin). */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    /** Batch-fetched so listing N products costs one image query, not N (no N+1). */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    @BatchSize(size = 100)
    @Builder.Default
    private List<ProductImage> images = new ArrayList<>();

    @Column(updatable = false)
    private Instant createdAt;

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

    /**
     * Out of stock when not even one quantity step is left (0.3 kg of a product sold in 0.5 kg steps
     * can't be ordered); low when at or under the threshold.
     */
    public Availability availability() {
        if (stockQuantity.compareTo(quantityStep) < 0) return Availability.OUT_OF_STOCK;
        if (stockQuantity.compareTo(lowStockThreshold) <= 0) return Availability.LOW_STOCK;
        return Availability.IN_STOCK;
    }

    /** The most a customer may put in one line: min(stock, lineLimit), rounded DOWN to a whole number of steps. */
    public BigDecimal maxOrderQuantity(BigDecimal lineLimit) {
        BigDecimal cap = stockQuantity.min(lineLimit);
        BigDecimal steps = cap.divide(quantityStep, 0, RoundingMode.FLOOR);
        return steps.multiply(quantityStep);
    }

    public boolean isPubliclyVisible() {
        return active && category.isPubliclyVisible();
    }
}
