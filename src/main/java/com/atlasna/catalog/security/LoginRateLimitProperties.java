package com.atlasna.catalog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login throttling limits (atlasna.rate-limit.login.*); unset values fall back to the defaults below.
 * Durations accept Spring's formats, e.g. {@code 15m} or {@code PT15M}.
 *
 * @param maxFailuresPerEmail failed logins allowed per account before it is locked (default 5)
 * @param emailWindow         how long those failures are counted / the lock lasts (default 15 minutes)
 * @param maxAttemptsPerIp    login attempts allowed per client IP (default 20)
 * @param ipWindow            window for the per-IP count (default 1 minute)
 */
@ConfigurationProperties(prefix = "atlasna.rate-limit.login")
public record LoginRateLimitProperties(
        Integer maxFailuresPerEmail,
        Duration emailWindow,
        Integer maxAttemptsPerIp,
        Duration ipWindow
) {
    public LoginRateLimitProperties {
        if (maxFailuresPerEmail == null) maxFailuresPerEmail = 5;
        if (emailWindow == null) emailWindow = Duration.ofMinutes(15);
        if (maxAttemptsPerIp == null) maxAttemptsPerIp = 20;
        if (ipWindow == null) ipWindow = Duration.ofMinutes(1);
    }
}
