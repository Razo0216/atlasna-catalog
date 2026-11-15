package com.atlasna.catalog.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The port to an online payment provider (ADR-0013). The domain only talks to this interface; Chargily is one adapter.
 * Cash on delivery needs no gateway at all.
 */
public interface PaymentGateway {

    /** Short id stored with each payment ("chargily"). */
    String provider();

    /** False when no credentials are configured: card payment is then not offered. */
    boolean enabled();

    /**
     * Starts a hosted checkout. NOT retried automatically (the provider has no idempotency key for it, so a retry after a
     * timeout could create a second checkout); protected by timeouts, a circuit breaker and a bulkhead instead.
     *
     * @throws PaymentUnavailableException when the provider is down, slow, or the breaker is open
     */
    CheckoutSession createCheckout(CheckoutRequest request);

    /** The provider's view of a checkout (idempotent read: retried with backoff + jitter). */
    PaymentEvent.Status checkoutStatus(String providerRef);

    /**
     * Verifies the signature over the RAW body (constant-time) and parses the event.
     *
     * @throws InvalidWebhookException on a missing/bad signature or an unreadable body
     */
    PaymentEvent parseAndVerifyWebhook(Map<String, String> headers, byte[] rawBody);

    /** Refunds are handled by hand for now (REFUND_PENDING); the provider call comes with merchant onboarding. */
    default void refund(String providerRef, BigDecimal amount) {
        throw new UnsupportedOperationException("Refunds are not automated yet");
    }

    record CheckoutRequest(String orderNumber, long amountDzd, String locale, String successUrl, String failureUrl,
                           String webhookUrl) {}

    record CheckoutSession(String providerRef, String checkoutUrl) {}
}
