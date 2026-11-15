package com.atlasna.catalog.inventory.web;

import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.inventory.LotView;
import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Lots (deliveries with expiry dates) and the stock reports, for the shop floor (STAFF) and the owner (ADMIN). ADR-0015. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@RequiredArgsConstructor
public class LotController {

    private final InventoryService inventory;

    public record ReceiveLotRequest(@NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal quantity,
                                    LocalDate expiryDate, @Size(max = 60) String lotCode, @Size(max = 300) String note) {}

    @GetMapping("/api/admin/products/{productId}/lots")
    public List<LotView> lots(@PathVariable long productId) {
        return inventory.lots(productId);
    }

    /** Receive goods: a new lot (perishables need the expiry date printed on the goods) + RESTOCK in the ledger. */
    @PostMapping("/api/admin/products/{productId}/lots")
    @ResponseStatus(HttpStatus.CREATED)
    public LotView receive(@PathVariable long productId, @Valid @RequestBody ReceiveLotRequest r, @AuthenticationPrincipal User actor) {
        return inventory.receiveLot(productId, r.quantity(), r.expiryDate(), r.lotCode(), r.note(), actor.getId());
    }

    /** Lots that expire within {@code days} days (today included): sell, discount or move them first. */
    @GetMapping("/api/admin/inventory/expiring")
    public List<LotView> expiring(@RequestParam(defaultValue = "3") int days) {
        if (days < 0 || days > 60) throw ApiException.invalidField("days", "Days must be between 0 and 60");
        return inventory.expiringSoon(days);
    }

    @GetMapping("/api/admin/inventory/low-stock")
    public List<Map<String, Object>> lowStock() {
        return inventory.lowStock();
    }
}
