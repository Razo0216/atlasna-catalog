package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.delivery.DeliveryApi;
import com.atlasna.catalog.delivery.ZoneInfo;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderItem;
import com.atlasna.catalog.order.domain.PaymentStatus;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderSummary;
import com.atlasna.catalog.order.service.OrderViews.DeliveryView;
import com.atlasna.catalog.order.service.OrderViews.HistoryView;
import com.atlasna.catalog.order.service.OrderViews.OrderItemView;
import com.atlasna.catalog.order.service.OrderViews.OrderResponse;
import com.atlasna.catalog.order.service.OrderViews.OrderSummary;
import com.atlasna.catalog.order.service.OrderViews.ZoneRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Entity → JSON. Only fields the contract names; nothing internal (ids of other users, versions) leaks to customers. */
@Component
@RequiredArgsConstructor
public class OrderMapper {

    private final DeliveryApi delivery;

    public OrderResponse toResponse(Order o) {
        ZoneRef zone = delivery.zone(o.getZoneId())
                .map((ZoneInfo z) -> new ZoneRef(z.id(), z.name()))
                .orElse(new ZoneRef(o.getZoneId(), LocalizedText.of("—", null)));
        return new OrderResponse(o.getOrderNumber(), o.getStatus(), o.getCreatedAt(),
                o.getItems().stream().map(OrderMapper::item).toList(),
                o.getSubtotal(), o.getDeliveryFee(), o.getTotal(), o.isTotalEstimate(), o.getPaymentMethod(),
                o.getPayment() == null ? PaymentStatus.PENDING : o.getPayment().getStatus(), o.getCustomerNote(), o.getChangeFor(),
                new DeliveryView(zone, o.getRecipientName(), o.getPhone(), o.getStreet(), o.getLandmark(), o.getBuildingNotes(),
                        o.getSlotDate(), o.getSlotStart(), o.getSlotEnd()),
                o.getHistory().stream().map(h -> new HistoryView(h.getToStatus(), h.getAt(), h.getReason())).toList());
    }

    public OrderSummary toSummary(Order o) {
        return new OrderSummary(o.getOrderNumber(), o.getStatus(), o.getCreatedAt(), o.getItems().size(), o.getTotal(),
                o.isTotalEstimate(), o.getSlotDate(), o.getSlotStart(), o.getSlotEnd());
    }

    public AdminOrderSummary toAdminSummary(Order o) {
        return new AdminOrderSummary(o.getOrderNumber(), o.getStatus(), o.getCreatedAt(), o.getRecipientName(), o.getPhone(),
                o.getZoneId(), o.getSlotDate(), o.getSlotStart(), o.getSlotEnd(), o.getTotal(), o.isTotalEstimate(),
                o.getAssignedDriverId(), o.isTotalChangeFlagged());
    }

    private static OrderItemView item(OrderItem i) {
        return new OrderItemView(i.getId(), i.getProductId(), LocalizedText.of(i.getNameFr(), i.getNameAr()), i.getSku(),
                i.getSoldBy(), i.getUnitPrice(), i.getQuantityOrdered(), i.getQuantityFulfilled(), i.getLineTotal(),
                i.getSubstitutionPolicy(), i.getStatus(), i.getSubstituteProductId());
    }
}
