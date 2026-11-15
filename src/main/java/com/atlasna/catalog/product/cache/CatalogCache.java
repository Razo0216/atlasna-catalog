package com.atlasna.catalog.product.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cache-aside for PUBLIC catalog reads only (ADR-0010): category tree, product pages, listing/search pages.
 * Postgres stays the source of truth; nothing about orders, carts, users or exact stock is ever stored here.
 *
 * <ul>
 *   <li><b>Invalidation by generation.</b> Every key embeds a generation number kept in Redis
 *       ({@code atlasna:v1:catalog:generation}). Any catalog, stock or settings change increments it
 *       ({@link #invalidateAll()}), so every old entry becomes unreachable at once — including all the listing
 *       pages, which can't be enumerated — and simply expires. Readers take the generation BEFORE loading from the
 *       database, so a read racing with a write stores its (old) result under the old generation: never served.</li>
 *   <li><b>Stampede protection.</b> When a hot key is missing, only one request per key (per instance) loads it;
 *       concurrent requests for the same key wait for that result instead of all hitting Postgres.</li>
 *   <li><b>Redis is optional.</b> Every Redis call goes through a circuit breaker with short timeouts. If Redis is
 *       slow, down or the breaker is open, the request is served from Postgres (slower, still correct) and the
 *       {@code atlasna.cache.requests{result=error}} counter goes up. The cache never fails a request.</li>
 *   <li>Values are JSON (the app's own JsonMapper), never Java serialisation.</li>
 * </ul>
 */
@Slf4j
@Component
public class CatalogCache {

    public static final String PREFIX = "atlasna:v1:catalog:";
    static final String GENERATION_KEY = PREFIX + "generation";
    /** A follower waits this long for the request that is loading the key, then loads it itself. */
    private static final long FOLLOWER_WAIT_MS = 5_000;

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CatalogCacheProperties properties;
    private final CircuitBreaker breaker;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private final Counter bypassed;

    public CatalogCache(StringRedisTemplate redis, JsonMapper json, CatalogCacheProperties properties,
                        CircuitBreakerRegistry breakers, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.breaker = breakers.circuitBreaker("catalogCache");
        this.hits = counter(meters, "hit");
        this.misses = counter(meters, "miss");
        this.errors = counter(meters, "error");
        this.bypassed = counter(meters, "bypass");
    }

    public JavaType type(Class<?> raw, Class<?>... parameters) {
        return json.getTypeFactory().constructParametricType(raw, parameters);
    }

    public JavaType listOf(Class<?> element) {
        return json.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }

    /**
     * Returns the cached value for {@code name}, or loads it with {@code loader}, stores it for {@code ttl} and
     * returns it. Exceptions from the loader (e.g. 404) propagate and are not cached.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Duration ttl, JavaType type, Supplier<T> loader) {
        if (!properties.enabled()) {
            bypassed.increment();
            return loader.get();
        }
        Optional<String> generation = redisCall(() -> redis.opsForValue().get(GENERATION_KEY));
        if (generation == null) return loader.get(); // Redis unavailable: straight to the database
        String key = PREFIX + generation.orElse("0") + ":" + name;

        Optional<String> cached = redisCall(() -> redis.opsForValue().get(key));
        if (cached != null && cached.isPresent()) {
            try {
                T value = json.readValue(cached.get(), type);
                hits.increment();
                return value;
            } catch (RuntimeException e) {
                log.warn("Unreadable cache entry {} ({}); reloading", key, e.getMessage()); // e.g. after a DTO change
            }
        }
        misses.increment();

        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) return (T) awaitLeader(leader, loader);
        try {
            T value = loader.get();
            mine.complete(value);
            if (cached != null) { // Redis answered a moment ago: worth trying to store
                String body = json.writeValueAsString(value);
                redisCall(() -> {
                    redis.opsForValue().set(key, body, ttl);
                    return "";
                });
            }
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** Makes every cached catalog entry unreachable (they expire on their own TTL). Safe to call often. */
    public void invalidateAll() {
        if (!properties.enabled()) return;
        Optional<String> bumped = redisCall(() -> String.valueOf(redis.opsForValue().increment(GENERATION_KEY)));
        if (bumped == null) {
            // Redis unreachable: entries written before the outage may be served until their TTL (≤ a few minutes).
            log.warn("Could not invalidate the catalog cache (Redis unavailable); entries expire within their TTL");
        }
    }

    /** For tests/ops: the breaker state ("CLOSED", "OPEN", "HALF_OPEN"). */
    public String breakerState() {
        return breaker.getState().name();
    }

    private Object awaitLeader(CompletableFuture<Object> leader, Supplier<?> loader) {
        try {
            return leader.get(FOLLOWER_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re; // same answer as the leader (e.g. 404)
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            return loader.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loader.get();
        }
    }

    /**
     * Runs one Redis operation through the circuit breaker. Returns the (possibly empty) result, or {@code null}
     * when Redis failed or the breaker is open — callers then fall back to the database.
     */
    private Optional<String> redisCall(Supplier<String> operation) {
        try {
            return Optional.ofNullable(breaker.executeSupplier(operation));
        } catch (CallNotPermittedException open) {
            errors.increment(); // breaker open: don't even try Redis until it half-opens
            return null;
        } catch (RuntimeException e) {
            errors.increment();
            log.debug("Catalog cache unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Counter counter(MeterRegistry meters, String result) {
        return Counter.builder("atlasna.cache.requests")
                .description("Public catalog cache lookups by outcome")
                .tag("cache", "catalog").tag("result", result)
                .register(meters);
    }
}
