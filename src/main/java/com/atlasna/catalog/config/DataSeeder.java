package com.atlasna.catalog.config;

import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import com.atlasna.catalog.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Local-development demo products only. Active under the "dev" profile, which
 * `./mvnw spring-boot:run` enables via the spring-boot-maven-plugin config in pom.xml.
 * The dev admin account is created by AdminBootstrap from application-dev.yml.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;

    @Override
    public void run(String... args) {
        seedProducts();
    }

    /** Only seeds an empty table, so restarting the app never creates duplicates. */
    private void seedProducts() {
        if (productRepository.count() > 0) return;
        productRepository.saveAll(List.of(
                Product.builder().name("Atlas Studio Wireless Headphones")
                        .description("Over-ear, ANC, 30h battery.")
                        .price(new BigDecimal("4900.00")).category(Category.ELECTRONICS).stockQuantity(50).build(),
                Product.builder().name("Desert Runner Sneakers")
                        .description("Breathable mesh running shoes.")
                        .price(new BigDecimal("7200.00")).category(Category.FASHION).stockQuantity(80).build(),
                Product.builder().name("Ceramic Tea Set")
                        .description("6-piece traditional tea set.")
                        .price(new BigDecimal("3100.00")).category(Category.HOME_AND_KITCHEN).stockQuantity(30).build()
        ));
        log.info("Seeded 3 demo products");
    }
}
