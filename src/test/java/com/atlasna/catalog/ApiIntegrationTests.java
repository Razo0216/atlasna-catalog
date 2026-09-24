package com.atlasna.catalog;

import com.atlasna.catalog.config.DataSeeder;
import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import com.atlasna.catalog.product.ProductRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTests {

    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "AdminPass123";
    private static final String PRODUCT_JSON =
            "{\"name\":\"Tea Set\",\"price\":3100.00,\"category\":\"HOME_AND_KITCHEN\",\"stockQuantity\":5}";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ProductRepository productRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ApplicationContext applicationContext;
    @Value("${atlasna.jwt.secret}") String jwtSecret;

    @BeforeEach
    void resetData() {
        productRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .fullName("Test Admin").email(ADMIN_EMAIL)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(Role.ADMIN).build());
    }

    @Test
    void productListingIsPublic() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void productListingUsesStablePageShape() throws Exception {
        productRepository.save(Product.builder().name("A").price(BigDecimal.ONE)
                .category(Category.BOOKS).stockQuantity(1).build());
        productRepository.save(Product.builder().name("B").price(BigDecimal.TEN)
                .category(Category.BOOKS).stockQuantity(1).build());

        mockMvc.perform(get("/api/products").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.pageable").doesNotExist());
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
        register("", "not-an-email", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fullName").value("Full name is required"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Email must be valid"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be at least 8 characters"));
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        register("Jane", "jane@example.com", "SuperSecret1").andExpect(status().isCreated());
        register("Jane Again", "jane@example.com", "SuperSecret1").andExpect(status().isConflict());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        login(ADMIN_EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void customerCannotCreateProduct() throws Exception {
        String customerToken = tokenFrom(register("Jane", "jane@example.com", "SuperSecret1"));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isForbidden());

        assertThat(productRepository.count()).isZero();
    }

    @Test
    void adminCanCreateProduct() throws Exception {
        String adminToken = tokenFrom(login(ADMIN_EMAIL, ADMIN_PASSWORD));

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Tea Set"))
                .andExpect(jsonPath("$.category").value("HOME_AND_KITCHEN"))
                .andExpect(jsonPath("$.stockQuantity").value(5));

        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    void adminDeleteHidesProductFromPublicReads() throws Exception {
        String adminToken = tokenFrom(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        String created = mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(created, "$.id");

        mockMvc.perform(delete("/api/products/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        assertThat(productRepository.findById(id.longValue())).hasValueSatisfying(p -> assertThat(p.isActive()).isFalse());
    }

    @Test
    void missingTokenOnProtectedEndpointIsUnauthorizedWithJsonBody() throws Exception {
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));
        assertThat(productRepository.count()).isZero();
    }

    @Test
    void malformedTokenDoesNotBreakPublicEndpoints() throws Exception {
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/products").header("Authorization", "Bearer not.a.jwt")
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
        mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + expired)
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
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidEnumOrIdParameterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/products").param("category", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'NOPE' for parameter 'category'"));
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frameworkErrorsKeepTheirStatusInsteadOf500() throws Exception {
        mockMvc.perform(get("/api/auth/does-not-exist"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void devSeederDoesNotRunOutsideDevProfile() {
        assertThat(applicationContext.getBeansOfType(DataSeeder.class)).isEmpty();
    }

    private ResultActions register(String fullName, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(fullName, email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String tokenFrom(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }
}
