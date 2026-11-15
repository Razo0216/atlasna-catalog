package com.atlasna.catalog;

import com.atlasna.catalog.inventory.ExpiryJob;
import com.atlasna.catalog.inventory.LotView;
import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lots and FEFO (ADR-0015): sales take the lot that expires first and never an expired one; cancellations give back to
 * the same lots; the hourly job writes off expired lots exactly once; receiving goods requires an expiry date for
 * perishables; reports; and the invariant stock = Σ lots = Σ ledger, also under 20 concurrent buyers.
 */
class InventoryLotTests extends OrderTestSupport {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Algiers"));

    @Test
    void salesTakeTheLotThatExpiresFirst() throws Exception {
        long yoghurt = perishable("Yaourt", "600.00");
        long late = receive(yoghurt, "5", TODAY.plusDays(10), "LATE");
        long early = receive(yoghurt, "5", TODAY.plusDays(3), "EARLY");

        long o1 = order(yoghurt, "4");
        assertThat(allocations(o1)).containsExactly(Map.entry(early, new BigDecimal("4.000")));
        long o2 = order(yoghurt, "4");
        assertThat(allocations(o2)).containsExactly(Map.entry(early, new BigDecimal("1.000")), Map.entry(late, new BigDecimal("3.000")));
        assertThat(remaining(early)).isEqualByComparingTo("0");
        assertThat(remaining(late)).isEqualByComparingTo("2");
        assertLedgerHolds();
    }

    @Test
    void expiredLotsAreNeverSold() throws Exception {
        long milk = perishable("Lait frais", "700.00");
        long old = receive(milk, "5", TODAY.plusDays(1), "OLD");
        receive(milk, "2", TODAY.plusDays(5), "NEW");
        jdbc.update("UPDATE inventory_lots SET expiry_date = ? WHERE id = ?", TODAY.minusDays(1), old); // time passed

        User u = customer();
        inCart(u, milk, "3"); // stock says 7, but only 2 are sellable
        assertThatThrownBy(() -> placement.place(u.getId(), "k", request(address(u)))).hasMessageContaining("Not enough stock");
        assertThat(stock(milk)).isEqualByComparingTo("7"); // the whole order rolled back
        assertLedgerHolds();
    }

    @Test
    void theExpiryJobWritesOffExpiredLotsOnce() throws Exception {
        long cream = perishable("Crème", "500.00");
        long old = receive(cream, "4", TODAY.plusDays(1), "OLD");
        long fresh = receive(cream, "6", TODAY.plusDays(8), "FRESH");
        jdbc.update("UPDATE inventory_lots SET expiry_date = ? WHERE id = ?", TODAY.minusDays(1), old);

        assertThat(ExpiryJob.run(inventory, 3)).isEqualTo(1);
        assertThat(ExpiryJob.run(inventory, 3)).isZero(); // idempotent
        assertThat(stock(cream)).isEqualByComparingTo("6");
        assertThat(remaining(old)).isEqualByComparingTo("0");
        assertThat(remaining(fresh)).isEqualByComparingTo("6");
        var movement = jdbc.queryForMap("SELECT delta, reason, lot_id FROM stock_movements WHERE product_id = ? ORDER BY id DESC LIMIT 1", cream);
        assertThat(movement.get("reason")).isEqualTo("EXPIRED");
        assertThat((BigDecimal) movement.get("delta")).isEqualByComparingTo("-4");
        assertThat(((Number) movement.get("lot_id")).longValue()).isEqualTo(old);
        assertLedgerHolds();
    }

    @Test
    void aCancellationGivesBackToTheSameLots() throws Exception {
        long cheese = perishable("Fromage", "800.00");
        long a = receive(cheese, "2", TODAY.plusDays(2), "A");
        long b = receive(cheese, "5", TODAY.plusDays(9), "B");
        long orderId = order(cheese, "3"); // 2 from A, 1 from B
        User staff = user(Role.STAFF);
        String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
        mockMvc.perform(post("/api/admin/orders/" + number + "/transition").header("Authorization", bearer(staff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"CANCELLED\",\"reason\":\"test\"}")).andExpect(status().isOk());
        assertThat(remaining(a)).isEqualByComparingTo("2");
        assertThat(remaining(b)).isEqualByComparingTo("5");
        assertThat(allocations(orderId)).isEmpty();
        assertLedgerHolds();
    }

    @Test
    void receivingGoods_perishablesNeedAFutureExpiryDate() throws Exception {
        long yoghurt = perishable("Yaourt nature", "600.00");
        long rice = product("Riz", "200.00", "0");
        User staff = user(Role.STAFF);
        receiveApi(staff, yoghurt, "{\"quantity\":10}").andExpect(status().isBadRequest());
        receiveApi(staff, yoghurt, "{\"quantity\":10,\"expiryDate\":\"%s\"}".formatted(TODAY.minusDays(1))).andExpect(status().isBadRequest());
        receiveApi(staff, yoghurt, "{\"quantity\":10,\"expiryDate\":\"%s\",\"lotCode\":\"BL-778\"}".formatted(TODAY.plusDays(12)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lotCode").value("BL-778"))
                .andExpect(jsonPath("$.quantityRemaining").value(10));
        receiveApi(staff, rice, "{\"quantity\":20}").andExpect(status().isCreated()); // non-perishable: no date needed
        assertThat(stock(yoghurt)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_movements WHERE product_id = ? ORDER BY id DESC LIMIT 1", String.class, yoghurt))
                .isEqualTo("RESTOCK");
        receiveApi(customer(), rice, "{\"quantity\":1}").andExpect(status().isForbidden());
        assertLedgerHolds();
    }

    @Test
    void reportsAndTheOldAdjustmentEndpointStillWork() throws Exception {
        long yoghurt = perishable("Yaourt fraise", "600.00");
        long soon = receive(yoghurt, "3", TODAY.plusDays(2), "SOON");
        receive(yoghurt, "3", TODAY.plusDays(20), "LATER");
        User admin = user(Role.ADMIN);
        mockMvc.perform(get("/api/admin/inventory/expiring").param("days", "3").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(soon));
        assertThat(inventory.notifyExpiringSoon(3)).isEqualTo(1);
        assertThat(inventory.notifyExpiringSoon(3)).isZero(); // raised once per lot

        // A count correction removes from the earliest-expiring lot first.
        mockMvc.perform(post("/api/admin/products/" + yoghurt + "/stock-adjustments").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-2,\"reason\":\"ADJUSTMENT\"}")).andExpect(status().isOk());
        assertThat(remaining(soon)).isEqualByComparingTo("1");
        jdbc.update("UPDATE products SET low_stock_threshold = 5 WHERE id = ?", yoghurt);
        mockMvc.perform(get("/api/admin/inventory/low-stock").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[?(@.productId == %d)].stockQuantity".formatted(yoghurt)).value(4.0));
        assertLedgerHolds();
    }

    @Test
    void twentyConcurrentBuyersAcrossTwoLotsNeverGoNegative() throws Exception {
        long bread = perishable("Pain", "2000.00");
        long a = receive(bread, "3", TODAY.plusDays(1), "A");
        long b = receive(bread, "3", TODAY.plusDays(2), "B"); // 6 < the slot's 8 places: stock is the limit
        List<User> buyers = new ArrayList<>();
        List<Long> addresses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User u = customer();
            inCart(u, bread, "1");
            buyers.add(u);
            addresses.add(address(u));
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                User u = buyers.get(i);
                long address = addresses.get(i);
                Callable<Boolean> buy = () -> {
                    go.await();
                    try {
                        placement.place(u.getId(), "k-" + u.getId(), request(address));
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                };
                results.add(pool.submit(buy));
            }
            go.countDown();
            long sold = 0;
            for (Future<Boolean> f : results) if (f.get()) sold++;
            assertThat(sold).isEqualTo(6);
        }
        assertThat(stock(bread)).isEqualByComparingTo("0");
        assertThat(remaining(a)).isEqualByComparingTo("0");
        assertThat(remaining(b)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM lot_allocations WHERE product_id = ?", BigDecimal.class, bread))
                .isEqualByComparingTo("6");
        assertLedgerHolds();
    }

    // ---------- helpers ----------

    private long perishable(String name, String price) {
        Long category = categories.findBySlug("epicerie-salee").orElseThrow().getId();
        return productService.create(new ProductRequest(new ProductRequest.Name(name + " " + System.nanoTime(), null), null, null,
                null, null, category, new BigDecimal(price), null, SoldBy.UNIT, BigDecimal.ONE, 500,
                BigDecimal.ZERO, BigDecimal.ZERO, true, false, Map.of(), null)).id();
    }

    private long receive(long productId, String quantity, LocalDate expiry, String code) {
        LotView lot = inventory.receiveLot(productId, new BigDecimal(quantity), expiry, code, null, null);
        return lot.id();
    }

    /** Places an order (above the 2 000 DA minimum thanks to the prices used here) and returns its id. */
    private long order(long productId, String quantity) {
        User u = customer();
        inCart(u, productId, quantity);
        var placed = placement.place(u.getId(), "k-" + u.getId(), request(address(u)));
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, placed.order().orderNumber());
    }

    private Map<Long, BigDecimal> allocations(long orderId) {
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT lot_id, quantity FROM lot_allocations WHERE order_id = ? ORDER BY id",
                rs -> { out.put(rs.getLong(1), rs.getBigDecimal(2)); }, orderId);
        return out;
    }

    private BigDecimal remaining(long lotId) {
        return jdbc.queryForObject("SELECT quantity_remaining FROM inventory_lots WHERE id = ?", BigDecimal.class, lotId);
    }

    private ResultActions receiveApi(User u, long productId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/products/" + productId + "/lots").header("Authorization", bearer(u))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
