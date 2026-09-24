package com.atlasna.catalog.user;

import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.common.exception.InvalidCredentialsException;
import com.atlasna.catalog.security.JwtService;
import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import com.atlasna.catalog.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyInUseException(email);
        }
        User user = User.builder()
                .fullName(request.fullName())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CUSTOMER)
                .build();
        try {
            // Flush now so a unique-constraint violation surfaces here rather than at commit.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // The existsByEmail check above can race with a concurrent registration of the
            // same email; the email unique constraint is the real guard.
            throw new EmailAlreadyInUseException(email);
        }
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, "Bearer", jwtService.getExpirationSeconds(), UserResponse.from(user));
    }
}
