package com.atlasna.catalog.payment;

/** A webhook request we must not trust: missing or wrong signature, or a body we can't read. */
public class InvalidWebhookException extends RuntimeException {
    public InvalidWebhookException(String message) {
        super(message);
    }
}
