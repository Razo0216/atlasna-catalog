package com.atlasna.catalog.delivery;

/** Why a slot can't be booked, shown to the customer. */
public enum SlotUnavailableReason {
    /** Capacity reached for that day. */
    FULL,
    /** The day's cut-off (or the slot itself) has passed. */
    PAST_CUTOFF,
    /** Starts inside the packing lead time. */
    TOO_SOON,
    /** Shop closed, outside opening hours, or too far ahead. */
    CLOSED
}
