package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderRepository;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may see which order. Customers: only their own. Drivers: only the ones assigned to them. Staff/admin: all.
 * Everything else is 404 ORDER_NOT_FOUND — never 403, so ids can't be probed (BOLA / IDOR).
 */
@Component
@RequiredArgsConstructor
public class OrderLookup {

    private final OrderRepository orders;

    public Order forCustomer(User customer, String orderNumber) {
        return orders.findByOrderNumberAndUserId(orderNumber, customer.getId()).orElseThrow(() -> notFound(orderNumber));
    }

    public Order forStaffOrDriver(User actor, String orderNumber) {
        return (actor.getRole() == Role.DRIVER
                ? orders.findByOrderNumberAndAssignedDriverId(orderNumber, actor.getId())
                : actor.getRole() == Role.ADMIN || actor.getRole() == Role.STAFF
                        ? orders.findByOrderNumber(orderNumber)
                        : java.util.Optional.<Order>empty())
                .orElseThrow(() -> notFound(orderNumber));
    }

    private static ApiException notFound(String orderNumber) {
        return ApiException.notFound("ORDER_NOT_FOUND", "Order " + orderNumber + " not found");
    }
}
