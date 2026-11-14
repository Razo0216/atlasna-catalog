package com.atlasna.catalog.order.domain;

/** The order lifecycle; allowed moves live in {@link Transitions}. */
public enum OrderStatus {
    PLACED, CONFIRMED, PREPARING, READY_FOR_DELIVERY, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
