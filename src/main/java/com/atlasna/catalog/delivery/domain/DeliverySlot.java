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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

/** A delivery window (e.g. 14:00–16:00) with a capacity per day. */
@Entity
@Table(name = "delivery_slots")
@Getter
@Setter
public class DeliverySlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** null = offered in every zone. */
    private Long zoneId;
    /** ISO day (1 = Monday … 7 = Sunday); null = every day. */
    private Short dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private int capacity;
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

    /** Whether this slot is offered in {@code zoneId} on that weekday. */
    public boolean appliesTo(long zoneId, DayOfWeek day) {
        return (this.zoneId == null || this.zoneId == zoneId) && (dayOfWeek == null || dayOfWeek == day.getValue());
    }
}
