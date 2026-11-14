package com.atlasna.catalog.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A placed order. Prices, the delivery address and the slot are SNAPSHOTS taken at placement: later edits to the
 * catalog, the address book or the slot table never rewrite an order. The numeric id stays internal; customers see
 * {@code orderNumber}.
 */
@Entity(name = "CustomerOrder") // "Order" clashes with the ORDER BY keyword in JPQL
@Table(name = "orders")
@Getter
@Setter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String orderNumber;
    private Long userId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private Long zoneId;
    private String recipientName;
    private String phone;
    private String street;
    private String landmark;
    private String buildingNotes;
    @Column(precision = 9, scale = 6)
    private BigDecimal lat;
    @Column(precision = 9, scale = 6)
    private BigDecimal lng;
    private Long slotId;
    private LocalDate slotDate;
    private LocalTime slotStart;
    private LocalTime slotEnd;
    @Column(precision = 12, scale = 2)
    private BigDecimal subtotal;
    @Column(precision = 12, scale = 2)
    private BigDecimal deliveryFee;
    @Column(precision = 12, scale = 2)
    private BigDecimal total;
    /** The total at placement, before any weighing; the tolerance check compares against it. */
    @Column(precision = 12, scale = 2)
    private BigDecimal estimatedTotal;
    private int totalWeightGrams;
    private boolean totalChangeFlagged;
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;
    private String customerNote;
    @Column(precision = 12, scale = 2)
    private BigDecimal changeFor;
    private Long assignedDriverId;
    private String idempotencyKey;
    private String cancelledReason;
    private String failureReason;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("at ASC, id ASC")
    @BatchSize(size = 50)
    private List<OrderStatusHistory> history = new ArrayList<>();

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private Payment payment;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** True while any per-kg line still waits to be weighed: the total may still change. */
    public boolean isTotalEstimate() {
        return items.stream().anyMatch(i -> i.getSoldBy().equals("WEIGHT") && i.getQuantityFulfilled() == null
                && i.getStatus() != ItemStatus.OUT_OF_STOCK);
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public void record(OrderStatus from, OrderStatus to, Long actorId, String reason) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setOrder(this);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setActorId(actorId);
        h.setReason(reason);
        history.add(h);
    }
}
