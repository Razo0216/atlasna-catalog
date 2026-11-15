package com.atlasna.catalog.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Raised once per lot when it enters the "expiring soon" window (notifications subscribe in Phase 7). */
public record LotExpiring(long lotId, long productId, String lotCode, BigDecimal quantityRemaining, LocalDate expiryDate) {}
