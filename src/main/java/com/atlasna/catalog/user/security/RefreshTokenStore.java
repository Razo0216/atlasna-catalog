package com.atlasna.catalog.user.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh tokens in Redis (ADR-0009). A refresh token is an opaque 256-bit random value — not a JWT, because it must
 * be revocable. Only its SHA-256 hash is stored, so a Redis dump contains nothing usable.
 *
 * <p>Each login starts a <b>family</b>. Every refresh <b>consumes</b> the presented token (single use, atomic Lua
 * script) and issues the next one in the same family. A consumed token is remembered until its original expiry; if it
 * is ever presented again, a copy exists (stolen, or replayed by an attacker) → the whole family is revoked, so both
 * the thief and the real user must log in again.
 *
 * <p><b>Grace window</b> (default 30 s): a token consumed moments ago may be presented <i>once</i> more without
 * counting as reuse. That's the honest case of a lost response — the page reloaded or the phone lost signal while the
 * refresh was in flight, so the browser never stored the successor cookie and sends the old one again. The orphaned
 * successor is revoked and a fresh one issued, so the family still has exactly one live token. The trade-off (an
 * attacker replaying within those seconds) is bounded: one extra use, inside 30 s, and the real user's next refresh
 * then fails and sends them to log in. ADR-0009 "Grace window".
 *
 * <pre>
 * atlasna:v1:refresh:{hash}            → "userId|familyId"   (TTL = token lifetime, sliding 30 days)
 * atlasna:v1:refresh-used:{hash}       → "userId|familyId"   (TTL = what was left of the token's lifetime)
 * atlasna:v1:refresh-grace:{hash}      → "userId|familyId"   (TTL = grace window; deleted on its one use)
 * atlasna:v1:refresh-family:{familyId} → hash of the family's current token
 * atlasna:v1:refresh-user:{userId}     → set of the user's family ids (for "log out everywhere")
 * </pre>
 */
@Slf4j
@Component
public class RefreshTokenStore {

    @ConfigurationProperties(prefix = "atlasna.refresh-token")
    public record Properties(Duration lifetime, String cookieName, boolean cookieSecure, Duration reuseGrace) {
        public Properties {
            if (lifetime == null) lifetime = Duration.ofDays(30);
            if (reuseGrace == null) reuseGrace = Duration.ofSeconds(30);
            if (cookieName == null || cookieName.isBlank()) cookieName = "atlasna_refresh";
        }
    }

    /** What a consumed token said about itself. */
    public record Consumed(Outcome outcome, long userId, String familyId) {}

    /** GRACE: a just-consumed token presented once more within the grace window (treat like OK). */
    public enum Outcome { OK, GRACE, REUSED, INVALID }

    private static final String PREFIX = "atlasna:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final Properties properties;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> consume;

    public RefreshTokenStore(StringRedisTemplate redis, Properties properties) {
        this.redis = redis;
        this.properties = properties;
        this.consume = RedisScript.of(new ClassPathResource("auth/refresh-consume.lua"), List.class);
    }

    public Properties properties() {
        return properties;
    }

    /** A new login: new family, first token. Returns the raw token (only ever sent to the client, in the cookie). */
    public String startFamily(long userId) {
        String familyId = UUID.randomUUID().toString();
        redis.opsForSet().add(userKey(userId), familyId);
        redis.expire(userKey(userId), properties.lifetime());
        return issue(userId, familyId);
    }

    /** The next token of a family (after a successful {@link #consume}). */
    public String issue(long userId, String familyId) {
        String raw = newToken();
        String hash = hash(raw);
        Duration ttl = properties.lifetime();
        redis.opsForValue().set(tokenKey(hash), userId + "|" + familyId, ttl);
        redis.opsForValue().set(familyKey(familyId), hash, ttl);
        redis.expire(userKey(userId), ttl);
        return raw;
    }

    /** Uses up {@code raw}. On {@link Outcome#REUSED} the family has already been revoked here. */
    public Consumed consume(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 200) return new Consumed(Outcome.INVALID, 0, null);
        String hash = hash(raw);
        List<?> result = redis.execute(consume, List.of(tokenKey(hash), usedKey(hash), graceKey(hash)),
                String.valueOf(properties.reuseGrace().toMillis()));
        Outcome outcome = Outcome.valueOf(String.valueOf(result.get(0)));
        if (outcome == Outcome.INVALID) return new Consumed(outcome, 0, null);
        String[] parts = String.valueOf(result.get(1)).split("\\|", 2);
        Consumed consumed = new Consumed(outcome, Long.parseLong(parts[0]), parts[1]);
        if (outcome == Outcome.GRACE) {
            // The family must still be alive (not logged out / revoked meanwhile)…
            String successor = redis.opsForValue().getAndDelete(familyKey(consumed.familyId()));
            if (successor == null) return new Consumed(Outcome.INVALID, 0, null);
            // …and its successor, which the client never received, goes away: one live token per family.
            redis.delete(tokenKey(successor));
            log.info("Refresh token presented again within the grace window for user {}: re-issuing", consumed.userId());
        }
        if (outcome == Outcome.REUSED) {
            log.warn("Refresh token reuse detected for user {}: revoking the session family", consumed.userId());
            revokeFamily(consumed.userId(), consumed.familyId());
        }
        return consumed;
    }

    /** Logout of this device: the family's current token stops working. */
    public void revokeFamily(long userId, String familyId) {
        String current = redis.opsForValue().getAndDelete(familyKey(familyId));
        if (current != null) redis.delete(tokenKey(current));
        redis.opsForSet().remove(userKey(userId), familyId);
    }

    /** Logout everywhere (password changed, account disabled). */
    public void revokeAll(long userId) {
        Set<String> families = redis.opsForSet().members(userKey(userId));
        if (families != null) families.forEach(f -> revokeFamily(userId, f));
        redis.delete(userKey(userId));
    }

    /** The family of a live token, without consuming it (logout with the current cookie). */
    public Consumed peek(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 200) return new Consumed(Outcome.INVALID, 0, null);
        String value = redis.opsForValue().get(tokenKey(hash(raw)));
        if (value == null) return new Consumed(Outcome.INVALID, 0, null);
        String[] parts = value.split("\\|", 2);
        return new Consumed(Outcome.OK, Long.parseLong(parts[0]), parts[1]);
    }

    private static String newToken() {
        byte[] bytes = new byte[32]; // 256 bits
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tokenKey(String hash) {
        return PREFIX + "refresh:" + hash;
    }

    private static String usedKey(String hash) {
        return PREFIX + "refresh-used:" + hash;
    }

    private static String graceKey(String hash) {
        return PREFIX + "refresh-grace:" + hash;
    }

    private static String familyKey(String familyId) {
        return PREFIX + "refresh-family:" + familyId;
    }

    private static String userKey(long userId) {
        return PREFIX + "refresh-user:" + userId;
    }
}
