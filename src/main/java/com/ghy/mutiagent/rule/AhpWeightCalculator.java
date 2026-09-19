package com.ghy.mutiagent.rule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AHP 权重计算器：成对比较矩阵 → 几何平均归一化求权重（特征向量近似）。
 *
 * 默认优先级：路径优 > 成本低 > 旅游需求 > 美食需求（≈ 0.467 / 0.278 / 0.160 / 0.095）。
 * 当需求分析给出各准则强度（1~5）且某一需求「明显突出」（≥4 且比第二名高 ≥2）时，
 * 按强度比例调整矩阵重新求权，实现权重的灵活设置；强度全相等时结果 ≈ 默认权重。
 */
public final class AhpWeightCalculator {

    public static final String PATH = "path";                  // 路径优
    public static final String COST = "cost";                  // 成本低
    public static final String SIGHTSEEING = "sightseeing";    // 旅游需求（拍摄/游玩）
    public static final String FOOD = "food";                  // 美食需求

    private static final List<String> ORDER = List.of(PATH, COST, SIGHTSEEING, FOOD);

    /** 默认成对比较矩阵（行 vs 列，a[i][j] = 行重要性 / 列重要性） */
    private static final double[][] DEFAULT_MATRIX = {
            {1, 2, 3, 4},
            {0.5, 1, 2, 3},
            {1.0 / 3, 0.5, 1, 2},
            {0.25, 1.0 / 3, 0.5, 1}
    };

    private AhpWeightCalculator() {
    }

    /** 默认权重（没有需求分析结果时使用） */
    public static Map<String, Double> defaultWeights() {
        return weights(DEFAULT_MATRIX);
    }

    /**
     * 按需求强度灵活调整：
     * 1. boost_i = 强度_i / 平均强度（强度缺省按 3 计，截断到 [1,5]）；
     * 2. 新矩阵 a_ij = 默认 a_ij × (boost_i / boost_j)，截断到 [1/9, 9]；
     * 3. 重新求权。
     */
    public static Map<String, Double> adjust(Map<String, Integer> needs) {
        double[] boost = new double[4];
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            Integer v = needs == null ? null : needs.get(ORDER.get(i));
            double s = v == null ? 3 : Math.max(1, Math.min(5, v));
            boost[i] = s;
            sum += s;
        }
        double avg = sum / 4;
        for (int i = 0; i < 4; i++) {
            boost[i] = boost[i] / avg;
        }
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double v = DEFAULT_MATRIX[i][j] * (boost[i] / boost[j]);
                m[i][j] = Math.max(1.0 / 9, Math.min(9, v));
            }
        }
        return weights(m);
    }

    /** 判断某需求是否「明显大于其他」：强度 ≥4 且比第二名高 ≥2 */
    public static boolean dominantNeed(Map<String, Integer> needs, String criterion) {
        if (needs == null) {
            return false;
        }
        Integer v = needs.get(criterion);
        if (v == null || v < 4) {
            return false;
        }
        int second = 0;
        for (Map.Entry<String, Integer> e : needs.entrySet()) {
            if (criterion.equals(e.getKey())) {
                continue;
            }
            second = Math.max(second, e.getValue() == null ? 3 : e.getValue());
        }
        return v - second >= 2;
    }

    /** 几何平均归一化求权重（AHP 标准近似算法），保留 3 位小数 */
    private static Map<String, Double> weights(double[][] m) {
        int n = m.length;
        double[] gm = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            double prod = 1;
            for (int j = 0; j < n; j++) {
                prod *= m[i][j];
            }
            gm[i] = Math.pow(prod, 1.0 / n);
            total += gm[i];
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            out.put(ORDER.get(i), Math.round(gm[i] / total * 1000.0) / 1000.0);
        }
        return out;
    }
}
