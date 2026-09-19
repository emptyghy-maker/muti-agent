package com.ghy.mutiagent.service.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据集契约验证器（手册 §3.1）：重复 ID、缺 expected、未知 PlaceKey、缺版本
 * 必须在发模型请求前拒绝。返回错误码列表（按样本顺序首次出现序，去重），
 * 空列表表示契约通过、允许进入执行阶段。
 */
public final class DatasetValidator {

    public static final String DUPLICATE_ID = "DUPLICATE_ID";
    public static final String MISSING_EXPECTED = "MISSING_EXPECTED";
    public static final String UNKNOWN_PLACE_KEY = "UNKNOWN_PLACE_KEY";
    public static final String MISSING_VERSION = "MISSING_VERSION";

    /** 地点键形态：大写类型前缀 + 冒号 + 数字 ID（如 ATTRACTION:1、FOOD:2、HOTEL:1） */
    private static final Pattern PLACE_KEY = Pattern.compile("^[A-Z]+:\\d+$");

    private DatasetValidator() {
    }

    public static List<String> validate(DatasetFile file) {
        List<String> codes = new ArrayList<>();

        Set<String> seen = new LinkedHashSet<>();
        for (DatasetSample s : file.samples()) {
            if (s.id() != null && !s.id().isBlank() && !seen.add(s.id())) {
                addOnce(codes, DUPLICATE_ID);
            }
        }

        for (DatasetSample s : file.samples()) {
            if (s.expected() == null || s.expected().isEmpty()) {
                addOnce(codes, MISSING_EXPECTED);
            }
            if (isBlank(s.fixtureVersion()) || isBlank(s.promptVersion())
                    || isBlank(s.modelVersion()) || isBlank(s.labelSource())) {
                addOnce(codes, MISSING_VERSION);
            }
            for (String key : placeKeysIn(s.expected())) {
                if (file.toolSnapshot() == null || !file.toolSnapshot().containsKey(key)) {
                    addOnce(codes, UNKNOWN_PLACE_KEY);
                }
            }
        }
        return codes;
    }

    /** 期望约束中出现的地点键引用（递归收集字符串值） */
    static List<String> placeKeysIn(Map<String, Object> map) {
        List<String> keys = new ArrayList<>();
        if (map == null) {
            return keys;
        }
        for (Object value : map.values()) {
            collectKeys(value, keys);
        }
        return keys;
    }

    private static void collectKeys(Object value, List<String> keys) {
        if (value instanceof String s) {
            if (PLACE_KEY.matcher(s).matches() && !keys.contains(s)) {
                keys.add(s);
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                collectKeys(v, keys);
            }
        } else if (value instanceof Iterable<?> it) {
            for (Object v : it) {
                collectKeys(v, keys);
            }
        }
    }

    private static void addOnce(List<String> codes, String code) {
        if (!codes.contains(code)) {
            codes.add(code);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
