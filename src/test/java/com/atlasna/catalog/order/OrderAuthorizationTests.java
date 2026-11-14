package com.atlasna.catalog.order;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The authorization matrix over EVERY Phase 3 route × anonymous / customer / other customer / staff / driver / admin.
 * Expected: anonymous → 401; wrong role → 403; someone else's order or address → 404 (not 403: ids can't be probed).
 */
class OrderAuthorizationTests extends OrderTestSupport {

    private User owner, other, staff, driver, otherDriver, admin;
    private String orderNumber;
    private long addressId, productId;

    @BeforeEach
    void anOrderAndItsPeople() throws Exception {
        owner = customer();
        other = customer();
        staff = user(Role.STAFF);
        driver = user(Role.DRIVER);
        otherDriver = user(Role.DRIVER);
        admin = user(Role.ADMIN);
        productId = product("Article", "2500.00", "50");
        addressId = address(owner);
        inCart(owner, productId, "1");
        orderNumber = placement.place(owner.getId(), UUID.randomUUID().toString(), request(addressId)).order().orderNumber();
        jdbc.update("UPDATE orders SET assigned_driver_id = ?", driver.getId());
    }

    private record Route(HttpMethod method, String path, String body) {}

    private int status(Route r, User who) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(r.method(), r.path());
        if (who != null) req.header("Authorization", bearer(who));
        if (r.body() != null) req.contentType(MediaType.APPLICATION_JSON).content(r.body());
        if (r.path().equals("/api/orders") && r.method() == HttpMethod.POST) req.header("Idempotency-Key", UUID.randomUUID().toString());
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void everyRouteRefusesTheWrongCaller() throws Exception {
        String o = orderNumber;
        Map<Route, int[]> matrix = new LinkedHashMap<>();
        // expected status per caller:                 anon, owner, other, staff, driver, admin   (0 = any 2xx/4xx business answer)
        matrix.put(new Route(HttpMethod.GET, "/api/cart", null),                                    new int[] {401, 200, 200, 403, 403, 403});
        matrix.put(new Route(HttpMethod.PUT, "/api/cart/items/" + productId, "{\"quantity\":1}"),    new int[] {401, 200, 200, 403, 403, 403});
        matrix.put(new Route(HttpMethod.POST, "/api/cart/merge", "{\"items\":[]}"),                   new int[] {401, 200, 200, 403, 403, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/me/addresses", null),                             new int[] {401, 200, 200, 403, 403, 403});
        matrix.put(new Route(HttpMethod.PUT, "/api/me/addresses/" + addressId,
                "{\"recipientName\":\"X\",\"phone\":\"0551000000\",\"zoneId\":" + zoneId("el-bouni") + ",\"street\":\"s\",\"landmark\":\"l\"}"),
                                                                                                     new int[] {401, 200, 404, 403, 403, 403});
        matrix.put(new Route(HttpMethod.DELETE, "/api/me/addresses/" + addressId + "0", null),       new int[] {401, 404, 404, 403, 403, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/orders", null),                                   new int[] {401, 200, 200, 403, 403, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/orders/" + o, null),                              new int[] {401, 200, 404, 403, 403, 403});
        matrix.put(new Route(HttpMethod.POST, "/api/orders/" + o + "/reorder", null),                new int[] {401, 200, 404, 403, 403, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/orders", null),                             new int[] {401, 403, 403, 200, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/orders/" + o, null),                        new int[] {401, 403, 403, 200, 403, 200});
        matrix.put(new Route(HttpMethod.POST, "/api/admin/orders/" + o + "/assign-driver",
                "{\"driverId\":" + 999999 + "}"),                                                     new int[] {401, 403, 403, 400, 403, 400});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/products/" + productId + "/stock-movements", null),
                                                                                                     new int[] {401, 403, 403, 200, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/settings", null),                           new int[] {401, 403, 403, 403, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/delivery/zones", null),                     new int[] {401, 403, 403, 403, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/delivery/slots", null),                     new int[] {401, 403, 403, 403, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/admin/users?role=DRIVER", null),                  new int[] {401, 403, 403, 403, 403, 200});
        matrix.put(new Route(HttpMethod.GET, "/api/driver/orders", null),                            new int[] {401, 403, 403, 403, 200, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/driver/orders/" + o, null),                       new int[] {401, 403, 403, 403, 200, 403});
        matrix.put(new Route(HttpMethod.GET, "/api/delivery/zones", null),                           new int[] {200, 200, 200, 200, 200, 200});

        User[] callers = {null, owner, other, staff, driver, admin};
        String[] names = {"anonymous", "owner", "other customer", "staff", "driver", "admin"};
        List<String> mismatches = new ArrayList<>();
        for (var e : matrix.entrySet()) {
            for (int i = 0; i < callers.length; i++) {
                int actual = status(e.getKey(), callers[i]);
                if (actual != e.getValue()[i]) {
                    mismatches.add(e.getKey().method() + " " + e.getKey().path() + " as " + names[i] + ": expected "
                            + e.getValue()[i] + ", got " + actual);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void anotherDriverSeesNothingAndCustomersCannotTouchTheBoard() throws Exception {
        assertThat(status(new Route(HttpMethod.GET, "/api/driver/orders/" + orderNumber, null), otherDriver)).isEqualTo(404);
        assertThat(status(new Route(HttpMethod.POST, "/api/driver/orders/" + orderNumber + "/start", null), otherDriver)).isEqualTo(404);
        assertThat(status(new Route(HttpMethod.POST, "/api/orders/" + orderNumber + "/cancel", null), other)).isEqualTo(404);
        assertThat(status(new Route(HttpMethod.POST, "/api/admin/orders/" + orderNumber + "/transition", "{\"to\":\"CONFIRMED\"}"), owner))
                .isEqualTo(403);
        String body = mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.GET, "/api/driver/orders").header("Authorization", bearer(otherDriver)))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$")).isEmpty();
    }
}
