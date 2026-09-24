package com.atlasna.catalog;

import com.atlasna.catalog.config.AdminBootstrapProperties;
import com.atlasna.catalog.security.JwtProperties;
import com.atlasna.catalog.security.LoginRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point of the Atlasna catalog API (single-store marketplace: ADMIN manages products, CUSTOMER browses).
 *
 * <p>Package layout:
 * <ul>
 *   <li>{@code user} – accounts, registration and login</li>
 *   <li>{@code product} – the product catalog</li>
 *   <li>{@code security} – JWT handling, the request filter and login rate limiting</li>
 *   <li>{@code config} – Spring Security rules, admin bootstrap and dev seed data</li>
 *   <li>{@code common.exception} – error types and the JSON error handler</li>
 * </ul>
 *
 * <p>{@code @EnableConfigurationProperties} registers the typed settings classes that bind the
 * {@code atlasna.*} keys from application*.yml and environment variables.
 */
@SpringBootApplication
@EnableConfigurationProperties({JwtProperties.class, AdminBootstrapProperties.class, LoginRateLimitProperties.class})
public class AtlasnaCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtlasnaCatalogApplication.class, args);
    }
}
