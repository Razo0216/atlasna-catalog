package com.atlasna.catalog.delivery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** A customer's delivery address. The landmark matters more than the street number in Annaba. */
@Entity
@Table(name = "addresses")
@Getter
@Setter
public class Address {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long userId;
    private String label;
    private String recipientName;
    /** Canonical +213 form. */
    private String phone;
    private Long zoneId;
    private String street;
    private String landmark;
    private String buildingNotes;
    @Column(precision = 9, scale = 6)
    private BigDecimal lat;
    @Column(precision = 9, scale = 6)
    private BigDecimal lng;
    private boolean isDefault;
    @Column(insertable = false, updatable = false)
    private Instant createdAt;
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
