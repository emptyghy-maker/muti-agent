package com.ghy.mutiagent.service.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据切分与版本管理（手册 §3.2）：同一行程、多轮变体、近重复文本按 sourceGroup
 * 必须落在同一 split；同一 sourceGroup 跨集合即 DATASET_LEAKAGE，实验不得启动。
 * 只按随机行切分容易泄漏，先划分 development / validation / holdout 再调提示词。
 */
public final class DatasetSplits {

    public static final String DATASET_LEAKAGE = "DATASET_LEAKAGE";

    /** 一行切分登记：id + sourceGroup + split */
    public record SplitRow(String id, String sourceGroup, String split) {
    }

    private DatasetSplits() {
    }

    /**
     * 切分一致性检查：返回 null 表示无泄漏；
     * 同一 sourceGroup 出现不同 split 时返回 DATASET_LEAKAGE。
     */
    public static String leakageCheck(List<SplitRow> rows) {
        Map<String, String> groupSplit = new LinkedHashMap<>();
        if (rows == null) {
            return null;
        }
        for (SplitRow row : rows) {
            if (row == null || isBlank(row.sourceGroup())) {
                continue;
            }
            String prev = groupSplit.putIfAbsent(row.sourceGroup(), row.split());
            if (prev != null && !prev.equals(row.split())) {
                return DATASET_LEAKAGE;
            }
        }
        return null;
    }

    /** 已登记 sourceGroup → split 快照（只读副本） */
    public static Map<String, String> snapshot(List<SplitRow> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (SplitRow row : rows) {
            if (row != null && !isBlank(row.sourceGroup())) {
                out.put(row.sourceGroup(), row.split());
            }
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
