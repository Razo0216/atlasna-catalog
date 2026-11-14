package com.atlasna.catalog.store.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

/** The single row of {@code store_settings} (id is always 1). */
@Entity
@Table(name = "store_settings")
@Getter
@Setter
public class StoreSettingsEntity {

    @Id
    private Short id;

    private boolean storeOpen;
    private LocalTime openingTime;
    private LocalTime closingTime;
    private LocalTime sameDayCutoff;
    private int leadTimeMinutes;
    private int maxDaysAhead;
    private int maxItemWeightGrams;
    private int maxOrderWeightGrams;
    private int maxLineQuantityUnit;
    @Column(precision = 10, scale = 3)
    private BigDecimal maxLineQuantityWeight;
    @Column(precision = 5, scale = 2)
    private BigDecimal weightTolerancePercent;
    private int failedDeliveryFlagCount;
    private int failedDeliveryWindowDays;
    private Instant updatedAt;

    @Version
    private long version;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
