package com.atlasna.catalog.order.domain;

/** Cash on delivery: PENDING → COLLECTED (driver has the cash) · NOT_COLLECTED (failed delivery) · VOID (cancelled). */
public enum PaymentStatus { PENDING, COLLECTED, NOT_COLLECTED, VOID }
