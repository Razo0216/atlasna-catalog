package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.order.domain.Cart;
import com.atlasna.catalog.order.domain.CartItem;
import com.atlasna.catalog.order.domain.CartRepository;
import com.atlasna.catalog.order.domain.SubstitutionPolicy;
import com.atlasna.catalog.order.service.OrderViews.CartIssue;
import com.atlasna.catalog.order.service.OrderViews.CartLine;
import com.atlasna.catalog.order.service.OrderViews.CartResponse;
import com.atlasna.catalog.order.service.OrderViews.MergeNote;
import com.atlasna.catalog.order.service.OrderViews.MergeResponse;
import com.atlasna.catalog.product.CatalogApi;
import com.atlasna.catalog.product.ProductInfo;
import com.atlasna.catalog.product.domain.Availability;
import com.atlasna.catalog.product.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The customer's server-side cart. It never reserves stock (placing the order does) and it never trusts client
 * prices: every read re-prices from the catalog and reports PRICE_CHANGED / LOW_STOCK / UNAVAILABLE.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository carts;
    private final CatalogApi catalog;
    private final NamedParameterJdbcTemplate jdbc;

    public record ItemInput(long productId, BigDecimal quantity) {}

    @Transactional
    public CartResponse view(long userId) {
        return render(cart(userId));
    }

    /**
     * Sets a line's quantity: snapped to the product's step, clamped to its maximum; 0 removes the line.
     * Unknown product → 404 PRODUCT_NOT_FOUND; inactive or out of stock → 422 PRODUCT_UNAVAILABLE.
     */
    @Transactional
    public CartResponse setItem(long userId, long productId, BigDecimal quantity, SubstitutionPolicy policy) {
        Cart cart = cart(userId);
        if (quantity.signum() <= 0) {
            cart.getItems().removeIf(i -> i.productId() == productId);
            cart.touch();
            return render(cart);
        }
        ProductInfo p = catalog.find(List.of(productId)).get(productId);
        if (p == null) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product " + productId + " not found");
        BigDecimal q = p.orderable() ? Quantities.clamp(quantity, p) : BigDecimal.ZERO;
        if (q.signum() == 0) throw ApiException.businessRule("PRODUCT_UNAVAILABLE", "This product can't be ordered right now");
        put(cart, p, q, policy);
        return render(cart);
    }

    @Transactional
    public CartResponse removeItem(long userId, long productId) {
        return setItem(userId, productId, BigDecimal.ZERO, null);
    }

    @Transactional
    public CartResponse clear(long userId) {
        Cart cart = cart(userId);
        cart.getItems().clear();
        cart.touch();
        return render(cart);
    }

    /**
     * Merges a guest cart (from the browser) into the account's cart after login. Idempotent: a line ends at the
     * larger of the two quantities, never their sum, so sending the same guest cart twice changes nothing.
     */
    @Transactional
    public MergeResponse merge(long userId, List<ItemInput> guestItems) {
        Cart cart = cart(userId);
        List<MergeNote> notes = new ArrayList<>();
        Map<Long, ProductInfo> products = catalog.find(guestItems.stream().map(ItemInput::productId).toList());
        for (ItemInput in : guestItems) {
            ProductInfo p = products.get(in.productId());
            if (p == null || !p.orderable()) {
                notes.add(new MergeNote(in.productId(), p == null ? "NOT_FOUND" : "UNAVAILABLE", in.quantity(), BigDecimal.ZERO));
                continue;
            }
            BigDecimal wanted = cart.item(p.id()).map(CartItem::getQuantity).filter(q -> q.compareTo(in.quantity()) > 0)
                    .orElse(in.quantity());
            BigDecimal q = Quantities.clamp(wanted, p);
            if (q.signum() == 0) {
                notes.add(new MergeNote(p.id(), "UNAVAILABLE", in.quantity(), BigDecimal.ZERO));
                continue;
            }
            if (q.compareTo(wanted) < 0) notes.add(new MergeNote(p.id(), "QUANTITY_REDUCED", wanted, q));
            put(cart, p, q, null);
        }
        return new MergeResponse(render(cart), notes);
    }

    /**
     * Adds a past order's lines to the cart (reorder). Unavailable products are reported, not added;
     * {@code previousPrices} become the "price seen" so the cart shows PRICE_CHANGED where the price moved since.
     */
    @Transactional
    public MergeResponse addLines(long userId, List<ItemInput> lines, Map<Long, BigDecimal> previousPrices,
                                  Map<Long, SubstitutionPolicy> policies) {
        Cart cart = cart(userId);
        List<MergeNote> notes = new ArrayList<>();
        Map<Long, ProductInfo> products = catalog.find(lines.stream().map(ItemInput::productId).toList());
        for (ItemInput in : lines) {
            ProductInfo p = products.get(in.productId());
            BigDecimal q = p == null || !p.orderable() ? BigDecimal.ZERO : Quantities.clamp(in.quantity(), p);
            if (q.signum() == 0) {
                notes.add(new MergeNote(in.productId(), "UNAVAILABLE", in.quantity(), BigDecimal.ZERO));
                continue;
            }
            if (q.compareTo(in.quantity()) < 0) notes.add(new MergeNote(p.id(), "QUANTITY_REDUCED", in.quantity(), q));
            put(cart, p, q, policies.get(p.id()));
            cart.item(p.id()).ifPresent(i -> i.setUnitPriceSeen(previousPrices.getOrDefault(p.id(), p.price())));
        }
        return new MergeResponse(render(cart), notes);
    }

    /** The cart, created on first use (concurrent first requests can't create two: unique index + ON CONFLICT). */
    Cart cart(long userId) {
        return carts.findByUserId(userId).orElseGet(() -> {
            jdbc.update("INSERT INTO carts (user_id) VALUES (:u) ON CONFLICT (user_id) DO NOTHING",
                    new MapSqlParameterSource("u", userId));
            return carts.findByUserId(userId).orElseThrow();
        });
    }

    private void put(Cart cart, ProductInfo p, BigDecimal q, SubstitutionPolicy policy) {
        CartItem item = cart.item(p.id()).orElseGet(() -> {
            CartItem created = new CartItem();
            created.setId(new CartItem.Key(cart.getId(), p.id()));
            created.setCart(cart);
            cart.getItems().add(created);
            return created;
        });
        item.setQuantity(q);
        item.setUnitPriceSeen(p.price());
        if (policy != null) item.setSubstitutionPolicy(policy);
        cart.touch();
    }

    /** Re-prices every line from the live catalog. Only orderable lines count toward the subtotal and weight. */
    CartResponse render(Cart cart) {
        List<Long> ids = cart.getItems().stream().map(CartItem::productId).toList();
        Map<Long, ProductInfo> products = catalog.find(ids);
        Map<Long, ProductResponse> views = catalog.publicViews(ids);
        List<CartLine> lines = new ArrayList<>();
        List<CartIssue> issues = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        int weight = 0;
        for (CartItem item : cart.getItems()) {
            ProductInfo p = products.get(item.productId());
            List<String> warnings = new ArrayList<>();
            if (p == null || !p.orderable()) {
                warnings.add("UNAVAILABLE");
                issues.add(new CartIssue(item.productId(), "UNAVAILABLE"));
                lines.add(new CartLine(item.productId(), views.get(item.productId()), p == null ? null : p.name(),
                        item.getQuantity(), item.getSubstitutionPolicy(), p == null ? null : p.price(), BigDecimal.ZERO.setScale(2), warnings));
                continue;
            }
            if (p.price().compareTo(item.getUnitPriceSeen()) != 0) warnings.add("PRICE_CHANGED");
            if (p.availability() == Availability.LOW_STOCK) warnings.add("LOW_STOCK");
            if (item.getQuantity().compareTo(p.maxOrderQuantity()) > 0) {
                if (!warnings.contains("LOW_STOCK")) warnings.add("LOW_STOCK");
                issues.add(new CartIssue(p.id(), "QUANTITY_EXCEEDS_STOCK"));
            }
            BigDecimal lineTotal = Quantities.lineTotal(p.price(), item.getQuantity());
            subtotal = subtotal.add(lineTotal);
            weight += Quantities.weightGrams(p.unitWeightGrams(), item.getQuantity());
            lines.add(new CartLine(p.id(), views.get(p.id()), p.name(), item.getQuantity(), item.getSubstitutionPolicy(),
                    p.price(), lineTotal, warnings));
        }
        return new CartResponse(lines, subtotal, weight, issues, !lines.isEmpty() && issues.isEmpty());
    }
}
