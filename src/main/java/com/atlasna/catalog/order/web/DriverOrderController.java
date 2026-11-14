package com.atlasna.catalog.order.web;

import com.atlasna.catalog.order.service.OrderLifecycleService;
import com.atlasna.catalog.order.service.OrderQueryService;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderResponse;
import com.atlasna.catalog.order.service.OrderViews.AdminOrderSummary;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The driver's day: only orders assigned to them (anything else is 404). */
@RestController
@RequestMapping("/api/driver/orders")
@PreAuthorize("hasRole('DRIVER')")
@RequiredArgsConstructor
public class DriverOrderController {

    private final OrderQueryService queries;
    private final OrderLifecycleService lifecycle;

    public record DeliverRequest(@NotNull(message = "Cash collected is required") @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal cashCollected,
                                 @Size(max = 500) String note) {}

    public record FailRequest(@NotBlank(message = "Reason is required") @Size(max = 500) String reason) {}

    @GetMapping
    public List<AdminOrderSummary> queue(@AuthenticationPrincipal User driver) {
        return queries.driverQueue(driver);
    }

    @GetMapping("/{orderNumber}")
    public AdminOrderResponse get(@AuthenticationPrincipal User driver, @PathVariable String orderNumber) {
        return queries.adminGet(driver, orderNumber);
    }

    @PostMapping("/{orderNumber}/start")
    public AdminOrderResponse start(@AuthenticationPrincipal User driver, @PathVariable String orderNumber) {
        lifecycle.start(driver, orderNumber);
        return queries.adminGet(driver, orderNumber);
    }

    @PostMapping("/{orderNumber}/deliver")
    public AdminOrderResponse deliver(@AuthenticationPrincipal User driver, @PathVariable String orderNumber,
                                      @Valid @RequestBody DeliverRequest r) {
        lifecycle.deliver(driver, orderNumber, r.cashCollected(), r.note());
        return queries.adminGet(driver, orderNumber);
    }

    @PostMapping("/{orderNumber}/fail")
    public AdminOrderResponse fail(@AuthenticationPrincipal User driver, @PathVariable String orderNumber,
                                   @Valid @RequestBody FailRequest r) {
        lifecycle.fail(driver, orderNumber, r.reason());
        return queries.adminGet(driver, orderNumber);
    }
}
