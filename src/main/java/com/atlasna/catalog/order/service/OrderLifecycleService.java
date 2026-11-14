package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.DeliveryApi;
import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.order.OrderStatusChanged;
import com.atlasna.catalog.order.domain.ItemStatus;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderItem;
import com.atlasna.catalog.order.domain.OrderRepository;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.Payment;
import com.atlasna.catalog.order.domain.PaymentStatus;
import com.atlasna.catalog.order.domain.Transitions;
import com.atlasna.catalog.product.CatalogApi;
import com.atlasna.catalog.product.ProductInfo;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Every status change goes through {@link #move}: the allow-table check (role per transition), the side effects
 * (stock back on cancel, slot released, payment updated), a history row and an {@link OrderStatusChanged} event.
 * Stock follows the packing: weighing heavier takes more, lighter or out-of-stock gives back.
 */
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final DeliveryApi delivery;
    private final CatalogApi catalog;
    private final UserRepository users;
    private final OrderLookup lookup;
    private final ApplicationEventPublisher events;

    public record ItemPatch(BigDecimal quantityFulfilled, ItemStatus status, Long substituteProductId) {}

    /** Staff/admin/driver move (POST /api/admin/orders/{n}/transition). */
    @Transactional
    public Order transition(User actor, String orderNumber, OrderStatus to, String reason, BigDecimal cashCollected) {
        Order order = lookup.forStaffOrDriver(actor, orderNumber);
        return move(order, to, actor, reason, cashCollected);
    }

    /** The customer cancels their own order (only before preparation starts). */
    @Transactional
    public Order cancelByCustomer(User customer, String orderNumber, String reason) {
        Order order = lookup.forCustomer(customer, orderNumber);
        return move(order, OrderStatus.CANCELLED, customer, reason, null);
    }

    @Transactional
    public Order assignDriver(User actor, String orderNumber, long driverId) {
        Order order = lookup.forStaffOrDriver(actor, orderNumber);
        if (order.getStatus().isTerminal() || order.getStatus() == OrderStatus.OUT_FOR_DELIVERY) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "The driver can't be changed in status " + order.getStatus());
        }
        User driver = users.findById(driverId).filter(u -> u.getRole() == Role.DRIVER && u.isEnabled())
                .orElseThrow(() -> ApiException.invalidField("driverId", "Not an active driver"));
        order.setAssignedDriverId(driver.getId());
        return order;
    }

    /** Packing a line (PREPARING only): weighed quantity, out of stock, or substituted. Totals are recomputed. */
    @Transactional
    public Order patchItem(User actor, String orderNumber, long itemId, ItemPatch patch) {
        Order order = lookup.forStaffOrDriver(actor, orderNumber);
        if (order.getStatus() != OrderStatus.PREPARING) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Lines can only be packed while the order is PREPARING");
        }
        OrderItem item = order.getItems().stream().filter(i -> i.getId() == itemId).findFirst()
                .orElseThrow(() -> ApiException.notFound("ORDER_ITEM_NOT_FOUND", "Line " + itemId + " not found"));

        long heldProduct = item.stockProductId();
        BigDecimal heldQty = item.reservedQuantity();

        ItemStatus status = patch.status() != null ? patch.status() : ItemStatus.PACKED;
        Long substitute = null;
        BigDecimal fulfilled;
        switch (status) {
            case PENDING -> throw ApiException.invalidField("status", "Use PACKED, OUT_OF_STOCK or SUBSTITUTED");
            case OUT_OF_STOCK -> fulfilled = BigDecimal.ZERO.setScale(3);
            case SUBSTITUTED -> {
                if (patch.substituteProductId() == null) {
                    throw ApiException.invalidField("substituteProductId", "Say which product replaces this one");
                }
                ProductInfo p = catalog.find(List.of(patch.substituteProductId())).get(patch.substituteProductId());
                if (p == null) throw ApiException.invalidField("substituteProductId", "Unknown product");
                substitute = p.id();
                fulfilled = quantity(patch.quantityFulfilled(), item);
            }
            default -> fulfilled = quantity(patch.quantityFulfilled(), item);
        }

        // Move stock from what the line held to what it holds now (net change when it's the same product).
        long newProduct = substitute != null ? substitute : item.getProductId();
        if (newProduct == heldProduct) {
            BigDecimal delta = fulfilled.subtract(heldQty);
            if (delta.signum() > 0) inventory.takeMore(newProduct, delta, order.getId(), actor.getId());
            if (delta.signum() < 0) inventory.restore(newProduct, delta.negate(), order.getId(), actor.getId());
        } else {
            inventory.restore(heldProduct, heldQty, order.getId(), actor.getId());
            inventory.takeMore(newProduct, fulfilled, order.getId(), actor.getId());
        }

        item.setStatus(status);
        item.setSubstituteProductId(substitute);
        item.setQuantityFulfilled(fulfilled);
        item.setLineTotal(Quantities.lineTotal(item.getUnitPrice(), fulfilled)); // substitutes keep the ordered price
        recompute(order);
        return order;
    }

    /** Driver: OUT_FOR_DELIVERY. */
    @Transactional
    public Order start(User driver, String orderNumber) {
        return move(lookup.forStaffOrDriver(driver, orderNumber), OrderStatus.OUT_FOR_DELIVERY, driver, null, null);
    }

    /** Driver: DELIVERED with the cash collected. */
    @Transactional
    public Order deliver(User driver, String orderNumber, BigDecimal cashCollected, String note) {
        return move(lookup.forStaffOrDriver(driver, orderNumber), OrderStatus.DELIVERED, driver, note, cashCollected);
    }

    /** Driver: DELIVERY_FAILED with the reason (no answer, refused, wrong address…). */
    @Transactional
    public Order fail(User driver, String orderNumber, String reason) {
        return move(lookup.forStaffOrDriver(driver, orderNumber), OrderStatus.DELIVERY_FAILED, driver, reason, null);
    }

    // ---------- the single place where status changes ----------

    Order move(Order order, OrderStatus to, User actor, String reason, BigDecimal cashCollected) {
        OrderStatus from = order.getStatus();
        if (!Transitions.isAllowed(from, to, actor.getRole())) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "Can't move an order from " + from + " to " + to);
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        Payment payment = order.getPayment();
        switch (to) {
            case CANCELLED -> {
                if (actor.getRole() != Role.CUSTOMER && why == null) {
                    throw ApiException.invalidField("reason", "A reason is required to cancel an order");
                }
                for (OrderItem i : order.getItems()) {
                    inventory.restore(i.stockProductId(), i.reservedQuantity(), order.getId(), actor.getId());
                }
                delivery.releaseSlot(order.getSlotId(), order.getSlotDate());
                payment.setStatus(PaymentStatus.VOID);
                order.setCancelledReason(why);
            }
            case READY_FOR_DELIVERY -> {
                for (OrderItem i : order.getItems()) {
                    if (i.getStatus() != ItemStatus.PENDING) continue;
                    if ("WEIGHT".equals(i.getSoldBy())) {
                        throw ApiException.conflict("INVALID_STATE_TRANSITION", "Weigh every per-kg line before marking the order ready")
                                .withDetails(List.of(java.util.Map.of("itemId", i.getId())));
                    }
                    i.setStatus(ItemStatus.PACKED); // unit lines packed as ordered
                    i.setQuantityFulfilled(i.getQuantityOrdered());
                }
                recompute(order);
                if (from == OrderStatus.DELIVERY_FAILED) payment.setStatus(PaymentStatus.PENDING); // second attempt
            }
            case OUT_FOR_DELIVERY -> {
                if (order.getAssignedDriverId() == null) {
                    throw ApiException.conflict("INVALID_STATE_TRANSITION", "Assign a driver first");
                }
            }
            case DELIVERED -> {
                if (cashCollected == null) throw ApiException.invalidField("cashCollected", "Enter the cash collected");
                BigDecimal cash = cashCollected.setScale(2, RoundingMode.HALF_UP);
                if (cash.compareTo(order.getTotal()) != 0 && why == null) {
                    throw ApiException.invalidField("note", "The cash differs from the total (" + order.getTotal() + " DA): explain why");
                }
                payment.setStatus(PaymentStatus.COLLECTED);
                payment.setAmount(cash);
                payment.setCollectedBy(actor.getId());
                payment.setCollectedAt(Instant.now());
                payment.setNote(why);
            }
            case DELIVERY_FAILED -> {
                if (why == null) throw ApiException.invalidField("reason", "Say why the delivery failed");
                payment.setStatus(PaymentStatus.NOT_COLLECTED);
                order.setFailureReason(why);
            }
            default -> { }
        }
        order.setStatus(to);
        order.record(from, to, actor.getId(), why);
        events.publishEvent(new OrderStatusChanged(order.getId(), order.getOrderNumber(), from.name(), to.name(), actor.getId()));
        return order;
    }

    /** Subtotal = Σ line totals; the delivery fee stays as quoted; flag when the total moved beyond the tolerance. */
    private void recompute(Order order) {
        BigDecimal subtotal = order.getItems().stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(order.getDeliveryFee());
        order.setSubtotal(subtotal);
        order.setTotal(total);
        if (order.getPayment().getStatus() == PaymentStatus.PENDING) order.getPayment().setAmount(total);
        BigDecimal estimate = order.getEstimatedTotal();
        if (estimate.signum() > 0) {
            BigDecimal changePercent = total.subtract(estimate).abs().multiply(BigDecimal.valueOf(100))
                    .divide(estimate, 2, RoundingMode.HALF_UP);
            order.setTotalChangeFlagged(changePercent.compareTo(delivery.settings().weightTolerancePercent()) > 0);
        }
    }

    private static BigDecimal quantity(BigDecimal requested, OrderItem item) {
        BigDecimal q = requested != null ? requested : item.getQuantityOrdered();
        if (q.signum() < 0) throw ApiException.invalidField("quantityFulfilled", "Must not be negative");
        q = q.setScale(3, RoundingMode.HALF_UP);
        if ("UNIT".equals(item.getSoldBy())) {
            if (q.stripTrailingZeros().scale() > 0) throw ApiException.invalidField("quantityFulfilled", "Use a whole number of units");
            if (q.compareTo(item.getQuantityOrdered()) > 0) {
                throw ApiException.invalidField("quantityFulfilled", "Can't pack more units than ordered");
            }
        } else if (q.compareTo(item.getQuantityOrdered().multiply(BigDecimal.TWO)) > 0) {
            throw ApiException.invalidField("quantityFulfilled", "Weighed quantity is more than twice what was ordered");
        }
        return q;
    }
}
