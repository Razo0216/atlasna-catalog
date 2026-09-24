package com.atlasna.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first-admin credentials, e.g. ATLASNA_BOOTSTRAP_ADMIN_EMAIL / ATLASNA_BOOTSTRAP_ADMIN_PASSWORD.
 * Only used to create the account if it doesn't exist yet; safe to remove once the admin exists.
 */
@ConfigurationProperties(prefix = "atlasna.bootstrap.admin")
public record AdminBootstrapProperties(String email, String password, String fullName) {

    /** Compact constructor: fills in a default display name when none is configured. */
    public AdminBootstrapProperties {
        if (fullName == null || fullName.isBlank()) {
            fullName = "Atlasna Admin";
        }
    }

    /** Bootstrap is opt-in: it only runs when an email is set. */
    public boolean isConfigured() {
        return email != null && !email.isBlank();
    }
}
