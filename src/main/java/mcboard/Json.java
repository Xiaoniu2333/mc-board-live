package mcboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器:只做本工具需要的事 —— 解析 Minecraft 状态响应、
 * 以及把 Minecraft 的 text component 拍平成纯文本。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 内容为空");
        }
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    public static String optString(Map<String, Object> object, String key, String defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    public static int optInt(Map<String, Object> object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                // 忽略,使用默认值
            }
        }
        return defaultValue;
    }

    /** 把 Minecraft 的 text component(字符串 / 对象 / 数组)拍平成纯文本。 */
    public static String flatten(Object value) {
        StringBuilder sb = new StringBuilder();
        flattenInto(value, sb);
        return sb.toString();
    }

    private static void flattenInto(Object value, StringBuilder sb) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            sb.append((String) value);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof List) {
            for (Object child : (List<?>) value) {
                flattenInto(child, sb);
            }
            return;
        }
        if (value instanceof Map) {
            Map<String, Object> map = asObject(value);
            Object text = map.get("text");
            if (text != null) {
                flattenInto(text, sb);
            } else {
                Object translate = map.get("translate");
                if (translate != null) {
                    flattenInto(translate, sb);
                }
            }
            flattenInto(map.get("extra"), sb);
        }
    }

    /** 生成一个合法的 JSON 字符串字面量(含引号)。 */
    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 解析实现
    // ------------------------------------------------------------------

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 提前结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // '{'
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new IllegalArgumentException("JSON 对象缺少 ':' (位置 " + pos + ")");
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 对象未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new IllegalArgumentException("JSON 对象中出现意外字符 '" + c + "' (位置 " + pos + ")");
        }
    }

    private java.util.List<Object> readArray() {
        java.util.List<Object> list = new java.util.ArrayList<Object>();
        pos++; // '['
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 数组未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new IllegalArgumentException("JSON 数组中出现意外字符 '" + c + "' (位置 " + pos + ")");
        }
    }

    private String readString() {
        if (pos >= src.length() || src.charAt(pos) != '"') {
            throw new IllegalArgumentException("JSON 字符串应以 '\"' 开始 (位置 " + pos + ")");
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("JSON 转义序列未完成");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("JSON \\u 转义不完整");
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                    break;
                default:
                    throw new IllegalArgumentException("未知 JSON 转义: \\" + esc);
            }
        }
        throw new IllegalArgumentException("JSON 字符串未闭合");
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("JSON 中出现无法识别的字符 '" + src.charAt(pos) + "' (位置 " + pos + ")");
        }
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException ignored) {
                // 超过 long 范围时退回 double
            }
        }
        return Double.valueOf(raw);
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("JSON 期望 '" + literal + "' (位置 " + pos + ")");
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }
}
