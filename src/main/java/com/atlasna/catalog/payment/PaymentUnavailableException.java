package com.atlasna.catalog.payment;

/** The provider can't be used right now (timeout, 5xx, breaker open, too many concurrent calls, not configured). */
public class PaymentUnavailableException extends RuntimeException {
    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
