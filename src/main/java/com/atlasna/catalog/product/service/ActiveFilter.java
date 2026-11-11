package com.atlasna.catalog.product.service;

import com.atlasna.catalog.common.exception.ApiException;

import java.util.Locale;

/** Admin {@code ?active=true|false|all}. */
public enum ActiveFilter {
    TRUE, FALSE, ALL;

    public static ActiveFilter parse(String value) {
        if (value == null || value.isBlank()) return TRUE;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> TRUE;
            case "false" -> FALSE;
            case "all" -> ALL;
            default -> throw ApiException.invalidField("active", "Use true, false or all");
        };
    }
}
