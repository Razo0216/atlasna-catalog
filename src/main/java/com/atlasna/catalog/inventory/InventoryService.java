package com.atlasna.catalog.inventory;

import com.atlasna.catalog.common.Actor;
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
import java.time.LocalDate;

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
    private final Lots lots;

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
            // FEFO: take it from the lots that expire first. Stock in expired lots doesn't count as sellable.
            if (lots.allocate(line.productId(), q, orderId).signum() > 0) {
                shortages.add(Map.of("productId", line.productId(), "available", lots.sellable(line.productId())));
                continue;
            }
            append(line.productId(), q.negate(), StockReason.SALE, orderId, actorId, null, null);
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
        if (orderId != null) lots.giveBack(productId, q, orderId); else lots.addToOpenLot(productId, q);
        append(productId, q, StockReason.CANCEL_RESTORE, orderId, actorId, null, null);
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
        if (orderId == null || lots.allocate(productId, q, orderId).signum() > 0) {
            throw ApiException.conflict("INSUFFICIENT_STOCK", "Not enough unexpired stock to pack this quantity")
                    .withDetails(List.of(Map.of("productId", productId, "available", lots.sellable(productId))));
        }
        append(productId, q.negate(), StockReason.SALE, orderId, actorId, null, null);
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
        Long lotId = null;
        if (d.signum() > 0) lotId = lots.addToOpenLot(productId, d); else lots.remove(productId, d.negate());
        append(productId, d, reason, null, actorId, note, lotId);
        return updated.getFirst();
    }

    /**
     * Records a change the catalog already wrote to {@code stock_quantity} itself (product created with stock,
     * admin product edit), so the ledger stays complete. Ledger-only: does not touch the product row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCatalogChange(long productId, BigDecimal delta, StockReason reason, Long actorId, String note) {
        BigDecimal d = scale(delta);
        if (d.signum() == 0) return;
        Long lotId = null;
        if (d.signum() > 0) {
            lotId = reason == StockReason.OPENING ? lots.create(productId, "OPENING", d, null, note) : lots.addToOpenLot(productId, d);
        } else {
            lots.remove(productId, d.negate());
        }
        append(productId, d, reason, null, actorId, note, lotId);
    }

    // ---------- lots (ADR-0015) ----------

    /**
     * Receives a delivery as a new lot: stock up, a RESTOCK movement naming the lot. Perishables need an expiry date
     * (the last day they may be sold), and it can't be in the past.
     */
    @Transactional
    public LotView receiveLot(long productId, BigDecimal quantity, LocalDate expiryDate, String lotCode, String note, Long actorId) {
        BigDecimal q = scale(quantity);
        if (q.signum() <= 0) throw ApiException.invalidField("quantity", "Quantity must be positive");
        List<Boolean> perishable = jdbc.queryForList("SELECT perishable FROM products WHERE id = :id",
                new MapSqlParameterSource("id", productId), Boolean.class);
        if (perishable.isEmpty()) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + productId + " not found");
        if (Boolean.TRUE.equals(perishable.getFirst()) && expiryDate == null) {
            throw ApiException.invalidField("expiryDate", "A perishable product needs an expiry date");
        }
        if (expiryDate != null && expiryDate.isBefore(lots.today())) {
            throw ApiException.invalidField("expiryDate", "The expiry date is in the past");
        }
        jdbc.update("UPDATE products SET stock_quantity = stock_quantity + :q, version = version + 1, updated_at = now() WHERE id = :id",
                params(productId, q));
        String code = lotCode == null || lotCode.isBlank() ? "L" + lots.today().toString().replace("-", "") : lotCode.strip();
        long lotId = lots.create(productId, code, q, expiryDate, note);
        append(productId, q, StockReason.RESTOCK, null, actorId, note == null ? "Lot " + code : note, lotId);
        return lot(lotId);
    }

    /** A product's lots: those with stock first, in the order they will be sold (FEFO). */
    @Transactional(readOnly = true)
    public List<LotView> lots(long productId) {
        requireProduct(productId);
        return jdbc.query("SELECT * FROM inventory_lots WHERE product_id = :p "
                        + "ORDER BY (quantity_remaining > 0) DESC, expiry_date ASC NULLS LAST, received_at ASC, id ASC",
                new MapSqlParameterSource("p", productId), LOT_ROW);
    }

    /** Lots past their expiry date that still hold stock (input of the expiry job). */
    @Transactional(readOnly = true)
    public List<Long> expiredLotIds() {
        return jdbc.queryForList("SELECT id FROM inventory_lots WHERE quantity_remaining > 0 AND expiry_date < :today ORDER BY id",
                new MapSqlParameterSource("today", lots.today()), Long.class);
    }

    /**
     * Writes off one expired lot: stock down, an EXPIRED movement naming the lot, lot emptied. Idempotent — an emptied
     * (or not yet expired) lot is skipped, so a second run, or a second instance, changes nothing. Returns true if done.
     */
    @Transactional
    public boolean expireLot(long lotId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT product_id, quantity_remaining, lot_code FROM inventory_lots "
                        + "WHERE id = :l AND quantity_remaining > 0 AND expiry_date < :today FOR UPDATE",
                new MapSqlParameterSource("l", lotId).addValue("today", lots.today()));
        if (rows.isEmpty()) return false;
        long productId = ((Number) rows.getFirst().get("product_id")).longValue();
        BigDecimal q = (BigDecimal) rows.getFirst().get("quantity_remaining");
        int updated = jdbc.update("UPDATE products SET stock_quantity = stock_quantity - :q, version = version + 1, "
                + "updated_at = now() WHERE id = :id AND stock_quantity >= :q", params(productId, q));
        if (updated == 0) throw new IllegalStateException("Stock below its lots for product " + productId);
        jdbc.update("UPDATE inventory_lots SET quantity_remaining = 0 WHERE id = :l", new MapSqlParameterSource("l", lotId));
        append(productId, q.negate(), StockReason.EXPIRED, null, null, "Lot " + rows.getFirst().get("lot_code") + " expired", lotId);
        return true;
    }

    /** Lots expiring within {@code days} (today included) that still hold stock — the "sell or discount first" list. */
    @Transactional(readOnly = true)
    public List<LotView> expiringSoon(int days) {
        return jdbc.query("SELECT * FROM inventory_lots WHERE quantity_remaining > 0 AND expiry_date BETWEEN :today AND :until "
                        + "ORDER BY expiry_date, product_id",
                new MapSqlParameterSource("today", lots.today()).addValue("until", lots.today().plusDays(days)), LOT_ROW);
    }

    /** Raises {@link LotExpiring} once per lot entering the window (notifications, Phase 7). Returns how many. */
    @Transactional
    public int notifyExpiringSoon(int days) {
        List<LotView> soon = jdbc.query("SELECT * FROM inventory_lots WHERE quantity_remaining > 0 AND expiring_notified_at IS NULL "
                        + "AND expiry_date BETWEEN :today AND :until FOR UPDATE SKIP LOCKED",
                new MapSqlParameterSource("today", lots.today()).addValue("until", lots.today().plusDays(days)), LOT_ROW);
        for (LotView l : soon) {
            jdbc.update("UPDATE inventory_lots SET expiring_notified_at = now() WHERE id = :l", new MapSqlParameterSource("l", l.id()));
            events.publishEvent(new LotExpiring(l.id(), l.productId(), l.lotCode(), l.quantityRemaining(), l.expiryDate()));
        }
        return soon.size();
    }

    /** Active products at or under their low-stock threshold. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lowStock() {
        return jdbc.queryForList("SELECT id AS \"productId\", sku, name_fr AS \"name\", stock_quantity AS \"stockQuantity\", "
                + "low_stock_threshold AS \"lowStockThreshold\" FROM products "
                + "WHERE active AND stock_quantity <= low_stock_threshold ORDER BY stock_quantity, id", new MapSqlParameterSource());
    }

    /** Products whose stock differs from the sum of their lots (must always be empty; checked by tests). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal[]> lotMismatches() {
        Map<Long, BigDecimal[]> out = new LinkedHashMap<>();
        jdbc.query("SELECT p.id, p.stock_quantity, COALESCE(SUM(l.quantity_remaining), 0) AS lots FROM products p "
                        + "LEFT JOIN inventory_lots l ON l.product_id = p.id GROUP BY p.id, p.stock_quantity "
                        + "HAVING p.stock_quantity <> COALESCE(SUM(l.quantity_remaining), 0)",
                rs -> { out.put(rs.getLong("id"), new BigDecimal[] {rs.getBigDecimal("stock_quantity"), rs.getBigDecimal("lots")}); });
        return out;
    }

    private LotView lot(long id) {
        return jdbc.queryForObject("SELECT * FROM inventory_lots WHERE id = :id", new MapSqlParameterSource("id", id), LOT_ROW);
    }

    private static final org.springframework.jdbc.core.RowMapper<LotView> LOT_ROW = (rs, i) -> new LotView(rs.getLong("id"),
            rs.getLong("product_id"), rs.getString("lot_code"), rs.getBigDecimal("quantity_received"),
            rs.getBigDecimal("quantity_remaining"), rs.getTimestamp("received_at").toInstant(),
            rs.getObject("expiry_date", LocalDate.class), rs.getString("note"));

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

    private void append(long productId, BigDecimal delta, StockReason reason, Long orderId, Long actorId, String note, Long lotId) {
        events.publishEvent(new StockChanged(productId));
        if (reason != StockReason.SALE && reason != StockReason.CANCEL_RESTORE) {
            events.publishEvent(new StockAdjusted(productId, delta, reason, note, Actor.current())); // audit log
        }
        jdbc.update("""
                INSERT INTO stock_movements (product_id, delta, reason, order_id, actor_id, note, lot_id)
                VALUES (:id, :delta, :reason, :orderId, :actorId, :note, :lotId)""",
                new MapSqlParameterSource("id", productId).addValue("delta", delta).addValue("reason", reason.name())
                        .addValue("orderId", orderId).addValue("actorId", actorId).addValue("note", note).addValue("lotId", lotId));
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
