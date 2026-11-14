package com.atlasna.catalog.order;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Placement, pricing, cart rules, cancellation, packing, lifecycle and reorder through the HTTP API. */
@RecordApplicationEvents
class OrderFlowTests extends OrderTestSupport {

    @Autowired ApplicationEvents events;

    private ResultActions place(User u, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/orders").header("Authorization", bearer(u)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions transition(User actor, String number, String to, String extra) throws Exception {
        return mockMvc.perform(post("/api/admin/orders/" + number + "/transition").header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"" + to + "\"" + extra + "}"));
    }

    private static String number(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.orderNumber");
    }

    // ---------- placement & pricing ----------

    @Test
    void placesACashOnDeliveryOrderPricedByTheServer() throws Exception {
        User u = customer();
        long oil = product("Huile", "1050.00", "10");
        long tomatoes = weighedProduct("Tomates", "80.00", "40");
        long addr = address(u);
        inCart(u, oil, "2");
        inCart(u, tomatoes, "1.5");

        ResultActions r = place(u, UUID.randomUUID().toString(), placeJson(addr))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.matchesRegex("ATL-\\d{6}-[2-9A-HJ-NP-Z]{4}")))
                .andExpect(jsonPath("$.subtotal").value(2220.00))      // 2 × 1050 + 1.5 × 80
                .andExpect(jsonPath("$.deliveryFee").value(200.00))    // Annaba centre
                .andExpect(jsonPath("$.total").value(2420.00))
                .andExpect(jsonPath("$.totalIsEstimate").value(true)) // per-kg line not weighed yet
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.delivery.slotStart").value("10:00"))
                .andExpect(jsonPath("$.delivery.zone.name.fr").value("Annaba centre"))
                .andExpect(jsonPath("$.history[0].status").value("PLACED"));

        assertThat(stock(oil)).isEqualByComparingTo("8");
        assertThat(stock(tomatoes)).isEqualByComparingTo("38.5");
        assertLedgerHolds();
        assertThat(carts.view(u.getId()).items()).isEmpty();
        assertThat(events.stream(OrderPlaced.class)).singleElement().satisfies(e -> assertThat(e.orderNumber()).isEqualTo(number(r)));
        mockMvc.perform(get("/api/orders/" + number(r)).header("Authorization", bearer(u))).andExpect(status().isOk());
        mockMvc.perform(get("/api/orders").header("Authorization", bearer(u))).andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void halfUpRoundingOnPerKgPrices() throws Exception {
        User u = customer();
        long saffron = weighedProduct("Safran", "59.99", "10");  // 59.99 × 0.5 = 29.995 → 30.00
        long filler = product("Panier", "1970.00", "10");        // keeps the order above the 2 000 DA minimum
        long addr = address(u);
        inCart(u, saffron, "0.5");
        inCart(u, filler, "1");
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[?(@.productId==" + saffron + ")].lineTotal").value(30.00))
                .andExpect(jsonPath("$.subtotal").value(2000.00));
    }

    @Test
    void minimumOrderBoundary() throws Exception {
        User u = customer();
        long p = product("Article", "1999.99", "10");
        long addr = address(u);
        inCart(u, p, "1");
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_BELOW_MINIMUM"))
                .andExpect(jsonPath("$.details[0].missing").value(0.01));
        jdbc.update("UPDATE products SET price = 2000.00 WHERE id = ?", p);   // exactly the minimum passes
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isCreated());
    }

    @Test
    void freeDeliveryThresholdBoundary() throws Exception {
        User a = customer(), b = customer();
        long under = product("Sous", "4999.99", "10"), at = product("Pile", "5000.00", "10");
        inCart(a, under, "1");
        inCart(b, at, "1");
        place(a, UUID.randomUUID().toString(), placeJson(address(a))).andExpect(jsonPath("$.deliveryFee").value(200.00));
        place(b, UUID.randomUUID().toString(), placeJson(address(b))).andExpect(jsonPath("$.deliveryFee").value(0.00))
                .andExpect(jsonPath("$.total").value(5000.00));
    }

    @Test
    void orderWeightCapBoundary() throws Exception {
        User u = customer();
        long heavy = product("Lourd", "200.00", "100", com.atlasna.catalog.product.domain.SoldBy.UNIT, "1", 5000);
        long addr = address(u);
        inCart(u, heavy, "3");   // 15 000 g = exactly the cap
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_BELOW_MINIMUM")); // 600 DA: the minimum is checked first
        jdbc.update("UPDATE products SET price = 1000.00 WHERE id = ?", heavy);
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isCreated());
        inCart(u, heavy, "4");   // 20 000 g
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_TOO_HEAVY"));
    }

    @Test
    void validationErrors() throws Exception {
        User u = customer();
        long addr = address(u);
        mockMvc.perform(post("/api/orders").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content(placeJson(addr))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['Idempotency-Key']").exists());
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
        long p = product("Article", "2500.00", "10");
        inCart(u, p, "1");
        jdbc.update("UPDATE delivery_zones SET active = FALSE WHERE slug = 'annaba-centre'");
        place(u, UUID.randomUUID().toString(), placeJson(addr)).andExpect(jsonPath("$.code").value("ZONE_NOT_SERVED"));
        jdbc.update("UPDATE delivery_zones SET active = TRUE");
        String past = placeJson(addr).replace(tomorrow().toString(), tomorrow().minusDays(3).toString());
        place(u, UUID.randomUUID().toString(), past).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE")).andExpect(jsonPath("$.details[0].reason").value("PAST_CUTOFF"));
    }

    // ---------- idempotency ----------

    @Test
    void sameKeyReturnsTheOriginalOrderAndADifferentBodyIsRefused() throws Exception {
        User u = customer();
        long p = product("Article", "2500.00", "10");
        long addr = address(u);
        inCart(u, p, "1");
        String key = UUID.randomUUID().toString();
        String first = number(place(u, key, placeJson(addr)).andExpect(status().isCreated()));
        place(u, key, placeJson(addr)).andExpect(status().isOk()).andExpect(jsonPath("$.orderNumber").value(first));
        assertThat(stock(p)).isEqualByComparingTo("9"); // stock taken once
        String other = placeJson(addr).replace("CASH_ON_DELIVERY\"", "CASH_ON_DELIVERY\",\"customerNote\":\"autre\"");
        place(u, key, other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    // ---------- cart ----------

    @Test
    void cartSnapsToStepClampsAndRefusesUnavailableProducts() throws Exception {
        User u = customer();
        long tomatoes = weighedProduct("Tomates", "80.00", "3.2");
        long gone = product("Retiré", "100.00", "10");
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", gone);
        mockMvc.perform(put("/api/cart/items/" + tomatoes).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1.7}")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(1.5));
        mockMvc.perform(put("/api/cart/items/" + tomatoes).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":9}")).andExpect(jsonPath("$.items[0].quantity").value(3.0)); // 3.2 kg left → 3.0
        mockMvc.perform(put("/api/cart/items/" + gone).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        mockMvc.perform(put("/api/cart/items/999999").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}")).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/cart/items/" + tomatoes).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":0}")).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void cartWarnsAboutPriceChangesAndBlocksOnUnavailableLines() throws Exception {
        User u = customer();
        long a = product("A", "1000.00", "10"), b = product("B", "1500.00", "10");
        inCart(u, a, "1");
        inCart(u, b, "1");
        jdbc.update("UPDATE products SET price = 1100.00 WHERE id = ?", a);
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", b);
        mockMvc.perform(get("/api/cart").header("Authorization", bearer(u)))
                .andExpect(jsonPath("$.items[?(@.productId==" + a + ")].warnings[0]").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.items[?(@.productId==" + b + ")].warnings[0]").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.subtotal").value(1100.00))
                .andExpect(jsonPath("$.issues[0].code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.checkoutReady").value(false));
    }

    @Test
    void guestCartMergeIsIdempotentAndReportsWhatItCouldNotAdd() throws Exception {
        User u = customer();
        long a = product("A", "1000.00", "5"), gone = product("Retiré", "100.00", "10");
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", gone);
        String body = "{\"items\":[{\"productId\":%d,\"quantity\":8},{\"productId\":%d,\"quantity\":1}]}".formatted(a, gone);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/cart/merge").header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cart.items", hasSize(1)))
                    .andExpect(jsonPath("$.cart.items[0].quantity").value(5.0))   // clamped to stock, never summed
                    .andExpect(jsonPath("$.notes[?(@.productId==" + gone + ")].reason").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.notes[?(@.productId==" + a + ")].reason").value("QUANTITY_REDUCED"));
        }
    }

    // ---------- cancellation ----------

    @Test
    void customerCancelRestoresStockReleasesTheSlotAndCannotCancelTwice() throws Exception {
        User u = customer();
        long p = product("Article", "2500.00", "10");
        inCart(u, p, "2");
        String n = number(place(u, UUID.randomUUID().toString(), placeJson(address(u))));
        assertThat(jdbc.queryForObject("SELECT booked FROM slot_usage", Integer.class)).isEqualTo(1);

        mockMvc.perform(post("/api/orders/" + n + "/cancel").header("Authorization", bearer(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("VOID"));
        assertThat(stock(p)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT booked FROM slot_usage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE reason = 'CANCEL_RESTORE'", Integer.class)).isEqualTo(1);
        assertLedgerHolds();
        mockMvc.perform(post("/api/orders/" + n + "/cancel").header("Authorization", bearer(u)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void customerCannotCancelOncePreparingAndStaffMustGiveAReason() throws Exception {
        User u = customer(), staff = user(Role.STAFF);
        long p = product("Article", "2500.00", "10");
        inCart(u, p, "1");
        String n = number(place(u, UUID.randomUUID().toString(), placeJson(address(u))));
        transition(staff, n, "CONFIRMED", "").andExpect(status().isOk());
        transition(staff, n, "PREPARING", "").andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/" + n + "/cancel").header("Authorization", bearer(u)))
                .andExpect(status().isConflict());
        transition(staff, n, "CANCELLED", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").exists());
        transition(staff, n, "CANCELLED", ",\"reason\":\"Client injoignable\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledReason").value("Client injoignable"));
        assertThat(stock(p)).isEqualByComparingTo("10");
    }

    // ---------- packing, weighing, full lifecycle ----------

    @Test
    void fullLifecycleWithWeighingAndCashOnDelivery() throws Exception {
        User u = customer(), staff = user(Role.STAFF), driver = user(Role.DRIVER);
        long tomatoes = weighedProduct("Tomates", "80.00", "40");
        long oil = product("Huile", "1900.00", "10");
        inCart(u, tomatoes, "1.5");
        inCart(u, oil, "1");
        String n = number(place(u, UUID.randomUUID().toString(), placeJson(address(u))));  // 120 + 1900 + 200 = 2220
        transition(staff, n, "CONFIRMED", "").andExpect(status().isOk());
        transition(staff, n, "PREPARING", "").andExpect(status().isOk());
        transition(staff, n, "READY_FOR_DELIVERY", "").andExpect(status().isConflict()); // tomatoes not weighed yet

        long tomatoLine = jdbc.queryForObject("SELECT id FROM order_items WHERE product_id = ?", Long.class, tomatoes);
        mockMvc.perform(patch("/api/admin/orders/" + n + "/items/" + tomatoLine).header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantityFulfilled\":1.6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.totalIsEstimate").value(false))
                .andExpect(jsonPath("$.order.subtotal").value(2028.00))   // 1.6 × 80 = 128
                .andExpect(jsonPath("$.order.total").value(2228.00))
                .andExpect(jsonPath("$.totalChangeFlagged").value(false));
        assertThat(stock(tomatoes)).isEqualByComparingTo("38.4"); // 0.1 kg more taken at the scale
        assertLedgerHolds();

        transition(staff, n, "READY_FOR_DELIVERY", "").andExpect(status().isOk());
        transition(staff, n, "OUT_FOR_DELIVERY", "").andExpect(status().isConflict());    // staff can't drive
        mockMvc.perform(post("/api/driver/orders/" + n + "/start").header("Authorization", bearer(driver)))
                .andExpect(status().isNotFound());                                       // not assigned yet
        mockMvc.perform(post("/api/admin/orders/" + n + "/assign-driver").header("Authorization", bearer(staff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + driver.getId() + "}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/driver/orders").header("Authorization", bearer(driver))).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(post("/api/driver/orders/" + n + "/start").header("Authorization", bearer(driver)))
                .andExpect(jsonPath("$.order.status").value("OUT_FOR_DELIVERY"));
        mockMvc.perform(post("/api/driver/orders/" + n + "/deliver").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cashCollected\":2000}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.note").exists()); // differs: explain
        mockMvc.perform(post("/api/driver/orders/" + n + "/deliver").header("Authorization", bearer(driver))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cashCollected\":2228}"))
                .andExpect(jsonPath("$.order.status").value("DELIVERED"))
                .andExpect(jsonPath("$.order.paymentStatus").value("COLLECTED"))
                .andExpect(jsonPath("$.cashCollected").value(2228.00));
        mockMvc.perform(get("/api/orders/" + n).header("Authorization", bearer(u)))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.history", hasSize(6)));
    }

    @Test
    void weighingBeyondTheToleranceFlagsTheOrderAndOutOfStockLinesGiveStockBack() throws Exception {
        User u = customer(), staff = user(Role.STAFF);
        long tomatoes = weighedProduct("Tomates", "100.00", "40");
        long rice = product("Riz", "1000.00", "10");
        inCart(u, tomatoes, "10");
        inCart(u, rice, "1");
        String n = number(place(u, UUID.randomUUID().toString(), placeJson(address(u)))); // 1000 + 1000 + 200 = 2200
        transition(staff, n, "CONFIRMED", "");
        transition(staff, n, "PREPARING", "");
        long riceLine = jdbc.queryForObject("SELECT id FROM order_items WHERE product_id = ?", Long.class, rice);
        mockMvc.perform(patch("/api/admin/orders/" + n + "/items/" + riceLine).header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OUT_OF_STOCK\"}"))
                .andExpect(jsonPath("$.order.total").value(1200.00))
                .andExpect(jsonPath("$.totalChangeFlagged").value(true)); // −45 % > 20 %
        assertThat(stock(rice)).isEqualByComparingTo("10");
        assertLedgerHolds();
    }

    @Test
    void failedDeliveryCanBeRetriedAndRepeatFailuresFlagTheCustomer() throws Exception {
        User u = customer(), staff = user(Role.STAFF), driver = user(Role.DRIVER);
        long p = product("Article", "2500.00", "50");
        long addr = address(u);
        for (int attempt = 0; attempt < 3; attempt++) {
            inCart(u, p, "1");
            String n = number(place(u, UUID.randomUUID().toString(), placeJson(addr)));
            transition(staff, n, "CONFIRMED", "");
            transition(staff, n, "PREPARING", "");
            transition(staff, n, "READY_FOR_DELIVERY", "");
            mockMvc.perform(post("/api/admin/orders/" + n + "/assign-driver").header("Authorization", bearer(staff))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":" + driver.getId() + "}"));
            mockMvc.perform(post("/api/driver/orders/" + n + "/start").header("Authorization", bearer(driver)));
            mockMvc.perform(post("/api/driver/orders/" + n + "/fail").header("Authorization", bearer(driver))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Pas de réponse\"}"))
                    .andExpect(jsonPath("$.order.status").value("DELIVERY_FAILED"))
                    .andExpect(jsonPath("$.order.paymentStatus").value("NOT_COLLECTED"))
                    .andExpect(jsonPath("$.customerFlagged").value(attempt == 2));
            if (attempt == 0) {
                transition(staff, n, "READY_FOR_DELIVERY", "").andExpect(jsonPath("$.order.paymentStatus").value("PENDING"));
                transition(staff, n, "CANCELLED", ",\"reason\":\"Abandon\"").andExpect(status().isOk());
            }
        }
    }

    @Test
    void reorderRebuildsTheCartWithWarnings() throws Exception {
        User u = customer();
        long a = product("A", "1500.00", "10"), b = product("B", "1000.00", "10");
        inCart(u, a, "1");
        inCart(u, b, "2");
        String n = number(place(u, UUID.randomUUID().toString(), placeJson(address(u))));
        jdbc.update("UPDATE products SET price = 1600.00 WHERE id = ?", a);
        jdbc.update("UPDATE products SET active = FALSE WHERE id = ?", b);
        mockMvc.perform(post("/api/orders/" + n + "/reorder").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.items", hasSize(1)))
                .andExpect(jsonPath("$.cart.items[0].warnings[0]").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.notes[0].productId").value(b))
                .andExpect(jsonPath("$.notes[0].reason").value("UNAVAILABLE"));
    }

    @Test
    void stockAdjustmentsKeepTheLedgerAndStaffCannotWriteOffExpiredStock() throws Exception {
        User admin = user(Role.ADMIN), staff = user(Role.STAFF);
        long p = product("Article", "100.00", "5");
        mockMvc.perform(post("/api/admin/products/" + p + "/stock-adjustments").header("Authorization", bearer(staff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":10,\"reason\":\"RESTOCK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(15.0));
        mockMvc.perform(post("/api/admin/products/" + p + "/stock-adjustments").header("Authorization", bearer(staff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1,\"reason\":\"EXPIRED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/products/" + p + "/stock-adjustments").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-20,\"reason\":\"ADJUSTMENT\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mockMvc.perform(get("/api/admin/products/" + p + "/stock-movements").header("Authorization", bearer(staff)))
                .andExpect(jsonPath("$.content[0].reason").value("RESTOCK"))
                .andExpect(jsonPath("$.content[1].reason").value("OPENING"));
        assertLedgerHolds();
    }

    @Test
    void adminCreatesStaffWithATemporaryPasswordThatMustBeChanged() throws Exception {
        User admin = user(Role.ADMIN);
        ResultActions r = mockMvc.perform(post("/api/admin/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Karim\",\"email\":\"karim@atlasna.dz\",\"role\":\"DRIVER\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.mustChangePassword").value(true));
        String temp = JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.temporaryPassword");
        mockMvc.perform(post("/api/admin/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"X\",\"email\":\"x@atlasna.dz\",\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
        String token = JsonPath.read(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"karim@atlasna.dz\",\"password\":\"" + temp + "\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        mockMvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"NouveauMotDePasse1\"}")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void publicSlotsShowRemainingPlacesAndReasons() throws Exception {
        long zone = zoneId("annaba-centre");
        jdbc.update("INSERT INTO slot_usage (slot_id, slot_date, booked) VALUES (?, ?, 8)", firstSlotId(), tomorrow());
        mockMvc.perform(get("/api/delivery/slots?zoneId=" + zone + "&date=" + tomorrow()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))   // three rounds a day (V4)
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].reason").value("FULL"))
                .andExpect(jsonPath("$[0].remaining").value(0))
                .andExpect(jsonPath("$[1].available").value(true))
                .andExpect(jsonPath("$[1].start").value("14:00"))
                .andExpect(jsonPath("$[1].remaining").value(8));
        mockMvc.perform(get("/api/delivery/zones")).andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].minOrderAmount").value(2000.00));
    }

    @Test
    void migrationFromThePhase1DatabaseWritesOpeningBalances() {
        // Upgrade path: V2 database with a product that has stock → V3 adds the ledger opening entry.
        org.flywaydb.core.Flyway v2 = org.flywaydb.core.Flyway.configure()
                .dataSource(jdbc.getDataSource()).schemas("phase1_upgrade").createSchemas(true).target("2").cleanDisabled(false).load();
        v2.clean();
        v2.migrate();
        jdbc.update("INSERT INTO phase1_upgrade.products (name_fr, slug, sku, category_id, price, sold_by, quantity_step, unit_weight_grams, "
                + "stock_quantity, low_stock_threshold, perishable, featured, attributes, active, version, created_at, updated_at) "
                + "SELECT 'Test', 'test', 'GRC-9999', id, 100, 'UNIT', 1, 500, 7.5, 0, false, false, '{}'::jsonb, true, 0, now(), now() "
                + "FROM phase1_upgrade.categories WHERE slug = 'boissons'");
        org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas("phase1_upgrade").load().migrate();
        assertThat(jdbc.queryForObject("SELECT delta FROM phase1_upgrade.stock_movements WHERE reason = 'OPENING'", BigDecimal.class))
                .isEqualByComparingTo("7.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phase1_upgrade.store_settings", Integer.class)).isEqualTo(1);
        jdbc.execute("DROP SCHEMA phase1_upgrade CASCADE");
    }
}
