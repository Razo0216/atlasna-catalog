package com.atlasna.catalog.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated at startup: the app refuses to boot without a real signing secret.
 * Outside the dev profile the secret must come from the ATLASNA_JWT_SECRET environment variable.
 */
@ConfigurationProperties(prefix = "atlasna.jwt")
@Validated
public record JwtProperties(
        @NotBlank(message = "atlasna.jwt.secret must be set (use the ATLASNA_JWT_SECRET environment variable)")
        @Size(min = 32, message = "atlasna.jwt.secret must be at least 32 characters (256 bits) for HS256")
        String secret,
        @Positive long expirationMinutes
) {}
