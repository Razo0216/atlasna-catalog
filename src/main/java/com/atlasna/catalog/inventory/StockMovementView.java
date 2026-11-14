package com.atlasna.catalog.inventory;

import java.math.BigDecimal;
import java.time.Instant;

/** One ledger row as shown to the admin. */
public record StockMovementView(long id, BigDecimal delta, StockReason reason, Long orderId, Long actorId, String note,
                                Instant at) {}
