package mcboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 读取器:把 YAML 压平成 "a.b.c" 这样的点号键,值统一为字符串。
 * 支持:注释、单双引号、嵌套映射、块状列表(拼成逗号分隔)、行内列表 [a, b]。
 * 目的是让 jar 零第三方依赖,不需要 snakeyaml。
 */
final class SimpleYaml {

    private SimpleYaml() {
    }

    static Map<String, String> parse(String text) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        List<Integer> indentStack = new ArrayList<Integer>();
        List<String> pathStack = new ArrayList<String>();

        String content = text;
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\r?\n", -1);

        for (String rawLine : lines) {
            String line = rawLine.replace("\t", "    ");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---") || trimmed.equals("...")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }

            // 块状列表项: "- value"
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                String item = unquote(stripComment(trimmed.length() > 2 ? trimmed.substring(2).trim() : ""));
                String parent = pathStack.isEmpty() ? "" : pathStack.get(pathStack.size() - 1);
                if (!parent.isEmpty()) {
                    String existing = result.get(parent);
                    if (existing == null || existing.isEmpty()) {
                        result.put(parent, item);
                    } else {
                        result.put(parent, existing + "," + item);
                    }
                }
                continue;
            }

            int separator = findSeparator(trimmed);
            if (separator < 0) {
                continue; // 不认识的行,忽略
            }
            String key = unquote(trimmed.substring(0, separator).trim());
            String value = trimmed.substring(separator + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }
            String fullKey = pathStack.isEmpty() ? key : pathStack.get(pathStack.size() - 1) + "." + key;

            if (value.isEmpty()) {
                // 可能是父节点(下一行缩进更深)
                result.put(fullKey, "");
                indentStack.add(indent);
                pathStack.add(fullKey);
                continue;
            }

            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            result.put(fullKey, unquote(stripComment(value)));
        }
        return result;
    }

    /** 找到 "key:" 中冒号的位置(忽略引号内的冒号)。 */
    private static int findSeparator(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                if (i == line.length() - 1 || line.charAt(i + 1) == ' ' || line.charAt(i + 1) == '\t') {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 去掉值尾部 " # 注释"(只在未加引号时生效)。 */
    private static String stripComment(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            return value;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '#' && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return value.substring(0, i).trim();
            }
        }
        return value.trim();
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                String inner = value.substring(1, value.length() - 1);
                if (first == '"') {
                    return inner.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
                }
                return inner.replace("''", "'");
            }
        }
        return value;
    }
}
