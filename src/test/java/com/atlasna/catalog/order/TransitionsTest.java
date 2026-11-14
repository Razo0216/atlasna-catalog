package com.atlasna.catalog.order;

import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.Transitions;
import com.atlasna.catalog.user.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static com.atlasna.catalog.order.domain.OrderStatus.*;
import static com.atlasna.catalog.user.Role.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Every (from, to, role) combination: exactly the documented moves are allowed (docs/02 §6, §3). */
class TransitionsTest {

    private static final Set<String> EXPECTED = Set.of(
            "PLACED>CONFIRMED:STAFF", "PLACED>CONFIRMED:ADMIN",
            "PLACED>CANCELLED:CUSTOMER", "PLACED>CANCELLED:STAFF", "PLACED>CANCELLED:ADMIN",
            "CONFIRMED>PREPARING:STAFF", "CONFIRMED>PREPARING:ADMIN",
            "CONFIRMED>CANCELLED:CUSTOMER", "CONFIRMED>CANCELLED:STAFF", "CONFIRMED>CANCELLED:ADMIN",
            "PREPARING>READY_FOR_DELIVERY:STAFF", "PREPARING>READY_FOR_DELIVERY:ADMIN",
            "PREPARING>CANCELLED:STAFF", "PREPARING>CANCELLED:ADMIN",
            "READY_FOR_DELIVERY>OUT_FOR_DELIVERY:DRIVER", "READY_FOR_DELIVERY>OUT_FOR_DELIVERY:ADMIN",
            "READY_FOR_DELIVERY>CANCELLED:STAFF", "READY_FOR_DELIVERY>CANCELLED:ADMIN",
            "OUT_FOR_DELIVERY>DELIVERED:DRIVER", "OUT_FOR_DELIVERY>DELIVERED:ADMIN",
            "OUT_FOR_DELIVERY>DELIVERY_FAILED:DRIVER", "OUT_FOR_DELIVERY>DELIVERY_FAILED:ADMIN",
            "OUT_FOR_DELIVERY>CANCELLED:STAFF", "OUT_FOR_DELIVERY>CANCELLED:ADMIN",
            "DELIVERY_FAILED>READY_FOR_DELIVERY:STAFF", "DELIVERY_FAILED>READY_FOR_DELIVERY:ADMIN",
            "DELIVERY_FAILED>CANCELLED:STAFF", "DELIVERY_FAILED>CANCELLED:ADMIN");

    @Test
    void exactlyTheDocumentedMovesAreAllowed() {
        Set<String> allowed = new HashSet<>();
        for (OrderStatus from : OrderStatus.values())
            for (OrderStatus to : OrderStatus.values())
                for (Role role : Role.values())
                    if (Transitions.isAllowed(from, to, role)) allowed.add(from + ">" + to + ":" + role);
        assertThat(allowed).isEqualTo(EXPECTED);
    }

    @Test
    void terminalStatesAreFinalAndCustomersOnlyCancelEarly() {
        for (OrderStatus to : OrderStatus.values())
            for (Role role : Role.values()) {
                assertThat(Transitions.isAllowed(DELIVERED, to, role)).isFalse();
                assertThat(Transitions.isAllowed(CANCELLED, to, role)).isFalse();
            }
        for (OrderStatus from : EnumSet.of(PREPARING, READY_FOR_DELIVERY, OUT_FOR_DELIVERY, DELIVERY_FAILED))
            assertThat(Transitions.isAllowed(from, CANCELLED, CUSTOMER)).isFalse();
    }
}
