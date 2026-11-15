package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderRepository;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.Payment;
import com.atlasna.catalog.order.domain.PaymentMethod;
import com.atlasna.catalog.order.domain.PaymentStatus;
import com.atlasna.catalog.payment.InvalidWebhookException;
import com.atlasna.catalog.payment.PaymentEvent;
import com.atlasna.catalog.payment.PaymentGateway;
import com.atlasna.catalog.payment.PaymentUnavailableException;
import com.atlasna.catalog.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Online card payment for an order (ADR-0013). The rules that matter:
 * <ul>
 *   <li><b>Only a verified webhook marks a payment PAID</b> — never the browser coming back to the success URL, which
 *       anyone can open.</li>
 *   <li>Webhooks are verified (signature over the raw body), not too old, and processed <b>once per provider event
 *       id</b> ({@code webhook_events}), because providers re-deliver.</li>
 *   <li>The amount must match what we asked the gateway to charge; otherwise the payment is flagged, not paid.</li>
 *   <li>If the gateway is down, the customer gets {@code 503 PAYMENT_UNAVAILABLE} quickly and can switch to cash.</li>
 *   <li>An unpaid card order is cancelled after {@code atlasna.payment.unpaid-timeout}: stock back, slot released.</li>
 * </ul>
 */
@Slf4j
@Service
@EnableConfigurationProperties(OrderPaymentService.Properties.class)
public class OrderPaymentService {

    /**
     * @param returnUrl        the storefront's base URL (success/failure pages after the hosted checkout)
     * @param webhookUrl       our public webhook URL, given to the gateway with each checkout
     * @param unpaidTimeout    an unpaid card order is cancelled after this
     * @param webhookTolerance events older than this are refused (replays of old captured requests)
     */
    @ConfigurationProperties(prefix = "atlasna.payment")
    public record Properties(String returnUrl, String webhookUrl, Duration unpaidTimeout, Duration webhookTolerance) {
        public Properties {
            if (returnUrl == null || returnUrl.isBlank()) returnUrl = "http://localhost:5173";
            if (webhookUrl == null || webhookUrl.isBlank()) webhookUrl = "http://localhost:8080/api/webhooks/payments";
            if (unpaidTimeout == null) unpaidTimeout = Duration.ofMinutes(30);
            if (webhookTolerance == null) webhookTolerance = Duration.ofHours(72);
        }
    }

    public record CheckoutStarted(String checkoutUrl, String orderNumber, long amountDzd) {}

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final OrderLifecycleService lifecycle;
    private final PaymentGateway gateway;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Properties properties;
    private final Clock clock;

    public OrderPaymentService(OrderRepository orders, OrderLookup lookup, OrderLifecycleService lifecycle, PaymentGateway gateway,
                               NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Properties properties, Clock clock) {
        this.orders = orders;
        this.lookup = lookup;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens (or re-opens) the hosted checkout for the customer's own PLACED card order. The gateway call happens OUTSIDE
     * any database transaction: a slow provider must never hold a connection.
     */
    public CheckoutStarted startCardPayment(User customer, String orderNumber, String locale) {
        record Snapshot(long orderId, String number, long amount, String existingUrl) {}
        Snapshot s = tx.execute(status -> {
            Order order = lookup.forCustomer(customer, orderNumber);
            Payment p = order.getPayment();
            if (order.getPaymentMethod() != PaymentMethod.CARD_ONLINE) {
                throw ApiException.conflict("PAYMENT_NOT_REQUIRED", "This order is paid in cash on delivery");
            }
            if (p.getStatus() == PaymentStatus.PAID) throw ApiException.conflict("ALREADY_PAID", "This order is already paid");
            if (order.getStatus() != OrderStatus.PLACED || (p.getStatus() != PaymentStatus.PENDING && p.getStatus() != PaymentStatus.FAILED)) {
                throw ApiException.conflict("INVALID_STATE_TRANSITION", "This order can't be paid any more");
            }
            long amount = order.getTotal().setScale(0, RoundingMode.HALF_UP).longValueExact();
            boolean fresh = p.getCheckoutUrl() != null && p.getStatus() == PaymentStatus.PENDING && p.getCheckoutCreatedAt() != null
                    && p.getCheckoutCreatedAt().isAfter(clock.instant().minus(properties.unpaidTimeout()));
            return new Snapshot(order.getId(), order.getOrderNumber(), amount, fresh ? p.getCheckoutUrl() : null);
        });
        if (s.existingUrl() != null) return new CheckoutStarted(s.existingUrl(), s.number(), s.amount()); // a double tap reuses it

        PaymentGateway.CheckoutSession session;
        try {
            String base = properties.returnUrl() + "/orders/" + s.number();
            session = gateway.createCheckout(new PaymentGateway.CheckoutRequest(s.number(), s.amount(),
                    locale == null ? "fr" : locale, base + "?payment=success", base + "?payment=failed", properties.webhookUrl()));
        } catch (PaymentUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE",
                    "Card payment is unavailable right now. You can pay cash on delivery instead");
        }
        tx.executeWithoutResult(status -> {
            Payment p = orders.findById(s.orderId()).orElseThrow().getPayment();
            p.setProvider(gateway.provider());
            p.setProviderRef(session.providerRef());
            p.setCheckoutUrl(session.checkoutUrl());
            p.setCheckoutCreatedAt(clock.instant());
            p.setChargedAmount(BigDecimal.valueOf(s.amount()));
            p.setStatus(PaymentStatus.PENDING);
        });
        return new CheckoutStarted(session.checkoutUrl(), s.number(), s.amount());
    }

    /** The gateway is down or the customer changed their mind: pay cash on delivery instead (only before paying). */
    @Transactional
    public Order switchToCash(User customer, String orderNumber) {
        Order order = lookup.forCustomer(customer, orderNumber);
        Payment p = order.getPayment();
        if (p.getStatus() == PaymentStatus.PAID) throw ApiException.conflict("ALREADY_PAID", "This order is already paid");
        if (order.getStatus() != OrderStatus.PLACED) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", "The payment method can't be changed any more");
        }
        order.setPaymentMethod(PaymentMethod.CASH_ON_DELIVERY);
        p.setMethod(PaymentMethod.CASH_ON_DELIVERY);
        p.setStatus(PaymentStatus.PENDING);
        p.setAmount(order.getTotal());
        return order;
    }

    public enum WebhookOutcome { PROCESSED, DUPLICATE, IGNORED, AMOUNT_MISMATCH }

    /**
     * Handles one webhook delivery. Verification first (401 on a bad signature, nothing changes); then the event id is
     * claimed in {@code webhook_events} inside the same transaction as the payment update, so a re-delivery is a no-op.
     */
    @Transactional
    public WebhookOutcome handleWebhook(Map<String, String> headers, byte[] rawBody) {
        PaymentEvent event;
        try {
            event = gateway.parseAndVerifyWebhook(headers, rawBody);
        } catch (InvalidWebhookException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", e.getMessage());
        }
        if (event.eventId() == null || event.providerRef() == null) {
            throw ApiException.badRequest("WEBHOOK_INVALID", "Event id or checkout id missing");
        }
        if (event.createdAt() != null && event.createdAt().isBefore(clock.instant().minus(properties.webhookTolerance()))) {
            throw ApiException.badRequest("WEBHOOK_STALE", "Event is too old");
        }

        WebhookOutcome outcome = WebhookOutcome.IGNORED;
        List<Long> claimed = jdbc.queryForList("""
                INSERT INTO webhook_events (provider, event_id, event_type, outcome) VALUES (:p, :e, :t, 'PENDING')
                ON CONFLICT (provider, event_id) DO NOTHING RETURNING id""",
                new MapSqlParameterSource("p", gateway.provider()).addValue("e", event.eventId()).addValue("t", event.type()), Long.class);
        if (claimed.isEmpty()) return WebhookOutcome.DUPLICATE;

        Order order = orders.findByPaymentProviderRef(gateway.provider(), event.providerRef()).orElse(null);
        if (order == null) {
            log.warn("Webhook {} for unknown checkout {}", event.eventId(), event.providerRef());
        } else {
            outcome = apply(order, event);
        }
        jdbc.update("UPDATE webhook_events SET outcome = :o WHERE id = :id",
                new MapSqlParameterSource("o", outcome.name()).addValue("id", claimed.getFirst()));
        return outcome;
    }

    private WebhookOutcome apply(Order order, PaymentEvent event) {
        Payment p = order.getPayment();
        if (p.getStatus() == PaymentStatus.PAID || p.getStatus() == PaymentStatus.REFUND_PENDING) return WebhookOutcome.IGNORED;
        switch (event.status()) {
            case PAID -> {
                if (p.getChargedAmount() == null || event.amountDzd() == null
                        || p.getChargedAmount().compareTo(BigDecimal.valueOf(event.amountDzd())) != 0) {
                    p.setAmountMismatch(true);
                    p.setNote("Gateway reported " + event.amountDzd() + " DA, expected " + p.getChargedAmount() + " DA");
                    log.error("Payment amount mismatch on order {}: {}", order.getOrderNumber(), p.getNote());
                    return WebhookOutcome.AMOUNT_MISMATCH;
                }
                if (order.getStatus() == OrderStatus.CANCELLED) {
                    p.setStatus(PaymentStatus.REFUND_PENDING); // paid after it was cancelled (e.g. timed out): give it back
                } else {
                    p.setStatus(PaymentStatus.PAID);
                }
                p.setPaidAt(clock.instant());
                p.setAmount(BigDecimal.valueOf(event.amountDzd()).setScale(2));
                return WebhookOutcome.PROCESSED;
            }
            case FAILED, CANCELED -> {
                if (p.getStatus() == PaymentStatus.PENDING) p.setStatus(PaymentStatus.FAILED);
                return WebhookOutcome.PROCESSED;
            }
            default -> {
                return WebhookOutcome.IGNORED;
            }
        }
    }

    /** Called every minute by UnpaidCardOrderJob. Idempotent: an order already moved on is skipped. */
    public int cancelExpiredUnpaid() {
        Instant cutoff = clock.instant().minus(properties.unpaidTimeout());
        List<Long> ids = jdbc.queryForList("""
                SELECT o.id FROM orders o JOIN payments p ON p.order_id = o.id
                WHERE o.payment_method = 'CARD_ONLINE' AND o.status = 'PLACED' AND p.status IN ('PENDING', 'FAILED')
                  AND o.created_at < :cutoff ORDER BY o.id LIMIT 200""",
                new MapSqlParameterSource("cutoff", java.sql.Timestamp.from(cutoff)), Long.class);
        int cancelled = 0;
        for (long id : ids) {
            try {
                if (lifecycle.cancelUnpaid(id, "Card payment not received in time")) cancelled++;
            } catch (RuntimeException e) {
                log.warn("Could not cancel unpaid order {}: {}", id, e.getMessage());
            }
        }
        if (cancelled > 0) log.info("Cancelled {} unpaid card order(s)", cancelled);
        return cancelled;
    }
}
