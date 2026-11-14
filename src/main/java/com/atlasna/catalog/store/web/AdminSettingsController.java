package com.atlasna.catalog.store.web;

import com.atlasna.catalog.store.StoreSettings;
import com.atlasna.catalog.store.StoreSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalTime;

/** The shop's rules: GET/PUT /api/admin/settings (ADMIN). Times are "HH:mm", Africa/Algiers. */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final StoreSettingsService service;

    @GetMapping
    public StoreSettings get() {
        return service.current();
    }

    @PutMapping
    public StoreSettings update(@Valid @RequestBody SettingsRequest r) {
        return service.update(new StoreSettings(r.storeOpen(), r.openingTime(), r.closingTime(), r.sameDayCutoff(),
                r.leadTimeMinutes(), r.maxDaysAhead(), r.maxItemWeightGrams(), r.maxOrderWeightGrams(),
                r.maxLineQuantityUnit(), r.maxLineQuantityWeight(), r.weightTolerancePercent(),
                r.failedDeliveryFlagCount(), r.failedDeliveryWindowDays(), r.version()));
    }

    public record SettingsRequest(
            @NotNull Boolean storeOpen,
            @NotNull LocalTime openingTime,
            @NotNull LocalTime closingTime,
            @NotNull LocalTime sameDayCutoff,
            @NotNull @Min(0) @Max(1440) Integer leadTimeMinutes,
            @NotNull @Min(0) @Max(30) Integer maxDaysAhead,
            @NotNull @Positive Integer maxItemWeightGrams,
            @NotNull @Positive Integer maxOrderWeightGrams,
            @NotNull @Positive Integer maxLineQuantityUnit,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxLineQuantityWeight,
            @NotNull @DecimalMin("0") BigDecimal weightTolerancePercent,
            @NotNull @Positive Integer failedDeliveryFlagCount,
            @NotNull @Positive Integer failedDeliveryWindowDays,
            @NotNull(message = "Version is required") Long version) {}
}
