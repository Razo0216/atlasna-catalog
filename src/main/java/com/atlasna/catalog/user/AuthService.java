package com.atlasna.catalog.user;

import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.common.exception.InvalidCredentialsException;
import com.atlasna.catalog.security.JwtService;
import com.atlasna.catalog.security.LoginRateLimiter;
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

/**
 * Registration and login business logic.
 *
 * <p>Both flows end by issuing a JWT for the user. Emails are normalized first (trim + lowercase)
 * so "Jane@Example.com" and "jane@example.com" are the same account.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;

    /**
     * Creates a new CUSTOMER account (there is no way to self-register as ADMIN) and returns a token,
     * so the user is logged in right away.
     *
     * @throws EmailAlreadyInUseException if the email is already registered (409)
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        // Fast path for the common case; the unique constraint below handles the race.
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

    /**
     * Verifies email + password and returns a token.
     *
     * <p>Order matters: the rate limiter is checked <em>before</em> the password, so a locked account
     * is refused even with the correct password (guessing gains nothing). Password checking is
     * delegated to Spring Security's AuthenticationManager (DaoAuthenticationProvider + BCrypt),
     * which also reports unknown emails as bad credentials.
     *
     * @param clientIp the caller's address, used for per-IP rate limiting
     * @throws com.atlasna.catalog.common.exception.TooManyRequestsException when rate-limited (429)
     * @throws InvalidCredentialsException for an unknown email or wrong password (401)
     */
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = User.normalizeEmail(request.email());
        loginRateLimiter.checkAndRecordAttempt(email, clientIp);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            loginRateLimiter.recordFailure(email);
            throw new InvalidCredentialsException();
        }
        loginRateLimiter.recordSuccess(email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, "Bearer", jwtService.getExpirationSeconds(), UserResponse.from(user));
    }
}
