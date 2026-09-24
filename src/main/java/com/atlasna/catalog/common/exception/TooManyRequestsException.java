package com.atlasna.catalog.common.exception;

import lombok.Getter;

/**
 * Thrown by the login rate limiter when a client or account is temporarily blocked.
 * Mapped to 429 Too Many Requests, with {@code retryAfterSeconds} sent as the {@code Retry-After} header.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Too many login attempts. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
