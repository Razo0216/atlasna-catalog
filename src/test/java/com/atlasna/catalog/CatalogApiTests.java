package com.atlasna.catalog;

import com.atlasna.catalog.product.domain.Category;
import com.atlasna.catalog.product.domain.CategoryRepository;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.security.JwtService;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalog API on real Postgres: category tree, combinable filters, sort allow-list, no N+1, business rules
 * (heavy items, leaf categories, unit steps), slugs/SKUs, soft delete + restore, optimistic locking, the
 * public/admin DTO split, and the admin authorization matrix.
 *
 * <p>Categories are reference data from Flyway V2 (2 roots, 15 children). Each test starts with no products,
 * the seeded categories active, test-created categories removed, and one ADMIN plus one CUSTOMER.
 */
@AutoConfigureMockMvc
class CatalogApiTests extends PostgresIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired EntityManagerFactory entityManagerFactory;

    String adminToken;
    String customerToken;

    @BeforeEach
    void reset() {
        productRepository.deleteAll();
        categoryRepository.findAll().stream().filter(c -> c.getSlug().startsWith("test-") && !c.isRoot())
                .forEach(categoryRepository::delete);
        categoryRepository.findAll().stream().filter(c -> c.getSlug().startsWith("test-"))
                .forEach(categoryRepository::delete);
        categoryRepository.findAll().forEach(c -> {
            c.setActive(true);
            categoryRepository.save(c);
        });
        userRepository.deleteAll();
        adminToken = tokenFor("admin@test.local", Role.ADMIN);
        customerToken = tokenFor("customer@test.local", Role.CUSTOMER);
    }

    // ---------------------------------------------------------------- categories (test 3)

    @Test
    void categoryTreeHasTwoDepartmentsWithTheirChildrenInOrder() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", contains("epicerie", "beaute")))
                .andExpect(jsonPath("$[0].type").value("GROCERY"))
                .andExpect(jsonPath("$[0].name.ar").value("البقالة"))
                .andExpect(jsonPath("$[0].children.length()").value(8))
                .andExpect(jsonPath("$[0].children[0].slug").value("fruits-legumes"))
                .andExpect(jsonPath("$[1].children.length()").value(7))
                .andExpect(jsonPath("$[1].children[*].type", contains(
                        "BEAUTY", "BEAUTY", "BEAUTY", "BEAUTY", "BEAUTY", "BEAUTY", "BEAUTY")));
    }

    @Test
    void inactiveCategoriesAreHiddenPubliclyButListedForAdmins() throws Exception {
        Category bakery = category("boulangerie-patisserie");
        bakery.setActive(false);
        categoryRepository.save(bakery);

        mockMvc.perform(get("/api/categories"))
                .andExpect(jsonPath("$[0].children.length()").value(7));
        mockMvc.perform(get("/api/admin/categories").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].children.length()").value(8))
                .andExpect(jsonPath("$[0].children[2].active").value(false));
    }

    @Test
    void productsOfAnInactiveCategoryAreHiddenFromThePublic() throws Exception {
        long id = createUnit("cheveux", "Shampooing test", "500", 300, "10", "");
        Category hair = category("cheveux");
        hair.setActive(false);
        categoryRepository.save(hair);

        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products")).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void adminCategoryRules() throws Exception {
        long epicerie = category("epicerie").getId();
        long fruits = category("fruits-legumes").getId();

        // Child under a root: slug generated from the French name, type must match the parent.
        String created = adminCategory(post("/api/admin/categories"),
                "{\"parentId\":%d,\"type\":\"GROCERY\",\"name\":{\"fr\":\"Test Surgelés légers\",\"ar\":null}}".formatted(epicerie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("test-surgeles-legers"))
                .andExpect(jsonPath("$.parentId").value(epicerie))
                .andReturn().getResponse().getContentAsString();
        long newId = ((Number) JsonPath.read(created, "$.id")).longValue();

        adminCategory(post("/api/admin/categories"),
                "{\"parentId\":%d,\"type\":\"BEAUTY\",\"name\":{\"fr\":\"Test mismatch\"}}".formatted(epicerie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists());
        adminCategory(post("/api/admin/categories"),
                "{\"parentId\":%d,\"type\":\"GROCERY\",\"name\":{\"fr\":\"Test too deep\"}}".formatted(fruits))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.parentId").value("Categories can only be two levels deep"));
        adminCategory(post("/api/admin/categories"),
                "{\"slug\":\"fruits-legumes\",\"parentId\":%d,\"type\":\"GROCERY\",\"name\":{\"fr\":\"Test dup\"}}".formatted(epicerie))
                .andExpect(status().isConflict());

        // A category with products, or with children, can't be deleted; an empty one can.
        createUnit("fruits-legumes", "Pommes", "200", 1000, "5", "");
        mockMvc.perform(delete("/api/admin/categories/" + fruits).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/categories/" + epicerie).header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/admin/categories/" + newId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- listing & filters (test 4)

    @Test
    void filtersCombine() throws Exception {
        createWeight("fruits-legumes", "Tomates", "طماطم", "80", "42.5", "\"featured\":false");
        createUnit("epicerie-salee", "Couscous fin 1 kg", "130", 1000, "80", "");
        createUnit("naturel-traditionnel", "Huile d'argan pure 50 ml", "1800", 120, "15", "\"featured\":true");
        createUnit("maquillage", "Rouge à lèvres mat", "900", 30, "0", "");
        createUnit("hygiene-personnelle", "Promo 50% savon", "450", 230, "40", "");
        createUnit("hygiene-personnelle", "Promo 500 savon", "450", 230, "40", "");

        assertNames("category=epicerie", "Tomates", "Couscous fin 1 kg");                 // root includes children
        assertNames("category=fruits-legumes", "Tomates");
        assertNames("type=BEAUTY", "Huile d'argan pure 50 ml", "Rouge à lèvres mat", "Promo 50% savon", "Promo 500 savon");
        assertNames("q=TOMATES", "Tomates");                                             // case-insensitive
        assertNames("q=طماطم", "Tomates");                                               // Arabic name
        assertNames("q=50%", "Promo 50% savon");                                         // % matched literally
        assertNames("q=GRC-", "Tomates", "Couscous fin 1 kg");                           // SKU
        assertNames("featured=true", "Huile d'argan pure 50 ml");
        assertNames("type=BEAUTY&inStock=true", "Huile d'argan pure 50 ml", "Promo 50% savon", "Promo 500 savon");
        assertNames("category=epicerie&q=couscous", "Couscous fin 1 kg");               // search + category together
        assertNames("type=GROCERY&q=savon");
        assertNames("category=does-not-exist");                                          // unknown slug: empty, not an error
    }

    @Test
    void pageShapeAndLimits() throws Exception {
        createUnit("boissons", "Café moulu 250 g", "420", 260, "45", "");
        createUnit("boissons", "Thé vert 100 g", "250", 110, "50", "");

        mockMvc.perform(get("/api/products").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.pageable").doesNotExist());
        mockMvc.perform(get("/api/products").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").exists());
        mockMvc.perform(get("/api/products").param("page", "-1")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- sort allow-list (test 5)

    @Test
    void onlyWhitelistedSortsAreAccepted() throws Exception {
        createUnit("boissons", "B moyen", "300", 100, "5", "");
        createUnit("boissons", "A cher", "900", 100, "5", "");
        createUnit("boissons", "C bon marché", "100", 100, "5", "");

        mockMvc.perform(get("/api/products").param("sort", "price,asc"))
                .andExpect(jsonPath("$.content[*].name.fr", contains("C bon marché", "B moyen", "A cher")));
        mockMvc.perform(get("/api/products").param("sort", "price,desc"))
                .andExpect(jsonPath("$.content[*].name.fr", contains("A cher", "B moyen", "C bon marché")));
        mockMvc.perform(get("/api/products").param("sort", "name,asc"))
                .andExpect(jsonPath("$.content[*].name.fr", contains("A cher", "B moyen", "C bon marché")));
        mockMvc.perform(get("/api/products").param("sort", "createdAt,desc"))
                .andExpect(jsonPath("$.content[*].name.fr", contains("C bon marché", "A cher", "B moyen")));

        for (String bad : List.of("nope", "passwordHash,asc", "price", "price,asc;drop", "stockQuantity,desc")) {
            mockMvc.perform(get("/api/products").param("sort", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ---------------------------------------------------------------- no N+1 (test 6)

    @Test
    void listingCostsTheSameNumberOfQueriesForOneOrTwentyProducts() throws Exception {
        createUnit("boissons", "Produit 0", "100", 100, "5", "");
        long one = statementsForListing();
        for (int i = 1; i < 20; i++) createUnit("boissons", "Produit " + i, "100", 100, "5", "");
        long twenty = statementsForListing();

        assertThat(one).as("statements for 1 product").isPositive();
        assertThat(twenty).as("statements for 20 products").isEqualTo(one);
    }

    // ---------------------------------------------------------------- business rules (tests 7, 8)

    @Test
    void heavyItemsAreRejected() throws Exception {
        adminCreate(unitJson("epicerie-salee", "Sac de farine 25 kg", "2500", 6000, "5", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("Items heavier than 5000 g per unit are not sold"));
        adminCreate(unitJson("epicerie-salee", "Farine 5 kg", "900", 5000, "5", ""))
                .andExpect(status().isCreated());
    }

    @Test
    void categoryAndStepRules() throws Exception {
        adminCreate(json("epicerie-salee", "Pâtes", null, "100", "UNIT", "0.5", 500, "5", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.quantityStep").exists());
        adminCreate(json("epicerie", "Pâtes", null, "100", "UNIT", "1", 500, "5", ""))       // a department, not a leaf
                .andExpect(status().isUnprocessableContent());
        adminCreate(jsonWithCategoryId(999_999L, "Pâtes"))
                .andExpect(status().isNotFound());
        adminCreate(unitJson("epicerie-salee", "Pâtes", "100", 500, "5", "\"compareAtPrice\":90"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.compareAtPrice").exists());
        adminCreate("{\"name\":{\"fr\":\"\"},\"price\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['name.fr']").value("French name is required"))
                .andExpect(jsonPath("$.fieldErrors.price").value("Price must be greater than 0"))
                .andExpect(jsonPath("$.fieldErrors.categoryId").value("Category is required"));
        adminCreate(unitJson("epicerie-salee", "Pâtes", "10.555", 500, "5", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.price").exists());
    }

    @Test
    void availabilityAndMaxOrderQuantity() throws Exception {
        long tomatoes = createWeight("fruits-legumes", "Tomates", null, "80", "42.5", "");
        long fewOranges = createWeight("fruits-legumes", "Oranges", null, "120", "3", "");
        long almostNone = createWeight("fruits-legumes", "Citrons", null, "150", "0.3", "");
        long couscous = createUnit("epicerie-salee", "Couscous", "130", 1000, "80", "");

        mockMvc.perform(get("/api/products/" + tomatoes))
                .andExpect(jsonPath("$.availability").value("IN_STOCK"))
                .andExpect(jsonPath("$.quantityStep").value(0.5))
                .andExpect(jsonPath("$.maxOrderQuantity").value(10));                     // weight line limit
        mockMvc.perform(get("/api/products/" + fewOranges))
                .andExpect(jsonPath("$.availability").value("LOW_STOCK"))
                .andExpect(jsonPath("$.maxOrderQuantity").value(3));
        mockMvc.perform(get("/api/products/" + almostNone))                               // 0.3 kg < one 0.5 kg step
                .andExpect(jsonPath("$.availability").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.maxOrderQuantity").value(0));
        mockMvc.perform(get("/api/products/" + couscous))
                .andExpect(jsonPath("$.quantityStep").value(1))
                .andExpect(jsonPath("$.maxOrderQuantity").value(20));                     // unit line limit
    }

    // ---------------------------------------------------------------- slugs & SKUs (test 9)

    @Test
    void slugsAreGeneratedOnceAndStayStable() throws Exception {
        long id = createUnit("naturel-traditionnel", "Huile d'argan pure 50 ml", "1800", 120, "15", "");
        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.slug").value("huile-d-argan-pure-50-ml"));
        long twin = createUnit("naturel-traditionnel", "Huile d'argan pure 50 ml", "1700", 120, "15", "");
        mockMvc.perform(get("/api/products/" + twin)).andExpect(jsonPath("$.slug").value("huile-d-argan-pure-50-ml-2"));

        long version = version(id);
        adminUpdate(id, unitJson("naturel-traditionnel", "Huile d'argan bio 50 ml", "1800", 120, "15", "\"version\":" + version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name.fr").value("Huile d'argan bio 50 ml"))
                .andExpect(jsonPath("$.slug").value("huile-d-argan-pure-50-ml"));
        mockMvc.perform(get("/api/products/by-slug/huile-d-argan-pure-50-ml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void skusAreGeneratedPerDepartmentAndMustBeUnique() throws Exception {
        long a = createUnit("boissons", "Jus A", "100", 1000, "5", "");
        long b = createUnit("boissons", "Jus B", "100", 1000, "5", "");
        long c = createUnit("soins-corps", "Lait corps", "600", 300, "5", "");
        mockMvc.perform(get("/api/products/" + a)).andExpect(jsonPath("$.sku").value("GRC-0001"));
        mockMvc.perform(get("/api/products/" + b)).andExpect(jsonPath("$.sku").value("GRC-0002"));
        mockMvc.perform(get("/api/products/" + c)).andExpect(jsonPath("$.sku").value("BTY-0001"));

        adminCreate(unitJson("boissons", "Jus C", "100", 1000, "5", "\"sku\":\"grc-0001\""))
                .andExpect(status().isConflict());
        adminCreate(unitJson("boissons", "Jus D", "100", 1000, "5", "\"sku\":\"jus-d-1l\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("JUS-D-1L"));
    }

    // ---------------------------------------------------------------- soft delete & restore (test 10)

    @Test
    void softDeleteHidesAndRestoreBringsBack() throws Exception {
        long id = createUnit("boissons", "Thé vert", "250", 110, "50", "");

        mockMvc.perform(delete("/api/admin/products/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/by-slug/the-vert")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/products").param("active", "false").header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.content[*].id", contains((int) id)))
                .andExpect(jsonPath("$.content[0].active").value(false));
        mockMvc.perform(get("/api/admin/products").header("Authorization", bearer(adminToken)))   // default: active only
                .andExpect(jsonPath("$.content", empty()));

        mockMvc.perform(post("/api/admin/products/" + id + "/restore").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/products/" + id)).andExpect(status().isOk());
        assertThat(productRepository.findById(id)).isPresent();
    }

    // ---------------------------------------------------------------- optimistic locking (test 11)

    @Test
    void staleVersionIsRejected() throws Exception {
        long id = createUnit("boissons", "Café", "420", 260, "45", "");
        long v = version(id);

        adminUpdate(id, unitJson("boissons", "Café moulu", "430", 260, "45", "\"version\":" + v))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v + 1));
        adminUpdate(id, unitJson("boissons", "Café en grains", "440", 260, "45", "\"version\":" + v))
                .andExpect(status().isConflict());
        adminUpdate(id, unitJson("boissons", "Café sans version", "440", 260, "45", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.version").exists());

        mockMvc.perform(get("/api/products/" + id)).andExpect(jsonPath("$.name.fr").value("Café moulu"));
    }

    // ---------------------------------------------------------------- authorization matrix (test 12)

    @Test
    void everyAdminRouteIsAdminOnly() throws Exception {
        long productId = createUnit("boissons", "Produit admin", "100", 100, "5", "");
        long categoryId = ((Number) JsonPath.read(adminCategory(post("/api/admin/categories"),
                "{\"parentId\":%d,\"type\":\"GROCERY\",\"name\":{\"fr\":\"Test matrice\"}}".formatted(category("epicerie").getId()))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        String categoryBody = "{\"parentId\":%d,\"type\":\"GROCERY\",\"name\":{\"fr\":\"Test matrice 2\"}}"
                .formatted(category("epicerie").getId());

        record Route(String label, Function<String, MockHttpServletRequestBuilder> request, int adminStatus) {}
        List<Route> routes = new ArrayList<>(List.of(
                new Route("GET products", t -> get("/api/admin/products"), 200),
                new Route("GET product", t -> get("/api/admin/products/" + productId), 200),
                new Route("POST product", t -> post("/api/admin/products").contentType(MediaType.APPLICATION_JSON)
                        .content(uncheckedUnitJson("Produit matrice")), 201),
                new Route("PUT product", t -> put("/api/admin/products/" + productId).contentType(MediaType.APPLICATION_JSON)
                        .content(uncheckedUnitJson("Produit admin 2", "\"version\":" + uncheckedVersion(productId))), 200),
                new Route("DELETE product", t -> delete("/api/admin/products/" + productId), 204),
                new Route("POST restore", t -> post("/api/admin/products/" + productId + "/restore"), 200),
                new Route("GET categories", t -> get("/api/admin/categories"), 200),
                new Route("POST category", t -> post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody), 201),
                new Route("PUT category", t -> put("/api/admin/categories/" + categoryId).contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody.replace("matrice 2", "matrice renamed")), 200),
                new Route("DELETE category", t -> delete("/api/admin/categories/" + categoryId), 204)));

        for (Route route : routes) {
            mockMvc.perform(route.request().apply(null))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).as(route.label() + " anonymous").isEqualTo(401));
            mockMvc.perform(route.request().apply(null).header("Authorization", bearer(customerToken)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).as(route.label() + " customer").isEqualTo(403))
                    .andExpect(jsonPath("$.status").value(403));
            mockMvc.perform(route.request().apply(null).header("Authorization", bearer(adminToken)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).as(route.label() + " admin").isEqualTo(route.adminStatus()));
        }
        // Writes never exist on the public path.
        mockMvc.perform(post("/api/products").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(uncheckedUnitJson("Nope")))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------------------------------------------------------------- DTO split + JSONB (test 13)

    @Test
    void publicViewHidesInternalsAdminViewShowsThem() throws Exception {
        long id = createUnit("naturel-traditionnel", "Huile d'argan", "1800", 120, "15",
                "\"attributes\":{\"skinType\":[\"dry\",\"normal\"],\"vegan\":true,\"origin\":\"MA\"}");

        mockMvc.perform(get("/api/products/" + id))
                .andExpect(jsonPath("$.stockQuantity").doesNotExist())
                .andExpect(jsonPath("$.lowStockThreshold").doesNotExist())
                .andExpect(jsonPath("$.active").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.availability").value("IN_STOCK"))
                .andExpect(jsonPath("$.category.slug").value("naturel-traditionnel"))
                .andExpect(jsonPath("$.category.type").value("BEAUTY"))
                .andExpect(jsonPath("$.images", empty()))
                // JSONB round trip through Postgres
                .andExpect(jsonPath("$.attributes.skinType", containsInAnyOrder("dry", "normal")))
                .andExpect(jsonPath("$.attributes.vegan").value(true));

        mockMvc.perform(get("/api/admin/products/" + id).header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.stockQuantity").value(15))
                .andExpect(jsonPath("$.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    // ---------------------------------------------------------------- helpers

    private void assertNames(String query, String... names) throws Exception {
        mockMvc.perform(get("/api/products?" + query + "&size=100"))
                .andExpect(status().isOk())
                .andExpect(names.length == 0
                        ? jsonPath("$.content", empty())
                        : jsonPath("$.content[*].name.fr", containsInAnyOrder(names)));
    }

    private long statementsForListing() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        // size=100 so the count query is skipped in both cases (fewer results than the page size).
        mockMvc.perform(get("/api/products").param("size", "100")).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    private long createUnit(String categorySlug, String nameFr, String price, int weightGrams, String stock, String extra) throws Exception {
        return id(adminCreate(unitJson(categorySlug, nameFr, price, weightGrams, stock, extra)).andExpect(status().isCreated()));
    }

    private long createWeight(String categorySlug, String nameFr, String nameAr, String pricePerKg, String stockKg, String extra) throws Exception {
        return id(adminCreate(json(categorySlug, nameFr, nameAr, pricePerKg, "WEIGHT", "0.5", 1000, stockKg, extra))
                .andExpect(status().isCreated()));
    }

    private String unitJson(String categorySlug, String nameFr, String price, int weightGrams, String stock, String extra) {
        return json(categorySlug, nameFr, nameFr.equals("Tomates") ? "طماطم" : null, price, "UNIT", "1", weightGrams, stock, extra);
    }

    private String json(String categorySlug, String nameFr, String nameAr, String price, String soldBy, String step,
                        int weightGrams, String stock, String extra) {
        return """
                {"name":{"fr":"%s","ar":%s},"categoryId":%d,"price":%s,"soldBy":"%s","quantityStep":%s,\
                "unitWeightGrams":%d,"stockQuantity":%s%s}""".formatted(nameFr, nameAr == null ? "null" : "\"" + nameAr + "\"",
                category(categorySlug).getId(), price, soldBy, step, weightGrams, stock, extra.isEmpty() ? "" : "," + extra);
    }

    private String jsonWithCategoryId(long categoryId, String nameFr) {
        return """
                {"name":{"fr":"%s"},"categoryId":%d,"price":100,"soldBy":"UNIT","quantityStep":1,"unitWeightGrams":100,"stockQuantity":1}"""
                .formatted(nameFr, categoryId);
    }

    private String uncheckedUnitJson(String nameFr, String... extra) {
        return unitJson("boissons", nameFr, "100", 100, "5", extra.length == 0 ? "" : extra[0]);
    }

    private long uncheckedVersion(long id) {
        return productRepository.findById(id).orElseThrow().getVersion();
    }

    private long version(long id) {
        return uncheckedVersion(id);
    }

    private ResultActions adminCreate(String json) throws Exception {
        return mockMvc.perform(post("/api/admin/products").header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions adminUpdate(long id, String json) throws Exception {
        return mockMvc.perform(put("/api/admin/products/" + id).header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions adminCategory(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private Category category(String slug) {
        return categoryRepository.findBySlug(slug).orElseThrow();
    }

    private String tokenFor(String email, Role role) {
        User user = userRepository.save(User.builder().fullName(role.name()).email(email)
                .passwordHash(passwordEncoder.encode("irrelevant-password")).role(role).build());
        return jwtService.generateToken(user);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
