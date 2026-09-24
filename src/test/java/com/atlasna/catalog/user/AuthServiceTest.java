package com.atlasna.catalog.user;

import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.security.JwtService;
import com.atlasna.catalog.security.LoginRateLimiter;
import com.atlasna.catalog.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService} with mocked collaborators (no Spring context), used for
 * scenarios that are hard to trigger for real, like two registrations racing on the same email.
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthService authService = new AuthService(
            userRepository, passwordEncoder, mock(AuthenticationManager.class), jwtService, mock(LoginRateLimiter.class));

    @Test
    void concurrentDuplicateRegistrationIsReportedAsEmailInUse() {
        // Simulates the race: the existence check passes, but another request inserts
        // the same email before this one, so the unique constraint rejects the insert.
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Jane", "Jane@Example.com", "SuperSecret1")))
                .isInstanceOf(EmailAlreadyInUseException.class)
                .hasMessageContaining("jane@example.com");
        verifyNoInteractions(jwtService);
    }
}
