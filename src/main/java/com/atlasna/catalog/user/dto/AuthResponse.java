package com.atlasna.catalog.user.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}
