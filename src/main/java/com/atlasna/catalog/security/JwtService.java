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

/**
 * Creates and verifies the JWTs used as access tokens.
 *
 * <p>Tokens are signed with HMAC using {@code atlasna.jwt.secret}; jjwt picks HS256/384/512 from the
 * key length. The payload is only Base64-encoded (readable by anyone), so it holds nothing secret:
 * {@code sub} = the user's email, {@code roles}, {@code iat} (issued at) and {@code exp} (expiry).
 * Anyone without the secret can read a token but cannot forge or modify one.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.expirationMinutes();
    }

    /**
     * Issues a signed token for the user. The {@code roles} claim is informational for clients;
     * the server always reloads the user's current role from the database (see JwtAuthFilter).
     */
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

    /**
     * Returns the token's subject (the user's email).
     *
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has a bad signature, or is expired
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /** True if the token belongs to this user and hasn't expired. Never throws. */
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

    /** Parses the token, verifying its signature with our key, then reads one claim from the payload. */
    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        return resolver.apply(claims);
    }
}
