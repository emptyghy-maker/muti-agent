package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.RequirementAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * LLM 输出统一解析层（docs/agent-io-spec.md 规范的唯一代码实现）。
 *
 * 所有 Agent 的输出去向这里解析，业务代码只认「items + 数字 id」：
 * 1. 先按统一信封 {items:[...]} 解析；
 * 2. 信封缺失/元素不像 id 对象时，按内容广度搜索「元素带 id 的对象数组」（键名无关，
 *    模型自创 selectedXxx/recommendedXxx 等键名也能接住）；
 * 3. advice 提取顺序固定：advice → budgetAnalysis.note → summary.budgetEstimate.note；
 * 4. 结果被包进别的结构时（需求分析含 mode 的对象、行程含 days 的对象），自动内层查找。
 */
public final class AgentOutputParser {

    private AgentOutputParser() {
    }

    /** 统一入口：提取 items 数组（元素已归一为模板字段）；空数组表示无有效选择 */
    public static ArrayNode extractItems(JsonNode node, Function<JsonNode, ObjectNode> normalizer,
                                         String... idKeys) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (node == null) {
            return out;
        }
        JsonNode items = node.isArray() ? node : node.path("items");
        if (items.isArray() && !items.isEmpty()) {
            JsonNode first = items.get(0);
            boolean looksLikeIds = false;
            if (first.isObject()) {
                for (String k : idKeys) {
                    if (first.has(k)) {
                        looksLikeIds = true;
                        break;
                    }
                }
            }
            if (!looksLikeIds) {
                // items 元素不像 id 对象（如旧版分组输出）→ 按内容重新定位
                items = JsonUtils.findIdArray(node, idKeys);
            }
        }
        if (items == null || !items.isArray() || items.isEmpty()) {
            items = JsonUtils.findIdArray(node, idKeys);
        }
        if (items == null || !items.isArray()) {
            return out;
        }
        for (JsonNode el : items) {
            ObjectNode n = normalizer.apply(el);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    // ==================== 元素归一 ====================

    public static ObjectNode attractionItem(JsonNode el) {
        if (el == null || !el.isObject()) {
            return null;
        }
        rejectConflict(el, "attractionId", "id");
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("attractionId", firstLong(el, "attractionId", "id"));
        o.put("feature", firstText(el, "feature", "features"));
        o.put("why", firstText(el, "why", "reason", "feature", "features"));
        return o;
    }

    public static ObjectNode foodItem(JsonNode el) {
        if (el == null || !el.isObject()) {
            return null;
        }
        rejectConflict(el, "restaurantId", "id");
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("restaurantId", firstLong(el, "restaurantId", "id"));
        String mt = firstText(el, "mealType");
        if (mt != null && !mt.isBlank()) {
            String norm = switch (mt.trim()) {
                case "早餐", "早饭" -> "早餐";
                case "午餐", "午饭", "中饭" -> "午餐";
                case "晚餐", "晚饭" -> "晚餐";
                case "小吃", "夜宵" -> "小吃";
                default -> null;
            };
            if (norm != null) {
                o.put("mealType", norm);
            }
        }
        return o;
    }

    public static ObjectNode hotelItem(JsonNode el) {
        if (el == null || !el.isObject()) {
            return null;
        }
        rejectConflict(el, "hotelId", "id");
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("hotelId", firstLong(el, "hotelId", "id"));
        o.put("why", firstText(el, "why", "reason"));
        return o;
    }

    /** 推荐建议提取：优先 advice 键，其次模型附带的预算分析/摘要结论；模型拼错 advice 时按 ad 前缀字符串字段兜底 */
    public static String adviceOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String p : new String[]{"advice", "budgetAnalysis/note", "summary/budgetEstimate/note"}) {
            JsonNode n = node;
            for (String seg : p.split("/")) {
                n = n.path(seg);
            }
            String v = n.asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (e.getKey().startsWith("ad") && e.getValue().isTextual()) {
                String v = e.getValue().asText("");
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    // ==================== 嵌套兜底 ====================

    /** 需求分析：模型偶发把结果包在其他键里时，泛化查找第一个含 mode 字段的对象并手工映射 */
    public static RequirementAnalysis nestedRequirement(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode o = JsonUtils.findObjectWithField(root, "mode");
        if (o == null) {
            return null;
        }
        RequirementAnalysis a = new RequirementAnalysis();
        a.setMode(o.path("mode").asText(""));
        if (o.path("focus").isArray()) {
            List<String> focus = new ArrayList<>();
            o.path("focus").forEach(f -> {
                String s = f.asText("");
                if (!s.isBlank()) {
                    focus.add(s);
                }
            });
            a.setFocus(focus);
        }
        a.setBrief(o.path("brief").asText(""));
        if (o.path("needs").isObject()) {
            JsonNode needs = o.path("needs");
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String k : List.of("path", "cost", "sightseeing", "food")) {
                m.put(k, needs.path(k).isInt() ? needs.path(k).asInt() : 3);
            }
            a.setNeeds(m);
        }
        return a;
    }

    /** 行程规划：模型偶发把 days 包在其他键里时，泛化查找第一个含 days 字段的对象再解析 */
    public static ItineraryPlan nestedItinerary(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode o = JsonUtils.findObjectWithField(root, "days");
        if (o == null || !o.path("days").isArray()) {
            return null;
        }
        return JsonUtils.treeToBean(o, ItineraryPlan.class);
    }

    // ==================== 内部 ====================

    /**
     * S03 严格 ID 契约：只接受可表示为正 long 的整数字面量。
     * 小数（1.9）、浮点形式（1.0）、字符串（"1"）、负数、0、超 long 上限均返回 0（拒绝），
     * 不得截断或溢出映射到真实地点。
     */
    private static long firstLong(JsonNode el, String... keys) {
        for (String k : keys) {
            JsonNode n = el.path(k);
            if (n.isIntegralNumber() && n.canConvertToLong() && n.longValue() > 0) {
                return n.longValue();
            }
        }
        return 0L;
    }

    /** 标准字段与旧 alias 同时出现且不一致：结构违规，明确拒绝而不是任选一个 */
    private static void rejectConflict(JsonNode el, String primary, String alias) {
        JsonNode a = el.get(primary);
        JsonNode b = el.get(alias);
        if (a != null && b != null && !a.equals(b)) {
            throw new IllegalArgumentException("元素同时声明冲突的 " + primary + "=" + a.asText()
                    + " 与 " + alias + "=" + b.asText());
        }
    }

    private static String firstText(JsonNode el, String... keys) {
        for (String k : keys) {
            String v = el.path(k).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
