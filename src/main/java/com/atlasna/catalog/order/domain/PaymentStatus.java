package com.atlasna.catalog.order.domain;

/**
 * Cash on delivery: PENDING → COLLECTED (driver has the cash) · NOT_COLLECTED (failed delivery) · VOID (cancelled).
 * Card online: PENDING → PAID (verified webhook only) · FAILED (declined/cancelled, the customer may retry or switch to
 * cash) · REFUND_PENDING (a paid order was cancelled: refund by hand until refunds are automated).
 */
public enum PaymentStatus { PENDING, COLLECTED, NOT_COLLECTED, VOID, PAID, FAILED, REFUND_PENDING }
