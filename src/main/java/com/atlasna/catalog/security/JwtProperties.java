package com.atlasna.catalog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlasna.jwt")
public record JwtProperties(String secret, long expirationMinutes) {}
