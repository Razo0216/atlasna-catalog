package com.atlasna.catalog.delivery.web;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.domain.DeliverySlot;
import com.atlasna.catalog.delivery.domain.DeliverySlotRepository;
import com.atlasna.catalog.delivery.domain.DeliveryZone;
import com.atlasna.catalog.delivery.domain.DeliveryZoneRepository;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;

/**
 * Admin management of zones and slots (ADMIN). "Delete" deactivates: past orders keep pointing at the row.
 * Zones and slots carry a version; PUT must send the one it read (409 CONCURRENT_MODIFICATION otherwise).
 */
@RestController
@RequestMapping("/api/admin/delivery")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDeliveryController {

    private final DeliveryZoneRepository zones;
    private final DeliverySlotRepository slots;

    public record ZoneRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Slug may contain only a-z, 0-9 and single dashes") String slug,
            @NotBlank @Size(max = 150) String nameFr, @Size(max = 150) String nameAr,
            @NotNull @DecimalMin("0") BigDecimal fee, @NotNull @DecimalMin("0") BigDecimal minOrderAmount,
            @DecimalMin(value = "0", inclusive = false) BigDecimal freeDeliveryThreshold,
            @NotNull @Positive Integer etaMinutes, Integer sortOrder, Boolean active, Long version) {}

    public record ZoneView(long id, String slug, String nameFr, String nameAr, BigDecimal fee, BigDecimal minOrderAmount,
                           BigDecimal freeDeliveryThreshold, int etaMinutes, int sortOrder, boolean active, long version) {}

    public record SlotRequest(Long zoneId, @Min(1) @Max(7) Short dayOfWeek, @NotNull LocalTime startTime,
                              @NotNull LocalTime endTime, @NotNull @Positive Integer capacity, Boolean active, Long version) {}

    public record SlotView(long id, Long zoneId, Short dayOfWeek, @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                           @JsonFormat(pattern = "HH:mm") LocalTime endTime, int capacity, boolean active, long version) {}

    // ---------- zones ----------

    @GetMapping("/zones")
    @Transactional(readOnly = true)
    public List<ZoneView> zones() {
        return zones.findAllByOrderBySortOrderAscIdAsc().stream().map(AdminDeliveryController::view).toList();
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ZoneView createZone(@Valid @RequestBody ZoneRequest r) {
        if (zones.existsBySlug(r.slug())) throw ApiException.conflict("SLUG_CONFLICT", "Slug '" + r.slug() + "' is already used");
        DeliveryZone z = new DeliveryZone();
        apply(z, r);
        return view(zones.saveAndFlush(z));
    }

    @PutMapping("/zones/{id}")
    @Transactional
    public ZoneView updateZone(@PathVariable long id, @Valid @RequestBody ZoneRequest r) {
        DeliveryZone z = zones.findById(id).orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone " + id + " not found"));
        checkVersion(z.getVersion(), r.version());
        if (zones.existsBySlugAndIdNot(r.slug(), id)) throw ApiException.conflict("SLUG_CONFLICT", "Slug '" + r.slug() + "' is already used");
        apply(z, r);
        return view(zones.saveAndFlush(z));
    }

    @DeleteMapping("/zones/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivateZone(@PathVariable long id) {
        zones.findById(id).orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone " + id + " not found")).setActive(false);
    }

    // ---------- slots ----------

    @GetMapping("/slots")
    @Transactional(readOnly = true)
    public List<SlotView> slots() {
        return slots.findAllByOrderByStartTimeAscIdAsc().stream().map(AdminDeliveryController::view).toList();
    }

    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SlotView createSlot(@Valid @RequestBody SlotRequest r) {
        DeliverySlot s = new DeliverySlot();
        apply(s, r);
        return view(slots.saveAndFlush(s));
    }

    @PutMapping("/slots/{id}")
    @Transactional
    public SlotView updateSlot(@PathVariable long id, @Valid @RequestBody SlotRequest r) {
        DeliverySlot s = slots.findById(id).orElseThrow(() -> ApiException.notFound("SLOT_NOT_FOUND", "Slot " + id + " not found"));
        checkVersion(s.getVersion(), r.version());
        apply(s, r);
        return view(slots.saveAndFlush(s));
    }

    @DeleteMapping("/slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivateSlot(@PathVariable long id) {
        slots.findById(id).orElseThrow(() -> ApiException.notFound("SLOT_NOT_FOUND", "Slot " + id + " not found")).setActive(false);
    }

    // ---------- helpers ----------

    private void apply(DeliveryZone z, ZoneRequest r) {
        z.setSlug(r.slug());
        z.setNameFr(r.nameFr().trim());
        z.setNameAr(r.nameAr() == null || r.nameAr().isBlank() ? null : r.nameAr().trim());
        z.setFee(r.fee().setScale(2, RoundingMode.HALF_UP));
        z.setMinOrderAmount(r.minOrderAmount().setScale(2, RoundingMode.HALF_UP));
        z.setFreeDeliveryThreshold(r.freeDeliveryThreshold() == null ? null : r.freeDeliveryThreshold().setScale(2, RoundingMode.HALF_UP));
        z.setEtaMinutes(r.etaMinutes());
        z.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
        z.setActive(r.active() == null || r.active());
    }

    private void apply(DeliverySlot s, SlotRequest r) {
        if (!r.startTime().isBefore(r.endTime())) throw ApiException.invalidField("endTime", "End time must be after start time");
        if (r.zoneId() != null && !zones.existsById(r.zoneId())) throw ApiException.invalidField("zoneId", "Unknown zone");
        s.setZoneId(r.zoneId());
        s.setDayOfWeek(r.dayOfWeek());
        s.setStartTime(r.startTime());
        s.setEndTime(r.endTime());
        s.setCapacity(r.capacity());
        s.setActive(r.active() == null || r.active());
    }

    private static void checkVersion(long current, Long sent) {
        if (sent == null) throw ApiException.invalidField("version", "Version is required");
        if (current != sent) throw ApiException.conflict("CONCURRENT_MODIFICATION", "Changed by someone else. Reload and retry.");
    }

    private static ZoneView view(DeliveryZone z) {
        return new ZoneView(z.getId(), z.getSlug(), z.getNameFr(), z.getNameAr(), z.getFee(), z.getMinOrderAmount(),
                z.getFreeDeliveryThreshold(), z.getEtaMinutes(), z.getSortOrder(), z.isActive(), z.getVersion());
    }

    private static SlotView view(DeliverySlot s) {
        return new SlotView(s.getId(), s.getZoneId(), s.getDayOfWeek(), s.getStartTime(), s.getEndTime(), s.getCapacity(),
                s.isActive(), s.getVersion());
    }
}
