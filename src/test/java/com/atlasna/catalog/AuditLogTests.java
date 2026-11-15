package com.atlasna.catalog;

import com.atlasna.catalog.audit.AuditEntry;
import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.ratelimit.RateLimiter;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MongoDB audit log (ADR-0012): written from application events after commit, with the actor, IP and only the
 * changed fields; no secrets; queryable by admins only; TTL retention; and a dead MongoDB never fails the business
 * operation — the failure is counted and the event is re-delivered later.
 */
class AuditLogTests extends OrderTestSupport {

    @Autowired MongoTemplate mongo;
    @Autowired MeterRegistry meters;
    @Autowired RateLimiter limiter;
    @Autowired IncompleteEventPublications incomplete;

    @BeforeEach
    void emptyLog() {
        mongo.remove(new Query(), AuditEntry.class); // keeps the indexes
        limiter.clearAll();
    }

    @Test
    void adminProductEdit_recordsOnlyTheChangedFields_withActorAndIp() throws Exception {
        long id = product("Harissa", "180.00", "10");
        User admin = user(Role.ADMIN);
        updatePrice(admin, id, "199.00");

        AuditEntry e = await(() -> find("PRODUCT_UPDATED", "PRODUCT", id));
        assertThat(e.before()).containsExactly(java.util.Map.entry("price", "180"));
        assertThat(e.after()).containsExactly(java.util.Map.entry("price", "199"));
        assertThat(e.actorId()).isEqualTo(admin.getId());
        assertThat(e.actorRole()).isEqualTo("ADMIN");
        assertThat(e.ip()).isEqualTo("127.0.0.1");
        assertThat(e.at()).isNotNull();
    }

    @Test
    void manualStockMovesAreLogged_salesAreNot() throws Exception {
        long id = product("Dattes", "450.00", "10");
        User staff = user(Role.STAFF);
        mockMvc.perform(post("/api/admin/products/" + id + "/stock-adjustments").header("Authorization", bearer(staff))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5,\"reason\":\"RESTOCK\",\"note\":\"Livraison fournisseur\"}"))
                .andExpect(status().isOk());
        AuditEntry e = await(() -> find("STOCK_ADJUSTED", "PRODUCT", id));
        assertThat(e.details()).containsEntry("delta", "5").containsEntry("reason", "RESTOCK").containsEntry("note", "Livraison fournisseur");
        assertThat(e.actorRole()).isEqualTo("STAFF");

        User u = customer();
        inCart(u, id, "5"); // 2 250 DA: above the 2 000 DA minimum
        placement.place(u.getId(), "k1", request(address(u)));
        await(() -> find("ORDER_PLACED", "ORDER", null));
        assertThat(count("STOCK_ADJUSTED")).isEqualTo(2); // OPENING (product created) + RESTOCK; the sale isn't one
    }

    @Test
    void orderLifecycleIsLogged() throws Exception {
        long id = product("Couscous", "130.00", "50");
        User u = customer();
        inCart(u, id, "20");
        var placed = placement.place(u.getId(), "k1", request(address(u)));
        User staff = user(Role.STAFF);
        mockMvc.perform(post("/api/admin/orders/" + placed.order().orderNumber() + "/transition").header("Authorization", bearer(staff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"CONFIRMED\"}")).andExpect(status().isOk());

        AuditEntry placedEntry = await(() -> find("ORDER_PLACED", "ORDER", null));
        assertThat(placedEntry.actorId()).isEqualTo(u.getId());
        assertThat(placedEntry.details()).containsEntry("orderNumber", placed.order().orderNumber());
        AuditEntry moved = await(() -> find("ORDER_STATUS_CHANGED", "ORDER", null));
        assertThat(moved.before()).containsEntry("status", "PLACED");
        assertThat(moved.after()).containsEntry("status", "CONFIRMED");
        assertThat(moved.actorRole()).isEqualTo("STAFF");
    }

    @Test
    void settingsAccountsAndFailedLoginsAreLogged_withoutSecrets() throws Exception {
        User admin = user(Role.ADMIN);
        String body = mockMvc.perform(get("/api/admin/settings").header("Authorization", bearer(admin))).andReturn().getResponse().getContentAsString();
        mockMvc.perform(put("/api/admin/settings").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"leadTimeMinutes\":90", "\"leadTimeMinutes\":120"))).andExpect(status().isOk());
        AuditEntry settingsEntry = await(() -> find("SETTINGS_UPDATED", "SETTINGS", null));
        assertThat(settingsEntry.before()).containsExactly(java.util.Map.entry("leadTimeMinutes", 90));
        assertThat(settingsEntry.after()).containsExactly(java.util.Map.entry("leadTimeMinutes", 120));

        User customer = customer();
        mockMvc.perform(patch("/api/admin/users/" + customer.getId()).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
        assertThat(await(() -> find("USER_DISABLED", "USER", customer.getId())).actorId()).isEqualTo(admin.getId());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"Someone@Test.dz\",\"password\":\"Hunter2-secret\"}")).andExpect(status().isUnauthorized());
        AuditEntry failed = await(() -> find("LOGIN_FAILED", "USER", null));
        assertThat(failed.details()).containsEntry("email", "someone@test.dz").containsEntry("reason", "INVALID_CREDENTIALS");

        // Nothing secret anywhere in the collection: no password, token or phone number.
        for (Document d : mongo.getCollection("audit_logs").find()) {
            String json = d.toJson();
            assertThat(json).doesNotContain("Hunter2").doesNotContain("passwordHash").doesNotContain(customer.getPhone())
                    .doesNotContainIgnoringCase("token");
        }
    }

    @Test
    void onlyAdminsCanReadTheLog_filtersCombine() throws Exception {
        long a = product("Thé", "250.00", "10"), b = product("Café", "420.00", "10");
        await(() -> find("PRODUCT_CREATED", "PRODUCT", b));
        User admin = user(Role.ADMIN);
        // A new product = PRODUCT_CREATED + its opening stock (STOCK_ADJUSTED, reason OPENING).
        await(() -> mongo.count(new Query(Criteria.where("entityId").is(String.valueOf(b))), AuditEntry.class) == 2 ? true : null);
        mockMvc.perform(get("/api/admin/audit-logs").param("entityType", "product").param("entityId", String.valueOf(a))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mockMvc.perform(get("/api/admin/audit-logs").param("entityType", "product").param("entityId", String.valueOf(a))
                        .param("action", "product_created").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].action").value("PRODUCT_CREATED"));
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", bearer(user(Role.STAFF)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void entriesExpireAfterTheRetentionPeriod() {
        List<Document> indexes = mongo.getCollection("audit_logs").listIndexes().into(new java.util.ArrayList<>());
        assertThat(indexes).anySatisfy(i -> {
            assertThat(i.getString("name")).isEqualTo("ttl_at");
            assertThat(((Number) i.get("expireAfterSeconds")).longValue()).isEqualTo(Duration.ofDays(365).toSeconds());
        });
    }

    @Test
    void mongoDown_theBusinessOperationStillSucceeds_theFailureIsCounted_andTheEntryIsDeliveredLater() throws Exception {
        long id = product("Semoule", "95.00", "10");
        await(() -> find("PRODUCT_CREATED", "PRODUCT", id));
        User admin = user(Role.ADMIN);
        double failuresBefore = failures();
        MONGO.getDockerClient().pauseContainerCmd(MONGO.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            updatePrice(admin, id, "99.00"); // must not wait for MongoDB
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            await(() -> failures() > failuresBefore ? Boolean.TRUE : null);
        } finally {
            MONGO.getDockerClient().unpauseContainerCmd(MONGO.getContainerId()).exec();
        }
        // The publication stayed incomplete; re-delivering it (as a restart does) leaves exactly ONE entry — even
        // when MongoDB had in fact stored the timed-out write once it resumed (the id is derived from the event).
        incomplete.resubmitIncompletePublications(p -> true);
        AuditEntry e = await(() -> find("PRODUCT_UPDATED", "PRODUCT", id));
        assertThat(e.after()).containsEntry("price", "99");
        Thread.sleep(500);
        incomplete.resubmitIncompletePublications(p -> true);
        Thread.sleep(500);
        assertThat(mongo.count(new Query(Criteria.where("action").is("PRODUCT_UPDATED").and("entityId").is(String.valueOf(id))),
                AuditEntry.class)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private void updatePrice(User admin, long id, String price) throws Exception {
        String body = mockMvc.perform(get("/api/admin/products/" + id).header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(body, "$.version");
        String name = JsonPath.read(body, "$.name.fr");
        long category = categories.findBySlug("epicerie-salee").orElseThrow().getId();
        mockMvc.perform(put("/api/admin/products/" + id).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":{"fr":"%s"},"categoryId":%d,"price":%s,"soldBy":"UNIT","quantityStep":1,
                                 "unitWeightGrams":500,"stockQuantity":%s,"lowStockThreshold":0,"version":%d}"""
                                .formatted(name, category, price, stock(id).toPlainString(), version)))
                .andExpect(status().isOk());
    }

    private AuditEntry find(String action, String entityType, Object entityId) {
        Query q = new Query(Criteria.where("action").is(action).and("entityType").is(entityType));
        if (entityId != null) q.addCriteria(Criteria.where("entityId").is(String.valueOf(entityId)));
        return mongo.findOne(q, AuditEntry.class);
    }

    private long count(String action) {
        return mongo.count(new Query(Criteria.where("action").is(action)), AuditEntry.class);
    }

    private double failures() {
        var c = meters.find("atlasna.audit.writes").tag("outcome", "failure").counter();
        return c == null ? 0 : c.count();
    }

    /** The log is written asynchronously after commit: poll for up to 10 s. */
    private static <T> T await(Supplier<T> probe) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.get();
            if (v != null) return v;
            Thread.sleep(50);
        }
        throw new AssertionError("audit entry not written within 10 s");
    }
}
