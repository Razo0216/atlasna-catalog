package com.atlasna.catalog.config;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdminBootstrap}: creating the first admin, and the cases where it must refuse or do nothing. */
class AdminBootstrapTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(userRepository, passwordEncoder,
                new AdminBootstrapProperties(email, password, null));
    }

    @Test
    void createsAdminFromConfiguredCredentials() {
        when(userRepository.findByEmail("ops@atlasna.dz")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("a-long-admin-password")).thenReturn("hashed");

        bootstrap(" Ops@Atlasna.dz ", "a-long-admin-password").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ops@atlasna.dz");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getFullName()).isEqualTo("Atlasna Admin");
    }

    @Test
    void leavesExistingAccountUntouched() {
        User existing = User.builder().email("ops@atlasna.dz").role(Role.CUSTOMER).build();
        when(userRepository.findByEmail("ops@atlasna.dz")).thenReturn(Optional.of(existing));

        bootstrap("ops@atlasna.dz", "a-long-admin-password").run(null);

        verify(userRepository, never()).save(any());
        assertThat(existing.getRole()).isEqualTo(Role.CUSTOMER); // never silently promoted
    }

    @Test
    void refusesWeakPassword() {
        assertThatThrownBy(() -> bootstrap("ops@atlasna.dz", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesEmailWithoutPassword() {
        assertThatThrownBy(() -> bootstrap("ops@atlasna.dz", null).run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        bootstrap(null, null).run(null);

        verify(userRepository, never()).save(any());
    }
}
