package com.atlasna.catalog.order.web;

import com.atlasna.catalog.order.domain.ItemStatus;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.service.OrderLifecycleService;
import com.atlasna.catalog.order.service.OrderLifecycleService.ItemPatch;
import com.atlasna.catalog.order.service.OrderQueryService;
import com.atlasna.catalog.order.service.OrderQueryService.AdminFilter;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderResponse;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderSummary;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The shop's order board (STAFF, ADMIN): list, detail, move through the lifecycle, pack lines, assign drivers. */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasAnyRole('STAFF','ADMIN')")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderQueryService queries;
    private final OrderLifecycleService lifecycle;

    public record TransitionRequest(@NotNull(message = "Target status is required") OrderStatus to,
                                    @Size(max = 500) String reason,
                                    @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cashCollected) {}

    public record ItemPatchRequest(@DecimalMin("0") @Digits(integer = 7, fraction = 3) BigDecimal quantityFulfilled,
                                   ItemStatus status, Long substituteProductId) {}

    public record AssignDriverRequest(@NotNull(message = "Driver is required") Long driverId) {}

    @GetMapping
    public Page<AdminOrderSummary> list(@RequestParam(required = false) OrderStatus status,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                        @RequestParam(required = false) Long zoneId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Long assignedDriverId,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        OrderController.Paging.check(page, size);
        return queries.adminList(new AdminFilter(status, date, zoneId, q, assignedDriverId), page, size);
    }

    @GetMapping("/{orderNumber}")
    public AdminOrderResponse get(@AuthenticationPrincipal User actor, @PathVariable String orderNumber) {
        return queries.adminGet(actor, orderNumber);
    }

    @PostMapping("/{orderNumber}/transition")
    public AdminOrderResponse transition(@AuthenticationPrincipal User actor, @PathVariable String orderNumber,
                                         @Valid @RequestBody TransitionRequest r) {
        lifecycle.transition(actor, orderNumber, r.to(), r.reason(), r.cashCollected());
        return queries.adminGet(actor, orderNumber);
    }

    @PatchMapping("/{orderNumber}/items/{itemId}")
    public AdminOrderResponse patchItem(@AuthenticationPrincipal User actor, @PathVariable String orderNumber,
                                        @PathVariable long itemId, @Valid @RequestBody ItemPatchRequest r) {
        lifecycle.patchItem(actor, orderNumber, itemId, new ItemPatch(r.quantityFulfilled(), r.status(), r.substituteProductId()));
        return queries.adminGet(actor, orderNumber);
    }

    @PostMapping("/{orderNumber}/assign-driver")
    public AdminOrderResponse assignDriver(@AuthenticationPrincipal User actor, @PathVariable String orderNumber,
                                           @Valid @RequestBody AssignDriverRequest r) {
        lifecycle.assignDriver(actor, orderNumber, r.driverId());
        return queries.adminGet(actor, orderNumber);
    }
}
