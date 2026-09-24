# Task: Build Atlasna Phase 1 Backend — Auth + Catalog

## Context

This is `atlasna-catalog`, a Spring Boot 4 / Java 21 project generated via
start.spring.io (dependencies: Web, Data JPA, PostgreSQL Driver, Security,
Validation, Lombok). Package root: `com.atlasna.catalog`.

**Business model:** single-store marketplace (Atlasna) — no multi-vendor
support. Two roles: `ADMIN` (manages the catalog) and `CUSTOMER` (browses and
orders).

**Goal of this task:** implement JWT-based authentication (register/login)
and a product catalog API (public browsing, admin-only writes), fully
tested and running against PostgreSQL.

## Important — Spring Boot 4 API notes (read before writing SecurityConfig)

This project uses **Spring Boot 4.1 / Spring Security 7**, which changed two
APIs from the older Spring Boot 3 style still shown in most tutorials online:

1. `DaoAuthenticationProvider` no longer takes a `PasswordEncoder` in its
   constructor. Construct it as `new DaoAuthenticationProvider(userDetailsService)`
   then call `.setPasswordEncoder(...)` on the instance.
2. `HttpSecurity.authorizeHttpRequests(...).requestMatchers(...)` — when
   restricting by HTTP method, you **must** pass `org.springframework.http.HttpMethod.GET`
   as the first argument, not the string `"GET"`. Passing a string gets
   interpreted as an additional URL pattern, not a method, and silently
   produces the wrong security rule (a real bug, not just a warning).

**Verify current API shape before writing security code**: run
`mvn dependency:build-classpath` or inspect the resolved
`spring-security-core`/`spring-security-config` jars directly if anything
here seems off — do not assume tutorials matching Spring Boot 3 are accurate
for this project's Spring Boot 4 dependency versions.

After every file below, run `./mvnw -q compile` and fix any errors before
moving to the next file. Do not batch all files and compile once at the end.

---

## 1. Add dependencies to `pom.xml`

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

Run `./mvnw -q compile` to confirm dependencies resolve.

---

## 2. `src/main/resources/application.yml`

Delete `application.properties` if it exists; create `application.yml`:

```yaml
spring:
  application:
    name: atlasna-catalog
  datasource:
    url: jdbc:postgresql://localhost:5432/atlasna
    username: atlasna
    password: atlasna_dev_password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
    open-in-view: false

server:
  port: 8080

atlasna:
  jwt:
    secret: ${ATLASNA_JWT_SECRET:dev-only-secret-change-me-please-this-must-be-long-and-random}
    expiration-minutes: 60

logging:
  level:
    com.atlasna: DEBUG
    org.springframework.security: INFO
```

---

## 3. `docker-compose.yml` (repo root)

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: atlasna-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: atlasna
      POSTGRES_USER: atlasna
      POSTGRES_PASSWORD: atlasna_dev_password
    ports:
      - "5432:5432"
    volumes:
      - atlasna_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U atlasna -d atlasna"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  atlasna_pgdata:
```

---

## 4. `src/main/java/com/atlasna/catalog/user/Role.java`

```java
package com.atlasna.catalog.user;

/**
 * Single-store model: ADMIN manages the catalog, CUSTOMER shops.
 * A future SELLER role slots in here without touching anything else.
 */
public enum Role {
    ADMIN,
    CUSTOMER
}
```

---

## 5. `src/main/java/com/atlasna/catalog/user/User.java`

```java
package com.atlasna.catalog.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.CUSTOMER;

    @Builder.Default
    private boolean enabled = true;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
```

---

## 6. `src/main/java/com/atlasna/catalog/user/UserRepository.java`

```java
package com.atlasna.catalog.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

---

## 7. DTOs — `src/main/java/com/atlasna/catalog/user/dto/`

**RegisterRequest.java**
```java
package com.atlasna.catalog.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Full name is required") String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Email must be valid") String email,
        @NotBlank(message = "Password is required") @Size(min = 8, message = "Password must be at least 8 characters") String password
) {}
```

**LoginRequest.java**
```java
package com.atlasna.catalog.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
```

**UserResponse.java**
```java
package com.atlasna.catalog.user.dto;

import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;

public record UserResponse(Long id, String fullName, String email, Role role) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole());
    }
}
```

**AuthResponse.java**
```java
package com.atlasna.catalog.user.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}
```

---

## 8. `src/main/java/com/atlasna/catalog/security/JwtProperties.java`

```java
package com.atlasna.catalog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlasna.jwt")
public record JwtProperties(String secret, long expirationMinutes) {}
```

---

## 9. `src/main/java/com/atlasna/catalog/security/JwtService.java`

```java
package com.atlasna.catalog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.expirationMinutes();
    }

    public String generateToken(UserDetails userDetails) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationMinutes * 60);
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("roles", userDetails.getAuthorities().stream().map(Object::toString).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public long getExpirationSeconds() {
        return expirationMinutes * 60;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        return resolver.apply(claims);
    }
}
```

---

## 10. `src/main/java/com/atlasna/catalog/security/UserDetailsServiceImpl.java`

```java
package com.atlasna.catalog.security;

import com.atlasna.catalog.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));
    }
}
```

---

## 11. `src/main/java/com/atlasna/catalog/security/JwtAuthFilter.java`

```java
package com.atlasna.catalog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
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
        String userEmail = jwtService.extractUsername(token);
        boolean notAlreadyAuthenticated = SecurityContextHolder.getContext().getAuthentication() == null;

        if (userEmail != null && notAlreadyAuthenticated) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
            if (jwtService.isTokenValid(token, userDetails)) {
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

---

## 12. `src/main/java/com/atlasna/catalog/config/SecurityConfig.java`

**Remember the two Spring Boot 4 gotchas from the top of this doc.**

```java
package com.atlasna.catalog.config;

import com.atlasna.catalog.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

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
```

---

## 13. Exceptions — `src/main/java/com/atlasna/catalog/common/exception/`

**EmailAlreadyInUseException.java**
```java
package com.atlasna.catalog.common.exception;

public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException(String email) {
        super("An account with email '" + email + "' already exists");
    }
}
```

**InvalidCredentialsException.java**
```java
package com.atlasna.catalog.common.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
```

**ResourceNotFoundException.java**
```java
package com.atlasna.catalog.common.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

**ApiError.java**
```java
package com.atlasna.catalog.common.exception;

import java.time.Instant;
import java.util.Map;

public record ApiError(Instant timestamp, int status, String error, String message, Map<String, String> fieldErrors) {
    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message, null);
    }
    public static ApiError ofFieldErrors(int status, String error, String message, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, fieldErrors);
    }
}
```

**GlobalExceptionHandler.java**
```java
package com.atlasna.catalog.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.ofFieldErrors(
                HttpStatus.BAD_REQUEST.value(), "Validation Failed", "One or more fields are invalid", fieldErrors));
    }

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ApiError> handleEmailInUse(EmailAlreadyInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Email Already In Use", ex.getMessage()));
    }

    @ExceptionHandler({InvalidCredentialsException.class, BadCredentialsException.class})
    public ResponseEntity<ApiError> handleBadCredentials(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", "Invalid email or password"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(HttpStatus.FORBIDDEN.value(), "Forbidden", "You don't have permission to do that"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "Something went wrong. Please try again."));
    }
}
```

---

## 14. `src/main/java/com/atlasna/catalog/user/AuthService.java`

```java
package com.atlasna.catalog.user;

import com.atlasna.catalog.common.exception.EmailAlreadyInUseException;
import com.atlasna.catalog.common.exception.InvalidCredentialsException;
import com.atlasna.catalog.security.JwtService;
import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import com.atlasna.catalog.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
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
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyInUseException(request.email());
        }
        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(user);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, "Bearer", jwtService.getExpirationSeconds(), UserResponse.from(user));
    }
}
```

---

## 15. `src/main/java/com/atlasna/catalog/user/AuthController.java`

```java
package com.atlasna.catalog.user;

import com.atlasna.catalog.user.dto.AuthResponse;
import com.atlasna.catalog.user.dto.LoginRequest;
import com.atlasna.catalog.user.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
```

---

## 16. Product domain — `src/main/java/com/atlasna/catalog/product/`

**Category.java**
```java
package com.atlasna.catalog.product;

public enum Category {
    ELECTRONICS, FASHION, HOME_AND_KITCHEN, BEAUTY, GROCERIES, SPORTS, BOOKS
}
```

**Product.java**
```java
package com.atlasna.catalog.product;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Single-store model: no sellerId. Add it later if multi-vendor ever happens — nothing else here changes. */
@Entity
@Table(name = "products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

**ProductRepository.java**
```java
package com.atlasna.catalog.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByActiveTrue(Pageable pageable);
    Page<Product> findByActiveTrueAndCategory(Category category, Pageable pageable);
    Page<Product> findByActiveTrueAndNameContainingIgnoreCase(String name, Pageable pageable);
}
```

**dto/ProductRequest.java**
```java
package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.Category;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank(message = "Name is required") @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull(message = "Price is required") @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than 0") BigDecimal price,
        @NotNull(message = "Category is required") Category category,
        @NotNull(message = "Stock quantity is required") @Min(value = 0, message = "Stock quantity cannot be negative") Integer stockQuantity
) {}
```

**dto/ProductResponse.java**
```java
package com.atlasna.catalog.product.dto;

import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(Long id, String name, String description, BigDecimal price, Category category, Integer stockQuantity, Instant createdAt) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCategory(), product.getStockQuantity(), product.getCreatedAt());
    }
}
```

**ProductService.java**
```java
package com.atlasna.catalog.product;

import com.atlasna.catalog.common.exception.ResourceNotFoundException;
import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Category category, String search, Pageable pageable) {
        Page<Product> page;
        if (search != null && !search.isBlank()) {
            page = productRepository.findByActiveTrueAndNameContainingIgnoreCase(search, pageable);
        } else if (category != null) {
            page = productRepository.findByActiveTrueAndCategory(category, pageable);
        } else {
            page = productRepository.findByActiveTrue(pageable);
        }
        return page.map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long id) {
        return ProductResponse.from(getActiveOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = Product.builder()
                .name(request.name()).description(request.description()).price(request.price())
                .category(request.category()).stockQuantity(request.stockQuantity()).build();
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getOrThrow(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(request.category());
        product.setStockQuantity(request.stockQuantity());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = getOrThrow(id);
        product.setActive(false); // soft delete — preserves order history integrity later
    }

    private Product getOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
    }

    private Product getActiveOrThrow(Long id) {
        Product product = getOrThrow(id);
        if (!product.isActive()) {
            throw new ResourceNotFoundException("Product " + id + " not found");
        }
        return product;
    }
}
```

**ProductController.java**
```java
package com.atlasna.catalog.product;

import com.atlasna.catalog.product.dto.ProductRequest;
import com.atlasna.catalog.product.dto.ProductResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(required = false) Category category,
                                       @RequestParam(required = false) String search,
                                       Pageable pageable) {
        return productService.list(category, search, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 17. Update the main application class

`src/main/java/com/atlasna/catalog/AtlasnaCatalogApplication.java`:

```java
package com.atlasna.catalog;

import com.atlasna.catalog.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class AtlasnaCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtlasnaCatalogApplication.class, args);
    }
}
```

---

## 18. Dev data seeder

`src/main/java/com/atlasna/catalog/config/DataSeeder.java`:

```java
package com.atlasna.catalog.config;

import com.atlasna.catalog.product.Category;
import com.atlasna.catalog.product.Product;
import com.atlasna.catalog.product.ProductRepository;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        seedAdmin();
        seedProducts();
    }

    private void seedAdmin() {
        String adminEmail = "admin@atlasna.dz";
        if (userRepository.existsByEmail(adminEmail)) return;
        User admin = User.builder()
                .fullName("Atlasna Admin").email(adminEmail)
                .passwordHash(passwordEncoder.encode("ChangeMe123!"))
                .role(Role.ADMIN).build();
        userRepository.save(admin);
        log.info("Seeded admin -> {} / ChangeMe123! (change outside dev)", adminEmail);
    }

    private void seedProducts() {
        if (productRepository.count() > 0) return;
        productRepository.saveAll(List.of(
                Product.builder().name("Atlas Studio Wireless Headphones")
                        .description("Over-ear, ANC, 30h battery.")
                        .price(new BigDecimal("4900.00")).category(Category.ELECTRONICS).stockQuantity(50).build(),
                Product.builder().name("Desert Runner Sneakers")
                        .description("Breathable mesh running shoes.")
                        .price(new BigDecimal("7200.00")).category(Category.FASHION).stockQuantity(80).build(),
                Product.builder().name("Ceramic Tea Set")
                        .description("6-piece traditional tea set.")
                        .price(new BigDecimal("3100.00")).category(Category.HOME_AND_KITCHEN).stockQuantity(30).build()
        ));
        log.info("Seeded 3 demo products");
    }
}
```

---

## 19. Test setup

`src/test/resources/application-test.yml`:
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:atlasna_test;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: create-drop
    database-platform: org.hibernate.dialect.H2Dialect
atlasna:
  jwt:
    secret: test-only-secret-value-not-used-in-any-real-environment-at-all
    expiration-minutes: 60
```

Update `AtlasnaCatalogApplicationTests.java` to add `@ActiveProfiles("test")` above the class declaration (import `org.springframework.test.context.ActiveProfiles`).

---

## Final verification steps

1. `./mvnw -q compile` — must be silent (no errors).
2. `./mvnw test` — all tests must pass. If `spring-boot-starter-test` doesn't already provide Mockito/AssertJ in this Spring Boot 4 version, check `pom.xml`'s resolved dependency tree (`./mvnw dependency:tree`) and add whatever's missing — don't assume, verify against what's actually resolved.
3. Start Postgres: `docker compose up -d` (create `~/.docker` Desktop app running first if needed).
4. Run the app: `./mvnw spring-boot:run`
5. Test with curl:
   ```bash
   curl http://localhost:8080/api/products
   curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
     -d '{"fullName":"Test User","email":"test@example.com","password":"SuperSecret1"}'
   ```
   Confirm registration returns a JWT, and product listing works with no auth.
6. Log in as the seeded admin (`admin@atlasna.dz` / `ChangeMe123!`), create a product, then confirm a **customer's** token gets `403 Forbidden` on the same request.
7. Commit: `git add -A && git commit -m "Phase 1: JWT auth + product catalog API"` then `git push`.
