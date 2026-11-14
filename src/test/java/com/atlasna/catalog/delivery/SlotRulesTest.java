package com.atlasna.catalog.delivery;

import com.atlasna.catalog.store.StoreSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The slot availability rule at its boundaries (pure function, Africa/Algiers time). */
class SlotRulesTest {

    private static final StoreSettings S = settings(true);
    private static final LocalDate MON = LocalDate.of(2026, 11, 16);
    private static final LocalTime T14 = LocalTime.of(14, 0), T16 = LocalTime.of(16, 0);

    private static StoreSettings settings(boolean open) {
        return new StoreSettings(open, LocalTime.of(9, 0), LocalTime.of(21, 0), LocalTime.of(19, 0), 90, 3, 5000, 15000, 20,
                BigDecimal.TEN, BigDecimal.valueOf(20), 3, 30, 0);
    }

    private static ZonedDateTime at(LocalDate d, int h, int m) {
        return d.atTime(h, m).atZone(StoreSettings.STORE_ZONE);
    }

    private static Optional<SlotUnavailableReason> check(StoreSettings s, LocalDate date, LocalTime start, LocalTime end, int booked, ZonedDateTime now) {
        return SlotRules.check(s, date, start, end, 8, booked, now);
    }

    @Test
    void bookableTomorrowMorning() {
        assertThat(check(S, MON.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, at(MON, 20, 30))).isEmpty();
    }

    @Test
    void leadTimeBoundary() {
        // slot at 14:00, lead 90 min: 12:30 is exactly on time, 12:31 is too soon
        assertThat(check(S, MON, T14, T16, 0, at(MON, 12, 30))).isEmpty();
        assertThat(check(S, MON, T14, T16, 0, at(MON, 12, 31))).contains(SlotUnavailableReason.TOO_SOON);
    }

    @Test
    void sameDayCutoffBoundary() {
        LocalTime t19 = LocalTime.of(19, 30), t21 = LocalTime.of(21, 0);
        StoreSettings noLead = new StoreSettings(true, LocalTime.of(9, 0), LocalTime.of(21, 0), LocalTime.of(19, 0), 0, 3, 5000,
                15000, 20, BigDecimal.TEN, BigDecimal.valueOf(20), 3, 30, 0);
        assertThat(check(noLead, MON, t19, t21, 0, at(MON, 18, 59))).isEmpty();
        assertThat(check(noLead, MON, t19, t21, 0, at(MON, 19, 0))).contains(SlotUnavailableReason.PAST_CUTOFF);
        // the cut-off only applies to the same day
        assertThat(check(noLead, MON.plusDays(1), t19, t21, 0, at(MON, 23, 0))).isEmpty();
    }

    @Test
    void aroundMidnight() {
        // 23:59 Monday → Tuesday 10:00 is fine; Monday's slots are past
        assertThat(check(S, MON.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, at(MON, 23, 59))).isEmpty();
        assertThat(check(S, MON, T14, T16, 0, at(MON.plusDays(1), 0, 1))).contains(SlotUnavailableReason.PAST_CUTOFF);
        // 00:30 Tuesday → Tuesday 10:00 is bookable (before the cut-off, outside the lead time)
        assertThat(check(S, MON.plusDays(1), LocalTime.of(10, 0), LocalTime.of(12, 0), 0, at(MON.plusDays(1), 0, 30))).isEmpty();
    }

    @Test
    void maxDaysAheadBoundary() {
        assertThat(check(S, MON.plusDays(3), T14, T16, 0, at(MON, 10, 0))).isEmpty();
        assertThat(check(S, MON.plusDays(4), T14, T16, 0, at(MON, 10, 0))).contains(SlotUnavailableReason.CLOSED);
    }

    @Test
    void capacityBoundary() {
        assertThat(check(S, MON.plusDays(1), T14, T16, 7, at(MON, 10, 0))).isEmpty();
        assertThat(check(S, MON.plusDays(1), T14, T16, 8, at(MON, 10, 0))).contains(SlotUnavailableReason.FULL);
    }

    @Test
    void closedShopAndOpeningHours() {
        assertThat(check(settings(false), MON.plusDays(1), T14, T16, 0, at(MON, 10, 0))).contains(SlotUnavailableReason.CLOSED);
        assertThat(check(S, MON.plusDays(1), LocalTime.of(8, 0), LocalTime.of(10, 0), 0, at(MON, 10, 0))).contains(SlotUnavailableReason.CLOSED);
        assertThat(check(S, MON.plusDays(1), LocalTime.of(20, 0), LocalTime.of(22, 0), 0, at(MON, 10, 0))).contains(SlotUnavailableReason.CLOSED);
    }

    @Test
    void pastDate() {
        assertThat(check(S, MON.minusDays(1), T14, T16, 0, at(MON, 8, 0))).contains(SlotUnavailableReason.PAST_CUTOFF);
    }
}
