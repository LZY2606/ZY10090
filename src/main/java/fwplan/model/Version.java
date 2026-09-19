package fwplan.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import fwplan.util.Json.JsonException;

/**
 * 语义化版本：数字点分段比较，数字段用尽时以 0 补齐；
 * 允许预发布后缀（如 2.1.0-rc1），相同数字版本下无后缀高于有后缀，后缀按字符串比较。
 */
public final class Version implements Comparable<Version> {

    private final String raw;
    private final int[] nums;
    private final String suffix;

    private Version(String raw, int[] nums, String suffix) {
        this.raw = raw;
        this.nums = nums;
        this.suffix = suffix;
    }

    public static Version of(String text) {
        if (text == null) throw new JsonException("version is null");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) throw new JsonException("version is empty");
        String suffix = null;
        String main = trimmed;
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            main = trimmed.substring(0, dash);
            suffix = trimmed.substring(dash + 1);
            if (suffix.isEmpty()) throw new JsonException("invalid version suffix: " + text);
        }
        String[] parts = main.split("\\.");
        if (parts.length == 0) throw new JsonException("invalid version: " + text);
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.chars().allMatch(Character::isDigit) || part.isEmpty()) {
                throw new JsonException("invalid version segment: " + text);
            }
            nums[i] = Integer.parseInt(part);
        }
        return new Version(trimmed, nums, suffix);
    }

    public String raw() { return raw; }

    @Override
    public int compareTo(Version other) {
        int len = Math.max(nums.length, other.nums.length);
        for (int i = 0; i < len; i++) {
            int a = i < nums.length ? nums[i] : 0;
            int b = i < other.nums.length ? other.nums[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        if (Objects.equals(suffix, other.suffix)) return 0;
        if (suffix == null) return 1;
        if (other.suffix == null) return -1;
        return suffix.compareTo(other.suffix);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int n : nums) result = 31 * result + n;
        return 31 * result + (suffix == null ? 0 : suffix.hashCode());
    }

    @Override
    public String toString() { return raw; }

    /** 数学区间：[1.2,2.0)、[1.0,*]、* 等。边界可开可闭，右端 * 表示无界。 */
    public static final class Range {
        public final Version low;
        public final boolean lowInclusive;
        public final Version high;
        public final boolean highInclusive;

        public Range(Version low, boolean lowInclusive, Version high, boolean highInclusive) {
            this.low = low;
            this.lowInclusive = lowInclusive;
            this.high = high;
            this.highInclusive = highInclusive;
        }

        public static Range parse(String expr) {
            if (expr == null) throw new JsonException("range is null");
            String s = expr.trim();
            if (s.equals("*")) return new Range(null, true, null, true);
            if (s.length() < 3) throw new JsonException("invalid version range: " + expr);
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first != '[' && first != '(') || (last != ']' && last != ')')) {
                throw new JsonException("range must look like [1.0,2.0): " + expr);
            }
            String inner = s.substring(1, s.length() - 1).trim();
            int comma = inner.indexOf(',');
            if (comma < 0) throw new JsonException("range needs a comma: " + expr);
            String lo = inner.substring(0, comma).trim();
            String hi = inner.substring(comma + 1).trim();
            Version low = lo.equals("*") || lo.isEmpty() ? null : Version.of(lo);
            Version high = hi.equals("*") || hi.isEmpty() ? null : Version.of(hi);
            if (low != null && high != null && low.compareTo(high) > 0) {
                throw new JsonException("range lower bound above upper bound: " + expr);
            }
            return new Range(low, first == '[', high, last == ']');
        }

        public boolean contains(Version v) {
            if (low != null) {
                int c = v.compareTo(low);
                if (lowInclusive ? c < 0 : c <= 0) return false;
            }
            if (high != null) {
                int c = v.compareTo(high);
                if (highInclusive ? c > 0 : c >= 0) return false;
            }
            return true;
        }

        /** 是否存在两个区间的公共版本不由此判断（版本离散），这里仅判断数学区间是否相交。 */
        public boolean overlaps(Range o) {
            if (low != null && o.high != null) {
                int c = low.compareTo(o.high);
                if (c > 0 || (c == 0 && (!lowInclusive || !o.highInclusive))) return false;
            }
            if (o.low != null && high != null) {
                int c = o.low.compareTo(high);
                if (c > 0 || (c == 0 && (!o.lowInclusive || !highInclusive))) return false;
            }
            return true;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(lowInclusive ? '[' : '(');
            sb.append(low == null ? "*" : low.raw);
            sb.append(',');
            sb.append(high == null ? "*" : high.raw);
            sb.append(highInclusive ? ']' : ')');
            return sb.toString();
        }

        @Override
        public String toString() { return format(); }
    }

    public static List<String> sortRaw(List<String> rawVersions) {
        List<Version> versions = new ArrayList<>();
        for (String r : rawVersions) versions.add(Version.of(r));
        versions.sort(null);
        List<String> out = new ArrayList<>();
        for (Version v : versions) out.add(v.raw);
        return out;
    }
}
