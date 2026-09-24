package com.atlasna.catalog.config;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin account in any environment from atlasna.bootstrap.admin.* properties.
 * Never modifies an existing account, and refuses to start with a weak password.
 *
 * <p>Runs once at every startup (ApplicationRunner), after Flyway has migrated the schema. It is
 * idempotent: once the account exists, later startups do nothing, so the variables can stay set.
 * Typical production use: set ATLASNA_BOOTSTRAP_ADMIN_EMAIL and ATLASNA_BOOTSTRAP_ADMIN_PASSWORD for the first
 * deploy. In dev, application-dev.yml supplies admin@atlasna.dz.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // run before other startup runners (e.g. DataSeeder)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            if (!userRepository.existsByRole(Role.ADMIN)) {
                log.warn("No admin account exists. Set ATLASNA_BOOTSTRAP_ADMIN_EMAIL and "
                        + "ATLASNA_BOOTSTRAP_ADMIN_PASSWORD to create one on next startup.");
            }
            return;
        }

        String password = properties.password();
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("atlasna.bootstrap.admin.password must be at least "
                    + MIN_PASSWORD_LENGTH + " characters when atlasna.bootstrap.admin.email is set");
        }

        String email = User.normalizeEmail(properties.email());
        userRepository.findByEmail(email).ifPresentOrElse(
                existing -> {
                    // Deliberately never promote an existing account: a customer who registered with
                    // this email first must not silently become an admin.
                    if (existing.getRole() != Role.ADMIN) {
                        log.warn("Bootstrap admin email {} belongs to an existing {} account; not changing it",
                                email, existing.getRole());
                    }
                },
                () -> {
                    userRepository.save(User.builder()
                            .fullName(properties.fullName())
                            .email(email)
                            .passwordHash(passwordEncoder.encode(password))
                            .role(Role.ADMIN)
                            .build());
                    log.info("Created bootstrap admin account {}", email);
                });
    }
}
