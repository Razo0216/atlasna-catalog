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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** The order's payment record. Cash on delivery: the driver collects and confirms. */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;
    @Enumerated(EnumType.STRING)
    private PaymentMethod method;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;
    private Long collectedBy;
    private Instant collectedAt;
    private String providerRef;
    private String note;
    /** Card: the gateway ("chargily"), its hosted checkout and what we asked it to charge (whole dinars). */
    private String provider;
    private String checkoutUrl;
    private Instant checkoutCreatedAt;
    @Column(precision = 12, scale = 0)
    private BigDecimal chargedAmount;
    private Instant paidAt;
    /** The webhook reported another amount than {@link #chargedAmount}: not marked PAID, staff must check. */
    private boolean amountMismatch;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
