package com.atlasna.catalog.user.security;

import com.atlasna.catalog.ratelimit.RateLimitPolicies;
import com.atlasna.catalog.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for /api/auth/login, now shared by all instances through Redis (ADR-0011):
 * <ul>
 *   <li>per email: wrong passwords drain the account's bucket; an empty bucket locks its logins — even with the right
 *       password — until a token refills (5 per 15 min by default: then one more try every 3 minutes);</li>
 *   <li>per client IP: caps total attempts, which stops one client spraying many accounts.</li>
 * </ul>
 * The client IP comes from {@code ClientIpResolver} (trusted proxies only). If Redis is down, login is refused with
 * 503 rather than left unthrottled.
 *
 * <p>Called by AuthService in this order: {@link #checkAndRecordAttempt} before the password is checked,
 * then {@link #recordFailure} or {@link #recordSuccess} depending on the result.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final RateLimiter limiter;
    private final RateLimitPolicies policies;

    /** Throws 429 if the account is locked or the IP has used its attempts; otherwise counts this attempt for the IP. */
    public void checkAndRecordAttempt(String email, String clientIp) {
        limiter.check(policies.loginFailuresPerEmail(), email);
        limiter.consume(policies.loginAttemptsPerIp(), clientIp);
    }

    /** Counts a wrong password (or unknown email) against the account. */
    public void recordFailure(String email) {
        limiter.penalize(policies.loginFailuresPerEmail(), email);
    }

    /** A successful login clears the account's failure count. */
    public void recordSuccess(String email) {
        limiter.reset(policies.loginFailuresPerEmail(), email);
    }

    /** Forget all limits; used by tests to isolate cases. */
    public void clear() {
        limiter.clearAll();
    }
}
