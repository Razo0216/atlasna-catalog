package com.atlasna.catalog.user;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The active drivers, for the order board's "assign driver" picker (Phase 6). STAFF assign drivers too, but the user
 * administration list ({@code GET /api/admin/users}) is ADMIN only — so this read-only directory exposes just what
 * the picker needs: id, name, phone. Never emails or account details.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('STAFF','ADMIN')")
public class DriverDirectoryController {

    /** A small shop has a handful of drivers; the cap only guards against a runaway table. */
    static final int MAX_DRIVERS = 200;

    private final UserRepository users;

    public record DriverSummary(long id, String fullName, String phone) {}

    @GetMapping("/api/admin/drivers")
    @Transactional(readOnly = true)
    public List<DriverSummary> drivers() {
        return users.findByRoleOrderByIdAsc(Role.DRIVER, PageRequest.of(0, MAX_DRIVERS)).stream()
                .filter(User::isEnabled)
                .map(u -> new DriverSummary(u.getId(), u.getFullName(), u.getPhone()))
                .toList();
    }
}
