package com.atlasna.catalog.payment.chargily;

import com.atlasna.catalog.payment.InvalidWebhookException;
import com.atlasna.catalog.payment.PaymentEvent;
import com.atlasna.catalog.payment.PaymentGateway;
import com.atlasna.catalog.payment.PaymentUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Chargily Pay V2 (CIB / Edahabia, Algeria) — verified against dev.chargily.com on 2026-11-15:
 * base URL {@code https://pay.chargily.net/test/api/v2}, {@code Authorization: Bearer <secret key>},
 * {@code POST /checkouts {amount (integer DZD), currency "dzd", success_url, failure_url, webhook_endpoint, locale,
 * metadata}} → {@code {id, checkout_url, status…}}; webhooks carry header {@code signature} = hex HMAC-SHA256 of the raw
 * body with the secret key; event types {@code checkout.paid | checkout.failed | checkout.canceled}.
 *
 * <p>Every outbound call: connect/read <b>timeouts</b> → <b>bulkhead</b> (cap on concurrent gateway calls, so a slow
 * provider can't eat all request threads) → <b>circuit breaker</b> (after repeated failures, fail fast for a while).
 * Only the idempotent status read is <b>retried</b> (exponential backoff + jitter); creating a checkout is not, because
 * Chargily offers no idempotency key — a retry after a timeout could create a second checkout.
 */
@Slf4j
@Component
@EnableConfigurationProperties(ChargilyProperties.class)
public class ChargilyGateway implements PaymentGateway {

    private final ChargilyProperties properties;
    private final RestClient http;
    private final JsonMapper json;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Retry statusRetry;

    public ChargilyGateway(ChargilyProperties properties, JsonMapper json, CircuitBreakerRegistry breakers,
                           BulkheadRegistry bulkheads, RetryRegistry retries) {
        this.properties = properties;
        this.json = json;
        this.breaker = breakers.circuitBreaker("paymentGateway");
        this.bulkhead = bulkheads.bulkhead("paymentGateway");
        this.statusRetry = retries.retry("paymentStatus");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + (properties.configured() ? properties.secretKey() : "")).build();
    }

    @Override
    public String provider() {
        return "chargily";
    }

    @Override
    public boolean enabled() {
        return properties.configured();
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest r) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", r.amountDzd());
        body.put("currency", "dzd");
        body.put("success_url", r.successUrl());
        body.put("failure_url", r.failureUrl());
        body.put("webhook_endpoint", r.webhookUrl());
        body.put("locale", r.locale());
        body.put("description", "Atlasna " + r.orderNumber());
        body.put("metadata", Map.of("order_number", r.orderNumber()));
        JsonNode res = protect(() -> json.readTree(http.post().uri("/checkouts").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class)));
        String id = res.path("id").asString(null);
        String url = res.path("checkout_url").asString(null);
        if (id == null || url == null) throw new PaymentUnavailableException("Unexpected checkout response", null);
        return new CheckoutSession(id, url);
    }

    @Override
    public PaymentEvent.Status checkoutStatus(String providerRef) {
        requireConfigured();
        Supplier<JsonNode> call = () -> json.readTree(http.get().uri("/checkouts/{id}", providerRef).retrieve().body(String.class));
        JsonNode res = protect(Retry.decorateSupplier(statusRetry, call));
        return status(res.path("status").asString(""));
    }

    @Override
    public PaymentEvent parseAndVerifyWebhook(Map<String, String> headers, byte[] rawBody) {
        if (!properties.configured()) throw new InvalidWebhookException("Payment provider not configured");
        String signature = header(headers, "signature");
        if (signature == null || signature.isBlank()) throw new InvalidWebhookException("Missing signature");
        byte[] expected = hmacHex(rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, given)) throw new InvalidWebhookException("Bad signature"); // constant time
        try {
            JsonNode event = json.readTree(rawBody);
            JsonNode data = event.path("data");
            String type = event.path("type").asString("");
            PaymentEvent.Status status = switch (type) {
                case "checkout.paid" -> PaymentEvent.Status.PAID;
                case "checkout.failed" -> PaymentEvent.Status.FAILED;
                case "checkout.canceled" -> PaymentEvent.Status.CANCELED;
                default -> status(data.path("status").asString(""));
            };
            Long amount = data.path("amount").isNumber() ? data.path("amount").asLong() : null;
            String orderNumber = data.path("metadata").path("order_number").asString(null);
            return new PaymentEvent(event.path("id").asString(null), type, status, data.path("id").asString(null), amount,
                    orderNumber, instant(event.path("created_at")));
        } catch (RuntimeException e) {
            throw new InvalidWebhookException("Unreadable webhook body");
        }
    }

    /** Signs a body as Chargily does (tests use it to build valid webhooks). */
    public String sign(byte[] rawBody) {
        return hmacHex(rawBody);
    }

    // ---------- helpers ----------

    private <T> T protect(Supplier<T> call) {
        try {
            return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, call)).get();
        } catch (RuntimeException e) {
            log.warn("Payment gateway call failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            throw new PaymentUnavailableException("Payment provider unavailable", e);
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) throw new PaymentUnavailableException("Payment provider not configured", null);
    }

    private String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaymentEvent.Status status(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "paid" -> PaymentEvent.Status.PAID;
            case "failed" -> PaymentEvent.Status.FAILED;
            case "canceled", "cancelled", "expired" -> PaymentEvent.Status.CANCELED;
            default -> PaymentEvent.Status.PENDING;
        };
    }

    /** {@code created_at} as Unix seconds or an ISO-8601 string; null when absent. */
    private static Instant instant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return Instant.ofEpochSecond(node.asLong());
        try {
            return Instant.parse(node.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }
}
