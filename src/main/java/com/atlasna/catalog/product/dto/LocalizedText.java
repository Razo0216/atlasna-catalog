package com.atlasna.catalog.product.dto;

/**
 * Text in French and Arabic, the shape used for every bilingual field in the API: {@code {"fr": ..., "ar": ...}}.
 * Either may be null (French is required for names). Clients fall back ar -> fr.
 */
public record LocalizedText(String fr, String ar) {
    public static LocalizedText of(String fr, String ar) {
        return new LocalizedText(blankToNull(fr), blankToNull(ar));
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
