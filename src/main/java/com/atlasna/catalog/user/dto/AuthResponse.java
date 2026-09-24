package com.atlasna.catalog.user.dto;

/**
 * Response body for successful register and login.
 *
 * @param accessToken      the JWT; send it as {@code Authorization: Bearer <accessToken>}
 * @param tokenType        always "Bearer"
 * @param expiresInSeconds how long the token stays valid (atlasna.jwt.expiration-minutes × 60)
 * @param user             the logged-in user, without sensitive fields
 */
public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}
