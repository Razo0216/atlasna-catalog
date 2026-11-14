package com.atlasna.catalog.order;

/** Published on every lifecycle move (notifications subscribe in Phase 7). */
public record OrderStatusChanged(long orderId, String orderNumber, String from, String to, Long actorId) {}
