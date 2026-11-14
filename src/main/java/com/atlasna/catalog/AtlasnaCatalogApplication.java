package com.atlasna.catalog;

import com.atlasna.catalog.config.AdminBootstrapProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point of the Atlasna catalog API (single-store marketplace: ADMIN manages products, CUSTOMER browses).
 *
 * <p>Package layout:
 * Each top-level package is an application module with enforced boundaries (Spring Modulith, see docs/modules):
 * <ul>
 *   <li>{@code user} – identity: accounts, registration, login, JWT security, staff/driver accounts</li>
 *   <li>{@code product} – the catalog: categories, products, photos, dev seed data</li>
 *   <li>{@code inventory} – the stock ledger and every atomic stock change</li>
 *   <li>{@code store} – the shop's settings (hours, cut-off, weight limits…)</li>
 *   <li>{@code delivery} – zones, slots and their availability rules, customer addresses</li>
 *   <li>{@code order} – server cart, idempotent order placement, lifecycle, cash-on-delivery payment</li>
 *   <li>{@code config} – wiring: security rules, admin bootstrap, clock</li>
 *   <li>{@code common} – shared helpers and the JSON error handling</li>
 * </ul>
 *
 * <p>{@code @EnableConfigurationProperties} registers the typed settings classes that bind the
 * {@code atlasna.*} keys from application*.yml and environment variables.
 */
@SpringBootApplication
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AtlasnaCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtlasnaCatalogApplication.class, args);
    }
}
