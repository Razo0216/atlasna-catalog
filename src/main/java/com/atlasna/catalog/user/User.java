package com.atlasna.catalog.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * A user account (table {@code users}, created by Flyway migration V1).
 *
 * <p>Also implements Spring Security's {@link UserDetails}, so the entity loaded from the database
 * can be handed straight to Spring Security: the email is the username, the BCrypt hash is the
 * password, and the {@link Role} becomes a single granted authority.
 *
 * <p>Lombok generates getters, setters, a builder and constructors. {@code @Builder.Default}
 * keeps the field initialisers below when building with {@code User.builder()}.
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    /** Always stored normalized (see {@link #normalizeEmail}); unique across all accounts. */
    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash of the password. The plain password is never stored. */
    @Column(nullable = false)
    private String passwordHash;

    /** Stored as the enum name ("ADMIN"/"CUSTOMER"). New registrations are always CUSTOMER. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.CUSTOMER;

    /** Disabled accounts can't log in (Spring Security checks {@link #isEnabled()}). */
    @Builder.Default
    private boolean enabled = true;

    @Column(updatable = false)
    private Instant createdAt;

    /** JPA lifecycle hook: runs just before the first INSERT. */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Emails are stored and looked up in this canonical form so login is case-insensitive. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The role as a Spring Security authority. The "ROLE_" prefix is required:
     * {@code hasRole('ADMIN')} checks for the authority "ROLE_ADMIN".
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Spring Security compares the login password against this BCrypt hash. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** The email is the login identifier (and the JWT subject). */
    @Override
    public String getUsername() {
        return email;
    }

    // Account expiry, locking and credential expiry aren't modelled yet, so these are always true.

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
