package com.atlasna.catalog.common;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * "What changed" for the audit log: of two snapshots, keep only the fields whose value differs.
 * BigDecimals compare by value (80.00 = 80.0), so a re-save that changes nothing records nothing.
 */
public final class FieldChanges {

    /** {@code before} and {@code after} with only the differing keys (both empty when nothing changed). */
    public record Diff(Map<String, Object> before, Map<String, Object> after) {
        public boolean isEmpty() {
            return before.isEmpty() && after.isEmpty();
        }
    }

    private FieldChanges() {}

    public static Diff diff(Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (before != null) keys.addAll(before.keySet());
        if (after != null) keys.addAll(after.keySet());
        for (String k : keys) {
            Object x = before == null ? null : before.get(k);
            Object y = after == null ? null : after.get(k);
            if (!same(x, y)) {
                b.put(k, plain(x));
                a.put(k, plain(y));
            }
        }
        return new Diff(b, a);
    }

    /** A record's components as a map (e.g. the shop settings), for {@link #diff}. */
    public static Map<String, Object> of(Record record) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (record == null) return map;
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            try {
                map.put(c.getName(), c.getAccessor().invoke(record));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return map;
    }

    private static boolean same(Object x, Object y) {
        if (x instanceof BigDecimal p && y instanceof BigDecimal q) return p.compareTo(q) == 0;
        return Objects.equals(x, y);
    }

    /** Values a document store can hold as-is (numbers, text, booleans); everything else becomes its text form. */
    private static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long) return v;
        if (v instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (v instanceof Enum<?> e) return e.name();
        return String.valueOf(v);
    }
}
