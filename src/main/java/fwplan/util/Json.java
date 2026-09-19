package fwplan.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化实现（仅依赖 JDK），保证完全离线可构建。
 * 支持对象、数组、字符串、数字、true/false/null，对象键保持插入顺序。
 */
public final class Json {

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    private Json() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new JsonException("expected JSON object");
        return (Map<String, Object>) value;
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (p.pos < p.text.length()) throw new JsonException("trailing characters at " + p.pos);
        return value;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, value);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writePretty(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(sb, indent + 1);
                writeTo(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                writePretty(sb, list.get(i), indent + 1);
                if (i < list.size() - 1) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            writeTo(sb, value);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) sb.append("null");
        else if (value instanceof String s) writeString(sb, s);
        else if (value instanceof Boolean b) sb.append(b.booleanValue());
        else if (value instanceof Number n) sb.append(n.toString());
        else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, list.get(i));
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) { this.text = text; }

        void skipWs() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= text.length()) throw new JsonException("unexpected end of input");
            char c = text.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWs();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new JsonException("expected , or } at " + pos);
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new JsonException("expected , or ] at " + pos);
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw new JsonException("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw new JsonException("bad unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("bad escape: " + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("invalid literal at " + pos);
        }

        Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("invalid literal at " + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) pos++;
            String token = text.substring(start, pos);
            if (token.isEmpty()) throw new JsonException("invalid number at " + start);
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.parseDouble(token);
                long l = Long.parseLong(token);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ex) {
                throw new JsonException("invalid number: " + token);
            }
        }

        char peek() {
            if (pos >= text.length()) throw new JsonException("unexpected end of input");
            return text.charAt(pos);
        }

        char next() {
            if (pos >= text.length()) throw new JsonException("unexpected end of input");
            return text.charAt(pos++);
        }

        void expect(char c) {
            char actual = next();
            if (actual != c) throw new JsonException("expected '" + c + "' but got '" + actual + "' at " + pos);
        }
    }

    // ---- 便捷取值 ----

    public static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        return String.valueOf(value);
    }

    public static String requireStr(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new JsonException("missing or invalid string field: " + key);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map)) throw new JsonException("field is not an object: " + key);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List)) throw new JsonException("field is not a list: " + key);
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objList(Map<String, Object> map, String key) {
        List<Object> raw = list(map, key);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map)) throw new JsonException("list items must be objects: " + key);
            out.add((Map<String, Object>) item);
        }
        return out;
    }

    public static int intVal(Map<String, Object> map, String key, int def) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        return def;
    }
}
