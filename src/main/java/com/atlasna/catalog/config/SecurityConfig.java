package com.atlasna.catalog.config;

import com.atlasna.catalog.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * The security rulebook: how requests are authenticated and which ones are allowed.
 *
 * <p>Two layers of authorization:
 * <ol>
 *   <li>URL rules (below): who may reach a URL at all — public vs. logged-in.</li>
 *   <li>Method rules: {@code @PreAuthorize} on controller methods checks roles (e.g. ADMIN-only writes).
 *       {@code @EnableMethodSecurity} switches these on.</li>
 * </ol>
 *
 * <p>Status codes: no/invalid token on a protected URL gives 401; a valid token with the wrong role gives 403.
 *
 * <p>Spring Boot 4 / Security 7 notes: {@code DaoAuthenticationProvider} takes the UserDetailsService in its
 * constructor (the password encoder is set afterwards), and HTTP-method matchers must use the
 * {@link HttpMethod} enum; a string like "GET" would be treated as a URL pattern.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthFilter jwtAuthFilter;

    /**
     * The filter chain every request passes through.
     *
     * @param exceptionResolver Spring MVC's resolver, used so security errors are rendered by
     *                          GlobalExceptionHandler like all other errors
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver
    ) throws Exception {
        http
                // CSRF protection is off because this API is stateless and authenticates only via the
                // Authorization: Bearer header, which browsers never attach automatically. If auth ever
                // moves to cookies (session or JWT-in-cookie), CSRF protection must be re-enabled.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // No HTTP session: every request must carry its own token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Checked top to bottom; the first match wins, so the catch-all must stay last.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                // Unauthenticated access to a protected endpoint -> 401 with the same ApiError
                // body as every other error, via GlobalExceptionHandler.
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) ->
                        exceptionResolver.resolveException(request, response, null, authException)))
                .authenticationProvider(authenticationProvider())
                // Read the JWT and establish the user before Spring's own authentication filters run.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Checks email + password at login: loads the user via UserDetailsService and compares BCrypt hashes. */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /** Exposes Spring's AuthenticationManager so AuthService can call it during login. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** BCrypt: slow, salted hashing, so a stolen database can't be reversed into passwords quickly. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Which browser origins may call this API. Only affects browsers (Postman and curl ignore CORS).
     * Add the real frontend domain here when deploying.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
