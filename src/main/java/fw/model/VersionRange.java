package fw.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inclusive version interval {@code [min, max]}.
 *
 * <p>String forms accepted on input: {@code "1.0.0 .. 2.3.0"},
 * {@code "1.0.0..2.3.0"}, a single exact version {@code "1.2"}, a
 * {@code "&gt;=1.0"} / {@code "&lt;=2.3"} expression, or {@code "*"} for
 * unbounded. This is the way companion-component compatibility windows are
 * expressed in the manifest.</p>
 */
public final class VersionRange {

    private final String min; // null = unbounded
    private final String max; // null = unbounded

    public VersionRange(String min, String max) {
        if (min != null && max != null && Version.of(min).isGreaterThan(Version.of(max))) {
            throw new IllegalArgumentException("Invalid range: min " + min + " > max " + max);
        }
        this.min = min;
        this.max = max;
    }

    public static VersionRange any() {
        return new VersionRange(null, null);
    }

    /** Parses the compact string form used in manifests. */
    public static VersionRange parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Null version range");
        }
        String t = text.trim();
        if (t.isEmpty() || t.equals("*")) {
            return any();
        }
        String min = null;
        String max = null;
        if (t.contains("..")) {
            String[] parts = t.split("\\.\\.", 2);
            min = blankToNull(parts[0]);
            max = blankToNull(parts[1]);
        } else {
            String rest = t;
            if (rest.startsWith(">=")) {
                min = rest.substring(2).trim();
            } else if (rest.startsWith("<=")) {
                max = rest.substring(2).trim();
            } else if (rest.startsWith(">")) {
                throw new IllegalArgumentException("Only inclusive bounds are supported: " + text);
            } else if (rest.startsWith("<")) {
                throw new IllegalArgumentException("Only inclusive bounds are supported: " + text);
            } else {
                min = rest;
                max = rest;
            }
        }
        return new VersionRange(min, max);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public boolean contains(String version) {
        Version v = Version.of(version);
        if (min != null && v.isLessThan(Version.of(min))) {
            return false;
        }
        if (max != null && v.isGreaterThan(Version.of(max))) {
            return false;
        }
        return true;
    }

    public String min() {
        return min;
    }

    public String max() {
        return max;
    }

    @Override
    public String toString() {
        if (min == null && max == null) {
            return "*";
        }
        if (min != null && min.equals(max)) {
            return min;
        }
        return (min == null ? "" : min) + ".." + (max == null ? "" : max);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", min);
        m.put("max", max);
        m.put("display", toString());
        return m;
    }
}
