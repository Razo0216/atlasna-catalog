package com.atlasna.catalog.delivery;

import com.atlasna.catalog.common.LocalizedText;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.domain.Address;
import com.atlasna.catalog.delivery.domain.AddressRepository;
import com.atlasna.catalog.delivery.domain.DeliverySlot;
import com.atlasna.catalog.delivery.domain.DeliverySlotRepository;
import com.atlasna.catalog.delivery.domain.DeliveryZone;
import com.atlasna.catalog.delivery.domain.DeliveryZoneRepository;
import com.atlasna.catalog.store.StoreSettings;
import com.atlasna.catalog.store.StoreSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What other modules (orders) may ask the delivery module. Everything else in this module is internal.
 */
@Service
@RequiredArgsConstructor
public class DeliveryApi {

    private final DeliveryZoneRepository zones;
    private final DeliverySlotRepository slots;
    private final AddressRepository addresses;
    private final StoreSettingsService settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** An active zone, or 422 ZONE_NOT_SERVED. */
    @Transactional(readOnly = true)
    public ZoneInfo requireServedZone(long zoneId) {
        return zones.findById(zoneId).filter(DeliveryZone::isActive).map(DeliveryApi::toInfo)
                .orElseThrow(() -> ApiException.businessRule("ZONE_NOT_SERVED", "We don't deliver to this zone"));
    }

    /** Any zone by id, active or not (orders keep pointing at zones that were later deactivated). */
    @Transactional(readOnly = true)
    public Optional<ZoneInfo> zone(long zoneId) {
        return zones.findById(zoneId).map(DeliveryApi::toInfo);
    }

    /** The user's own address, or 404 ADDRESS_NOT_FOUND (also for someone else's address — never 403). */
    @Transactional(readOnly = true)
    public AddressSnapshot requireOwnAddress(long userId, long addressId) {
        Address a = addresses.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address " + addressId + " not found"));
        return new AddressSnapshot(a.getId(), a.getZoneId(), a.getRecipientName(), a.getPhone(), a.getStreet(),
                a.getLandmark(), a.getBuildingNotes(), a.getLat(), a.getLng());
    }

    /**
     * A slot that exists, is offered in the zone on that weekday, and passes every rule except capacity
     * (capacity is claimed atomically by {@link #reserveSlot}). Otherwise 409 SLOT_UNAVAILABLE with the reason.
     */
    @Transactional(readOnly = true)
    public SlotInfo requireBookableSlot(long zoneId, long slotId, LocalDate date) {
        DeliverySlot slot = slots.findById(slotId)
                .filter(s -> s.isActive() && s.appliesTo(zoneId, date.getDayOfWeek()))
                .orElseThrow(() -> ApiException.conflict("SLOT_UNAVAILABLE", "This slot is not offered for that zone and day")
                        .withDetails(List.of(Map.of("reason", SlotUnavailableReason.CLOSED.name()))));
        Optional<SlotUnavailableReason> reason = SlotRules.check(settings.current(), date, slot.getStartTime(),
                slot.getEndTime(), slot.getCapacity(), 0, now());
        if (reason.isPresent()) {
            throw ApiException.conflict("SLOT_UNAVAILABLE", "This slot can't be booked any more")
                    .withDetails(List.of(Map.of("reason", reason.get().name())));
        }
        return new SlotInfo(slot.getId(), slot.getStartTime(), slot.getEndTime(), slot.getCapacity());
    }

    /**
     * Claims one place in the slot with ONE conditional upsert: concurrent bookings serialize on the row and the
     * one that finds it full gets no row back → 409 SLOT_FULL (the caller's transaction then rolls back).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveSlot(long slotId, LocalDate date, int capacity) {
        List<Integer> booked = jdbc.queryForList("""
                INSERT INTO slot_usage (slot_id, slot_date, booked) VALUES (:slot, :date, 1)
                ON CONFLICT (slot_id, slot_date) DO UPDATE SET booked = slot_usage.booked + 1
                WHERE slot_usage.booked < :capacity
                RETURNING booked""",
                new MapSqlParameterSource("slot", slotId).addValue("date", date).addValue("capacity", capacity), Integer.class);
        if (booked.isEmpty()) throw ApiException.conflict("SLOT_FULL", "This delivery slot is full");
    }

    /** Gives a place back (order cancelled). Never goes below zero. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseSlot(long slotId, LocalDate date) {
        jdbc.update("UPDATE slot_usage SET booked = booked - 1 WHERE slot_id = :slot AND slot_date = :date AND booked > 0",
                new MapSqlParameterSource("slot", slotId).addValue("date", date));
    }

    public StoreSettings settings() {
        return settings.current();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(StoreSettings.STORE_ZONE));
    }

    static ZoneInfo toInfo(DeliveryZone z) {
        return new ZoneInfo(z.getId(), z.getSlug(), LocalizedText.of(z.getNameFr(), z.getNameAr()), z.getFee(),
                z.getMinOrderAmount(), z.getFreeDeliveryThreshold(), z.getEtaMinutes());
    }
}
