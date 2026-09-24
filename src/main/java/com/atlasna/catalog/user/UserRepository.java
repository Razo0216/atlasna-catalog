package com.atlasna.catalog.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Database access for {@link User}. Spring Data generates the SQL from the method names
 * (e.g. {@code findByEmail} becomes {@code SELECT ... WHERE email = ?}).
 * Callers must pass emails already normalized with {@link User#normalizeEmail}.
 */
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByRole(Role role);
}
