package com.atlasna.catalog;

import com.atlasna.catalog.inventory.StockReason;
import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.product.cache.CachedCatalog;
import com.atlasna.catalog.product.cache.CatalogCache;
import com.atlasna.catalog.product.service.PageParams;
import com.atlasna.catalog.product.service.ProductFilter;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public catalog cache on real Redis (ADR-0010): it really serves from Redis, every kind of write invalidates
 * it (admin product/category edits, stock movements incl. orders, settings), concurrent misses load once, only
 * catalog data is stored, and a dead Redis never breaks the catalog — the breaker opens and Postgres serves.
 */
@TestPropertySource(properties = "atlasna.cache.enabled=true")
class CatalogCacheTests extends OrderTestSupport {

    @Autowired StringRedisTemplate redis;
    @Autowired CatalogCache cache;
    @Autowired CachedCatalog catalog;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void emptyRedis() {
        // Ids restart at 1 in every test (TRUNCATE … RESTART IDENTITY): entries from a previous test must go.
        redis.execute(c -> {
            c.serverCommands().flushAll();
            return null;
        }, true);
        breakers.circuitBreaker("catalogCache").reset();
    }

    @Test
    void secondReadIsServedFromRedis_aStockMovementInvalidatesIt() throws Exception {
        long id = product("Couscous", "130.00", "10");
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.price").value(130.0));

        // Change the row behind the application's back: no event, so only the cache can explain the old price.
        jdbc.update("UPDATE products SET price = 150.00 WHERE id = ?", id);
        double hitsBefore = hits();
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.price").value(130.0));
        assertThat(hits()).isEqualTo(hitsBefore + 1);

        // A stock movement (here a restock by staff) publishes StockChanged → next read comes from Postgres.
        inventory.adjust(id, new BigDecimal("5"), StockReason.RESTOCK, null, null);
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.price").value(150.0));
    }

    @Test
    void adminProductEditIsVisibleImmediately_inDetailSlugAndLists() throws Exception {
        long id = product("Harissa", "180.00", "10");
        String slug = jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, id);
        mockMvc.perform(get("/api/products/by-slug/" + slug)).andExpect(jsonPath("$.price").value(180.0));
        mockMvc.perform(get("/api/products?q=harissa")).andExpect(jsonPath("$.content[0].price").value(180.0));

        User admin = user(Role.ADMIN);
        String body = mockMvc.perform(get("/api/admin/products/" + id).header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsString();
        int version = com.jayway.jsonpath.JsonPath.read(body, "$.version");
        long category = categories.findBySlug("epicerie-salee").orElseThrow().getId();
        mockMvc.perform(put("/api/admin/products/" + id).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":{"fr":"Harissa piquante"},"categoryId":%d,"price":199.00,"soldBy":"UNIT","quantityStep":1,
                                 "unitWeightGrams":500,"stockQuantity":10,"version":%d}""".formatted(category, version)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products/by-slug/" + slug)).andExpect(jsonPath("$.price").value(199.0));
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.name.fr").value("Harissa piquante"));
        mockMvc.perform(get("/api/products?q=harissa")).andExpect(jsonPath("$.content[0].price").value(199.0));
    }

    @Test
    void hidingACategoryUpdatesTheCachedTree() throws Exception {
        mockMvc.perform(get("/api/categories")).andExpect(jsonPath("$[*].children[*].slug", org.hamcrest.Matchers.hasItem("epicerie-salee")));
        var cat = categories.findBySlug("epicerie-salee").orElseThrow();
        User admin = user(Role.ADMIN);
        mockMvc.perform(put("/api/admin/categories/" + cat.getId()).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":{"fr":"%s"},"type":"%s","parentId":%d,"active":false}"""
                                .formatted(cat.getNameFr(), cat.getType(), cat.getParent().getId())))
                .andExpect(status().isOk());
        try {
            mockMvc.perform(get("/api/categories"))
                    .andExpect(jsonPath("$[*].children[*].slug", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("epicerie-salee"))));
        } finally {
            jdbc.update("UPDATE categories SET active = TRUE");
        }
    }

    @Test
    void anOrderThatEmptiesTheShelfShowsOutOfStockRightAway() throws Exception {
        long id = product("Dattes", "450.00", "5");
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.availability").value("IN_STOCK"));
        User u = customer();
        inCart(u, id, "5");
        placement.place(u.getId(), "order-1", request(address(u)));
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"));
    }

    @Test
    void settingsChangeInvalidates_maxOrderQuantityFollowsTheNewLineCap() throws Exception {
        long id = product("Lait", "120.00", "100");
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.maxOrderQuantity").value(20));
        User admin = user(Role.ADMIN);
        String body = mockMvc.perform(get("/api/admin/settings").header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsString();
        String updated = body.replace("\"maxLineQuantityUnit\":20", "\"maxLineQuantityUnit\":12");
        mockMvc.perform(put("/api/admin/settings").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.maxOrderQuantity").value(12));
    }

    @Test
    void concurrentMissesLoadOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Callable<String> read = () -> cache.get("stampede-test", Duration.ofMinutes(1), cache.type(String.class), () -> {
            loads.incrementAndGet();
            sleep(300); // a slow query: everyone else arrives while it runs
            return "fresh";
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(read));
            for (Future<String> r : results) assertThat(r.get()).isEqualTo("fresh");
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void equivalentSearchesShareOneEntry() {
        var pageable = PageParams.of(0, 20, PageParams.DEFAULT_SORT);
        assertThat(CachedCatalog.listKey(ProductFilter.publicFilter(null, null, "  Tomates ", false, false), pageable))
                .isEqualTo(CachedCatalog.listKey(ProductFilter.publicFilter(null, null, "tomates", false, false), pageable));
        assertThat(CachedCatalog.listKey(ProductFilter.publicFilter(null, null, "tomates", true, false), pageable))
                .isNotEqualTo(CachedCatalog.listKey(ProductFilter.publicFilter(null, null, "tomates", false, false), pageable));
    }

    @Test
    void onlyCatalogDataIsEverStoredInRedis() throws Exception {
        long id = product("Thé vert", "250.00", "10");
        User u = customer();
        mockMvc.perform(get("/api/categories"));
        mockMvc.perform(get("/api/products/" + id));
        mockMvc.perform(get("/api/products?q=th"));
        mockMvc.perform(put("/api/cart/items/" + id).header("Authorization", bearer(u)).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"));
        mockMvc.perform(get("/api/me/addresses").header("Authorization", bearer(u)));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"%s\",\"password\":\"x\"}".formatted(u.getEmail())));

        // Catalog entries, plus the login rate-limit buckets (hashed subjects, step 2) — nothing else.
        Set<String> keys = redis.keys("*");
        assertThat(keys).isNotEmpty().anyMatch(k -> k.startsWith(CatalogCache.PREFIX))
                .allMatch(k -> k.startsWith(CatalogCache.PREFIX) || k.startsWith("atlasna:v1:ratelimit:"))
                .noneMatch(k -> k.contains(u.getEmail()) || k.contains(u.getPhone()));
        for (String k : keys) {
            if (!k.startsWith(CatalogCache.PREFIX) || k.endsWith("generation")) continue;
            String v = redis.opsForValue().get(k);
            assertThat(v).doesNotContain(u.getEmail()).doesNotContain(u.getPhone()).doesNotContain("passwordHash");
        }
    }

    @Test
    void withRedisDownTheCatalogStillAnswers_andTheBreakerStopsCallingRedis() throws Exception {
        long id = product("Semoule", "95.00", "10");
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            double errorsBefore = count("error");
            for (int i = 0; i < 8; i++) {
                mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.price").value(95.0));
            }
            assertThat(count("error")).isGreaterThan(errorsBefore);
            assertThat(cache.breakerState()).isEqualTo("OPEN");

            // Open breaker: Redis isn't even tried, so no 250 ms timeout per request — Postgres answers at once.
            long start = System.nanoTime();
            mockMvc.perform(get("/api/products?q=semoule")).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            breakers.circuitBreaker("catalogCache").reset();
        }
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());
    }

    private double hits() {
        return count("hit");
    }

    private double count(String result) {
        var c = meters.find("atlasna.cache.requests").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
