package com.atlasna.catalog;

import com.atlasna.catalog.common.exception.TooManyRequestsException;
import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.ratelimit.ClientIpResolver;
import com.atlasna.catalog.ratelimit.RateLimitPolicy;
import com.atlasna.catalog.ratelimit.RateLimitPolicy.OnRedisFailure;
import com.atlasna.catalog.ratelimit.RateLimiter;
import com.atlasna.catalog.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis token-bucket rate limits (ADR-0011) with small limits so the edges are reachable: sign-ups per IP, orders per
 * customer, atomicity under 50 parallel requests, refill over time, X-Forwarded-For only from trusted proxies, no
 * personal data in keys, and the explicit Redis-down behaviour (login refuses, orders and sign-ups go through).
 */
@TestPropertySource(properties = {"atlasna.rate-limit.register.max-per-ip=3", "atlasna.rate-limit.orders.max-per-user=2"})
class RateLimitTests extends OrderTestSupport {

    @Autowired RateLimiter limiter;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void freshBuckets() {
        limiter.clearAll();
    }

    @Test
    void signUpsArePerIp() throws Exception {
        for (int i = 0; i < 3; i++) register("10.1.1.1").andExpect(status().isCreated());
        register("10.1.1.1").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        register("10.1.1.2").andExpect(status().isCreated()); // another address is not affected
    }

    @Test
    void orderAttemptsArePerCustomer() throws Exception {
        User u = customer(), other = customer();
        long address = address(u);
        for (int i = 0; i < 2; i++) placeOrder(u, address).andExpect(jsonPath("$.code").value("CART_EMPTY"));
        placeOrder(u, address).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        placeOrder(other, address(other)).andExpect(jsonPath("$.code").value("CART_EMPTY")); // other customers unaffected
    }

    @Test
    void fiftyParallelRequestsCannotShareTheLastTokens() throws Exception {
        RateLimitPolicy tenPerHour = new RateLimitPolicy("test-parallel", 10, Duration.ofHours(1), OnRedisFailure.DENY);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                limiter.consume(tenPerHour, "same-subject");
                return true;
            } catch (TooManyRequestsException e) {
                return false;
            }
        };
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            for (int i = 0; i < 50; i++) results.add(pool.submit(attempt));
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> r : results) if (r.get()) allowed++;
            assertThat(allowed).isEqualTo(10);
        }
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        RateLimitPolicy twoPerSecond = new RateLimitPolicy("test-refill", 2, Duration.ofSeconds(1), OnRedisFailure.DENY);
        limiter.consume(twoPerSecond, "s");
        limiter.consume(twoPerSecond, "s");
        try {
            limiter.consume(twoPerSecond, "s");
            throw new AssertionError("third request should be limited");
        } catch (TooManyRequestsException e) {
            assertThat(e.getRetryAfterSeconds()).isEqualTo(1); // ~500 ms until the next token, rounded up
        }
        Thread.sleep(600);
        limiter.consume(twoPerSecond, "s"); // one token is back
    }

    @Test
    void spoofedForwardedForDoesNotBypassThePerIpLoginLimit() throws Exception {
        // No trusted proxies are configured, so the header is ignored: all 21 attempts count against 10.2.2.2.
        for (int i = 0; i < 20; i++) {
            login("10.2.2.2", "203.0.113." + i, "nobody" + i + "@test.dz").andExpect(status().isUnauthorized());
        }
        login("10.2.2.2", "203.0.113.99", "nobody-else@test.dz").andExpect(status().isTooManyRequests());
    }

    @Test
    void forwardedForIsReadOnlyFromTrustedProxies() {
        var resolver = new ClientIpResolver(new ClientIpResolver.Properties(List.of("10.0.0.0/8")));
        // From our load balancer: the right-most address our proxies didn't add is the client.
        assertThat(resolver.resolve(request("10.0.0.5", "1.2.3.4, 41.100.20.30, 10.0.0.9"))).isEqualTo("41.100.20.30");
        // From anyone else: the header is ignored.
        assertThat(resolver.resolve(request("41.100.20.30", "1.2.3.4"))).isEqualTo("41.100.20.30");
        // Garbage or a hostname in the header: never looked up, fall back to the peer.
        assertThat(resolver.resolve(request("10.0.0.5", "evil.example.com"))).isEqualTo("10.0.0.5");
        // IPv6 ranges work too.
        var v6 = new ClientIpResolver(new ClientIpResolver.Properties(List.of("fd00::/8")));
        assertThat(v6.resolve(request("fd00::1", "2001:db8::7"))).isEqualTo("2001:db8::7");
    }

    @Test
    void keysNeverContainEmailsOrIps() throws Exception {
        login("10.3.3.3", null, "someone.private@test.dz");
        register("10.3.3.4");
        assertThat(redis.keys("atlasna:v1:ratelimit:*")).isNotEmpty()
                .noneMatch(k -> k.contains("@") || k.contains("someone") || k.contains("10.3.3"));
    }

    @Test
    void redisDown_loginRefusesButOrdersAndSignUpsGoThrough() throws Exception {
        User u = customer();
        long address = address(u);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            login("10.4.4.4", null, u.getEmail()).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"));
            placeOrder(u, address).andExpect(jsonPath("$.code").value("CART_EMPTY")); // limit skipped, not refused
            register("10.4.4.5").andExpect(status().isCreated());
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            limiter.clearAll(); // also closes the breaker
        }
        login("10.4.4.4", null, u.getEmail()).andExpect(status().isOk()); // the right password works again
    }

    // ---------- helpers ----------

    private ResultActions register(String ip) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String phone = "07" + String.format("%08d", Math.abs(id.hashCode()) % 100_000_000);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content("""
                        {"fullName":"Client %s","email":"c-%s@test.dz","phone":"%s","password":"Password123"}""".formatted(id, id, phone)));
    }

    private ResultActions login(String ip, String forwardedFor, String email) throws Exception {
        var req = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .content("{\"email\":\"%s\",\"password\":\"irrelevant-password\"}".formatted(email));
        if (forwardedFor != null) req.header("X-Forwarded-For", forwardedFor);
        return mockMvc.perform(req);
    }

    private ResultActions placeOrder(User u, long address) throws Exception {
        return mockMvc.perform(post("/api/orders").header("Authorization", bearer(u)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(placeJson(address)));
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(peer);
        r.addHeader("X-Forwarded-For", forwardedFor);
        return r;
    }
}
