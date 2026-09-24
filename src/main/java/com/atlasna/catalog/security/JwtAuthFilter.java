package com.atlasna.catalog.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests that carry a JWT. Registered in SecurityConfig before Spring's
 * username/password filter, and runs once per request.
 *
 * <p>With a valid {@code Authorization: Bearer <token>} header, it loads the user and stores them in
 * the {@link SecurityContextHolder}; the authorization rules and {@code @PreAuthorize} checks read
 * the user and their role from there. Without a header, or with an unusable token, the request simply
 * continues unauthenticated. This filter never rejects a request itself: it only establishes identity.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        try {
            authenticate(token, request);
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            // Malformed, expired, or orphaned token: continue as anonymous so public
            // endpoints still work and protected ones are rejected by the security rules.
            log.debug("Ignoring unusable bearer token: {}", e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Verifies the token and marks the request as authenticated.
     *
     * <p>The user is reloaded from the database on every request rather than trusted from the token,
     * so a deleted user or a changed role takes effect immediately, even while an old token is unexpired.
     */
    private void authenticate(String token, HttpServletRequest request) {
        String userEmail = jwtService.extractUsername(token);
        boolean notAlreadyAuthenticated = SecurityContextHolder.getContext().getAuthentication() == null;

        if (userEmail != null && notAlreadyAuthenticated) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
            if (jwtService.isTokenValid(token, userDetails)) {
                // Credentials are null: the token has already been verified, and the password isn't needed again.
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
    }
}
