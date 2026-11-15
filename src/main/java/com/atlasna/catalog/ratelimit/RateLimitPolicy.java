package com.atlasna.catalog.ratelimit;

import java.time.Duration;

/**
 * One limit: at most {@code capacity} actions per {@code period} for a subject (an email, an IP, a user id),
 * refilled continuously — after a burst, one more action is allowed every {@code period / capacity}.
 *
 * @param name           short id used in the Redis key and the metrics ("login-ip", "orders"…)
 * @param onRedisFailure what to do when Redis can't be asked: let the request through, or refuse it
 */
public record RateLimitPolicy(String name, int capacity, Duration period, OnRedisFailure onRedisFailure) {

    public RateLimitPolicy {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least 1");
        if (period == null || period.isNegative() || period.isZero()) throw new IllegalArgumentException("period must be positive");
    }

    /**
     * Explicit per policy (ADR-0011): a security control such as login throttling must not silently switch off
     * when Redis is down (DENY), while refusing orders because of a cache outage would hurt the shop (ALLOW).
     */
    public enum OnRedisFailure { ALLOW, DENY }
}
