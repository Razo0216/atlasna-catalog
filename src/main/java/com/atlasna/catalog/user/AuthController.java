package com.atlasna.catalog.user;

import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import com.atlasna.catalog.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
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

    /** Creates a CUSTOMER account and logs it in. 201 on success, 409 if the email or phone is taken. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /**
     * Exchanges email + password for a JWT. 401 on bad credentials, 429 when rate-limited.
     * The client IP is passed along for per-IP rate limiting.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, httpRequest.getRemoteAddr()));
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
