package com.atlasna.catalog.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One lot of a product (a delivery with its expiry date). {@code expiryDate} null = doesn't expire. */
public record LotView(long id, long productId, String lotCode, BigDecimal quantityReceived, BigDecimal quantityRemaining,
                      Instant receivedAt, LocalDate expiryDate, String note) {}
