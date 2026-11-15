package com.atlasna.catalog.audit;

import com.atlasna.catalog.common.Actor;
import com.atlasna.catalog.inventory.StockAdjusted;
import com.atlasna.catalog.order.OrderPlaced;
import com.atlasna.catalog.order.OrderStatusChanged;
import com.atlasna.catalog.product.CatalogChanged;
import com.atlasna.catalog.store.StoreSettingsChanged;
import com.atlasna.catalog.user.LoginFailed;
import com.atlasna.catalog.user.UserAccountChanged;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the audit log from APPLICATION EVENTS (ADR-0012), never from scattered calls in business code:
 * a module publishes what happened; this module decides what to keep.
 *
 * <ul>
 *   <li><b>After commit, on another thread</b> ({@code @Async @TransactionalEventListener}): a rolled-back change is
 *       never logged, and a slow or dead MongoDB never slows down or fails the request.</li>
 *   <li><b>Not lost when Mongo is down:</b> Spring Modulith records each event in {@code event_publication} before
 *       the listener runs and marks it complete only when the listener returns. A failed write leaves it incomplete;
 *       outstanding events are re-delivered on the next start
 *       ({@code spring.modulith.events.republish-outstanding-events-on-restart}).</li>
 *   <li>Each failure increments {@code atlasna.audit.writes{outcome=failure}} — the metric to alert on.</li>
 *   <li>No JDBC transaction is opened here, so a Mongo timeout never holds a database connection.</li>
 * </ul>
 */
@Slf4j
@Component
class AuditLogWriter {

    private final MongoTemplate mongo;
    private final MeterRegistry meters;
    private final Clock clock;

    AuditLogWriter(MongoTemplate mongo, MeterRegistry meters, Clock clock) {
        this.mongo = mongo;
        this.meters = meters;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(CatalogChanged e) {
        if (e.before().isEmpty() && e.after().isEmpty() && e.action().endsWith("_UPDATED")) return; // saved, nothing changed
        write(e, e.actor(), e.action(), e.entityType(), e.entityId(), e.before(), e.after(), Map.of());
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(StockAdjusted e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("delta", e.delta().stripTrailingZeros().toPlainString());
        details.put("reason", e.reason().name());
        if (e.note() != null) details.put("note", e.note());
        write(e, e.actor(), "STOCK_ADJUSTED", "PRODUCT", e.productId(), null, null, details);
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(OrderPlaced e) {
        write(e, e.actor(), "ORDER_PLACED", "ORDER", e.orderId(), null, null,
                Map.of("orderNumber", e.orderNumber(), "total", e.total().toPlainString()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(OrderStatusChanged e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("orderNumber", e.orderNumber());
        if (e.reason() != null) details.put("reason", e.reason());
        write(e, e.actor(), "ORDER_STATUS_CHANGED", "ORDER", e.orderId(), Map.of("status", e.from()), Map.of("status", e.to()), details);
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(StoreSettingsChanged e) {
        if (e.before().isEmpty() && e.after().isEmpty()) return;
        write(e, e.actor(), "SETTINGS_UPDATED", "SETTINGS", 1, e.before(), e.after(), Map.of("version", e.version()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(UserAccountChanged e) {
        write(e, e.actor(), e.action(), "USER", e.userId(), null, null, Map.of("role", e.role()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(LoginFailed e) {
        write(e, e.actor(), "LOGIN_FAILED", "USER", null, null, null, Map.of("email", e.email(), "reason", e.reason()));
    }

    /**
     * The entry's id is a hash of the event itself (which includes the actor's timestamp), so writing the same event
     * twice — a re-delivery after a timeout that MongoDB had in fact completed — is harmless: the second insert hits
     * the duplicate key and counts as done. A timeout never means "not written".
     */
    private void write(Record event, Actor actor, String action, String entityType, Object entityId, Map<String, Object> before,
                       Map<String, Object> after, Map<String, Object> details) {
        Actor a = actor == null ? Actor.system() : actor;
        AuditEntry entry = new AuditEntry(idOf(event), a.at() == null ? clock.instant() : a.at(), a.id(), a.role(), a.ip(), action,
                entityType, entityId == null ? null : String.valueOf(entityId), empty(before), empty(after), empty(details));
        try {
            mongo.insert(entry);
            meters.counter("atlasna.audit.writes", "outcome", "success").increment();
        } catch (org.springframework.dao.DuplicateKeyException already) {
            meters.counter("atlasna.audit.writes", "outcome", "duplicate").increment(); // written by an earlier attempt
        } catch (RuntimeException ex) {
            meters.counter("atlasna.audit.writes", "outcome", "failure").increment();
            log.error("Audit log write failed for {} {} {} (kept for re-delivery): {}", action, entityType, entityId, ex.getMessage());
            throw ex; // leaves the Modulith event publication incomplete, so it can be re-delivered
        }
    }

    private static String idOf(Record event) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((event.getClass().getName() + event).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> empty(Map<String, Object> m) {
        return m == null || m.isEmpty() ? null : m;
    }
}
