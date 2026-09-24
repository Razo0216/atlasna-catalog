package com.atlasna.catalog.product;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A catalog product (table {@code products}, created by Flyway migration V1).
 *
 * <p>Single-store model: no sellerId. Add it later if multi-vendor ever happens — nothing else here changes.
 * Products are never physically deleted: {@code active = false} hides them from public reads while keeping
 * the row for future order history.
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
    private String name;

    @Column(length = 2000)
    private String description;

    /** BigDecimal, never double: money needs exact decimal arithmetic. Up to 12 digits, 2 after the point. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    /** Soft-delete flag: false means deleted (hidden from public endpoints). */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    /** JPA lifecycle hook: runs just before the first INSERT. */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** JPA lifecycle hook: runs just before each UPDATE. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
