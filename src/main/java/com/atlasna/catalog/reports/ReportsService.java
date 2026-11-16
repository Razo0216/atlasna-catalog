package com.atlasna.catalog.reports;

import com.atlasna.catalog.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers behind the back-office dashboard and reports. Everything is per <b>delivery day</b> ({@code slot_date},
 * Africa/Algiers): that's how the shop thinks ("Monday's orders"), and it's indexed.
 *
 * <ul>
 *   <li><b>Expected</b> revenue = the totals of the day's orders that aren't cancelled.</li>
 *   <li><b>Collected</b> revenue = what was actually received: cash collected at the door, or a card payment PAID.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReportsService {

    static final ZoneId SHOP = ZoneId.of("Africa/Algiers");
    static final int MAX_RANGE_DAYS = 366;
    private static final String RECEIVED = "('COLLECTED', 'PAID')";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SHOP));
    }

    // ---------- dashboard ----------

    public record Revenue(BigDecimal expected, BigDecimal collected) {}

    public record SlotLoad(long slotId, LocalTime start, LocalTime end, int capacity, int booked) {}

    public record Dashboard(LocalDate date, Map<String, Integer> ordersByStatus, Revenue revenue, List<SlotLoad> slots,
                            int toConfirm, int failedDeliveries, int lowStockCount, int expiringCount) {}

    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate date) {
        var p = new MapSqlParameterSource("d", date);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) AS n FROM orders WHERE slot_date = :d GROUP BY status ORDER BY status", p,
                rs -> { byStatus.put(rs.getString("status"), rs.getInt("n")); });
        Revenue revenue = jdbc.queryForObject("""
                SELECT COALESCE(SUM(o.total) FILTER (WHERE o.status <> 'CANCELLED'), 0) AS expected,
                       COALESCE(SUM(pay.amount) FILTER (WHERE pay.status IN %s), 0) AS collected
                FROM orders o LEFT JOIN payments pay ON pay.order_id = o.id
                WHERE o.slot_date = :d""".formatted(RECEIVED), p,
                (rs, i) -> new Revenue(rs.getBigDecimal("expected"), rs.getBigDecimal("collected")));
        // Slots offered that day (every-day or that weekday), with how many orders took them.
        p.addValue("dow", date.getDayOfWeek().getValue());
        List<SlotLoad> slots = jdbc.query("""
                SELECT s.id, s.start_time, s.end_time, s.capacity, COALESCE(u.booked, 0) AS booked
                FROM delivery_slots s LEFT JOIN slot_usage u ON u.slot_id = s.id AND u.slot_date = :d
                WHERE s.active AND (s.day_of_week IS NULL OR s.day_of_week = :dow)
                ORDER BY s.start_time, s.id""", p,
                (rs, i) -> new SlotLoad(rs.getLong("id"), rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class), rs.getInt("capacity"), rs.getInt("booked")));
        int toConfirm = count("SELECT count(*) FROM orders WHERE status = 'PLACED'", p);
        int lowStock = count("SELECT count(*) FROM products WHERE active AND stock_quantity <= low_stock_threshold", p);
        p.addValue("today", today()).addValue("until", today().plusDays(3));
        int expiring = count("SELECT count(*) FROM inventory_lots WHERE quantity_remaining > 0 AND expiry_date <= :until", p);
        return new Dashboard(date, byStatus, revenue, slots, toConfirm, byStatus.getOrDefault("DELIVERY_FAILED", 0), lowStock, expiring);
    }

    // ---------- reports (date range, inclusive) ----------

    public record DaySales(LocalDate day, int orders, int delivered, int cancelled, BigDecimal expected, BigDecimal collected) {}

    public record ProductSales(long productId, String sku, String nameFr, String nameAr, String soldBy, BigDecimal quantity,
                               BigDecimal revenue, int orders) {}

    public record CategorySales(long categoryId, String nameFr, String nameAr, String type, BigDecimal revenue, int orders) {}

    public record ZoneSales(long zoneId, String nameFr, String nameAr, int orders, BigDecimal revenue, BigDecimal deliveryFees) {}

    public record DriverCash(long driverId, String fullName, int deliveries, int failed, BigDecimal cashCollected) {}

    @Transactional(readOnly = true)
    public List<DaySales> salesByDay(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT o.slot_date AS day, count(*) AS orders,
                       count(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered,
                       count(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled,
                       COALESCE(SUM(o.total) FILTER (WHERE o.status <> 'CANCELLED'), 0) AS expected,
                       COALESCE(SUM(pay.amount) FILTER (WHERE pay.status IN %s), 0) AS collected
                FROM orders o LEFT JOIN payments pay ON pay.order_id = o.id
                WHERE o.slot_date BETWEEN :from AND :to
                GROUP BY o.slot_date ORDER BY o.slot_date""".formatted(RECEIVED), range(from, to),
                (rs, i) -> new DaySales(rs.getObject("day", LocalDate.class), rs.getInt("orders"), rs.getInt("delivered"),
                        rs.getInt("cancelled"), rs.getBigDecimal("expected"), rs.getBigDecimal("collected")));
    }

    /** What was actually handed over: delivered orders, packed quantities (a substitute counts as itself). */
    @Transactional(readOnly = true)
    public List<ProductSales> topProducts(LocalDate from, LocalDate to, int limit) {
        if (limit < 1 || limit > 100) throw ApiException.invalidField("limit", "Limit must be between 1 and 100");
        return jdbc.query("""
                SELECT p.id, p.sku, p.name_fr, p.name_ar, p.sold_by,
                       SUM(COALESCE(i.quantity_fulfilled, i.quantity_ordered)) AS quantity,
                       SUM(i.line_total) AS revenue, count(DISTINCT o.id) AS orders
                FROM order_items i
                JOIN orders o ON o.id = i.order_id
                JOIN products p ON p.id = COALESCE(i.substitute_product_id, i.product_id)
                WHERE o.status = 'DELIVERED' AND o.slot_date BETWEEN :from AND :to AND i.status <> 'OUT_OF_STOCK'
                GROUP BY p.id, p.sku, p.name_fr, p.name_ar, p.sold_by
                ORDER BY revenue DESC, p.id LIMIT :limit""", range(from, to).addValue("limit", limit),
                (rs, i) -> new ProductSales(rs.getLong("id"), rs.getString("sku"), rs.getString("name_fr"), rs.getString("name_ar"),
                        rs.getString("sold_by"), rs.getBigDecimal("quantity"), rs.getBigDecimal("revenue"), rs.getInt("orders")));
    }

    @Transactional(readOnly = true)
    public List<CategorySales> byCategory(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT c.id, c.name_fr, c.name_ar, c.type, SUM(i.line_total) AS revenue, count(DISTINCT o.id) AS orders
                FROM order_items i
                JOIN orders o ON o.id = i.order_id
                JOIN products p ON p.id = COALESCE(i.substitute_product_id, i.product_id)
                JOIN categories c ON c.id = p.category_id
                WHERE o.status = 'DELIVERED' AND o.slot_date BETWEEN :from AND :to AND i.status <> 'OUT_OF_STOCK'
                GROUP BY c.id, c.name_fr, c.name_ar, c.type
                ORDER BY revenue DESC, c.id""", range(from, to),
                (rs, i) -> new CategorySales(rs.getLong("id"), rs.getString("name_fr"), rs.getString("name_ar"), rs.getString("type"),
                        rs.getBigDecimal("revenue"), rs.getInt("orders")));
    }

    @Transactional(readOnly = true)
    public List<ZoneSales> byZone(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT z.id, z.name_fr, z.name_ar, count(o.id) AS orders,
                       COALESCE(SUM(o.total), 0) AS revenue, COALESCE(SUM(o.delivery_fee), 0) AS fees
                FROM orders o JOIN delivery_zones z ON z.id = o.zone_id
                WHERE o.status = 'DELIVERED' AND o.slot_date BETWEEN :from AND :to
                GROUP BY z.id, z.name_fr, z.name_ar
                ORDER BY revenue DESC, z.id""", range(from, to),
                (rs, i) -> new ZoneSales(rs.getLong("id"), rs.getString("name_fr"), rs.getString("name_ar"), rs.getInt("orders"),
                        rs.getBigDecimal("revenue"), rs.getBigDecimal("fees")));
    }

    /** Cash each driver collected (what they owe the till), and their failed attempts, by delivery day. */
    @Transactional(readOnly = true)
    public List<DriverCash> cashByDriver(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT u.id, u.full_name,
                       count(*) FILTER (WHERE o.status = 'DELIVERED') AS deliveries,
                       count(*) FILTER (WHERE o.status = 'DELIVERY_FAILED') AS failed,
                       COALESCE(SUM(pay.amount) FILTER (WHERE pay.status = 'COLLECTED' AND pay.method = 'CASH_ON_DELIVERY'), 0) AS cash
                FROM orders o
                JOIN users u ON u.id = o.assigned_driver_id
                LEFT JOIN payments pay ON pay.order_id = o.id
                WHERE o.slot_date BETWEEN :from AND :to
                GROUP BY u.id, u.full_name
                ORDER BY cash DESC, u.id""", range(from, to),
                (rs, i) -> new DriverCash(rs.getLong("id"), rs.getString("full_name"), rs.getInt("deliveries"), rs.getInt("failed"),
                        rs.getBigDecimal("cash")));
    }

    private MapSqlParameterSource range(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw ApiException.invalidField("to", "The end date must be on or after the start date");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw ApiException.invalidField("to", "At most " + MAX_RANGE_DAYS + " days at a time");
        }
        return new MapSqlParameterSource("from", from).addValue("to", to);
    }

    private int count(String sql, MapSqlParameterSource p) {
        Integer n = jdbc.queryForObject(sql, p, Integer.class);
        return n == null ? 0 : n;
    }
}
