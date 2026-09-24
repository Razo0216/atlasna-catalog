package com.atlasna.catalog.config;

import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import com.atlasna.catalog.product.ProductRepository;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        seedAdmin();
        seedProducts();
    }

    private void seedAdmin() {
        String adminEmail = "admin@atlasna.dz";
        if (userRepository.existsByEmail(adminEmail)) return;
        User admin = User.builder()
                .fullName("Atlasna Admin").email(adminEmail)
                .passwordHash(passwordEncoder.encode("ChangeMe123!"))
                .role(Role.ADMIN).build();
        userRepository.save(admin);
        log.info("Seeded admin -> {} / ChangeMe123! (change outside dev)", adminEmail);
    }

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
