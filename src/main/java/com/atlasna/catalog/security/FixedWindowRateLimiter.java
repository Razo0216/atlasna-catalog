package com.atlasna.catalog.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window counter: at most {@code maxHits} per key per {@code window}.
 * Per-instance only — if the app ever runs on several instances, move this to a shared store (e.g. Redis).
 *
 * <p>Each key gets a window that starts at its first hit and lasts {@code window}. Hits inside the window
 * are counted; once the count reaches {@code maxHits} the key is blocked until the window ends, and then
 * counting starts over. Thread-safe via {@link ConcurrentHashMap#compute}. The {@link Clock} is injected
 * so tests can control time.
 */
public class FixedWindowRateLimiter {

    /** When the map grows past this size, expired windows are removed to bound memory use. */
    private static final int PRUNE_THRESHOLD = 10_000;

    private final int maxHits;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxHits, Duration window, Clock clock) {
        this.maxHits = maxHits;
        this.window = window;
        this.clock = clock;
    }

    /** Seconds until {@code key} may proceed again, or 0 if it is not currently blocked. */
    public long retryAfterSeconds(String key) {
        Window w = windows.get(key);
        Instant now = clock.instant();
        if (w == null || w.hits < maxHits || !now.isBefore(w.end)) {
            return 0;
        }
        long millis = Duration.between(now, w.end).toMillis();
        // Round up so clients never retry a fraction of a second too early.
        return Math.max(1, (millis + 999) / 1000);
    }

    /** Counts one hit for {@code key}, starting a new window if there is none or the old one has ended. */
    public void record(String key) {
        Instant now = clock.instant();
        windows.compute(key, (k, w) ->
                w == null || !now.isBefore(w.end) ? new Window(now.plus(window), 1) : new Window(w.end, w.hits + 1));
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.values().removeIf(w -> !now.isBefore(w.end));
        }
    }

    /** Forgets {@code key}'s count, e.g. after a successful login. */
    public void reset(String key) {
        windows.remove(key);
    }

    public void clear() {
        windows.clear();
    }

    /** Immutable state for one key: when its window ends and how many hits it has seen. */
    private record Window(Instant end, int hits) {}
}
