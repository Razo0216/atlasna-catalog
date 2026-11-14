package com.atlasna.catalog.inventory.web;

import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.inventory.StockMovementView;
import com.atlasna.catalog.inventory.StockReason;
import com.atlasna.catalog.user.Role;
import com.atlasna.catalog.user.User;
import com.atlasna.catalog.common.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Manual stock changes and the ledger. ADMIN: any of RESTOCK / ADJUSTMENT / EXPIRED. STAFF: RESTOCK and ADJUSTMENT
 * only (the shop floor counts and receives goods; write-offs are the owner's call).
 */
@RestController
@RequestMapping("/api/admin/products/{productId}")
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@RequiredArgsConstructor
public class StockController {

    private final InventoryService inventory;

    @PostMapping("/stock-adjustments")
    public StockAdjustmentResponse adjust(@PathVariable long productId, @Valid @RequestBody StockAdjustmentRequest r,
                                          @AuthenticationPrincipal User actor) {
        if (actor.getRole() == Role.STAFF && r.reason() == StockReason.EXPIRED) {
            throw ApiException.forbidden("FORBIDDEN", "Only an admin can write off expired stock");
        }
        BigDecimal stock = inventory.adjust(productId, r.delta(), r.reason(), actor.getId(), r.note());
        return new StockAdjustmentResponse(productId, stock);
    }

    @GetMapping("/stock-movements")
    public Page<StockMovementView> movements(@PathVariable long productId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        if (page < 0) throw ApiException.invalidField("page", "Page must be 0 or greater");
        if (size < 1 || size > 100) throw ApiException.invalidField("size", "Size must be between 1 and 100");
        return inventory.movements(productId, PageRequest.of(page, size));
    }

    public record StockAdjustmentRequest(
            @NotNull(message = "Delta is required") @Digits(integer = 7, fraction = 3) BigDecimal delta,
            @NotNull(message = "Reason is required") StockReason reason,
            @Size(max = 300) String note) {}

    public record StockAdjustmentResponse(long productId, BigDecimal stockQuantity) {}
}
