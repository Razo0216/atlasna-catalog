package com.atlasna.catalog.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToLimitThenBlocksWithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.retryAfterSeconds("k")).isZero();
            limiter.record("k");
        }
        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.retryAfterSeconds("k")).isEqualTo(40);
    }

    @Test
    void unblocksWhenWindowExpires() {
        for (int i = 0; i < 3; i++) limiter.record("k");
        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.retryAfterSeconds("k")).isZero();
        limiter.record("k");
        assertThat(limiter.retryAfterSeconds("k")).isZero(); // new window, count = 1
    }

    @Test
    void keysAreIndependentAndResettable() {
        for (int i = 0; i < 3; i++) limiter.record("a");

        assertThat(limiter.retryAfterSeconds("a")).isPositive();
        assertThat(limiter.retryAfterSeconds("b")).isZero();

        limiter.reset("a");
        assertThat(limiter.retryAfterSeconds("a")).isZero();
    }

    @Test
    void retryAfterRoundsUpPartialSeconds() {
        for (int i = 0; i < 3; i++) limiter.record("k");
        clock.advance(Duration.ofMillis(59_500));

        assertThat(limiter.retryAfterSeconds("k")).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
