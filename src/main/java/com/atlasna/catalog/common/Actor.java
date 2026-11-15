package com.atlasna.catalog.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who did something, captured when an event is PUBLISHED (the audit log is written later on another thread, where the
 * security context and the request are gone). {@code id} null = the system (jobs, startup) or an anonymous caller.
 *
 * @param role "ADMIN", "STAFF", "DRIVER", "CUSTOMER", "ANONYMOUS" or "SYSTEM"
 * @param ip   the TCP peer address of the request, null outside a request
 * @param at   when it happened (publish time — also what makes each event, and its audit entry id, unique)
 */
public record Actor(Long id, String role, String ip, Instant at) {

    public static Actor system() {
        return new Actor(null, "SYSTEM", null, Instant.now());
    }

    /** Someone known by id and role, outside the security context (e.g. the customer who placed an order). */
    public static Actor of(Long id, String role) {
        return new Actor(id, role, currentIp(), Instant.now());
    }

    public static Actor current() {
        String ip = currentIp();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof HasUserId user)) {
            return new Actor(null, ip == null ? "SYSTEM" : "ANONYMOUS", ip, Instant.now());
        }
        String role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse("UNKNOWN");
        return new Actor(user.getId(), role, ip, Instant.now());
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
