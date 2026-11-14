package com.atlasna.catalog.order.service;

import com.atlasna.catalog.order.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Body of POST /api/orders. Items come from the SERVER cart; clients never send prices. */
public record PlaceOrderRequest(
        @NotNull(message = "Address is required") Long addressId,
        @NotNull(message = "Slot is required") Long slotId,
        @NotNull(message = "Slot date is required") LocalDate slotDate,
        @NotNull(message = "Payment method is required") PaymentMethod paymentMethod,
        @Size(max = 500, message = "Note must be at most 500 characters") String customerNote,
        @DecimalMin(value = "0", inclusive = false, message = "Change-for amount must be positive")
        @Digits(integer = 10, fraction = 2) BigDecimal changeFor) {

    /** Canonical form hashed into the idempotency record: same key + different body → 409. */
    String canonical() {
        return addressId + "|" + slotId + "|" + slotDate + "|" + paymentMethod + "|" + (customerNote == null ? "" : customerNote.strip())
                + "|" + (changeFor == null ? "" : changeFor.stripTrailingZeros().toPlainString());
    }
}
