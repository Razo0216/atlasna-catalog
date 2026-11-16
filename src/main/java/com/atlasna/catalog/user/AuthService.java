package com.atlasna.catalog.user;

import com.atlasna.catalog.common.PhoneNumbers;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.common.exception.InvalidCredentialsException;
import com.atlasna.catalog.user.security.JwtService;
import com.atlasna.catalog.user.security.LoginRateLimiter;
import com.atlasna.catalog.user.security.RefreshTokenStore;
import com.atlasna.catalog.user.security.RefreshTokenStore.Consumed;
import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import com.atlasna.catalog.user.dto.UserResponse;
import com.atlasna.catalog.common.Actor;
import com.atlasna.catalog.common.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.DisabledException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final RefreshTokenStore refreshTokens;
    private final ApplicationEventPublisher events;

    /** An access token (body) and, when the session store is up, a refresh token for the httpOnly cookie. */
    public record Session(AuthResponse body, String refreshToken) {}

    /**
     * Creates a new CUSTOMER account (there is no way to self-register as ADMIN) and returns a token,
     * so the user is logged in right away.
     *
     * @throws EmailAlreadyInUseException if the email is already registered (409 EMAIL_ALREADY_IN_USE)
     * @throws ApiException 409 PHONE_ALREADY_IN_USE if the phone belongs to another account
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        // Fast path for the common case; the unique constraint below handles the race.
        // @AlgerianMobile already validated the format, so normalize() always has a value here.
        String phone = PhoneNumbers.normalize(request.phone()).orElseThrow();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyInUseException(email);
        }
        if (userRepository.existsByPhone(phone)) {
            throw phoneInUse();
        }
        User user = User.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CUSTOMER)
                .build();
        try {
            // Flush now so a unique-constraint violation surfaces here rather than at commit.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // The exists checks above can race with a concurrent registration of the same email or
            // phone; the unique indexes are the real guard. Report whichever one fired.
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("ux_users_phone")) {
                throw phoneInUse();
            }
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
     * @throws InvalidCredentialsException for an unknown email or wrong password (401 INVALID_CREDENTIALS)
     * @throws org.springframework.security.authentication.DisabledException correct password but disabled
     *         account (401 ACCOUNT_DISABLED); only possible after the password matched, see SecurityConfig
     */
    public AuthResponse login(LoginRequest request, String clientIp) {
        String email = User.normalizeEmail(request.email());
        try {
            loginRateLimiter.checkAndRecordAttempt(email, clientIp);
        } catch (TooManyRequestsException e) {
            loginFailed(email, "RATE_LIMITED", clientIp);
            throw e;
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            loginRateLimiter.recordFailure(email);
            loginFailed(email, "INVALID_CREDENTIALS", clientIp);
            throw new InvalidCredentialsException();
        } catch (DisabledException e) {
            loginFailed(email, "ACCOUNT_DISABLED", clientIp);
            throw e;
        }
        loginRateLimiter.recordSuccess(email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        return buildAuthResponse(user);
    }

    /**
     * The current user, re-read from the database so a changed role or phone shows up immediately.
     *
     * @param userId id of the authenticated principal
     */
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                        "Authentication is required to access this resource"));
    }

    /** Checks the current password, stores the new one, clears the "must change" flag. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.invalidField("currentPassword", "Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.invalidField("newPassword", "Choose a different password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // A changed password ends every other session: whoever knew the old one loses access now.
        revokeAllQuietly(userId);
        events.publishEvent(new UserAccountChanged(userId, "PASSWORD_CHANGED", user.getRole().name(), Actor.current()));
    }

    // ---------- refresh tokens (ADR-0009) ----------

    /**
     * Starts a refresh-token family for a fresh login or sign-up. If Redis is down the user is still logged in
     * (access token only, no cookie) — they'll simply have to log in again when it expires.
     */
    public Session withRefreshToken(AuthResponse response) {
        try {
            return new Session(response, refreshTokens.startFamily(response.user().id()));
        } catch (RuntimeException e) {
            log.warn("No refresh token issued (session store unavailable): {}", e.getMessage());
            return new Session(response, null);
        }
    }

    /**
     * Exchanges a refresh token for a new access token + the next refresh token (rotation). A token that was already
     * used revokes its whole family (401 REFRESH_TOKEN_REUSED). Redis down → 503: refreshing fails closed.
     */
    @Transactional(readOnly = true)
    public Session refresh(String rawRefreshToken) {
        Consumed consumed;
        try {
            consumed = refreshTokens.consume(rawRefreshToken);
        } catch (RuntimeException e) {
            log.warn("Refresh refused: session store unavailable ({})", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_STORE_UNAVAILABLE",
                    "Sessions can't be refreshed right now; log in again in a moment");
        }
        switch (consumed.outcome()) {
            case INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                    "Your session has expired. Please log in again");
            case REUSED -> throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "This session was used from elsewhere and has been closed. Please log in again");
            case OK, GRACE -> { }
        }
        User user = userRepository.findById(consumed.userId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            revokeAllQuietly(consumed.userId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, user == null ? "REFRESH_TOKEN_INVALID" : "ACCOUNT_DISABLED",
                    user == null ? "Your session has expired. Please log in again" : "This account is disabled");
        }
        String next = refreshTokens.issue(user.getId(), consumed.familyId());
        return new Session(buildAuthResponse(user), next);
    }

    /** Ends this device's session (its refresh family). Idempotent; never fails the logout. */
    public void logout(String rawRefreshToken) {
        try {
            Consumed live = refreshTokens.peek(rawRefreshToken);
            if (live.outcome() == RefreshTokenStore.Outcome.OK) refreshTokens.revokeFamily(live.userId(), live.familyId());
        } catch (RuntimeException e) {
            log.warn("Logout could not revoke the refresh token (session store unavailable): {}", e.getMessage());
        }
    }

    /** Logs the user out everywhere (password change, account disabled). Best effort if Redis is down. */
    public void revokeAllQuietly(long userId) {
        try {
            refreshTokens.revokeAll(userId);
        } catch (RuntimeException e) {
            log.warn("Could not revoke the sessions of user {} (session store unavailable): {}", userId, e.getMessage());
        }
    }

    /** For the audit log: the email tried and why it failed — never the password. */
    private void loginFailed(String email, String reason, String clientIp) {
        events.publishEvent(new LoginFailed(email, reason, new Actor(null, "ANONYMOUS", clientIp, java.time.Instant.now())));
    }

    private static ApiException phoneInUse() {
        return ApiException.conflict("PHONE_ALREADY_IN_USE", "An account with this phone number already exists");
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, "Bearer", jwtService.getExpirationSeconds(), UserResponse.from(user));
    }
}
