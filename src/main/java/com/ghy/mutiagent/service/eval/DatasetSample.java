package com.ghy.mutiagent.service.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评测样本契约（手册 §3.1）：id、sourceGroup、类别、split、输入及多轮历史、日期人数预算、
 * 固定地点/天气事实、已锁定项、原计划（调整场景）、期望约束、标注版本。
 *
 * 期望约束（expected）是确定性判据，不是必须逐字匹配的自然语言答案；
 * 样本输入文本是不可信数据，绝不能进入判据配置（见 JudgeConfig 与 SampleRunner）。
 * 所有 map 字段构造时深拷贝，实例一经创建不可变。
 */
public final class DatasetSample {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final String sourceGroup;
    private final String category;
    private final String split;
    private final String input;
    private final Map<String, Object> base;
    private final Map<String, Object> overrides;
    private final Map<String, Object> expected;
    private final String fixtureVersion;
    private final String promptVersion;
    private final String modelVersion;
    private final String labelSource;

    public DatasetSample(String id, String sourceGroup, String category, String split, String input,
                         Map<String, Object> base, Map<String, Object> overrides,
                         Map<String, Object> expected, String fixtureVersion,
                         String promptVersion, String modelVersion, String labelSource) {
        this.id = id;
        this.sourceGroup = sourceGroup;
        this.category = category;
        this.split = split;
        this.input = input;
        this.base = copy(base);
        this.overrides = copy(overrides);
        this.expected = copy(expected);
        this.fixtureVersion = fixtureVersion;
        this.promptVersion = promptVersion;
        this.modelVersion = modelVersion;
        this.labelSource = labelSource;
    }

    public String id() {
        return id;
    }

    public String sourceGroup() {
        return sourceGroup;
    }

    public String category() {
        return category;
    }

    public String split() {
        return split;
    }

    public String input() {
        return input;
    }

    public Map<String, Object> base() {
        return copy(base);
    }

    public Map<String, Object> overrides() {
        return copy(overrides);
    }

    public Map<String, Object> expected() {
        return copy(expected);
    }

    public String fixtureVersion() {
        return fixtureVersion;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public String labelSource() {
        return labelSource;
    }

    /** 宽容读取：缺失字段为 null/空 map，由 DatasetValidator 报出契约级错误 */
    static DatasetSample fromJson(JsonNode n) {
        return new DatasetSample(
                text(n, "id"),
                text(n, "sourceGroup"),
                text(n, "category"),
                text(n, "split"),
                text(n, "input"),
                mapOf(n.get("base")),
                mapOf(n.get("overrides")),
                mapOf(n.get("expected")),
                text(n, "fixtureVersion"),
                text(n, "promptVersion"),
                text(n, "modelVersion"),
                text(n, "labelSource"));
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sourceGroup", sourceGroup);
        m.put("category", category);
        m.put("split", split);
        m.put("input", input);
        m.put("base", copy(base));
        m.put("overrides", copy(overrides));
        m.put("expected", copy(expected));
        m.put("fixtureVersion", fixtureVersion);
        m.put("promptVersion", promptVersion);
        m.put("modelVersion", modelVersion);
        m.put("labelSource", labelSource);
        return m;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Map<String, Object> mapOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        try {
            return JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> copy(Map<String, Object> src) {
        return src == null ? Map.of() : new LinkedHashMap<>(src);
    }
}
