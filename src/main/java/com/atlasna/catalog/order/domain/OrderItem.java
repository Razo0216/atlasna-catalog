package com.atlasna.catalog.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One order line with a snapshot of the product (name, sku, unit, price, weight) at placement. */
@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;
    private Long productId;
    private String nameFr;
    private String nameAr;
    private String sku;
    /** "UNIT" or "WEIGHT" (snapshot of the product's SoldBy). */
    private String soldBy;
    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;
    private int unitWeightGrams;
    @Column(precision = 10, scale = 3)
    private BigDecimal quantityOrdered;
    /** Set while packing (weighed kg, or packed units); null until then. */
    @Column(precision = 10, scale = 3)
    private BigDecimal quantityFulfilled;
    @Column(precision = 12, scale = 2)
    private BigDecimal lineTotal;
    @Enumerated(EnumType.STRING)
    private SubstitutionPolicy substitutionPolicy;
    @Enumerated(EnumType.STRING)
    private ItemStatus status = ItemStatus.PENDING;
    private Long substituteProductId;

    /** The product whose stock this line currently holds (the substitute once substituted). */
    public long stockProductId() {
        return substituteProductId != null ? substituteProductId : productId;
    }

    /** How much stock this line currently holds. */
    public BigDecimal reservedQuantity() {
        if (status == ItemStatus.OUT_OF_STOCK) return BigDecimal.ZERO;
        return quantityFulfilled != null ? quantityFulfilled : quantityOrdered;
    }
}
