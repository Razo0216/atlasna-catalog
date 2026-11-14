package com.atlasna.catalog.delivery;

import java.math.BigDecimal;

/** A customer's address copied into an order, so later edits never rewrite history. */
public record AddressSnapshot(long id, long zoneId, String recipientName, String phone, String street, String landmark,
                              String buildingNotes, BigDecimal lat, BigDecimal lng) {}
