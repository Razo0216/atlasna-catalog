package com.atlasna.catalog.order;

import com.atlasna.catalog.PostgresIntegrationTest;
import com.atlasna.catalog.delivery.AddressService;
import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.order.domain.PaymentMethod;
import com.atlasna.catalog.order.service.CartService;
import com.atlasna.catalog.order.service.OrderPlacementService;
import com.atlasna.catalog.order.service.PlaceOrderRequest;
import com.atlasna.catalog.product.domain.CategoryRepository;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.service.ProductService;
import com.atlasna.catalog.store.StoreSettings;
import com.atlasna.catalog.store.StoreSettingsService;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import com.atlasna.catalog.user.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared set-up for Phase 3 tests: a clean database, customers with tokens, products with stock, addresses, carts. */
@AutoConfigureMockMvc
public abstract class OrderTestSupport extends PostgresIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtService jwtService;
    @Autowired protected ProductService productService;
    @Autowired protected CategoryRepository categories;
    @Autowired protected AddressService addresses;
    @Autowired protected CartService carts;
    @Autowired protected OrderPlacementService placement;
    @Autowired protected InventoryService inventory;
    @Autowired protected StoreSettingsService settings;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        // Orders reference users and products without cascade; truncate everything this phase writes, then
        // restore the reference data (zones, slots, settings) to the V3 seed so tests never leak into each other.
        jdbc.execute("TRUNCATE users, products, slot_usage, event_publication RESTART IDENTITY CASCADE");
        jdbc.execute("""
                UPDATE delivery_zones SET active = TRUE, min_order_amount = 2000, free_delivery_threshold = 5000,
                  fee = CASE slug WHEN 'annaba-centre' THEN 200 WHEN 'el-bouni' THEN 250 ELSE 300 END""");
        jdbc.execute("UPDATE delivery_slots SET active = start_time IN ('10:00', '14:00', '18:00'), capacity = 8, zone_id = NULL, day_of_week = NULL");
        jdbc.execute("""
                UPDATE store_settings SET store_open = TRUE, opening_time = '09:00', closing_time = '21:00',
                  same_day_cutoff = '19:00', lead_time_minutes = 90, max_days_ahead = 3, max_item_weight_grams = 5000,
                  max_order_weight_grams = 15000, max_line_quantity_unit = 20, max_line_quantity_weight = 10,
                  weight_tolerance_percent = 20, failed_delivery_flag_count = 3, failed_delivery_window_days = 30""");
        settings.evict();
    }

    // ---------- people ----------

    protected User user(Role role) {
        int n = SEQ.incrementAndGet();
        return users.save(User.builder().fullName(role.name().toLowerCase() + " " + n).email(role.name().toLowerCase() + n + "@test.dz")
                .phone(role == Role.CUSTOMER ? String.format("+21355%07d", n) : null)
                .passwordHash(passwordEncoder.encode("irrelevant-password")).role(role).build());
    }

    protected User customer() {
        return user(Role.CUSTOMER);
    }

    protected String bearer(User u) {
        return "Bearer " + jwtService.generateToken(u);
    }

    // ---------- catalog ----------

    protected long product(String name, String price, String stock) {
        return product(name, price, stock, SoldBy.UNIT, "1", 500);
    }

    protected long weighedProduct(String name, String pricePerKg, String stockKg) {
        return product(name, pricePerKg, stockKg, SoldBy.WEIGHT, "0.5", 1000);
    }

    protected long product(String name, String price, String stock, SoldBy soldBy, String step, int weightGrams) {
        Long category = categories.findBySlug("epicerie-salee").orElseThrow().getId();
        return productService.create(new ProductRequest(new ProductRequest.Name(name + " " + SEQ.incrementAndGet(), null), null, null,
                null, null, category, new BigDecimal(price), null, soldBy, new BigDecimal(step), weightGrams,
                new BigDecimal(stock), BigDecimal.ZERO, false, false, Map.of(), null)).id();
    }

    protected BigDecimal stock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    protected void assertLedgerHolds() {
        assertThat(inventory.ledgerMismatches()).as("stock_quantity = SUM(stock_movements.delta) for every product").isEmpty();
    }

    // ---------- delivery & cart ----------

    protected long zoneId(String slug) {
        return jdbc.queryForObject("SELECT id FROM delivery_zones WHERE slug = ?", Long.class, slug);
    }

    protected long firstSlotId() {
        return jdbc.queryForObject("SELECT id FROM delivery_slots ORDER BY start_time LIMIT 1", Long.class);
    }

    protected LocalDate tomorrow() {
        return ZonedDateTime.now(StoreSettings.STORE_ZONE).toLocalDate().plusDays(1);
    }

    protected long address(User u) {
        return address(u, "annaba-centre");
    }

    protected long address(User u, String zoneSlug) {
        return addresses.create(u.getId(), new AddressService.AddressData("Maison", u.getFullName(), u.getPhone() == null ? "0661000000" : u.getPhone(),
                zoneId(zoneSlug), "12 rue Larbi Ben M'hidi", "En face de la pharmacie", null, null, null)).id();
    }

    protected void inCart(User u, long productId, String quantity) {
        carts.setItem(u.getId(), productId, new BigDecimal(quantity), null);
    }

    protected PlaceOrderRequest request(long addressId) {
        return new PlaceOrderRequest(addressId, firstSlotId(), tomorrow(), PaymentMethod.CASH_ON_DELIVERY, null, null);
    }

    protected String placeJson(long addressId) {
        return """
                {"addressId":%d,"slotId":%d,"slotDate":"%s","paymentMethod":"CASH_ON_DELIVERY"}""".formatted(addressId, firstSlotId(), tomorrow());
    }
}
