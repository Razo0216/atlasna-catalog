package com.atlasna.catalog;

import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 dashboard and reports: two orders on one delivery day, one delivered with cash, one still waiting — every
 * number is checked against what really happened. ADMIN only.
 */
class ReportsTests extends OrderTestSupport {

    @Test
    void dashboardAndReportsReflectADeliveredOrder() throws Exception {
        User admin = user(Role.ADMIN), driver = user(Role.DRIVER);
        long oil = product("Huile", "1000.00", "50");
        String delivered = order(oil, "3"); // 3000 + fee
        String waiting = order(oil, "2");
        deliver(admin, driver, delivered);
        String day = tomorrow().toString();

        BigDecimal total1 = jdbc.queryForObject("SELECT total FROM orders WHERE order_number = ?", BigDecimal.class, delivered);
        BigDecimal total2 = jdbc.queryForObject("SELECT total FROM orders WHERE order_number = ?", BigDecimal.class, waiting);

        mockMvc.perform(get("/api/admin/dashboard?date=" + day).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersByStatus.DELIVERED").value(1))
                .andExpect(jsonPath("$.ordersByStatus.PLACED").value(1))
                .andExpect(jsonPath("$.revenue.expected").value(total1.add(total2).doubleValue()))
                .andExpect(jsonPath("$.revenue.collected").value(total1.doubleValue()))
                .andExpect(jsonPath("$.toConfirm").value(1))
                .andExpect(jsonPath("$.slots[0].booked").value(2))
                .andExpect(jsonPath("$.slots[0].capacity").isNumber());

        mockMvc.perform(get("/api/admin/reports/sales?from=" + day + "&to=" + day).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orders").value(2))
                .andExpect(jsonPath("$[0].delivered").value(1))
                .andExpect(jsonPath("$[0].collected").value(total1.doubleValue()));

        mockMvc.perform(get("/api/admin/reports/top-products?from=" + day + "&to=" + day).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].productId").value(oil))
                .andExpect(jsonPath("$[0].quantity").value(3.0)) // only the delivered order counts
                .andExpect(jsonPath("$[0].revenue").value(3000.0));

        mockMvc.perform(get("/api/admin/reports/by-category?from=" + day + "&to=" + day).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].revenue").value(3000.0));
        mockMvc.perform(get("/api/admin/reports/by-zone?from=" + day + "&to=" + day).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].orders").value(1))
                .andExpect(jsonPath("$[0].revenue").value(total1.doubleValue()));
        mockMvc.perform(get("/api/admin/reports/cash-by-driver?from=" + day + "&to=" + day).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].driverId").value(driver.getId()))
                .andExpect(jsonPath("$[0].deliveries").value(1))
                .andExpect(jsonPath("$[0].cashCollected").value(total1.doubleValue()));
        // The driver's own end-of-day summary: the cash they hand over. Another driver sees nothing of it.
        mockMvc.perform(get("/api/driver/orders/summary?date=" + day).header("Authorization", bearer(driver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.cashCollected").value(total1.doubleValue()))
                .andExpect(jsonPath("$.orders.length()").value(1));
        mockMvc.perform(get("/api/driver/orders/summary?date=" + day).header("Authorization", bearer(user(Role.DRIVER))))
                .andExpect(jsonPath("$.delivered").value(0)).andExpect(jsonPath("$.cashCollected").value(0));
        mockMvc.perform(get("/api/admin/reports/low-stock").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/reports/expiring?days=7").header("Authorization", bearer(admin))).andExpect(status().isOk());
    }

    @Test
    void rangesAreValidated_andDefaultToTheLast30Days() throws Exception {
        User admin = user(Role.ADMIN);
        mockMvc.perform(get("/api/admin/reports/sales?from=2026-11-10&to=2026-11-01").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.to").exists());
        mockMvc.perform(get("/api/admin/reports/sales?from=2024-01-01&to=2026-01-01").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/reports/top-products?limit=500").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/reports/sales").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/dashboard").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").isString());
    }

    @Test
    void reportsAreForTheOwnerOnly() throws Exception {
        for (Role role : new Role[] {Role.STAFF, Role.DRIVER, Role.CUSTOMER}) {
            User u = user(role);
            for (String path : new String[] {"/api/admin/dashboard", "/api/admin/reports/sales", "/api/admin/reports/cash-by-driver"}) {
                mockMvc.perform(get(path).header("Authorization", bearer(u))).andExpect(status().isForbidden());
            }
        }
        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private String order(long productId, String quantity) {
        User c = customer();
        inCart(c, productId, quantity);
        return placement.place(c.getId(), UUID.randomUUID().toString(), request(address(c))).order().orderNumber();
    }

    private void deliver(User admin, User driver, String n) throws Exception {
        for (String to : new String[] {"CONFIRMED", "PREPARING", "READY_FOR_DELIVERY"}) move(admin, n, "{\"to\":\"" + to + "\"}");
        mockMvc.perform(post("/api/admin/orders/" + n + "/assign-driver").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + driver.getId() + "}")).andExpect(status().isOk());
        move(admin, n, "{\"to\":\"OUT_FOR_DELIVERY\"}");
        BigDecimal total = jdbc.queryForObject("SELECT total FROM orders WHERE order_number = ?", BigDecimal.class, n);
        // The driver collects the cash (recorded as collected_by = driver).
        mockMvc.perform(post("/api/driver/orders/" + n + "/deliver").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cashCollected\":" + total + "}")).andExpect(status().isOk());
    }

    private void move(User admin, String n, String body) throws Exception {
        mockMvc.perform(post("/api/admin/orders/" + n + "/transition").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
