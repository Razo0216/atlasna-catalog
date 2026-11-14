package com.atlasna.catalog.order.web;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.order.service.OrderLifecycleService;
import com.atlasna.catalog.order.service.OrderPlacementService;
import com.atlasna.catalog.order.service.OrderPlacementService.Placement;
import com.atlasna.catalog.order.service.OrderQueryService;
import com.atlasna.catalog.order.service.OrderViews.MergeResponse;
import com.atlasna.catalog.order.service.OrderViews.OrderResponse;
import com.atlasna.catalog.order.service.OrderViews.OrderSummary;
import com.atlasna.catalog.order.service.PlaceOrderRequest;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The customer's orders: place (idempotent), list, detail, cancel, reorder. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderPlacementService placement;
    private final OrderQueryService queries;
    private final OrderLifecycleService lifecycle;

    public record CancelRequest(@Size(max = 500) String reason) {}

    /** 201 for a new order; 200 with the ORIGINAL order when the same Idempotency-Key + body is sent again. */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal User user,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody PlaceOrderRequest request) {
        Placement p = placement.place(user.getId(), key, request);
        return ResponseEntity.status(p.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(p.order());
    }

    @GetMapping
    public Page<OrderSummary> list(@AuthenticationPrincipal User user, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        Paging.check(page, size);
        return queries.mine(user, page, size);
    }

    @GetMapping("/{orderNumber}")
    public OrderResponse get(@AuthenticationPrincipal User user, @PathVariable String orderNumber) {
        return queries.mine(user, orderNumber);
    }

    @PostMapping("/{orderNumber}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal User user, @PathVariable String orderNumber,
                                @Valid @RequestBody(required = false) CancelRequest r) {
        lifecycle.cancelByCustomer(user, orderNumber, r == null ? null : r.reason());
        return queries.mine(user, orderNumber);
    }

    @PostMapping("/{orderNumber}/reorder")
    public MergeResponse reorder(@AuthenticationPrincipal User user, @PathVariable String orderNumber) {
        return queries.reorder(user, orderNumber);
    }

    /** Shared page-parameter check (same limits as the catalog). */
    static final class Paging {
        private Paging() {}

        static void check(int page, int size) {
            if (page < 0) throw ApiException.invalidField("page", "Page must be 0 or greater");
            if (size < 1 || size > 100) throw ApiException.invalidField("size", "Size must be between 1 and 100");
        }
    }
}
