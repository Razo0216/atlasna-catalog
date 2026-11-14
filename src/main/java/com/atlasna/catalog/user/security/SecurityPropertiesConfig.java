package com.atlasna.catalog.user.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the identity module's own settings (atlasna.jwt.*, atlasna.rate-limit.login.*) inside the module. */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, LoginRateLimitProperties.class})
class SecurityPropertiesConfig {
}
