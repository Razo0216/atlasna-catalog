package com.atlasna.catalog.product.seed;

import com.atlasna.catalog.product.domain.CategoryRepository;
import com.atlasna.catalog.product.domain.ProductRepository;
import com.atlasna.catalog.product.domain.SoldBy;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Local-development demo catalog only (docs/02-DOMAIN-AND-DATA-MODEL.md §8). Active under the "dev" profile,
 * which {@code ./mvnw spring-boot:run} enables. Categories are reference data from Flyway V2; the dev admin
 * comes from AdminBootstrap. Products go through ProductService so they obey the same rules as the admin API.
 *
 * <p>No real brand names. Prices are plausible demo values. The Arabic strings need a native speaker's review.
 * Stock is chosen so every availability state appears (oranges: low stock, lipstick: out of stock).
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductService productService;
    private final DemoProductImages demoProductImages;

    @Override
    public void run(String... args) {
        if (productRepository.count() == 0) seedProducts(); // idempotent: restarting never duplicates
        // Also runs on an existing dev database, so catalogs seeded before photos existed get them too.
        demoProductImages.attachMissing();
    }

    private void seedProducts() {
        List<ProductRequest> products = List.of(
                weight("fruits-legumes", "Tomates", "طماطم", "80", "42.5", true, true, Map.of("origin", "DZ")),
                weight("fruits-legumes", "Pommes de terre", "بطاطا", "60", "120", false, false, Map.of("origin", "DZ")),
                weight("fruits-legumes", "Oranges", "برتقال", "120", "3", true, false, Map.of("origin", "DZ")),
                unit("produits-laitiers-oeufs", "Lait demi-écrémé 1 L", "حليب نصف منزوع الدسم 1 لتر", "120", 1050, 60, true, false, Map.of()),
                unit("produits-laitiers-oeufs", "Œufs ×6", "بيض ×6", "110", 400, 40, true, false, Map.of()),
                unit("epicerie-salee", "Couscous fin 1 kg", "كسكس رفيع 1 كغ", "130", 1000, 80, false, false, Map.of("halal", true)),
                unit("epicerie-salee", "Semoule moyenne 1 kg", "سميد متوسط 1 كغ", "95", 1000, 70, false, false, Map.of()),
                unit("epicerie-sucree", "Dattes Deglet Nour 500 g", "تمر دقلة نور 500 غ", "450", 520, 35, false, true, Map.of("origin", "DZ")),
                unit("huiles-condiments-epices", "Huile d'olive extra vierge 500 ml", "زيت الزيتون البكر الممتاز 500 مل", "1050", 650, 25, false, true, Map.of("origin", "DZ")),
                unit("huiles-condiments-epices", "Harissa 380 g", "هريسة 380 غ", "180", 450, 30, false, false, Map.of()),
                unit("boissons", "Café moulu 250 g", "قهوة مطحونة 250 غ", "420", 260, 45, false, false, Map.of()),
                unit("boissons", "Thé vert 100 g", "شاي أخضر 100 غ", "250", 110, 50, false, false, Map.of()),
                unit("naturel-traditionnel", "Huile d'argan pure 50 ml", "زيت الأركان الصافي 50 مل", "1800", 120, 15, false, true,
                        Map.of("skinType", List.of("dry", "normal"), "vegan", true)),
                unit("naturel-traditionnel", "Eau de rose 200 ml", "ماء الورد 200 مل", "400", 280, 20, false, false, Map.of("alcoholFree", true)),
                unit("naturel-traditionnel", "Henné naturel 100 g", "حناء طبيعية 100 غ", "200", 110, 25, false, false, Map.of()),
                unit("soins-visage", "Crème hydratante visage 50 ml", "كريم مرطب للوجه 50 مل", "1200", 110, 12, false, false,
                        Map.of("skinType", List.of("normal", "dry"))),
                unit("cheveux", "Shampooing au karité 250 ml", "شامبو بزبدة الشيا 250 مل", "700", 300, 18, false, false, Map.of()),
                unit("soins-corps", "Gel douche 250 ml", "جل استحمام 250 مل", "450", 290, 22, false, false, Map.of()),
                unit("maquillage", "Rouge à lèvres mat", "أحمر شفاه مات", "900", 30, 0, false, false, Map.of("shade", "rouge brique")),
                unit("hygiene-personnelle", "Savon noir beldi 200 g", "صابون أسود بلدي 200 غ", "450", 230, 40, false, false, Map.of()));
        products.forEach(productService::create);
        log.info("Seeded {} demo products", products.size());
    }

    private ProductRequest unit(String categorySlug, String fr, String ar, String price, int weightGrams, int stock,
                                boolean perishable, boolean featured, Map<String, Object> attributes) {
        return request(categorySlug, fr, ar, price, SoldBy.UNIT, "1", weightGrams, String.valueOf(stock), perishable, featured, attributes);
    }

    /** Loose produce: price per kg, sold in 0.5 kg steps, unit weight = 1 kg. */
    private ProductRequest weight(String categorySlug, String fr, String ar, String pricePerKg, String stockKg,
                                  boolean perishable, boolean featured, Map<String, Object> attributes) {
        return request(categorySlug, fr, ar, pricePerKg, SoldBy.WEIGHT, "0.5", 1000, stockKg, perishable, featured, attributes);
    }

    private ProductRequest request(String categorySlug, String fr, String ar, String price, SoldBy soldBy, String step,
                                   int weightGrams, String stock, boolean perishable, boolean featured, Map<String, Object> attributes) {
        Long categoryId = categoryRepository.findBySlug(categorySlug)
                .orElseThrow(() -> new IllegalStateException("Category " + categorySlug + " missing; is Flyway V2 applied?"))
                .getId();
        return new ProductRequest(new ProductRequest.Name(fr, ar), null, null, null, null, categoryId,
                new BigDecimal(price), null, soldBy, new BigDecimal(step), weightGrams, new BigDecimal(stock),
                null, perishable, featured, attributes, null);
    }
}
