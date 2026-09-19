package fw.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic JSON parser / writer.
 *
 * <p>Parsed objects are {@link LinkedHashMap} so insertion order is preserved;
 * the writer emits keys in iteration order. Firmware metadata is signed over a
 * canonical form produced by {@link #canonical(Object)} which recursively sorts
 * object keys, making signatures stable across whitespace / key reordering.</p>
 */
public final class Json {

    private Json() {
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }

        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("Trailing characters at position " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object v = parse(text);
        if (!(v instanceof List)) {
            throw new JsonException("Expected a JSON array");
        }
        return (List<Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (eof()) {
                throw new JsonException("Unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at position " + pos);
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at position " + pos);
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (eof()) {
                        throw new JsonException("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new JsonException("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape \\" + e);
                    }
                } else {
                    if (c < 0x20) {
                        throw new JsonException("Unescaped control character in string");
                    }
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("Invalid literal at position " + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty() || token.equals("-")) {
                throw new JsonException("Invalid number at position " + start);
            }
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                long l = Long.parseLong(token);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException ex) {
                throw new JsonException("Invalid number '" + token + "'");
            }
        }

        char peek() {
            if (eof()) {
                throw new JsonException("Unexpected end of input");
            }
            return s.charAt(pos);
        }

        char next() {
            if (eof()) {
                throw new JsonException("Unexpected end of input");
            }
            return s.charAt(pos++);
        }

        void expect(char expected) {
            if (eof() || s.charAt(pos) != expected) {
                throw new JsonException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------ write

    public static String write(Object value) {
        return write(value, false);
    }

    public static String writePretty(Object value) {
        return write(value, true);
    }

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, pretty, depth);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, pretty, depth);
        } else if (value instanceof java.util.Collection<?> collection) {
            writeArray(sb, new ArrayList<>(collection), pretty, depth);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, boolean pretty, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newline(sb, depth + 1);
            }
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(pretty ? ": " : ":");
            writeValue(sb, e.getValue(), pretty, depth + 1);
        }
        if (pretty) {
            newline(sb, depth);
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, boolean pretty, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newline(sb, depth + 1);
            }
            writeValue(sb, item, pretty, depth + 1);
        }
        if (pretty) {
            newline(sb, depth);
        }
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n');
        sb.append("  ".repeat(depth));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------- canonical

    /** Returns a canonical JSON representation: sorted keys, no whitespace. */
    @SuppressWarnings("unchecked")
    public static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.keySet().stream().map(String::valueOf).sorted()
                    .forEach(k -> sorted.put(k, canonical(map.get(k))));
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(canonical(item));
            }
            return out;
        }
        return value;
    }

    public static String canonicalString(Object value) {
        return write(canonical(value), false);
    }

    // ----------------------------------------------------------- convenience

    public static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static String reqString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new JsonException("Missing or invalid field '" + key + "'");
        }
        return s;
    }

    public static int reqInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new JsonException("Missing or invalid integer field '" + key + "'");
    }

    public static int optInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public static boolean optBool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> reqObject(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof Map)) {
            throw new JsonException("Missing or invalid object field '" + key + "'");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> reqList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof List)) {
            throw new JsonException("Missing or invalid array field '" + key + "'");
        }
        return (List<Object>) v;
    }
}
