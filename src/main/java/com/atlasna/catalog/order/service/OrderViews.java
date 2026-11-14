package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.order.domain.ItemStatus;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.PaymentMethod;
import com.atlasna.catalog.order.domain.PaymentStatus;
import com.atlasna.catalog.order.domain.SubstitutionPolicy;
import com.atlasna.catalog.product.dto.ProductResponse;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** JSON shapes of the cart and order endpoints (docs/03-API-CONTRACT.md §4). */
public final class OrderViews {

    private OrderViews() {}

    // ---------- cart ----------

    public record CartLine(long productId, ProductResponse product, LocalizedText name, BigDecimal quantity,
                           SubstitutionPolicy substitutionPolicy, BigDecimal unitPrice, BigDecimal lineTotal,
                           List<String> warnings) {}

    /** A problem that blocks checkout until the customer fixes the line. */
    public record CartIssue(long productId, String code) {}

    public record CartResponse(List<CartLine> items, BigDecimal subtotal, int totalWeightGrams, List<CartIssue> issues,
                               boolean checkoutReady) {}

    /** A guest-cart line that could not be merged as asked. */
    public record MergeNote(long productId, String reason, BigDecimal requested, BigDecimal added) {}

    public record MergeResponse(CartResponse cart, List<MergeNote> notes) {}

    // ---------- orders ----------

    public record OrderItemView(long id, long productId, LocalizedText name, String sku, String soldBy, BigDecimal unitPrice,
                                BigDecimal quantityOrdered, BigDecimal quantityFulfilled, BigDecimal lineTotal,
                                SubstitutionPolicy substitutionPolicy, ItemStatus status, Long substituteProductId) {}

    public record ZoneRef(long id, LocalizedText name) {}

    public record DeliveryView(ZoneRef zone, String recipientName, String phone, String street, String landmark,
                               String buildingNotes, LocalDate slotDate, @JsonFormat(pattern = "HH:mm") LocalTime slotStart,
                               @JsonFormat(pattern = "HH:mm") LocalTime slotEnd) {}

    public record HistoryView(OrderStatus status, Instant at, String reason) {}

    public record OrderResponse(String orderNumber, OrderStatus status, Instant createdAt, List<OrderItemView> items,
                                BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal total, boolean totalIsEstimate,
                                PaymentMethod paymentMethod, PaymentStatus paymentStatus, String customerNote,
                                BigDecimal changeFor, DeliveryView delivery, List<HistoryView> history) {}

    public record OrderSummary(String orderNumber, OrderStatus status, Instant createdAt, int itemCount, BigDecimal total,
                               boolean totalIsEstimate, LocalDate slotDate, @JsonFormat(pattern = "HH:mm") LocalTime slotStart,
                               @JsonFormat(pattern = "HH:mm") LocalTime slotEnd) {}

    /** Staff/admin view: the customer view plus the operational fields. */
    public record AdminOrderResponse(OrderResponse order, long customerId, Long assignedDriverId, BigDecimal estimatedTotal,
                                     boolean totalChangeFlagged, boolean customerFlagged, long failedDeliveriesRecently,
                                     BigDecimal cashCollected, String cancelledReason, String failureReason,
                                     int totalWeightGrams, long version) {}

    /** Staff/admin list row. */
    public record AdminOrderSummary(String orderNumber, OrderStatus status, Instant createdAt, String recipientName,
                                    String phone, long zoneId, LocalDate slotDate,
                                    @JsonFormat(pattern = "HH:mm") LocalTime slotStart,
                                    @JsonFormat(pattern = "HH:mm") LocalTime slotEnd, BigDecimal total,
                                    boolean totalIsEstimate, Long assignedDriverId, boolean totalChangeFlagged) {}
}
