package com.atlasna.catalog.order.web;

import com.atlasna.catalog.order.domain.SubstitutionPolicy;
import com.atlasna.catalog.order.service.CartService;
import com.atlasna.catalog.order.service.CartService.ItemInput;
import com.atlasna.catalog.order.service.OrderViews.CartResponse;
import com.atlasna.catalog.order.service.OrderViews.MergeResponse;
import com.atlasna.catalog.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** The signed-in customer's cart (/api/cart). */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService carts;

    public record ItemRequest(@NotNull(message = "Quantity is required") @DecimalMin("0") @Digits(integer = 7, fraction = 3) BigDecimal quantity,
                              SubstitutionPolicy substitutionPolicy) {}

    public record MergeLine(@NotNull Long productId, @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 7, fraction = 3) BigDecimal quantity) {}

    public record MergeRequest(@NotNull @Size(max = 100) List<@Valid MergeLine> items) {}

    @GetMapping
    public CartResponse get(@AuthenticationPrincipal User user) {
        return carts.view(user.getId());
    }

    @PutMapping("/items/{productId}")
    public CartResponse setItem(@AuthenticationPrincipal User user, @PathVariable long productId, @Valid @RequestBody ItemRequest r) {
        return carts.setItem(user.getId(), productId, r.quantity(), r.substitutionPolicy());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@AuthenticationPrincipal User user, @PathVariable long productId) {
        return carts.removeItem(user.getId(), productId);
    }

    @DeleteMapping
    public CartResponse clear(@AuthenticationPrincipal User user) {
        return carts.clear(user.getId());
    }

    @PostMapping("/merge")
    public MergeResponse merge(@AuthenticationPrincipal User user, @Valid @RequestBody MergeRequest r) {
        return carts.merge(user.getId(), r.items().stream().map(l -> new ItemInput(l.productId(), l.quantity())).toList());
    }
}
