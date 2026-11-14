package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.DeliveryApi;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderItem;
import com.atlasna.catalog.order.domain.OrderRepository;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.SubstitutionPolicy;
import com.atlasna.catalog.order.service.CartService.ItemInput;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderResponse;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderSummary;
import com.atlasna.catalog.order.service.OrderViews.MergeResponse;
import com.atlasna.catalog.order.service.OrderViews.OrderResponse;
import com.atlasna.catalog.order.service.OrderViews.OrderSummary;
import com.atlasna.catalog.store.StoreSettings;
import com.atlasna.catalog.user.User;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read side of orders for customers, staff/admin and drivers, plus reorder. */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final OrderMapper mapper;
    private final CartService carts;
    private final DeliveryApi delivery;
    private final Clock clock;

    public record AdminFilter(OrderStatus status, LocalDate date, Long zoneId, String q, Long assignedDriverId) {}

    @Transactional(readOnly = true)
    public Page<OrderSummary> mine(User customer, int page, int size) {
        return orders.findByUserIdOrderByCreatedAtDesc(customer.getId(), PageRequest.of(page, size)).map(mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public OrderResponse mine(User customer, String orderNumber) {
        return mapper.toResponse(lookup.forCustomer(customer, orderNumber));
    }

    @Transactional(readOnly = true)
    public Page<AdminOrderSummary> adminList(AdminFilter f, int page, int size) {
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.status() != null) p.add(cb.equal(root.get("status"), f.status()));
            if (f.date() != null) p.add(cb.equal(root.get("slotDate"), f.date()));
            if (f.zoneId() != null) p.add(cb.equal(root.get("zoneId"), f.zoneId()));
            if (f.assignedDriverId() != null) p.add(cb.equal(root.get("assignedDriverId"), f.assignedDriverId()));
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().strip().toUpperCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.upper(root.get("orderNumber")), like, '\\'), cb.like(root.get("phone"), like, '\\'),
                        cb.like(cb.upper(root.get("recipientName")), like, '\\')));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return orders.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Order.asc("slotDate"), Sort.Order.asc("slotStart"),
                Sort.Order.asc("id")))).map(mapper::toAdminSummary);
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse adminGet(User actor, String orderNumber) {
        return admin(lookup.forStaffOrDriver(actor, orderNumber));
    }

    /** Staff/admin detail, with the repeat-failure flag (rule 17) computed from the order history. */
    @Transactional(readOnly = true)
    public AdminOrderResponse admin(Order o) {
        StoreSettings s = delivery.settings();
        long failed = orders.countFailedDeliveries(o.getPhone(),
                clock.instant().minus(Duration.ofDays(s.failedDeliveryWindowDays())));
        BigDecimal cash = o.getPayment() != null && o.getPayment().getCollectedAt() != null ? o.getPayment().getAmount() : null;
        return new AdminOrderResponse(mapper.toResponse(o), o.getUserId(), o.getAssignedDriverId(), o.getEstimatedTotal(),
                o.isTotalChangeFlagged(), failed >= s.failedDeliveryFlagCount(), failed, cash, o.getCancelledReason(),
                o.getFailureReason(), o.getTotalWeightGrams(), o.getVersion());
    }

    @Transactional(readOnly = true)
    public List<AdminOrderSummary> driverQueue(User driver) {
        return orders.findByAssignedDriverIdAndStatusInOrderBySlotDateAscSlotStartAsc(driver.getId(),
                List.of(OrderStatus.READY_FOR_DELIVERY, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED))
                .stream().map(mapper::toAdminSummary).toList();
    }

    /** Puts a past order's products back in the cart (live prices; warnings where things changed). */
    @Transactional
    public MergeResponse reorder(User customer, String orderNumber) {
        Order o = lookup.forCustomer(customer, orderNumber);
        Map<Long, BigDecimal> quantities = new LinkedHashMap<>();
        Map<Long, BigDecimal> prices = new HashMap<>();
        Map<Long, SubstitutionPolicy> policies = new HashMap<>();
        for (OrderItem i : o.getItems()) {
            quantities.merge(i.getProductId(), i.getQuantityOrdered(), BigDecimal::add);
            prices.put(i.getProductId(), i.getUnitPrice());
            policies.put(i.getProductId(), i.getSubstitutionPolicy());
        }
        if (quantities.isEmpty()) throw ApiException.businessRule("CART_EMPTY", "This order has no lines to reorder");
        List<ItemInput> lines = quantities.entrySet().stream().map(e -> new ItemInput(e.getKey(), e.getValue())).toList();
        return carts.addLines(customer.getId(), lines, prices, policies);
    }
}
