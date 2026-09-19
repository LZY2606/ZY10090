package fw.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Numeric dotted version, e.g. 1.2.3 or 10. Compares segment by segment. */
public final class Version implements Comparable<Version> {

    private final List<Integer> segments;
    private final String raw;

    private Version(List<Integer> segments, String raw) {
        this.segments = List.copyOf(segments);
        this.raw = raw;
    }

    public static Version of(String text) {
        Objects.requireNonNull(text, "version");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty version");
        }
        List<Integer> parts = new ArrayList<>();
        for (String part : trimmed.split("\\.")) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("Invalid version '" + text + "'");
            }
            parts.add(Integer.parseInt(part));
        }
        return new Version(parts, trimmed);
    }

    public static boolean isValid(String text) {
        try {
            of(text);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public int compareTo(Version other) {
        int n = Math.max(segments.size(), other.segments.size());
        for (int i = 0; i < n; i++) {
            int a = i < segments.size() ? segments.get(i) : 0;
            int b = i < other.segments.size() ? other.segments.get(i) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    public boolean isGreaterThan(Version other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Version other) {
        return compareTo(other) < 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
