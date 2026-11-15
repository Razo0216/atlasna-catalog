package com.atlasna.catalog.payment;

import java.time.Instant;

/**
 * What a verified webhook says happened.
 *
 * @param eventId     the provider's event id: our idempotency key (providers re-deliver)
 * @param amountDzd   what the provider reports as paid, whole dinars
 * @param orderNumber from the metadata we attached at checkout (cross-check only; the providerRef is authoritative)
 */
public record PaymentEvent(String eventId, String type, Status status, String providerRef, Long amountDzd,
                           String orderNumber, Instant createdAt) {

    public enum Status { PENDING, PAID, FAILED, CANCELED }
}
