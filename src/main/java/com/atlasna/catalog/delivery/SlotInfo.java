package com.atlasna.catalog.delivery;

import java.time.LocalTime;

/** A bookable slot on a given day. */
public record SlotInfo(long id, LocalTime start, LocalTime end, int capacity) {}
