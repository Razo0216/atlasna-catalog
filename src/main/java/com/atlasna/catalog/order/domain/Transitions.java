package com.atlasna.catalog.order.domain;

import com.atlasna.catalog.user.Role;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.atlasna.catalog.order.domain.OrderStatus.*;
import static com.atlasna.catalog.user.Role.*;

/**
 * The order state machine as data: {@code from → (to → roles allowed)}. Anything not listed is refused with
 * 409 INVALID_STATE_TRANSITION. Customers may only cancel, and only before preparation starts.
 */
public final class Transitions {

    private static final Map<OrderStatus, Map<OrderStatus, Set<Role>>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        allow(PLACED, CONFIRMED, STAFF, ADMIN);
        allow(PLACED, CANCELLED, CUSTOMER, STAFF, ADMIN);
        allow(CONFIRMED, PREPARING, STAFF, ADMIN);
        allow(CONFIRMED, CANCELLED, CUSTOMER, STAFF, ADMIN);
        allow(PREPARING, READY_FOR_DELIVERY, STAFF, ADMIN);
        allow(PREPARING, CANCELLED, STAFF, ADMIN);
        allow(READY_FOR_DELIVERY, OUT_FOR_DELIVERY, DRIVER, ADMIN);
        allow(READY_FOR_DELIVERY, CANCELLED, STAFF, ADMIN);
        allow(OUT_FOR_DELIVERY, DELIVERED, DRIVER, ADMIN);
        allow(OUT_FOR_DELIVERY, DELIVERY_FAILED, DRIVER, ADMIN);
        allow(OUT_FOR_DELIVERY, CANCELLED, STAFF, ADMIN);
        allow(DELIVERY_FAILED, READY_FOR_DELIVERY, STAFF, ADMIN); // try again
        allow(DELIVERY_FAILED, CANCELLED, STAFF, ADMIN);
    }

    private Transitions() {}

    private static void allow(OrderStatus from, OrderStatus to, Role... roles) {
        ALLOWED.computeIfAbsent(from, k -> new EnumMap<>(OrderStatus.class)).put(to, Set.of(roles));
    }

    public static boolean isAllowed(OrderStatus from, OrderStatus to, Role role) {
        return ALLOWED.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(role);
    }
}
