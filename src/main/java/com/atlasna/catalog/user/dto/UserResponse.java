package com.atlasna.catalog.user.dto;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;

/**
 * Public view of a {@link User}. Exists so the entity (and its password hash) is never
 * serialized into an API response. {@code phone} is {@code +213XXXXXXXXX}, or null for accounts
 * created without one (e.g. the bootstrap admin).
 */
public record UserResponse(Long id, String fullName, String email, String phone, Role role, boolean mustChangePassword) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole(),
                user.isMustChangePassword());
    }
}
