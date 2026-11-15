package com.atlasna.catalog;

import com.atlasna.catalog.order.OrderTestSupport;
import com.atlasna.catalog.ratelimit.RateLimiter;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh tokens (ADR-0009): the cookie and its flags, rotation, reuse detection revoking the whole family, logout,
 * "log out everywhere" on password change and account disable, the CSRF header, forged tokens, hashed storage,
 * CORS with credentials, and fail-closed refresh when Redis is down.
 */
class RefreshTokenTests extends OrderTestSupport {

    @Autowired StringRedisTemplate redis;
    @Autowired RateLimiter limiter;

    String email;

    @BeforeEach
    void freshCustomer() {
        limiter.clearAll();
        email = "r-" + UUID.randomUUID().toString().substring(0, 8) + "@test.dz";
    }

    @Test
    void loginSetsAHardenedHttpOnlyCookie_andRefreshRotatesIt() throws Exception {
        register();
        MockHttpServletResponse login = login().andExpect(status().isOk()).andReturn().getResponse();
        String setCookie = login.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("atlasna_refresh=").contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=2592000");
        String first = cookie(login);
        assertThat(first).hasSizeGreaterThanOrEqualTo(43); // 256 bits, base64url

        MockHttpServletResponse refreshed = refresh(first).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn().getResponse();
        String second = cookie(refreshed);
        assertThat(second).isNotEqualTo(first);

        // The new access token works.
        String access = JsonPath.read(refreshed.getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access)).andExpect(jsonPath("$.email").value(email));
        // And the chain goes on.
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void reusingAnOldTokenRevokesTheWholeFamily() throws Exception {
        register();
        String first = cookie(login().andReturn().getResponse());
        String second = cookie(refresh(first).andReturn().getResponse());

        // An attacker replays the stolen first token: refused, and the session is closed for everyone.
        MockHttpServletResponse reused = refresh(first).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED")).andReturn().getResponse();
        assertThat(reused.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0"); // cookie cleared
        refresh(second).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void otherDevicesAreNotAffectedByOneFamilysRevocation() throws Exception {
        register();
        String phone = cookie(login().andReturn().getResponse());
        String laptop = cookie(login().andReturn().getResponse());
        refresh(phone).andExpect(status().isOk());
        refresh(phone).andExpect(status().isUnauthorized()); // reuse on the phone's family
        refresh(laptop).andExpect(status().isOk()); // the laptop's session lives on
    }

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        register();
        String token = cookie(login().andReturn().getResponse());
        mockMvc.perform(post("/api/auth/logout").header("X-Atlasna-Refresh", "1").cookie(new Cookie("atlasna_refresh", token)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        // Logging out twice (or without a cookie) is fine.
        mockMvc.perform(post("/api/auth/logout").header("X-Atlasna-Refresh", "1")).andExpect(status().isNoContent());
    }

    @Test
    void changingThePasswordLogsOutEverywhere() throws Exception {
        String access = JsonPath.read(register().andReturn().getResponse().getContentAsString(), "$.accessToken");
        String a = cookie(login().andReturn().getResponse());
        String b = cookie(login().andReturn().getResponse());
        mockMvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Password123\",\"newPassword\":\"NewPassword456\"}"))
                .andExpect(status().isNoContent());
        refresh(a).andExpect(status().isUnauthorized());
        refresh(b).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingAnAccountLogsItOutEverywhere() throws Exception {
        long id = ((Number) JsonPath.read(register().andReturn().getResponse().getContentAsString(), "$.user.id")).longValue();
        String token = cookie(login().andReturn().getResponse());
        User admin = user(Role.ADMIN);
        mockMvc.perform(patch("/api/admin/users/" + id).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void theCsrfHeaderIsRequired() throws Exception {
        register();
        String token = cookie(login().andReturn().getResponse());
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("atlasna_refresh", token)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_CHECK_FAILED"));
        refresh(token).andExpect(status().isOk()); // the token wasn't consumed by the refused request
    }

    @Test
    void forgedMissingAndOversizedTokensAreRejected() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        refresh("x".repeat(5000)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").header("X-Atlasna-Refresh", "1")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyHashesAreStored() throws Exception {
        register();
        String token = cookie(login().andReturn().getResponse());
        List<String> keys = List.copyOf(redis.keys("atlasna:v1:refresh*"));
        assertThat(keys).isNotEmpty().noneMatch(k -> k.contains(token));
        for (String k : keys) {
            if (k.startsWith("atlasna:v1:refresh-user:")) continue; // a set of family ids
            assertThat(redis.opsForValue().get(k)).doesNotContain(token);
        }
    }

    @Test
    void browsersMayCallRefreshCrossOriginWithCredentials_onlyFromAllowedOrigins() throws Exception {
        mockMvc.perform(options("/api/auth/refresh").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "x-atlasna-refresh"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mockMvc.perform(options("/api/auth/refresh").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "x-atlasna-refresh"))
                .andExpect(status().isForbidden());
    }

    @Test
    void redisDown_refreshFailsClosed() throws Exception {
        register();
        String token = cookie(login().andReturn().getResponse());
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            refresh(token).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SESSION_STORE_UNAVAILABLE"));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            limiter.clearAll();
        }
    }

    // ---------- helpers ----------

    private ResultActions register() throws Exception {
        String phone = "06" + String.format("%08d", Math.abs(email.hashCode()) % 100_000_000);
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Refresh Test\",\"email\":\"%s\",\"phone\":\"%s\",\"password\":\"Password123\"}".formatted(email, phone)))
                .andExpect(status().isCreated());
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"Password123\"}".formatted(email)));
    }

    private ResultActions refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Atlasna-Refresh", "1").cookie(new Cookie("atlasna_refresh", token)));
    }

    private static String cookie(MockHttpServletResponse response) {
        Cookie c = response.getCookie("atlasna_refresh");
        assertThat(c).as("refresh cookie").isNotNull();
        return c.getValue();
    }
}
