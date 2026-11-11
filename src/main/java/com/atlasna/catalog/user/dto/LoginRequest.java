package com.atlasna.catalog.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/auth/login}. Validated with {@code @Valid} in AuthController. */
public record LoginRequest(
        @NotBlank(message = "Email is required") @Email(message = "Email must be valid") String email,
        @NotBlank(message = "Password is required") String password
) {}
