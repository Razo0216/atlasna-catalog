package com.atlasna.catalog.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** A product and quantity in a cart. {@code unitPriceSeen} lets GET /api/cart warn PRICE_CHANGED. */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
public class CartItem {

    @Embeddable
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long cartId;
        private Long productId;

        public Key(Long cartId, Long productId) {
            this.cartId = cartId;
            this.productId = productId;
        }
    }

    @EmbeddedId
    private Key id;

    @MapsId("cartId")
    @ManyToOne(fetch = FetchType.LAZY)
    private Cart cart;

    @Column(precision = 10, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    private SubstitutionPolicy substitutionPolicy = SubstitutionPolicy.CALL_ME;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitPriceSeen;

    private Instant addedAt = Instant.now();

    public long productId() {
        return id.getProductId();
    }
}
