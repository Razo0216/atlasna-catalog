package com.atlasna.catalog.inventory;

import com.atlasna.catalog.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock is a ledger: every change appends a {@code stock_movements} row, and {@code products.stock_quantity}
 * is its cached sum. All decrements are ONE conditional SQL statement
 * ({@code UPDATE … SET stock = stock - q WHERE id = ? AND stock >= q}): the database row lock makes concurrent
 * buyers queue, and whoever finds too little stock gets 0 rows back — nobody can oversell. No read-check-write.
 * Each stock update also bumps {@code products.version}, so an admin editing a stale copy gets 409, not a lost update.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    /**
     * Takes stock for every line of an order, or none: lines are processed in ascending product id so two
     * orders never lock the same rows in opposite order (no deadlock). Any shortage → 409 INSUFFICIENT_STOCK with
     * {@code details:[{productId, available}]}, and the caller's transaction rolls back everything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(List<StockLine> lines, long orderId, Long actorId) {
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (StockLine line : lines.stream().sorted(Comparator.comparingLong(StockLine::productId)).toList()) {
            BigDecimal q = scale(line.quantity());
            List<Map<String, Object>> updated = jdbc.queryForList("""
                    UPDATE products SET stock_quantity = stock_quantity - :q, version = version + 1, updated_at = now()
                    WHERE id = :id AND active AND stock_quantity >= :q
                    RETURNING stock_quantity, low_stock_threshold""", params(line.productId(), q));
            if (updated.isEmpty()) {
                shortages.add(Map.of("productId", line.productId(), "available", available(line.productId())));
                continue;
            }
            append(line.productId(), q.negate(), StockReason.SALE, orderId, actorId, null);
            BigDecimal stock = (BigDecimal) updated.getFirst().get("stock_quantity");
            BigDecimal threshold = (BigDecimal) updated.getFirst().get("low_stock_threshold");
            if (stock.compareTo(threshold) <= 0) events.publishEvent(new StockLow(line.productId(), stock, threshold));
        }
        if (!shortages.isEmpty()) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Not enough stock for some items").withDetails(shortages);
        }
    }

    /** Gives stock back (cancellation, removed line, lighter weighing). Always succeeds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(long productId, BigDecimal quantity, Long orderId, Long actorId) {
        BigDecimal q = scale(quantity);
        if (q.signum() == 0) return;
        jdbc.update("""
                UPDATE products SET stock_quantity = stock_quantity + :q, version = version + 1, updated_at = now()
                WHERE id = :id""", params(productId, q));
        append(productId, q, StockReason.CANCEL_RESTORE, orderId, actorId, null);
    }

    /** Takes extra stock for one product (a weighed line came out heavier): conditional like {@link #reserve}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void takeMore(long productId, BigDecimal quantity, Long orderId, Long actorId) {
        BigDecimal q = scale(quantity);
        if (q.signum() == 0) return;
        int rows = jdbc.update("""
                UPDATE products SET stock_quantity = stock_quantity - :q, version = version + 1, updated_at = now()
                WHERE id = :id AND stock_quantity >= :q""", params(productId, q));
        if (rows == 0) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Not enough stock to pack this quantity")
                    .withDetails(List.of(Map.of("productId", productId, "available", available(productId))));
        }
        append(productId, q.negate(), StockReason.SALE, orderId, actorId, null);
    }

    /**
     * Manual change by staff/admin (restock, count correction, expired goods). A decrease never takes stock
     * below zero (409 INSUFFICIENT_STOCK). Returns the new stock.
     */
    @Transactional
    public BigDecimal adjust(long productId, BigDecimal delta, StockReason reason, Long actorId, String note) {
        if (reason == StockReason.OPENING || reason == StockReason.SALE || reason == StockReason.CANCEL_RESTORE) {
            throw ApiException.invalidField("reason", "Use RESTOCK, ADJUSTMENT or EXPIRED");
        }
        BigDecimal d = scale(delta);
        if (d.signum() == 0) throw ApiException.invalidField("delta", "Delta must not be zero");
        if (reason == StockReason.RESTOCK && d.signum() < 0) throw ApiException.invalidField("delta", "A restock adds stock");
        if (reason == StockReason.EXPIRED && d.signum() > 0) throw ApiException.invalidField("delta", "Expired stock is removed");
        requireProduct(productId);
        List<BigDecimal> updated = jdbc.queryForList("""
                UPDATE products SET stock_quantity = stock_quantity + :q, version = version + 1, updated_at = now()
                WHERE id = :id AND stock_quantity + :q >= 0
                RETURNING stock_quantity""", params(productId, d), BigDecimal.class);
        if (updated.isEmpty()) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Stock can't go below zero")
                    .withDetails(List.of(Map.of("productId", productId, "available", available(productId))));
        }
        append(productId, d, reason, null, actorId, note);
        return updated.getFirst();
    }

    /**
     * Records a change the catalog already wrote to {@code stock_quantity} itself (product created with stock,
     * admin product edit), so the ledger stays complete. Ledger-only: does not touch the product row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCatalogChange(long productId, BigDecimal delta, StockReason reason, Long actorId, String note) {
        BigDecimal d = scale(delta);
        if (d.signum() != 0) append(productId, d, reason, null, actorId, note);
    }

    @Transactional(readOnly = true)
    public Page<StockMovementView> movements(long productId, Pageable pageable) {
        requireProduct(productId);
        MapSqlParameterSource p = new MapSqlParameterSource("id", productId)
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<StockMovementView> rows = jdbc.query("""
                SELECT id, delta, reason, order_id, actor_id, note, at FROM stock_movements
                WHERE product_id = :id ORDER BY at DESC, id DESC LIMIT :limit OFFSET :offset""", p,
                (rs, i) -> new StockMovementView(rs.getLong("id"), rs.getBigDecimal("delta"),
                        StockReason.valueOf(rs.getString("reason")), (Long) rs.getObject("order_id"),
                        (Long) rs.getObject("actor_id"), rs.getString("note"), rs.getTimestamp("at").toInstant()));
        Long total = jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE product_id = :id", p, Long.class);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** Products whose cached stock differs from the ledger sum (must always be empty; checked by tests). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal[]> ledgerMismatches() {
        Map<Long, BigDecimal[]> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.id, p.stock_quantity, COALESCE(SUM(m.delta), 0) AS ledger
                FROM products p LEFT JOIN stock_movements m ON m.product_id = p.id
                GROUP BY p.id, p.stock_quantity HAVING p.stock_quantity <> COALESCE(SUM(m.delta), 0)""",
                rs -> { out.put(rs.getLong("id"), new BigDecimal[] {rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("ledger")}); });
        return out;
    }

    private void append(long productId, BigDecimal delta, StockReason reason, Long orderId, Long actorId, String note) {
        jdbc.update("""
                INSERT INTO stock_movements (product_id, delta, reason, order_id, actor_id, note)
                VALUES (:id, :delta, :reason, :orderId, :actorId, :note)""",
                new MapSqlParameterSource("id", productId).addValue("delta", delta).addValue("reason", reason.name())
                        .addValue("orderId", orderId).addValue("actorId", actorId).addValue("note", note));
    }

    private BigDecimal available(long productId) {
        List<BigDecimal> s = jdbc.queryForList("SELECT CASE WHEN active THEN stock_quantity ELSE 0 END FROM products WHERE id = :id",
                new MapSqlParameterSource("id", productId), BigDecimal.class);
        return s.isEmpty() ? BigDecimal.ZERO : s.getFirst();
    }

    private void requireProduct(long productId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM products WHERE id = :id",
                new MapSqlParameterSource("id", productId), Integer.class);
        if (n == null || n == 0) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + productId + " not found");
    }

    private static MapSqlParameterSource params(long productId, BigDecimal q) {
        return new MapSqlParameterSource("id", productId).addValue("q", q);
    }

    private static BigDecimal scale(BigDecimal q) {
        return q.setScale(3, RoundingMode.HALF_UP);
    }
}
