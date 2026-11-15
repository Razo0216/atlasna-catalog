package com.atlasna.catalog.common.exception;

import lombok.Getter;

/**
 * A rate limit that must not be skipped (e.g. login throttling) couldn't be checked because Redis is unavailable.
 * Mapped to 503 RATE_LIMIT_UNAVAILABLE with a Retry-After header: refusing is safer than unlimited password guessing.
 */
@Getter
public class RateLimitUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitUnavailableException(long retryAfterSeconds) {
        super("This action is temporarily unavailable. Try again in " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
