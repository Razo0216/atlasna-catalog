package com.atlasna.catalog.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Local development only: one STAFF and one DRIVER account to walk an order through its lifecycle.
 * The password is a public dev placeholder (same as the dev admin); never activate the dev profile in a real environment.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevAccountsSeeder implements CommandLineRunner {

    static final String DEV_PASSWORD = "ChangeMe123!";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        create("Staff Atlasna", "staff@atlasna.dz", Role.STAFF);
        create("Livreur Atlasna", "driver@atlasna.dz", Role.DRIVER);
    }

    private void create(String name, String email, Role role) {
        if (users.existsByEmail(email)) return;
        users.save(User.builder().fullName(name).email(email).passwordHash(passwordEncoder.encode(DEV_PASSWORD)).role(role).build());
        log.info("Created dev {} account {}", role, email);
    }
}
