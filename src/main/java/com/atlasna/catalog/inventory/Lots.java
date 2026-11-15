package com.atlasna.catalog.inventory;

import com.atlasna.catalog.common.exception.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * The lot side of every stock change (ADR-0015), always called by {@link InventoryService} in the same transaction and
 * AFTER the product row was updated — that row lock is what serialises concurrent buyers of a product, so the lot rows
 * are only ever touched by one transaction per product at a time ({@code FOR UPDATE} makes it explicit).
 *
 * <p>Sales take from the lot that expires first (FEFO), never from an expired lot; lots without an expiry date go last.
 */
@Component
class Lots {

    static final ZoneId SHOP = ZoneId.of("Africa/Algiers");

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    Lots(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(SHOP));
    }

    /**
     * Takes {@code quantity} for an order, FEFO, from lots that are not expired, and records where it came from.
     * @return what could NOT be allocated (0 when everything was found)
     */
    BigDecimal allocate(long productId, BigDecimal quantity, long orderId) {
        BigDecimal need = quantity;
        List<Map<String, Object>> lots = jdbc.queryForList("""
                SELECT id, quantity_remaining FROM inventory_lots
                WHERE product_id = :p AND quantity_remaining > 0 AND (expiry_date IS NULL OR expiry_date >= :today)
                ORDER BY expiry_date ASC NULLS LAST, received_at ASC, id ASC FOR UPDATE""",
                new MapSqlParameterSource("p", productId).addValue("today", today()));
        for (Map<String, Object> lot : lots) {
            if (need.signum() <= 0) break;
            long lotId = ((Number) lot.get("id")).longValue();
            BigDecimal take = need.min((BigDecimal) lot.get("quantity_remaining"));
            take(lotId, take);
            jdbc.update("INSERT INTO lot_allocations (order_id, product_id, lot_id, quantity) VALUES (:o, :p, :l, :q)",
                    new MapSqlParameterSource("o", orderId).addValue("p", productId).addValue("l", lotId).addValue("q", take));
            need = need.subtract(take);
        }
        return need.max(BigDecimal.ZERO);
    }

    /** Gives an order's goods back to the lots they came from (latest allocation first). */
    void giveBack(long productId, BigDecimal quantity, long orderId) {
        BigDecimal left = quantity;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, lot_id, quantity FROM lot_allocations WHERE order_id = :o AND product_id = :p
                ORDER BY id DESC FOR UPDATE""", new MapSqlParameterSource("o", orderId).addValue("p", productId));
        for (Map<String, Object> a : rows) {
            if (left.signum() <= 0) break;
            BigDecimal give = left.min((BigDecimal) a.get("quantity"));
            jdbc.update("UPDATE inventory_lots SET quantity_remaining = quantity_remaining + :q WHERE id = :l",
                    new MapSqlParameterSource("q", give).addValue("l", a.get("lot_id")));
            jdbc.update("UPDATE lot_allocations SET quantity = quantity - :q WHERE id = :id",
                    new MapSqlParameterSource("q", give).addValue("id", a.get("id")));
            left = left.subtract(give);
        }
        jdbc.update("DELETE FROM lot_allocations WHERE order_id = :o AND product_id = :p AND quantity <= 0",
                new MapSqlParameterSource("o", orderId).addValue("p", productId));
        if (left.signum() > 0) addToOpenLot(productId, left); // orders placed before lots existed
    }

    /** Stock added without lot details (count correction, product form): the open no-expiry lot, created if needed. */
    long addToOpenLot(long productId, BigDecimal quantity) {
        List<Long> open = jdbc.queryForList("""
                SELECT id FROM inventory_lots WHERE product_id = :p AND expiry_date IS NULL
                ORDER BY received_at DESC, id DESC LIMIT 1 FOR UPDATE""", new MapSqlParameterSource("p", productId), Long.class);
        if (open.isEmpty()) return create(productId, "DEFAULT", quantity, null, null);
        jdbc.update("""
                UPDATE inventory_lots SET quantity_remaining = quantity_remaining + :q, quantity_received = quantity_received + :q
                WHERE id = :l""", new MapSqlParameterSource("q", quantity).addValue("l", open.getFirst()));
        return open.getFirst();
    }

    long create(long productId, String code, BigDecimal quantity, LocalDate expiry, String note) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_lots (product_id, lot_code, quantity_received, quantity_remaining, expiry_date, note)
                VALUES (:p, :c, :q, :q, :e, :n) RETURNING id""",
                new MapSqlParameterSource("p", productId).addValue("c", code).addValue("q", quantity).addValue("e", expiry)
                        .addValue("n", note), Long.class);
    }

    /** Removes stock found missing or written off, earliest expiry first (expired lots included: they're the first to go). */
    void remove(long productId, BigDecimal quantity) {
        BigDecimal need = quantity;
        List<Map<String, Object>> lots = jdbc.queryForList("""
                SELECT id, quantity_remaining FROM inventory_lots WHERE product_id = :p AND quantity_remaining > 0
                ORDER BY expiry_date ASC NULLS LAST, received_at ASC, id ASC FOR UPDATE""", new MapSqlParameterSource("p", productId));
        for (Map<String, Object> lot : lots) {
            if (need.signum() <= 0) break;
            BigDecimal take = need.min((BigDecimal) lot.get("quantity_remaining"));
            take(((Number) lot.get("id")).longValue(), take);
            need = need.subtract(take);
        }
        if (need.signum() > 0) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "The lots don't hold that much stock");
        }
    }

    /** Sellable (non-expired) quantity, for the details of an INSUFFICIENT_STOCK answer. */
    BigDecimal sellable(long productId) {
        BigDecimal s = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity_remaining), 0) FROM inventory_lots
                WHERE product_id = :p AND (expiry_date IS NULL OR expiry_date >= :today)""",
                new MapSqlParameterSource("p", productId).addValue("today", today()), BigDecimal.class);
        return s == null ? BigDecimal.ZERO : s;
    }

    private void take(long lotId, BigDecimal quantity) {
        jdbc.update("UPDATE inventory_lots SET quantity_remaining = quantity_remaining - :q WHERE id = :l",
                new MapSqlParameterSource("q", quantity).addValue("l", lotId));
    }
}
