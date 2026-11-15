package com.atlasna.catalog.ratelimit;

import com.atlasna.catalog.common.exception.RateLimitUnavailableException;
import com.atlasna.catalog.common.exception.TooManyRequestsException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Token-bucket rate limiting shared by every app instance, in Redis (ADR-0011). Replaces the Phase 1 in-memory
 * limiter, which each instance kept separately (two instances = twice the attempts) and forgot on every restart.
 *
 * <p>One atomic Lua script per check (src/main/resources/ratelimit/token-bucket.lua), so 50 parallel requests can't
 * all slip through on the same token. Subjects (emails, IPs, user ids) are SHA-256-hashed into the key: no personal
 * data in Redis. Redis calls go through the {@code rateLimiter} circuit breaker with short timeouts; when Redis can't
 * answer, the policy's {@link RateLimitPolicy.OnRedisFailure} decides — explicitly, per policy.
 */
@Slf4j
@Service
public class RateLimiter {

    static final String PREFIX = "atlasna:v1:ratelimit:";

    private final StringRedisTemplate redis;
    private final CircuitBreaker breaker;
    private final MeterRegistry meters;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;

    public RateLimiter(StringRedisTemplate redis, CircuitBreakerRegistry breakers, MeterRegistry meters) {
        this.redis = redis;
        this.breaker = breakers.circuitBreaker("rateLimiter");
        this.meters = meters;
        this.script = RedisScript.of(new ClassPathResource("ratelimit/token-bucket.lua"), List.class);
    }

    /** Takes one token for {@code subject}, or throws 429 with Retry-After when the bucket is empty. */
    public void consume(RateLimitPolicy policy, String subject) {
        long retryMs = run(policy, subject, 1);
        if (retryMs > 0) throw tooMany(policy, retryMs);
    }

    /** Throws 429 if {@code subject} has no token left, without taking one (e.g. "is this account locked?"). */
    public void check(RateLimitPolicy policy, String subject) {
        long retryMs = run(policy, subject, 0);
        if (retryMs > 0) throw tooMany(policy, retryMs);
    }

    /** Takes a token if there is one and never throws (counting a failed login that already got its answer). */
    public void penalize(RateLimitPolicy policy, String subject) {
        try {
            run(policy, subject, 1);
        } catch (RateLimitUnavailableException e) {
            // the request already got its answer; nothing more to refuse
        }
    }

    /** Refills the subject's bucket (a successful login clears the failure count). Best effort. */
    public void reset(RateLimitPolicy policy, String subject) {
        try {
            breaker.executeRunnable(() -> redis.delete(key(policy, subject)));
        } catch (RuntimeException e) {
            log.debug("Rate-limit reset skipped: {}", e.getMessage());
        }
    }

    /** Tests only: forget every bucket. */
    public void clearAll() {
        redis.execute((RedisCallback<Void>) connection -> {
            List<byte[]> keys = new ArrayList<>();
            try (var cursor = connection.keyCommands().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match(PREFIX + "*").count(1000).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) connection.keyCommands().del(keys.toArray(byte[][]::new));
            return null;
        });
        breaker.reset();
    }

    /** @return 0 when allowed, otherwise milliseconds until a token is available */
    private long run(RateLimitPolicy policy, String subject, int cost) {
        try {
            List<?> result = breaker.executeSupplier(() -> redis.execute(script, List.of(key(policy, subject)),
                    String.valueOf(policy.capacity()), String.valueOf(policy.period().toMillis()), String.valueOf(cost)));
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            if (!allowed) count(policy, "limited");
            return allowed ? 0 : Math.max(1, ((Number) result.get(1)).longValue());
        } catch (RuntimeException e) {
            count(policy, "redis-error");
            if (policy.onRedisFailure() == RateLimitPolicy.OnRedisFailure.ALLOW) {
                log.warn("Rate limit '{}' skipped: Redis unavailable ({})", policy.name(), e.getMessage());
                return 0;
            }
            log.warn("Rate limit '{}' refused the request: Redis unavailable ({})", policy.name(), e.getMessage());
            throw new RateLimitUnavailableException(30);
        }
    }

    private TooManyRequestsException tooMany(RateLimitPolicy policy, long retryMs) {
        return new TooManyRequestsException((retryMs + 999) / 1000); // round up: never invite a retry too early
    }

    private void count(RateLimitPolicy policy, String outcome) {
        meters.counter("atlasna.ratelimit.events", "policy", policy.name(), "outcome", outcome).increment();
    }

    static String key(RateLimitPolicy policy, String subject) {
        return PREFIX + policy.name() + ":" + hash(subject);
    }

    private static String hash(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
