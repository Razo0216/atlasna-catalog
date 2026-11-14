package com.atlasna.catalog.user;

import com.atlasna.catalog.common.AlgerianMobile;
import com.atlasna.catalog.common.PhoneNumbers;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.user.dto.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;

/**
 * Staff and driver accounts (ADMIN only). New accounts get a random temporary password, shown ONCE in the response,
 * and must change it ({@code mustChangePassword}). Roles can't be changed afterwards and nobody can make an ADMIN here.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public record CreateStaffRequest(@NotBlank @Size(max = 120) String fullName,
                                     @NotBlank @Email String email,
                                     @AlgerianMobile String phone,
                                     @NotNull Role role) {}

    public record CreatedAccount(UserResponse user, String temporaryPassword) {}

    public record UpdateAccountRequest(@NotNull Boolean enabled) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatedAccount create(@Valid @RequestBody CreateStaffRequest r) {
        if (r.role() != Role.STAFF && r.role() != Role.DRIVER) {
            throw ApiException.invalidField("role", "Only STAFF or DRIVER accounts can be created here");
        }
        String email = User.normalizeEmail(r.email());
        if (users.existsByEmail(email)) throw new EmailAlreadyInUseException(email);
        String phone = r.phone() == null || r.phone().isBlank() ? null : PhoneNumbers.normalize(r.phone()).orElseThrow();
        if (phone != null && users.existsByPhone(phone)) {
            throw ApiException.conflict("PHONE_ALREADY_IN_USE", "An account with this phone number already exists");
        }
        String temporary = temporaryPassword();
        User user = users.saveAndFlush(User.builder().fullName(r.fullName().strip()).email(email).phone(phone)
                .passwordHash(passwordEncoder.encode(temporary)).role(r.role()).mustChangePassword(true).build());
        return new CreatedAccount(UserResponse.from(user), temporary);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<UserResponse> list(@RequestParam(required = false) Role role, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 100) throw ApiException.invalidField("size", "Size must be between 1 and 100");
        PageRequest p = PageRequest.of(page, size);
        return (role == null ? users.findAllByOrderByIdAsc(p) : users.findByRoleOrderByIdAsc(role, p)).map(UserResponse::from);
    }

    /** Enable or disable an account. A disabled account can't log in and its tokens stop working at once. */
    @PatchMapping("/{id}")
    @Transactional
    public UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateAccountRequest r, @AuthenticationPrincipal User admin) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User " + id + " not found"));
        if (user.getId().equals(admin.getId()) && !r.enabled()) {
            throw ApiException.businessRule("VALIDATION_FAILED", "You can't disable your own account");
        }
        user.setEnabled(r.enabled());
        return UserResponse.from(user);
    }

    private String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        return sb.toString();
    }
}
