package com.atlasna.catalog.delivery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A part of the Annaba area the shop delivers to, with its fee and minimum order. */
@Entity
@Table(name = "delivery_zones")
@Getter
@Setter
public class DeliveryZone {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String slug;
    private String nameFr;
    private String nameAr;
    @Column(precision = 12, scale = 2)
    private BigDecimal fee;
    @Column(precision = 12, scale = 2)
    private BigDecimal minOrderAmount;
    /** Orders at or above this subtotal deliver free; null = never free. */
    @Column(precision = 12, scale = 2)
    private BigDecimal freeDeliveryThreshold;
    private int etaMinutes;
    private int sortOrder;
    private boolean active = true;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    private Instant updatedAt = Instant.now();
    @Version
    private long version;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
