package com.atlasna.catalog.security;

import com.atlasna.catalog.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Brute-force protection for /api/auth/login:
 * - per email: too many failed attempts locks that account's logins until the window ends;
 * - per client IP: caps total attempts, which stops one client spraying many accounts.
 * Client IP is the servlet remote address; behind a reverse proxy, configure
 * server.forward-headers-strategy so it reflects the real client, not the proxy.
 */
@Component
public class LoginRateLimiter {

    private final FixedWindowRateLimiter failuresByEmail;
    private final FixedWindowRateLimiter attemptsByIp;

    public LoginRateLimiter(LoginRateLimitProperties properties) {
        Clock clock = Clock.systemUTC();
        this.failuresByEmail = new FixedWindowRateLimiter(
                properties.maxFailuresPerEmail(), properties.emailWindow(), clock);
        this.attemptsByIp = new FixedWindowRateLimiter(
                properties.maxAttemptsPerIp(), properties.ipWindow(), clock);
    }

    /** Throws if either limit is exceeded; otherwise counts this attempt against the client IP. */
    public void checkAndRecordAttempt(String email, String clientIp) {
        long retryAfter = Math.max(failuresByEmail.retryAfterSeconds(email), attemptsByIp.retryAfterSeconds(clientIp));
        if (retryAfter > 0) {
            throw new TooManyRequestsException(retryAfter);
        }
        attemptsByIp.record(clientIp);
    }

    public void recordFailure(String email) {
        failuresByEmail.record(email);
    }

    public void recordSuccess(String email) {
        failuresByEmail.reset(email);
    }

    /** Forget all state; used by tests to isolate cases. */
    public void clear() {
        failuresByEmail.clear();
        attemptsByIp.clear();
    }
}
