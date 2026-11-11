package com.atlasna.catalog.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** URL-safe slugs from French product names ("Œufs ×6" -> "oeufs-6"). */
public final class Slugs {

    private static final int MAX_LENGTH = 200; // leaves room for a "-NN" uniqueness suffix inside VARCHAR(220)

    private Slugs() {}

    public static String slugify(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT)
                .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss");   // ligatures NFD does not decompose
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", ""); // strip accents
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        return s.isEmpty() ? "produit" : s;
    }

    /** Appends -2, -3, … until {@code exists} says the slug is free. */
    public static String unique(String base, Predicate<String> exists) {
        String candidate = base;
        for (int i = 2; exists.test(candidate); i++) candidate = base + "-" + i;
        return candidate;
    }
}
