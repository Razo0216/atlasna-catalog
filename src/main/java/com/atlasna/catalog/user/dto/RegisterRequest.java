package com.atlasna.catalog.user.dto;

import com.atlasna.catalog.common.AlgerianMobile;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/register}.
 * The {@code message} values are returned to the client as {@code fieldErrors} when validation fails.
 * {@code phone} accepts any common spelling of an Algerian mobile number; it is stored as {@code +213XXXXXXXXX}.
 */
public record RegisterRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 120, message = "Full name must be at most 120 characters") String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Email must be valid") String email,
        @NotBlank(message = "Password is required") @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotBlank(message = "Phone is required") @AlgerianMobile String phone
) {}
