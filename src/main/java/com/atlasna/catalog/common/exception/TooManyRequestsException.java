package com.atlasna.catalog.common.exception;

import lombok.Getter;

@Getter
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("Too many login attempts. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
