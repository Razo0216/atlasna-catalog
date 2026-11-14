package com.atlasna.catalog.delivery;

import com.atlasna.catalog.store.StoreSettings;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * The single availability rule for a delivery slot (pure function — no clock, no database — so every boundary is
 * unit-tested). All times are the shop's local time (Africa/Algiers, UTC+1 all year). Checks, in order:
 * <ol>
 *   <li>date before today → PAST_CUTOFF; more than {@code maxDaysAhead} away → CLOSED</li>
 *   <li>shop closed, or the slot outside opening hours → CLOSED</li>
 *   <li>same day after the cut-off → PAST_CUTOFF</li>
 *   <li>slot starts sooner than now + lead time → TOO_SOON</li>
 *   <li>{@code booked >= capacity} → FULL</li>
 * </ol>
 */
public final class SlotRules {

    private SlotRules() {}

    /** @return empty when bookable, otherwise the first reason it isn't */
    public static Optional<SlotUnavailableReason> check(StoreSettings s, LocalDate date, LocalTime start, LocalTime end,
                                                        int capacity, int booked, ZonedDateTime nowInStore) {
        LocalDate today = nowInStore.toLocalDate();
        if (date.isBefore(today)) return Optional.of(SlotUnavailableReason.PAST_CUTOFF);
        if (date.isAfter(today.plusDays(s.maxDaysAhead()))) return Optional.of(SlotUnavailableReason.CLOSED);
        if (!s.storeOpen() || start.isBefore(s.openingTime()) || end.isAfter(s.closingTime())) {
            return Optional.of(SlotUnavailableReason.CLOSED);
        }
        LocalDateTime now = nowInStore.toLocalDateTime();
        if (date.equals(today) && !now.toLocalTime().isBefore(s.sameDayCutoff())) {
            return Optional.of(SlotUnavailableReason.PAST_CUTOFF);
        }
        if (now.plusMinutes(s.leadTimeMinutes()).isAfter(date.atTime(start))) {
            return Optional.of(SlotUnavailableReason.TOO_SOON);
        }
        if (booked >= capacity) return Optional.of(SlotUnavailableReason.FULL);
        return Optional.empty();
    }
}
