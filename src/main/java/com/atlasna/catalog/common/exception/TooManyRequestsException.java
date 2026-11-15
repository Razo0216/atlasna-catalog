package com.atlasna.catalog.common.exception;

import lombok.Getter;

/**
 * Thrown by a rate limit (login, registration, order placement) when a client or account is temporarily blocked.
 * Mapped to 429 Too Many Requests, with {@code retryAfterSeconds} sent as the {@code Retry-After} header.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Too many requests. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
