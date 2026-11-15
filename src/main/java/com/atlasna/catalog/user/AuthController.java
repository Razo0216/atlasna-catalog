package com.atlasna.catalog.user;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.ratelimit.ClientIpResolver;
import com.atlasna.catalog.ratelimit.RateLimitPolicies;
import com.atlasna.catalog.ratelimit.RateLimiter;
import com.atlasna.catalog.user.AuthService.Session;
import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.security.RefreshTokenStore;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import com.atlasna.catalog.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints. Register and login are public (SecurityConfig); {@code /me} needs a valid token.
 * Both return an {@link AuthResponse} containing a JWT to send as {@code Authorization: Bearer <token>}.
 * {@code @Valid} rejects bad input with 400 before the service is called.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final RateLimitPolicies limits;
    private final ClientIpResolver clientIp;
    private final RefreshTokenStore refreshTokens;

    /** The custom header a cross-site form can't send and a cross-site fetch can't add without a CORS preflight. */
    static final String REFRESH_HEADER = "X-Atlasna-Refresh";

    /**
     * Creates a CUSTOMER account and logs it in. 201 on success, 409 if the email or phone is taken,
     * 429 after too many sign-ups from one client IP (spam accounts).
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        rateLimiter.consume(limits.registrationsPerIp(), clientIp.resolve(httpRequest));
        return withCookie(ResponseEntity.status(HttpStatus.CREATED), authService.withRefreshToken(authService.register(request)));
    }

    /**
     * Exchanges email + password for a JWT. 401 on bad credentials, 429 when rate-limited.
     * The client IP (proxy headers trusted only from configured proxies) is passed along for per-IP rate limiting.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return withCookie(ResponseEntity.ok(), authService.withRefreshToken(authService.login(request, clientIp.resolve(httpRequest))));
    }

    /**
     * A new access token from the httpOnly refresh cookie, and the next refresh token (rotation, ADR-0009).
     * Requires the {@code X-Atlasna-Refresh: 1} header (CSRF defence on top of SameSite=Strict). Any failure clears
     * the cookie: 401 REFRESH_TOKEN_INVALID / REFRESH_TOKEN_REUSED / ACCOUNT_DISABLED, 503 SESSION_STORE_UNAVAILABLE.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestHeader(value = REFRESH_HEADER, required = false) String marker,
                                                HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        requireMarker(marker);
        try {
            return withCookie(ResponseEntity.ok(), authService.refresh(cookieValue(httpRequest)));
        } catch (ApiException e) {
            httpResponse.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
            throw e;
        }
    }

    /** Ends this device's session: the refresh token stops working and the cookie is removed. Always 204. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = REFRESH_HEADER, required = false) String marker,
                                       HttpServletRequest httpRequest) {
        requireMarker(marker);
        authService.logout(cookieValue(httpRequest));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    // ---------- cookie ----------

    private ResponseEntity<AuthResponse> withCookie(ResponseEntity.BodyBuilder builder, Session session) {
        if (session.refreshToken() != null) builder.header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken()).toString());
        return builder.body(session.body());
    }

    /** HttpOnly (JavaScript can't read it), Secure, SameSite=Strict, and only ever sent to /api/auth/*. */
    private ResponseCookie cookie(String value) {
        var p = refreshTokens.properties();
        return ResponseCookie.from(p.cookieName(), value).httpOnly(true).secure(p.cookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(p.lifetime()).build();
    }

    private ResponseCookie clearCookie() {
        var p = refreshTokens.properties();
        return ResponseCookie.from(p.cookieName(), "").httpOnly(true).secure(p.cookieSecure()).sameSite("Strict")
                .path("/api/auth").maxAge(0).build();
    }

    private String cookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) if (refreshTokens.properties().cookieName().equals(c.getName())) return c.getValue();
        return null;
    }

    private static void requireMarker(String marker) {
        if (!"1".equals(marker)) {
            throw ApiException.forbidden("CSRF_CHECK_FAILED", "Missing header " + REFRESH_HEADER + ": 1");
        }
    }

    /**
     * The logged-in user (re-read from the database). 401 without a valid token.
     * The principal is the {@link User} entity that JwtAuthFilter loaded for this request.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal User user) {
        return authService.currentUser(user.getId());
    }

    /** Replaces the password (required for staff/driver accounts created with a temporary one). */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal User user, @Valid @RequestBody ChangePasswordRequest r) {
        authService.changePassword(user.getId(), r.currentPassword(), r.newPassword());
    }

    public record ChangePasswordRequest(
            @jakarta.validation.constraints.NotBlank(message = "Current password is required") String currentPassword,
            @jakarta.validation.constraints.NotBlank(message = "New password is required")
            @jakarta.validation.constraints.Size(min = 8, message = "Password must be at least 8 characters") String newPassword) {}
}
