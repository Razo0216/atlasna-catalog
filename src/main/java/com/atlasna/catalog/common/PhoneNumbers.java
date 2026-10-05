package com.atlasna.catalog.common;

import java.util.Optional;
import java.util.regex.Pattern;

/** Algerian mobile numbers only (05/06/07). Canonical storage form: +213 + 9 digits. */
public final class PhoneNumbers {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-()]");
    private static final Pattern ALGERIAN_MOBILE = Pattern.compile("^(?:\\+213|00213|0)([567]\\d{8})$");

    private PhoneNumbers() {}

    /** @return the canonical "+213XXXXXXXXX" form, or empty if the input is not a valid Algerian mobile number. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        var matcher = ALGERIAN_MOBILE.matcher(SEPARATORS.matcher(raw).replaceAll(""));
        return matcher.matches() ? Optional.of("+213" + matcher.group(1)) : Optional.empty();
    }

    public static boolean isValid(String raw) {
        return normalize(raw).isPresent();
    }
}
