package com.atlasna.catalog.store;

import com.atlasna.catalog.common.Actor;

import java.util.Map;

/** Published when the admin saves the shop settings (line caps change the products' public maxOrderQuantity). */
public record StoreSettingsChanged(long version, Map<String, Object> before, Map<String, Object> after, Actor actor) {}
