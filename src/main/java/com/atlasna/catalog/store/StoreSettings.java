package com.atlasna.catalog.store;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The shop's rules, read by every module (table {@code store_settings}, one row, edited by the admin).
 * Values are the owner's business decisions; the defaults come from migration V3.
 *
 * @param storeOpen                 false closes every delivery slot (holidays, emergencies)
 * @param openingTime               slots must start at or after this time (Africa/Algiers)
 * @param closingTime               slots must end at or before this time
 * @param sameDayCutoff             after this time no same-day slot can be booked
 * @param leadTimeMinutes           minimum time between ordering and the slot start (packing time)
 * @param maxDaysAhead              how many days ahead a slot can be booked (0 = today only)
 * @param maxItemWeightGrams        heaviest single unit the catalog may sell (no heavy items)
 * @param maxOrderWeightGrams       heaviest order one driver can deliver
 * @param maxLineQuantityUnit       most pieces of one product per order line
 * @param maxLineQuantityWeight     most kilograms of one per-kg product per order line
 * @param weightTolerancePercent    weighed total may differ from the estimate by this much before the order is flagged
 * @param failedDeliveryFlagCount   failed cash-on-delivery orders (same phone) that flag a customer…
 * @param failedDeliveryWindowDays  …within this many days
 * @param version                   optimistic-locking version; PUT must send the one it read
 */
public record StoreSettings(
        boolean storeOpen,
        @JsonFormat(pattern = "HH:mm") LocalTime openingTime,
        @JsonFormat(pattern = "HH:mm") LocalTime closingTime,
        @JsonFormat(pattern = "HH:mm") LocalTime sameDayCutoff,
        int leadTimeMinutes,
        int maxDaysAhead,
        int maxItemWeightGrams,
        int maxOrderWeightGrams,
        int maxLineQuantityUnit,
        BigDecimal maxLineQuantityWeight,
        BigDecimal weightTolerancePercent,
        int failedDeliveryFlagCount,
        int failedDeliveryWindowDays,
        long version
) {
    /** All business-time rules (slots, cut-offs, order numbers) use the shop's local time. Algeria is UTC+1, no DST. */
    public static final ZoneId STORE_ZONE = ZoneId.of("Africa/Algiers");
}
