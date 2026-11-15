package com.atlasna.catalog.ratelimit;

import com.atlasna.catalog.ratelimit.RateLimitPolicy.OnRedisFailure;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Every rate limit of the API, in one place ({@code atlasna.rate-limit.*}; defaults below, ADR-0011).
 * Each limit is a token bucket: "5 per 15 min" allows a burst of 5, then one more every 3 minutes.
 */
@ConfigurationProperties(prefix = "atlasna.rate-limit")
public record RateLimitPolicies(Login login, Register register, Orders orders) {

    /**
     * @param maxFailuresPerEmail wrong passwords per account before it's locked (default 5 per 15 min)
     * @param maxAttemptsPerIp    login attempts per client IP, any account (default 20 per minute)
     */
    public record Login(Integer maxFailuresPerEmail, Duration emailWindow, Integer maxAttemptsPerIp, Duration ipWindow) {
        public Login {
            if (maxFailuresPerEmail == null) maxFailuresPerEmail = 5;
            if (emailWindow == null) emailWindow = Duration.ofMinutes(15);
            if (maxAttemptsPerIp == null) maxAttemptsPerIp = 20;
            if (ipWindow == null) ipWindow = Duration.ofMinutes(1);
        }
    }

    /** New accounts per client IP (default 10 per hour: many Algerian mobile users share one carrier IP). */
    public record Register(Integer maxPerIp, Duration window) {
        public Register {
            if (maxPerIp == null) maxPerIp = 10;
            if (window == null) window = Duration.ofHours(1);
        }
    }

    /** Order placement attempts per customer (default 10 per hour; a real household places one or two). */
    public record Orders(Integer maxPerUser, Duration window) {
        public Orders {
            if (maxPerUser == null) maxPerUser = 10;
            if (window == null) window = Duration.ofHours(1);
        }
    }

    public RateLimitPolicies {
        if (login == null) login = new Login(null, null, null, null);
        if (register == null) register = new Register(null, null);
        if (orders == null) orders = new Orders(null, null);
    }

    /** Login throttling is a security control: if Redis is down, refuse (503) rather than allow unlimited guessing. */
    public RateLimitPolicy loginFailuresPerEmail() {
        return new RateLimitPolicy("login-email", login.maxFailuresPerEmail(), login.emailWindow(), OnRedisFailure.DENY);
    }

    public RateLimitPolicy loginAttemptsPerIp() {
        return new RateLimitPolicy("login-ip", login.maxAttemptsPerIp(), login.ipWindow(), OnRedisFailure.DENY);
    }

    /** Spam sign-ups are an annoyance, not a breach: keep registration open if Redis is down. */
    public RateLimitPolicy registrationsPerIp() {
        return new RateLimitPolicy("register-ip", register.maxPerIp(), register.window(), OnRedisFailure.ALLOW);
    }

    /** Never turn customers away because a cache is down; idempotency keys already stop accidental duplicates. */
    public RateLimitPolicy ordersPerUser() {
        return new RateLimitPolicy("orders", orders.maxPerUser(), orders.window(), OnRedisFailure.ALLOW);
    }

    @Configuration
    @EnableConfigurationProperties(RateLimitPolicies.class)
    static class Config {
    }
}
