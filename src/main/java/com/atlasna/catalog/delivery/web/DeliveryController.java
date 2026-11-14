package com.atlasna.catalog.delivery.web;

import com.atlasna.catalog.delivery.DeliveryQueries;
import com.atlasna.catalog.delivery.DeliveryQueries.SlotAvailability;
import com.atlasna.catalog.delivery.ZoneInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Public delivery info: where we deliver and which slots are open. */
@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryQueries queries;

    @GetMapping("/zones")
    public List<ZoneInfo> zones() {
        return queries.activeZones();
    }

    /** Every slot offered in the zone that day, each with {@code available} and, when not, the {@code reason}. */
    @GetMapping("/slots")
    public List<SlotAvailability> slots(@RequestParam long zoneId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queries.slots(zoneId, date);
    }
}
