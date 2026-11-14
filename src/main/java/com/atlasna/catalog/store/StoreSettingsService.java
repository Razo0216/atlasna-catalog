package com.atlasna.catalog.store;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.store.domain.StoreSettingsEntity;
import com.atlasna.catalog.store.domain.StoreSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Reads and updates the single settings row. Reads are cached for a short time because the product mapper
 * consults the line limits for every product in a list; an update refreshes the cache immediately.
 */
@Service
@RequiredArgsConstructor
public class StoreSettingsService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final StoreSettingsRepository repository;
    private final Clock clock;

    private volatile StoreSettings cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    @Transactional(readOnly = true)
    public StoreSettings current() {
        StoreSettings s = cached;
        if (s == null || clock.instant().isAfter(cachedAt.plus(CACHE_TTL))) {
            s = toView(load());
            cached = s;
            cachedAt = clock.instant();
        }
        return s;
    }

    /** Full replace with an optimistic-lock check (409 CONCURRENT_MODIFICATION when someone saved in between). */
    @Transactional
    public StoreSettings update(StoreSettings request) {
        StoreSettingsEntity e = load();
        if (e.getVersion() != request.version()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION",
                    "Settings were changed by someone else (version " + e.getVersion() + "). Reload and retry.");
        }
        if (!request.openingTime().isBefore(request.closingTime())) {
            throw ApiException.invalidField("closingTime", "Closing time must be after opening time");
        }
        e.setStoreOpen(request.storeOpen());
        e.setOpeningTime(request.openingTime());
        e.setClosingTime(request.closingTime());
        e.setSameDayCutoff(request.sameDayCutoff());
        e.setLeadTimeMinutes(request.leadTimeMinutes());
        e.setMaxDaysAhead(request.maxDaysAhead());
        e.setMaxItemWeightGrams(request.maxItemWeightGrams());
        e.setMaxOrderWeightGrams(request.maxOrderWeightGrams());
        e.setMaxLineQuantityUnit(request.maxLineQuantityUnit());
        e.setMaxLineQuantityWeight(request.maxLineQuantityWeight().setScale(3, RoundingMode.HALF_UP));
        e.setWeightTolerancePercent(request.weightTolerancePercent().setScale(2, RoundingMode.HALF_UP));
        e.setFailedDeliveryFlagCount(request.failedDeliveryFlagCount());
        e.setFailedDeliveryWindowDays(request.failedDeliveryWindowDays());
        StoreSettings saved = toView(repository.saveAndFlush(e));
        refresh(saved);
        return saved;
    }

    /** Drop the cached copy (tests and other writers). */
    public void evict() {
        cached = null;
    }

    private void refresh(StoreSettings s) {
        cached = s;
        cachedAt = clock.instant();
    }

    private StoreSettingsEntity load() {
        return repository.findById((short) 1)
                .orElseThrow(() -> new IllegalStateException("store_settings row missing; is Flyway V3 applied?"));
    }

    private static StoreSettings toView(StoreSettingsEntity e) {
        return new StoreSettings(e.isStoreOpen(), e.getOpeningTime(), e.getClosingTime(), e.getSameDayCutoff(),
                e.getLeadTimeMinutes(), e.getMaxDaysAhead(), e.getMaxItemWeightGrams(), e.getMaxOrderWeightGrams(),
                e.getMaxLineQuantityUnit(), e.getMaxLineQuantityWeight(), e.getWeightTolerancePercent(),
                e.getFailedDeliveryFlagCount(), e.getFailedDeliveryWindowDays(), e.getVersion());
    }
}
