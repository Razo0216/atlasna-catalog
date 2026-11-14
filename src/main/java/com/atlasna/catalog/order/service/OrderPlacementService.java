package com.atlasna.catalog.order.service;

import com.atlasna.catalog.common.exception.ApiException;
import com.atlasna.catalog.delivery.AddressSnapshot;
import com.atlasna.catalog.delivery.DeliveryApi;
import com.atlasna.catalog.delivery.SlotInfo;
import com.atlasna.catalog.delivery.ZoneInfo;
import com.atlasna.catalog.inventory.InventoryService;
import com.atlasna.catalog.inventory.StockLine;
import com.atlasna.catalog.order.OrderPlaced;
import com.atlasna.catalog.order.domain.Cart;
import com.atlasna.catalog.order.domain.CartItem;
import com.atlasna.catalog.order.domain.Order;
import com.atlasna.catalog.order.domain.OrderItem;
import com.atlasna.catalog.order.domain.OrderRepository;
import com.atlasna.catalog.order.domain.OrderStatus;
import com.atlasna.catalog.order.domain.Payment;
import com.atlasna.catalog.order.domain.PaymentStatus;
import com.atlasna.catalog.order.service.OrderViews.OrderResponse;
import com.atlasna.catalog.product.CatalogApi;
import com.atlasna.catalog.product.ProductInfo;
import com.atlasna.catalog.store.StoreSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * POST /api/orders in ONE transaction, in this order (docs/phases/phase-03 §5):
 * <ol>
 *   <li>Idempotency: claim (user, key) with {@code INSERT … ON CONFLICT DO NOTHING}. A concurrent duplicate waits on
 *       the unique index until the first commits, then replays its order; same key + different body → 409.</li>
 *   <li>Validate cart, address ownership, zone, minimum amount, weight cap, slot rules.</li>
 *   <li>Write the order, reserve the slot (atomic upsert), take stock per line (atomic conditional update, ascending
 *       product id). Any failure rolls back everything, the idempotency claim included.</li>
 *   <li>Clear the cart, publish {@link OrderPlaced}.</li>
 * </ol>
 * Prices come only from the database, computed with BigDecimal (scale 2, HALF_UP).
 */
@Service
@RequiredArgsConstructor
public class OrderPlacementService {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O, 1/I: easy to read on the phone
    private static final DateTimeFormatter NUMBER_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final CartService carts;
    private final CatalogApi catalog;
    private final DeliveryApi delivery;
    private final InventoryService inventory;
    private final OrderRepository orders;
    private final OrderMapper mapper;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Outcome of a placement: {@code replayed} = the same key was seen before (HTTP 200 instead of 201). */
    public record Placement(OrderResponse order, boolean replayed) {}

    @Transactional
    public Placement place(long userId, String idempotencyKey, PlaceOrderRequest request) {
        String key = validKey(idempotencyKey);
        String hash = sha256(request.canonical());

        // 1. Idempotency claim
        List<Long> claimed = jdbc.queryForList("""
                INSERT INTO idempotency_records (user_id, idem_key, request_hash) VALUES (:u, :k, :h)
                ON CONFLICT (user_id, idem_key) DO NOTHING RETURNING id""",
                new MapSqlParameterSource("u", userId).addValue("k", key).addValue("h", hash), Long.class);
        if (claimed.isEmpty()) return replay(userId, key, hash);
        long recordId = claimed.getFirst();

        // 2. Validation
        Cart cart = carts.cart(userId);
        if (cart.getItems().isEmpty()) throw ApiException.businessRule("CART_EMPTY", "Your cart is empty");
        AddressSnapshot address = delivery.requireOwnAddress(userId, request.addressId());
        ZoneInfo zone = delivery.requireServedZone(address.zoneId());
        StoreSettings settings = delivery.settings();

        Map<Long, ProductInfo> products = catalog.find(cart.getItems().stream().map(CartItem::productId).toList());
        List<Map<String, Object>> unavailable = new ArrayList<>();
        Order order = new Order();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        int weight = 0;
        for (CartItem line : cart.getItems()) {
            ProductInfo p = products.get(line.productId());
            // Hidden/removed → PRODUCT_UNAVAILABLE now. Sold out a moment ago → let the atomic stock update decide,
            // so the customer gets INSUFFICIENT_STOCK with what is left (and racing buyers can't oversell).
            if (p == null || !p.publiclyVisible()) {
                unavailable.add(Map.of("productId", line.productId()));
                continue;
            }
            OrderItem item = new OrderItem();
            item.setProductId(p.id());
            item.setNameFr(p.name().fr());
            item.setNameAr(p.name().ar());
            item.setSku(p.sku());
            item.setSoldBy(p.soldBy().name());
            item.setUnitPrice(p.price());
            item.setUnitWeightGrams(p.unitWeightGrams());
            item.setQuantityOrdered(line.getQuantity());
            item.setLineTotal(Quantities.lineTotal(p.price(), line.getQuantity()));
            item.setSubstitutionPolicy(line.getSubstitutionPolicy());
            order.addItem(item);
            subtotal = subtotal.add(item.getLineTotal());
            weight += Quantities.weightGrams(p.unitWeightGrams(), line.getQuantity());
        }
        if (!unavailable.isEmpty()) {
            throw ApiException.conflict("PRODUCT_UNAVAILABLE", "Some products in your cart can't be ordered any more")
                    .withDetails(unavailable);
        }
        if (subtotal.compareTo(zone.minOrderAmount()) < 0) {
            throw ApiException.businessRule("ORDER_BELOW_MINIMUM", "The minimum order for this zone is " + zone.minOrderAmount() + " DA")
                    .withDetails(List.of(Map.of("minimum", zone.minOrderAmount(), "missing", zone.minOrderAmount().subtract(subtotal))));
        }
        if (weight > settings.maxOrderWeightGrams()) {
            throw ApiException.businessRule("ORDER_TOO_HEAVY", "This order is heavier than one delivery can carry")
                    .withDetails(List.of(Map.of("maxGrams", settings.maxOrderWeightGrams(), "totalGrams", weight)));
        }
        SlotInfo slot = delivery.requireBookableSlot(zone.id(), request.slotId(), request.slotDate());

        // 3. Write the order, then claim the slot and the stock (all-or-nothing with the transaction)
        BigDecimal fee = zone.feeFor(subtotal);
        BigDecimal total = subtotal.add(fee);
        order.setOrderNumber(newOrderNumber());
        order.setUserId(userId);
        order.setStatus(OrderStatus.PLACED);
        order.setZoneId(zone.id());
        order.setRecipientName(address.recipientName());
        order.setPhone(address.phone());
        order.setStreet(address.street());
        order.setLandmark(address.landmark());
        order.setBuildingNotes(address.buildingNotes());
        order.setLat(address.lat());
        order.setLng(address.lng());
        order.setSlotId(slot.id());
        order.setSlotDate(request.slotDate());
        order.setSlotStart(slot.start());
        order.setSlotEnd(slot.end());
        order.setSubtotal(subtotal);
        order.setDeliveryFee(fee);
        order.setTotal(total);
        order.setEstimatedTotal(total);
        order.setTotalWeightGrams(weight);
        order.setPaymentMethod(request.paymentMethod());
        order.setCustomerNote(request.customerNote() == null || request.customerNote().isBlank() ? null : request.customerNote().strip());
        order.setChangeFor(request.changeFor());
        order.setIdempotencyKey(key);
        order.record(null, OrderStatus.PLACED, userId, null);
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setMethod(request.paymentMethod());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(total);
        order.setPayment(payment);
        orders.saveAndFlush(order);

        delivery.reserveSlot(slot.id(), request.slotDate(), slot.capacity());
        inventory.reserve(order.getItems().stream().map(i -> new StockLine(i.getProductId(), i.getQuantityOrdered())).toList(),
                order.getId(), userId);

        // 4. Done: empty the cart, link the idempotency record, announce
        cart.getItems().clear();
        cart.touch();
        jdbc.update("UPDATE idempotency_records SET order_id = :o WHERE id = :id",
                new MapSqlParameterSource("o", order.getId()).addValue("id", recordId));
        events.publishEvent(new OrderPlaced(order.getId(), order.getOrderNumber(), userId, total));
        return new Placement(mapper.toResponse(order), false);
    }

    private Placement replay(long userId, String key, String hash) {
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT request_hash, order_id FROM idempotency_records WHERE user_id = :u AND idem_key = :k",
                new MapSqlParameterSource("u", userId).addValue("k", key));
        if (!hash.equals(record.get("request_hash"))) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different order");
        }
        Long orderId = (Long) record.get("order_id");
        Order order = orderId == null ? null : orders.findById(orderId).orElse(null);
        if (order == null) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key is already in use; retry with a new key");
        }
        return new Placement(mapper.toResponse(order), true);
    }

    /** ATL-YYMMDD-XXXX: the date in the shop's time zone + 4 random characters (32^4 ≈ 1M per day). */
    private String newOrderNumber() {
        String date = LocalDate.now(clock.withZone(StoreSettings.STORE_ZONE)).format(NUMBER_DATE);
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("ATL-").append(date).append('-');
            for (int i = 0; i < 4; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String candidate = sb.toString();
            if (!orders.existsByOrderNumber(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not generate a unique order number");
    }

    private static String validKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.invalidField("Idempotency-Key", "The Idempotency-Key header is required (a new UUID per order)");
        }
        String k = key.strip();
        if (k.length() > 100) throw ApiException.invalidField("Idempotency-Key", "Idempotency-Key must be at most 100 characters");
        return k;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
