package com.atlasna.catalog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Login throttling limits (atlasna.rate-limit.login.*); unset values fall back to the defaults below. */
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
