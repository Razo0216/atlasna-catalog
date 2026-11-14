package com.atlasna.catalog.order;

import java.math.BigDecimal;

/** Published inside the placing transaction; the event publication registry is the outbox Phase 7 ships to Kafka. */
public record OrderPlaced(long orderId, String orderNumber, long userId, BigDecimal total) {}
