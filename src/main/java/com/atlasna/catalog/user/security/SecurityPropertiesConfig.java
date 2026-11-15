package com.atlasna.catalog.user.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the identity module's own settings (atlasna.jwt.*) inside the module. Rate limits: RateLimitPolicies. */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, RefreshTokenStore.Properties.class})
class SecurityPropertiesConfig {
}
