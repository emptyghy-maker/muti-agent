package com.ghy.mutiagent.rule;

import java.math.BigDecimal;

/**
 * 模糊回答的默认值规则（纯函数，可单测）。
 *
 * 用户对某些偏好「没想好」时，用合理默认值一次通过、不反复追问——
 * 避免反复迭代重生成浪费 token，也符合「事后走修改通道」的产品设计。
 */
public final class DefaultsResolver {

    /** 预算参考区间缺失时的兜底值（元） */
    private static final BigDecimal FALLBACK_BUDGET = BigDecimal.valueOf(3000);

    private DefaultsResolver() {
    }

    public static int defaultDays() {
        return 3;
    }

    public static int defaultPeople() {
        return 2;
    }

    /** 预算 = 城市人均日消费区间中位数 × 天数 × 人数 */
    public static BigDecimal defaultBudget(BigDecimal min, BigDecimal max, int days, int people) {
        if (min == null || max == null || min.signum() <= 0 || max.signum() <= 0) {
            return FALLBACK_BUDGET;
        }
        BigDecimal mid = min.add(max).divide(BigDecimal.valueOf(2));
        return mid.multiply(BigDecimal.valueOf(days)).multiply(BigDecimal.valueOf(people));
    }

    public static String defaultAttractionType() {
        return "混合";
    }

    public static String defaultFoodTaste() {
        return "本地特色菜";
    }

    public static String defaultEnergyLevel() {
        return "一般";
    }

    public static String defaultHotelStyle() {
        return "性价比优先";
    }
}
