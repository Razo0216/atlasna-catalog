package com.atlasna.catalog.order;

import com.atlasna.catalog.common.Actor;

/** Published on every lifecycle move (notifications subscribe in Phase 7; the audit log records it). */
public record OrderStatusChanged(long orderId, String orderNumber, String from, String to, String reason, Actor actor) {}
