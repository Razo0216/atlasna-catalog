package com.atlasna.catalog.delivery;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.domain.DeliverySlotRepository;
import com.atlasna.catalog.delivery.domain.DeliveryZone;
import com.atlasna.catalog.delivery.domain.DeliveryZoneRepository;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read side for the public delivery endpoints. */
@Service
@RequiredArgsConstructor
public class DeliveryQueries {

    private final DeliveryZoneRepository zones;
    private final DeliverySlotRepository slots;
    private final DeliveryApi api;
    private final NamedParameterJdbcTemplate jdbc;

    /** One slot as the customer sees it on a given day. */
    public record SlotAvailability(long slotId, @JsonFormat(pattern = "HH:mm") LocalTime start,
                                   @JsonFormat(pattern = "HH:mm") LocalTime end, int remaining, boolean available,
                                   SlotUnavailableReason reason) {}

    @Transactional(readOnly = true)
    public List<ZoneInfo> activeZones() {
        return zones.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(DeliveryApi::toInfo).toList();
    }

    @Transactional(readOnly = true)
    public List<SlotAvailability> slots(long zoneId, LocalDate date) {
        DeliveryZone zone = zones.findById(zoneId).filter(DeliveryZone::isActive)
                .orElseThrow(() -> ApiException.businessRule("ZONE_NOT_SERVED", "We don't deliver to this zone"));
        Map<Long, Integer> booked = new HashMap<>();
        jdbc.query("SELECT slot_id, booked FROM slot_usage WHERE slot_date = :date",
                new MapSqlParameterSource("date", date), rs -> { booked.put(rs.getLong(1), rs.getInt(2)); });
        var settings = api.settings();
        var now = api.now();
        return slots.findByActiveTrueOrderByStartTimeAscIdAsc().stream()
                .filter(s -> s.appliesTo(zone.getId(), date.getDayOfWeek()))
                .map(s -> {
                    int b = booked.getOrDefault(s.getId(), 0);
                    Optional<SlotUnavailableReason> reason = SlotRules.check(settings, date, s.getStartTime(), s.getEndTime(),
                            s.getCapacity(), b, now);
                    return new SlotAvailability(s.getId(), s.getStartTime(), s.getEndTime(), Math.max(0, s.getCapacity() - b),
                            reason.isEmpty(), reason.orElse(null));
                })
                .toList();
    }
}
