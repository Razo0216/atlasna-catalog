package com.atlasna.catalog;

import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.order.domain.PaymentMethod;
import com.atlasna.catalog.order.service.PlaceOrderRequest;
import com.atlasna.catalog.ratelimit.RateLimiter;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 cross-cutting checks (plan §4): with a dependency down or slow, the shop keeps selling — MongoDB down (audit),
 * Redis down (cache, order rate limit), a payment gateway that hangs (bulkhead: no thread starvation elsewhere) — and
 * the authorization matrix of every route added in Phase 5.
 */
class FailureModeTests extends OrderTestSupport {

    static final WireMockServer GATEWAY = new WireMockServer(options().dynamicPort());

    static {
        GATEWAY.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("atlasna.payment.chargily.base-url", GATEWAY::baseUrl);
        r.add("atlasna.payment.chargily.secret-key", () -> "test_sk_failure_modes");
        r.add("atlasna.payment.chargily.read-timeout", () -> "3s");
        r.add("resilience4j.bulkhead.instances.paymentGateway.max-concurrent-calls", () -> "2");
    }

    @Autowired MeterRegistry meters;
    @Autowired RateLimiter limiter;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        GATEWAY.resetAll();
        limiter.clearAll();
        breakers.circuitBreaker("paymentGateway").reset();
    }

    @Test
    void mongoDown_ordersStillWork_andTheAuditFailureIsCounted() throws Exception {
        long p = product("Huile", "1300.00", "10");
        User u = customer();
        inCart(u, p, "2");
        long address = address(u);
        double before = auditFailures();
        MONGO.getDockerClient().pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            mockMvc.perform(MockMvcRequestBuilders.post("/api/orders").header("Authorization", bearer(u))
                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(placeJson(address)))
                    .andExpect(status().isCreated());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            long deadline = System.currentTimeMillis() + 10_000;
            while (auditFailures() <= before && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertThat(auditFailures()).isGreaterThan(before);
        } finally {
            MONGO.getDockerClient().unpauseContainerCmd(MONGO.getContainerId()).exec();
        }
    }

    @Test
    void redisDown_customersCanStillBrowseAndOrder() throws Exception {
        long p = product("Semoule", "1000.00", "10");
        User u = customer();
        inCart(u, p, "3");
        long address = address(u);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/products/" + p)).andExpect(status().isOk());
            mockMvc.perform(MockMvcRequestBuilders.post("/api/orders").header("Authorization", bearer(u))
                            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(placeJson(address)))
                    .andExpect(status().isCreated()); // the order rate limit fails open by design (ADR-0011)
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            limiter.clearAll();
        }
    }

    @Test
    void aHangingGatewayCantStarveTheRestOfTheShop() throws Exception {
        GATEWAY.stubFor(post("/checkouts").willReturn(okJson("{\"id\":\"x\",\"checkout_url\":\"u\"}").withFixedDelay(2500)));
        List<Order> orders = List.of(cardOrder(), cardOrder(), cardOrder());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> slow = new ArrayList<>();
            for (Order o : orders.subList(0, 2)) slow.add(pool.submit(() -> pay(o))); // fill the bulkhead (2)
            Thread.sleep(300);

            long start = System.nanoTime();
            assertThat(pay(orders.get(2))).isEqualTo(503); // rejected at once, no thread parked behind the provider
            mockMvc.perform(MockMvcRequestBuilders.get("/api/products")).andExpect(status().isOk()); // catalog unaffected
            mockMvc.perform(MockMvcRequestBuilders.get("/api/categories")).andExpect(status().isOk());
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1000));
            for (Future<Integer> f : slow) f.get();
        }
    }

    @Test
    void authorizationMatrixOfThePhase5Routes() throws Exception {
        User customer = customer(), staff = user(Role.STAFF), driver = user(Role.DRIVER), admin = user(Role.ADMIN);
        long p = product("Thé", "250.00", "10");
        record Route(HttpMethod method, String path, String body, int anonymous, int asCustomer, int asDriver, int asStaff, int asAdmin) {}
        String upload = "{\"productId\":%d,\"contentType\":\"image/png\",\"sizeBytes\":100}".formatted(p);
        String lot = "{\"quantity\":1}";
        List<Route> routes = List.of(
                new Route(HttpMethod.GET, "/api/admin/audit-logs", null, 401, 403, 403, 403, 200),
                new Route(HttpMethod.POST, "/api/admin/uploads/product-image", upload, 401, 403, 403, 200, 200),
                new Route(HttpMethod.PUT, "/api/admin/products/" + p + "/images", "{\"images\":[]}", 401, 403, 403, 200, 200),
                new Route(HttpMethod.GET, "/api/admin/products/" + p + "/lots", null, 401, 403, 403, 200, 200),
                new Route(HttpMethod.POST, "/api/admin/products/" + p + "/lots", lot, 401, 403, 403, 201, 201),
                new Route(HttpMethod.GET, "/api/admin/inventory/expiring", null, 401, 403, 403, 200, 200),
                new Route(HttpMethod.GET, "/api/admin/inventory/low-stock", null, 401, 403, 403, 200, 200),
                new Route(HttpMethod.POST, "/api/orders/ATL-000000-NONE/pay", null, 401, 404, 403, 403, 403),
                new Route(HttpMethod.POST, "/api/orders/ATL-000000-NONE/pay-on-delivery", null, 401, 404, 403, 403, 403));
        for (Route r : routes) {
            assertThat(call(r.method(), r.path(), r.body(), null)).as("anonymous " + r.path()).isEqualTo(r.anonymous());
            assertThat(call(r.method(), r.path(), r.body(), customer)).as("customer " + r.path()).isEqualTo(r.asCustomer());
            assertThat(call(r.method(), r.path(), r.body(), driver)).as("driver " + r.path()).isEqualTo(r.asDriver());
            assertThat(call(r.method(), r.path(), r.body(), staff)).as("staff " + r.path()).isEqualTo(r.asStaff());
            assertThat(call(r.method(), r.path(), r.body(), admin)).as("admin " + r.path()).isEqualTo(r.asAdmin());
        }
        // Public by design, trusted only through a signature (no login can make them succeed without one).
        assertThat(call(HttpMethod.POST, "/api/webhooks/payments", "{}", null)).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, "/api/media/uploads/products/1/x.png?ct=image/png&max=1&exp=1&sig=x", "x", null)).isEqualTo(403);
    }

    // ---------- helpers ----------

    record Order(String number, User customer) {}

    private Order cardOrder() {
        long p = product("Huile d'olive", "1300.00", "100");
        User u = customer();
        inCart(u, p, "2");
        var placed = placement.place(u.getId(), UUID.randomUUID().toString(),
                new PlaceOrderRequest(address(u), firstSlotId(), tomorrow(), PaymentMethod.CARD_ONLINE, null, null));
        return new Order(placed.order().orderNumber(), u);
    }

    private int pay(Order o) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/orders/" + o.number() + "/pay").header("Authorization", bearer(o.customer())))
                .andReturn().getResponse().getStatus();
    }

    private int call(HttpMethod method, String path, String body, User user) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, java.net.URI.create(path));
        if (user != null) req.header("Authorization", bearer(user));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    private double auditFailures() {
        var c = meters.find("atlasna.audit.writes").tag("outcome", "failure").counter();
        return c == null ? 0 : c.count();
    }
}
