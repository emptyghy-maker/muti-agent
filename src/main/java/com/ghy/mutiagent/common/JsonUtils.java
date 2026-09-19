package com.ghy.mutiagent.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * LLM 输出 JSON 宽容解析工具。
 *
 * LLM 常犯的小毛病：用 ```json 围栏包住 JSON、数组末尾带逗号、字段名不加引号、
 * 输出被 max_tokens 截断（字符串/数组写到一半就没了）。
 * 这里统一处理：截取第一个 JSON 数组/对象 + 宽容解析 + 截断修复重试，
 * 仍失败则抛异常交给调用方兜底。
 */
public final class JsonUtils {

    /** S03 输出大小上限：超过即结构违规，直接拒绝（不截断不猜测） */
    public static final int MAX_OUTPUT_BYTES = 128 * 1024;

    /** 带解析元信息的解析结果（S03）：repaired 表示截断修复后解析成功 */
    public record ParseTracked<T>(T value, boolean repaired, List<String> warnings) {
    }

    /**
     * 用老版 JsonParser.Feature（全 2.x 可用；项目被 langchain4j 拉低了 jackson-core 版本，
     * 2.15+ 的 JsonReadFeature 不存在，只能用这个）。
     */
    @SuppressWarnings("deprecation")
    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            // LLM 输出常自带模板外的字段（如 tripOverview）：忽略而不是抛错，只取模板里声明的字段
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static <T> T parse(String raw, Class<T> type) {
        ParseTracked<T> tracked = parseTracked(raw, type);
        return tracked == null ? null : tracked.value();
    }

    /** 解析并报告是否经过截断修复（S03 调用链：大小检查 → 解析(记录是否修复) → 结构验证） */
    public static <T> ParseTracked<T> parseTracked(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return new ParseTracked<>(null, false, List.of());
        }
        if (raw.length() > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("LLM 输出超过 " + (MAX_OUTPUT_BYTES / 1024) + "KiB，拒绝解析");
        }
        return lenientTracked(extractJson(raw), s -> LENIENT.readValue(s, type));
    }

    public static <T> List<T> parseList(String raw, Class<T> elementType) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        if (raw.length() > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("LLM 输出超过 " + (MAX_OUTPUT_BYTES / 1024) + "KiB，拒绝解析");
        }
        JavaType t = LENIENT.getTypeFactory().constructCollectionType(List.class, elementType);
        return lenientParse(extractJson(raw), s -> LENIENT.readValue(s, t));
    }

    /** 宽容解析为 JsonNode（结构不固定时用，如 Agent 输出 {items:[...], advice:"..."}） */
    public static JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 输出为空");
        }
        if (raw.length() > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("LLM 输出超过 " + (MAX_OUTPUT_BYTES / 1024) + "KiB，拒绝解析");
        }
        return lenientParse(extractJson(raw), LENIENT::readTree);
    }

    /**
     * 严格解析（S11 长行程分块）：分块输出必须完整合法 JSON。
     * 截断/非法一律视为不完整（返回 null）——绝不字符串补括号恢复，也不做截断修复重试。
     */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static <T> T parseStrict(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_OUTPUT_BYTES) {
            return null;
        }
        try {
            return STRICT.readValue(extractJson(raw), type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽容解析：先直接解析；失败则做截断修复后重试一次；仍失败抛第一次的原始异常 */
    private static <T> T lenientParse(String json, JsonOp<T> op) {
        try {
            return op.apply(json);
        } catch (Exception first) {
            String repaired = repairTruncated(json);
            if (repaired != null) {
                try {
                    return op.apply(repaired);
                } catch (Exception ignored) {
                    // 修复后仍失败：抛第一次的原始异常
                }
            }
            throw new IllegalArgumentException("LLM JSON 解析失败: " + first.getMessage(), first);
        }
    }

    /** 同 lenientParse，但报告是否经过截断修复 */
    private static <T> ParseTracked<T> lenientTracked(String json, JsonOp<T> op) {
        try {
            return new ParseTracked<>(op.apply(json), false, List.of());
        } catch (Exception first) {
            String repaired = repairTruncated(json);
            if (repaired != null) {
                try {
                    return new ParseTracked<>(op.apply(repaired), true, List.of("截断输出已修复"));
                } catch (Exception ignored) {
                    // 修复后仍失败：抛第一次的原始异常
                }
            }
            throw new IllegalArgumentException("LLM JSON 解析失败: " + first.getMessage(), first);
        }
    }

    @FunctionalInterface
    private interface JsonOp<T> {
        T apply(String s) throws Exception;
    }

    /**
     * 截取文本中第一个 JSON 数组/对象（自动跳过 ```json 围栏与前后说明文字）。
     * 找不到闭合点时返回从起点到结尾的片段（说明被截断，交给修复逻辑补全）。
     */
    private static String extractJson(String raw) {
        String s = raw.trim();
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return raw;
        }
        char open = s.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s.substring(start);
    }

    /**
     * 输出被 max_tokens 截断时尽力补全：闭合未结束的字符串、数组、对象，救回前半段数据。
     * 结构本来就完整时返回 null（不瞎改）；修复后仍不可解析由调用方走兜底。
     */
    private static String repairTruncated(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(json);
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && matches(stack.peek(), c)) {
                    stack.pop();
                }
            }
        }
        if (!inString && stack.isEmpty()) {
            return null;
        }
        if (inString) {
            if (escaped) {
                sb.setLength(sb.length() - 1);
            }
            sb.append('"');
        }
        while (!stack.isEmpty()) {
            sb.append(stack.pop() == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    private static boolean matches(char open, char close) {
        return (open == '{' && close == '}') || (open == '[' && close == ']');
    }

    /** JsonNode 转 Bean（走同一套宽容规则）；失败返回 null */
    public static <T> T treeToBean(JsonNode node, Class<T> type) {
        if (node == null) {
            return null;
        }
        try {
            return LENIENT.readValue(node.toString(), type);
        } catch (Exception e) {
            return null;
        }
    }

    /** 广度优先查找第一个包含指定字段的对象（模型偶发把结果包在其他键里时兜底） */
    public static JsonNode findObjectWithField(JsonNode root, String field) {
        if (root == null) {
            return null;
        }
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        int steps = 0;
        while (!queue.isEmpty() && steps++ < 500) {
            JsonNode n = queue.poll();
            if (n == null) {
                continue;
            }
            if (n.isObject()) {
                if (n.has(field)) {
                    return n;
                }
                for (JsonNode v : n) {
                    if (v != null && (v.isObject() || v.isArray())) {
                        queue.add(v);
                    }
                }
            } else if (n.isArray()) {
                for (JsonNode el : n) {
                    if (el != null && (el.isObject() || el.isArray())) {
                        queue.add(el);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 广度优先查找第一个「元素是带 id 字段的对象」的数组（任一元素带 id 即命中）。
     * 模型输出的键名多变（items/selectedXxx/recommendedXxx…），按内容识别而非按键名。
     */
    public static JsonNode findIdArray(JsonNode root, String... idKeys) {
        if (root == null) {
            return null;
        }
        Deque<JsonNode> queue = new ArrayDeque<>();
        queue.add(root);
        int steps = 0;
        while (!queue.isEmpty() && steps++ < 500) {
            JsonNode n = queue.poll();
            if (n == null) {
                continue;
            }
            if (n.isArray()) {
                for (JsonNode el : n) {
                    if (el != null && el.isObject()) {
                        for (String k : idKeys) {
                            if (el.has(k)) {
                                return n;
                            }
                        }
                    }
                }
                for (JsonNode el : n) {
                    if (el != null && (el.isObject() || el.isArray())) {
                        queue.add(el);
                    }
                }
            } else if (n.isObject()) {
                for (JsonNode v : n) {
                    if (v != null && (v.isObject() || v.isArray())) {
                        queue.add(v);
                    }
                }
            }
        }
        return null;
    }
}
