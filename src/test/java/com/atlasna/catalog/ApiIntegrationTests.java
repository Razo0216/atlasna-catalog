package com.atlasna.catalog;

import com.atlasna.catalog.product.seed.DataSeeder;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.user.security.LoginRateLimiter;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the HTTP API: the full Spring context (security filters, controllers, services,
 * Flyway-built PostgreSQL in a Testcontainers container) driven through MockMvc, with no real server.
 *
 * <p>Each test starts from a clean state: all users and products are deleted, one ADMIN is created,
 * and the login rate limiter is cleared. Covers registration, login, 401 vs 403 rules,
 * bad or expired tokens, email case handling, error mapping and rate limits. Catalog behaviour is in CatalogApiTests.
 */
@AutoConfigureMockMvc
class ApiIntegrationTests extends PostgresIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "AdminPass123";
    /** Any body: these tests only care that the request is refused before validation. */
    private static final String PRODUCT_JSON = "{}";
    private static final String ADMIN_PRODUCTS = "/api/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ApplicationContext applicationContext;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Value("${atlasna.jwt.secret}") String jwtSecret;

    @BeforeEach
    void resetData() {
        loginRateLimiter.clear();
        productRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .fullName("Test Admin").email(ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(Role.ADMIN).build());
    }

    @Test
    void registerReturnsTokenAndCustomerRole() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("jane@example.com"))
                .andExpect(jsonPath("$.user.role").value("CUSTOMER"));

        User saved = userRepository.findByEmail("jane@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("SuperSecret1");
    }

    @Test
    void registerRejectsInvalidInputWithFieldErrors() throws Exception {
        register("", "not-an-email", "short", "12345")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Full name is required"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email must be valid"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be at least 8 characters"))
                .andExpect(jsonPath("$.fieldErrors.phone").value("Phone must be an Algerian mobile number (05, 06 or 07)"));
    }

    @Test
    void registerRequiresPhone() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Jane\",\"email\":\"jane@example.com\",\"password\":\"SuperSecret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phone").value("Phone is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0551234567", "0551 23 45 67", "+213551234567", "00213-551-234-567", "(0551) 23.45.67"})
    void registerNormalisesPhoneVariants(String phone) throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1", phone)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.phone").value("+213551234567"));
        assertThat(userRepository.findByEmail("jane@example.com").orElseThrow().getPhone()).isEqualTo("+213551234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0451234567", "055123456", "05512345678", "+33612345678", "phone"})
    void registerRejectsNonAlgerianMobiles(String phone) throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1", phone)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
        assertThat(userRepository.findByEmail("jane@example.com")).isEmpty();
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1", "0551234567").andExpect(status().isCreated());
        register("Jane Again", "jane@example.com", "SuperSecret1", "0661234567")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_IN_USE"));
    }

    @Test
    void registerRejectsDuplicatePhoneInAnySpelling() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1", "0551234567").andExpect(status().isCreated());
        register("Sara", "sara@example.com", "SuperSecret1", "+213 551 23 45 67")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_IN_USE"));
        assertThat(userRepository.findByEmail("sara@example.com")).isEmpty();
    }

    @Test
    void concurrentRegistrationsWithTheSamePhoneGiveOneCreatedAndOneConflict() throws Exception {
        int attempts = 2;
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String email = "racer" + i + "@example.com";
                results.add(pool.submit(() -> {
                    start.await();
                    return register("Racer", email, "SuperSecret1", "0771234567").andReturn();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> r : results) {
                MvcResult result = r.get(30, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    assertThat(result.getResponse().getContentAsString()).contains("\"PHONE_ALREADY_IN_USE\"");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(userRepository.existsByPhone("+213771234567")).isTrue();
    }

    @Test
    void meReturnsTheCurrentUserIncludingPhone() throws Exception {
        String token = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1", "0551234567"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.phone").value("+213551234567"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void meReflectsARoleChangeWithoutANewToken() throws Exception {
        String token = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1", "0551234567"));
        User jane = userRepository.findByEmail("jane@example.com").orElseThrow();
        jane.setRole(Role.ADMIN);
        userRepository.save(jane);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void meWithoutTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        login(ADMIN_EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void loginWithUnknownEmailLooksLikeAWrongPassword() throws Exception {
        login("nobody@example.com", "whatever-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void disabledAccountIsReportedOnlyAfterTheRightPassword() throws Exception {
        userRepository.save(User.builder().fullName("Off").email("off@example.com")
                .passwordHash(passwordEncoder.encode("RightPassword1")).role(Role.CUSTOMER).enabled(false).build());

        login("off@example.com", "RightPassword1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        // A wrong password must not reveal that the account exists and is disabled.
        login("off@example.com", "WrongPassword1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void tokenStopsWorkingOnceTheAccountIsDisabled() throws Exception {
        String token = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1", "0551234567"));
        User jane = userRepository.findByEmail("jane@example.com").orElseThrow();
        jane.setEnabled(false);
        userRepository.save(jane);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsTheDevFrontendWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mockMvc.perform(options("/api/products")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenOnProtectedEndpointIsUnauthorizedWithJsonBody() throws Exception {
        mockMvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void malformedTokenDoesNotBreakPublicEndpoints() throws Exception {
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isOk());
        mockMvc.perform(post(ADMIN_PRODUCTS).header("Authorization", "Bearer not.a.jwt")
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsTreatedAsAnonymous() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = Jwts.builder()
                .subject(ADMIN_EMAIL)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());
        mockMvc.perform(post(ADMIN_PRODUCTS).header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isUnauthorized());
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void tokenForDeletedUserIsTreatedAsAnonymous() throws Exception {
        String token = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1"));
        userRepository.delete(userRepository.findByEmail("jane@example.com").orElseThrow());

        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void mixedCaseEmailCanLogInExactlyAsRegistered() throws Exception {
        register("Jane", "Jane@Example.com", "SuperSecret1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("jane@example.com"));

        login("Jane@Example.com", "SuperSecret1").andExpect(status().isOk());
        login("jane@example.com", "SuperSecret1").andExpect(status().isOk());
        login("JANE@EXAMPLE.COM", "SuperSecret1").andExpect(status().isOk());
    }

    @Test
    void duplicateEmailInDifferentCaseIsConflictNotServerError() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1").andExpect(status().isCreated());
        register("Jane Again", "JANE@example.com", "SuperSecret1").andExpect(status().isConflict());
        assertThat(userRepository.count()).isEqualTo(2); // admin + jane
    }

    @Test
    void malformedJsonBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidEnumOrIdParameterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/products").param("type", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Invalid value 'NOPE' for parameter 'type'"));
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void frameworkErrorsKeepTheirStatusInsteadOf500() throws Exception {
        // Unknown routes are protected by the catch-all rule, so use a logged-in caller to reach MVC.
        String token = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1", "0551234567"));
        mockMvc.perform(get("/api/auth/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(delete("/api/auth/login").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void devSeederDoesNotRunOutsideDevProfile() {
        assertThat(applicationContext.getBeansOfType(DataSeeder.class)).isEmpty();
    }

    @Test
    void repeatedFailedLoginsLockTheAccountTemporarily() throws Exception {
        for (int i = 0; i < 5; i++) {
            loginFrom("10.0.0.1", ADMIN_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        }

        // Even the correct password is refused while locked, so guessing gains nothing.
        loginFrom("10.0.0.1", ADMIN_EMAIL, ADMIN_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429));
        // The lock follows the account, not the client address.
        loginFrom("10.0.0.2", ADMIN_EMAIL.toUpperCase(), ADMIN_PASSWORD)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void failedLoginsOnOneAccountDoNotLockOthers() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1").andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            loginFrom("10.0.0.1", ADMIN_EMAIL, "wrong-password");
        }

        loginFrom("10.0.0.1", "jane@example.com", "SuperSecret1").andExpect(status().isOk());
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            loginFrom("10.0.0.1", ADMIN_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        }
        loginFrom("10.0.0.1", ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            loginFrom("10.0.0.1", ADMIN_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
        }
        loginFrom("10.0.0.1", ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void tooManyAttemptsFromOneAddressAreThrottled() throws Exception {
        for (int i = 0; i < 20; i++) {
            loginFrom("10.0.0.9", "user" + i + "@example.com", "whatever-password")
                    .andExpect(status().isUnauthorized());
        }

        loginFrom("10.0.0.9", ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isTooManyRequests());
        loginFrom("10.0.0.10", ADMIN_EMAIL, ADMIN_PASSWORD).andExpect(status().isOk());
    }

    private ResultActions loginFrom(String ip, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    /** Registers with a phone derived from the email, so different emails never collide on phone. */
    private ResultActions register(String fullName, String email, String password) throws Exception {
        int digits = Math.floorMod(email.toLowerCase(java.util.Locale.ROOT).hashCode(), 100_000_000);
        return register(fullName, email, password, "05" + String.format("%08d", digits));
    }

    private ResultActions register(String fullName, String email, String password, String phone) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"%s\",\"email\":\"%s\",\"password\":\"%s\",\"phone\":\"%s\"}"
                        .formatted(fullName, email, password, phone)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String tokenFrom(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }
}
