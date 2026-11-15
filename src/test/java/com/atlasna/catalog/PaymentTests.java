package com.atlasna.catalog;

import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.order.domain.PaymentMethod;
import com.atlasna.catalog.order.service.OrderPaymentService;
import com.atlasna.catalog.order.service.PlaceOrderRequest;
import com.atlasna.catalog.payment.PaymentEvent;
import com.atlasna.catalog.payment.PaymentUnavailableException;
import com.atlasna.catalog.payment.chargily.ChargilyGateway;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Card payment (ADR-0013) against a fake Chargily (WireMock): the checkout call, the webhook as the only source of
 * truth (signed, once per event, amount checked, not stale), and resilience — timeout, circuit breaker opening and
 * recovering, retry with backoff on idempotent reads, bulkhead — plus expiry of unpaid orders and refunds on cancel.
 */
class PaymentTests extends OrderTestSupport {

    static final String SECRET = "test_sk_atlasna_unit_tests_only";
    static final WireMockServer CHARGILY = new WireMockServer(options().dynamicPort());

    static {
        CHARGILY.start();
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry r) {
        r.add("atlasna.payment.chargily.base-url", CHARGILY::baseUrl);
        r.add("atlasna.payment.chargily.secret-key", () -> SECRET);
        r.add("atlasna.payment.chargily.read-timeout", () -> "1s");
        r.add("resilience4j.circuitbreaker.instances.paymentGateway.wait-duration-in-open-state", () -> "1s");
        r.add("resilience4j.bulkhead.instances.paymentGateway.max-concurrent-calls", () -> "2");
    }

    @Autowired ChargilyGateway gateway;
    @Autowired OrderPaymentService payments;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetGateway() {
        CHARGILY.resetAll();
        breakers.circuitBreaker("paymentGateway").reset();
    }

    // ---------- placing and paying ----------

    @Test
    void cardIsRefusedWhenTheTotalIsAnEstimate() {
        User u = customer();
        inCart(u, weighedProduct("Tomates", "400.00", "20"), "6");
        assertThatThrownBy(() -> placement.place(u.getId(), "k", cardRequest(address(u))))
                .hasMessageContaining("Card payment isn't available");
    }

    @Test
    void payReturnsTheHostedCheckout_aSecondTapReusesIt() throws Exception {
        Order o = cardOrder();
        stubCheckout("chk_1");
        pay(o).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value("https://pay.chargily.dz/test/checkouts/chk_1/pay"))
                .andExpect(jsonPath("$.amountDzd").value(2600));
        pay(o).andExpect(status().isOk()).andExpect(jsonPath("$.checkoutUrl").value("https://pay.chargily.dz/test/checkouts/chk_1/pay"));

        CHARGILY.verify(1, postRequestedFor(urlEqualTo("/checkouts"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET))
                .withRequestBody(equalToJson("""
                        {"amount":2600,"currency":"dzd","locale":"fr","metadata":{"order_number":"%s"},
                         "webhook_endpoint":"http://localhost:8080/api/webhooks/payments"}""".formatted(o.number()), true, true)));
        assertThat(paymentStatus(o)).isEqualTo("PENDING"); // the redirect proves nothing: still pending
    }

    @Test
    void onlyAVerifiedWebhookMarksItPaid_andAReplayChangesNothing() throws Exception {
        Order o = paidCheckout("chk_2");
        String event = event("evt_1", "checkout.paid", "chk_2", 2600, Instant.now());
        webhook(event, gateway.sign(bytes(event))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(paymentStatus(o)).isEqualTo("PAID");

        webhook(event, gateway.sign(bytes(event))).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = 'evt_1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void forgedOrUnsignedWebhooksAreRejected_andNothingChanges() throws Exception {
        Order o = paidCheckout("chk_3");
        String event = event("evt_2", "checkout.paid", "chk_3", 2600, Instant.now());
        webhook(event, "deadbeef").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        webhook(event, null).andExpect(status().isUnauthorized());
        // Signed for one body, sent with another (amount tampered).
        webhook(event.replace("2600", "1"), gateway.sign(bytes(event))).andExpect(status().isUnauthorized());
        assertThat(paymentStatus(o)).isEqualTo("PENDING");
    }

    @Test
    void aDifferentAmountIsFlagged_notPaid() throws Exception {
        Order o = paidCheckout("chk_4");
        String event = event("evt_3", "checkout.paid", "chk_4", 100, Instant.now());
        webhook(event, gateway.sign(bytes(event))).andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        assertThat(paymentStatus(o)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT amount_mismatch FROM payments WHERE order_id = ?", Boolean.class, o.id())).isTrue();
    }

    @Test
    void staleEventsAreRefused() throws Exception {
        paidCheckout("chk_5");
        String event = event("evt_4", "checkout.paid", "chk_5", 2600, Instant.now().minus(Duration.ofDays(4)));
        webhook(event, gateway.sign(bytes(event))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEBHOOK_STALE"));
    }

    @Test
    void aFailedPaymentCanBeRetried_orSwitchedToCash() throws Exception {
        Order o = paidCheckout("chk_6");
        String failed = event("evt_5", "checkout.failed", "chk_6", 2600, Instant.now());
        webhook(failed, gateway.sign(bytes(failed))).andExpect(jsonPath("$.outcome").value("PROCESSED"));
        assertThat(paymentStatus(o)).isEqualTo("FAILED");

        stubCheckout("chk_7");
        pay(o).andExpect(jsonPath("$.checkoutUrl").value("https://pay.chargily.dz/test/checkouts/chk_7/pay")); // a new checkout
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + o.number() + "/pay-on-delivery")
                        .header("Authorization", bearer(o.customer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMethod").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    // ---------- resilience ----------

    @Test
    void aSlowProviderTimesOut_fast503_andCashStillWorks() throws Exception {
        Order o = cardOrder();
        CHARGILY.stubFor(post("/checkouts").willReturn(okJson(checkoutJson("chk_slow")).withFixedDelay(3000)));
        long start = System.nanoTime();
        pay(o).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PAYMENT_UNAVAILABLE"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500)); // 1 s read timeout
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + o.number() + "/pay-on-delivery")
                .header("Authorization", bearer(o.customer()))).andExpect(status().isOk());
    }

    @Test
    void repeated5xxOpenTheBreaker_failFast_thenHalfOpenAndRecover() throws Exception {
        Order o = cardOrder();
        CHARGILY.stubFor(post("/checkouts").willReturn(serverError()));
        for (int i = 0; i < 5; i++) pay(o).andExpect(status().isServiceUnavailable());
        assertThat(breakers.circuitBreaker("paymentGateway").getState().name()).isEqualTo("OPEN");

        int callsSoFar = CHARGILY.findAll(postRequestedFor(urlEqualTo("/checkouts"))).size();
        pay(o).andExpect(status().isServiceUnavailable()); // refused without even calling the provider
        assertThat(CHARGILY.findAll(postRequestedFor(urlEqualTo("/checkouts")))).hasSize(callsSoFar);

        Thread.sleep(1200); // wait-duration-in-open-state (1 s in this test) → HALF_OPEN
        CHARGILY.stubFor(post("/checkouts").willReturn(uniqueCheckout()));
        pay(o).andExpect(status().isOk());
        pay(cardOrder()).andExpect(status().isOk());
        assertThat(breakers.circuitBreaker("paymentGateway").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void idempotentReadsAreRetriedWithBackoff() {
        CHARGILY.stubFor(get("/checkouts/chk_r").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        CHARGILY.stubFor(get("/checkouts/chk_r").inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("third"));
        CHARGILY.stubFor(get("/checkouts/chk_r").inScenario("flaky").whenScenarioStateIs("third")
                .willReturn(okJson("{\"id\":\"chk_r\",\"status\":\"paid\"}")));
        assertThat(gateway.checkoutStatus("chk_r")).isEqualTo(PaymentEvent.Status.PAID);
        CHARGILY.verify(3, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/checkouts/chk_r")));
    }

    @Test
    void creatingACheckoutIsNeverRetried() {
        CHARGILY.stubFor(post("/checkouts").willReturn(serverError()));
        assertThatThrownBy(() -> gateway.createCheckout(new com.atlasna.catalog.payment.PaymentGateway.CheckoutRequest(
                "ATL-X", 2600, "fr", "s", "f", "w"))).isInstanceOf(PaymentUnavailableException.class);
        // At most one attempt (a reused keep-alive connection can even fail before reaching the server); a retry would be 2+.
        assertThat(CHARGILY.findAll(postRequestedFor(urlEqualTo("/checkouts")))).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void theBulkheadCapsConcurrentGatewayCalls() throws Exception {
        CHARGILY.stubFor(post("/checkouts").willReturn(uniqueCheckout().withFixedDelay(700)));
        List<Order> orders = List.of(cardOrder(), cardOrder(), cardOrder(), cardOrder());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Order o : orders) {
                Callable<Integer> call = () -> {
                    go.await();
                    return pay(o).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get());
            assertThat(statuses).containsOnly(200, 503);
            assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(2); // max-concurrent-calls = 2
        }
    }

    // ---------- lifecycle ----------

    @Test
    void unpaidCardOrdersAreCancelledAfterTheTimeout_stockAndSlotComeBack() {
        long product = product("Huile", "1300.00", "10");
        User u = customer();
        inCart(u, product, "2");
        var placed = placement.place(u.getId(), "k", cardRequest(address(u)));
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, placed.order().orderNumber());
        assertThat(stock(product)).isEqualByComparingTo("8");
        int bookedBefore = jdbc.queryForObject("SELECT COALESCE(SUM(booked),0) FROM slot_usage", Integer.class);

        assertThat(payments.cancelExpiredUnpaid()).isZero(); // too recent
        jdbc.update("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?", orderId);
        assertThat(payments.cancelExpiredUnpaid()).isEqualTo(1);
        assertThat(payments.cancelExpiredUnpaid()).isZero(); // idempotent

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(stock(product)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(booked),0) FROM slot_usage", Integer.class)).isEqualTo(bookedBefore - 1);
        assertLedgerHolds();
    }

    @Test
    void cancellingAPaidOrderAsksForARefund_deliveringNeedsNoCash() throws Exception {
        Order o = paidCheckout("chk_8");
        String event = event("evt_6", "checkout.paid", "chk_8", 2600, Instant.now());
        webhook(event, gateway.sign(bytes(event)));
        User staff = user(Role.STAFF);
        transition(staff, o, "{\"to\":\"CANCELLED\",\"reason\":\"Client injoignable\"}").andExpect(status().isOk());
        assertThat(paymentStatus(o)).isEqualTo("REFUND_PENDING");

        Order paid = paidCheckout("chk_9");
        String e2 = event("evt_7", "checkout.paid", "chk_9", 2600, Instant.now());
        webhook(e2, gateway.sign(bytes(e2)));
        User driver = user(Role.DRIVER);
        for (String to : List.of("CONFIRMED", "PREPARING", "READY_FOR_DELIVERY")) transition(staff, paid, "{\"to\":\"" + to + "\"}");
        jdbc.update("UPDATE orders SET assigned_driver_id = ? WHERE id = ?", driver.getId(), paid.id());
        driverPost(driver, paid, "start", null).andExpect(status().isOk());
        driverPost(driver, paid, "deliver", "{}").andExpect(status().isOk()); // paid by card: no cashCollected
        assertThat(paymentStatus(paid)).isEqualTo("PAID");
    }

    // ---------- helpers ----------

    record Order(long id, String number, User customer) {}

    private Order cardOrder() {
        long p = product("Huile d'olive", "1300.00", "100");
        User u = customer();
        inCart(u, p, "2"); // 2 600 DA incl. … delivery is free from 5 000 only: fee added below
        var placed = placement.place(u.getId(), UUID.randomUUID().toString(), cardRequest(address(u)));
        long id = jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, placed.order().orderNumber());
        // Keep the amount simple for the assertions: no delivery fee in these tests.
        jdbc.update("UPDATE orders SET delivery_fee = 0, total = subtotal WHERE id = ?", id);
        jdbc.update("UPDATE payments SET amount = 2600 WHERE order_id = ?", id);
        return new Order(id, placed.order().orderNumber(), u);
    }

    private Order paidCheckout(String checkoutId) throws Exception {
        Order o = cardOrder();
        stubCheckout(checkoutId);
        pay(o).andExpect(status().isOk());
        return o;
    }

    private PlaceOrderRequest cardRequest(long addressId) {
        return new PlaceOrderRequest(addressId, firstSlotId(), tomorrow(), PaymentMethod.CARD_ONLINE, null, null);
    }

    private void stubCheckout(String id) {
        CHARGILY.stubFor(post("/checkouts").willReturn(okJson(checkoutJson(id))));
    }

    private static String checkoutJson(String id) {
        return """
                {"id":"%s","entity":"checkout","livemode":false,"amount":2600,"currency":"dzd","status":"pending",
                 "checkout_url":"https://pay.chargily.dz/test/checkouts/%s/pay"}""".formatted(id, id);
    }

    /** Like the real provider: every checkout gets its own id (WireMock response templating). */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder uniqueCheckout() {
        return okJson("""
                {"id":"chk_{{randomValue length=12 type='ALPHANUMERIC'}}","entity":"checkout","status":"pending",
                 "checkout_url":"https://pay.chargily.dz/test/checkouts/x/pay"}""").withTransformers("response-template");
    }

    private static String event(String id, String type, String checkout, long amount, Instant createdAt) {
        return """
                {"id":"%s","entity":"event","livemode":"false","type":"%s","data":{"id":"%s","entity":"checkout",
                 "status":"%s","amount":%d,"metadata":{"order_number":"x"}},"created_at":%d}"""
                .formatted(id, type, checkout, type.substring(9), amount, createdAt.getEpochSecond());
    }

    private ResultActions pay(Order o) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders/" + o.number() + "/pay")
                .header("Authorization", bearer(o.customer())).contentType(MediaType.APPLICATION_JSON).content("{\"locale\":\"fr\"}"));
    }

    private ResultActions webhook(String body, String signature) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req.header("signature", signature);
        return mockMvc.perform(req);
    }

    private ResultActions transition(User actor, Order o, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/orders/" + o.number() + "/transition")
                .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions driverPost(User driver, Order o, String action, String body) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/driver/orders/" + o.number() + "/" + action)
                .header("Authorization", bearer(driver));
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(req);
    }

    private String paymentStatus(Order o) throws Exception {
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/" + o.number()).header("Authorization", bearer(o.customer())))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.paymentStatus");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static BigDecimal dzd(String v) {
        return new BigDecimal(v);
    }
}
