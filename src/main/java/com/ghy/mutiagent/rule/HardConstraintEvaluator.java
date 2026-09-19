package com.ghy.mutiagent.rule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 统一硬约束出口（S07）：三类候选（景点/餐厅/酒店）× 三个来源（SQL/AI/规则补足）
 * 全部经过同一个评估函数，输出三态：
 *
 * - ELIGIBLE   事实齐备且满足所有硬条件；
 * - INELIGIBLE 事实明确违反硬条件（带原因码）；
 * - UNKNOWN    硬条件所需事实缺失/未知——未知不算通过，也不得伪装成 false 排除。
 *
 * 模型标签不能替代官方事实；软偏好只对 ELIGIBLE 集合排序，被排除项不得加回。
 */
public final class HardConstraintEvaluator {

    public enum Status { ELIGIBLE, INELIGIBLE, UNKNOWN }

    /** 硬条件事实（来自数据库行等结构化来源，不接受模型口头描述） */
    public record HardFact(String city, List<String> tags, BigDecimal price, Boolean accessible) {
        public HardFact {
            tags = tags == null ? List.of() : tags;
        }

        public static HardFact of(String city, List<String> tags, BigDecimal price, Boolean accessible) {
            return new HardFact(city, tags, price, accessible);
        }
    }

    /** 硬条件策略（由已确认需求约束构建，可空字段表示不启用该条件） */
    public record HardPolicy(String city, Set<String> forbidTags, BigDecimal maxPrice,
                             boolean requireAccessible) {
        public HardPolicy {
            forbidTags = forbidTags == null ? Set.of() : forbidTags;
        }

        public static HardPolicy of(String city, Set<String> forbidTags, BigDecimal maxPrice,
                                    boolean requireAccessible) {
            return new HardPolicy(city, forbidTags, maxPrice, requireAccessible);
        }
    }

    public record Verdict(Status status, String reasonCode) {
        static Verdict eligible() {
            return new Verdict(Status.ELIGIBLE, "OK");
        }

        static Verdict ineligible(String reason) {
            return new Verdict(Status.INELIGIBLE, reason);
        }

        static Verdict unknown(String reason) {
            return new Verdict(Status.UNKNOWN, reason);
        }
    }

    public static final String OK = "OK";
    public static final String CITY_MISMATCH = "CITY_MISMATCH";
    public static final String FORBIDDEN_TAG = "FORBIDDEN_TAG";
    public static final String OVER_BUDGET = "OVER_BUDGET";
    public static final String NOT_ACCESSIBLE = "NOT_ACCESSIBLE";
    public static final String CITY_UNKNOWN = "CITY_UNKNOWN";
    public static final String PRICE_UNKNOWN = "PRICE_UNKNOWN";
    public static final String ACCESSIBILITY_UNKNOWN = "ACCESSIBILITY_UNKNOWN";

    private HardConstraintEvaluator() {
    }

    /** 单一出口：所有候选入口共用。条件缺事实 → UNKNOWN，不算通过。 */
    public static Verdict evaluate(HardFact fact, HardPolicy policy) {
        if (fact == null || policy == null) {
            return Verdict.unknown("MISSING_INPUT");
        }
        if (policy.city() != null && !policy.city().isBlank()) {
            if (fact.city() == null || fact.city().isBlank()) {
                return Verdict.unknown(CITY_UNKNOWN);
            }
            if (!policy.city().equals(fact.city())) {
                return Verdict.ineligible(CITY_MISMATCH);
            }
        }
        for (String tag : fact.tags()) {
            if (policy.forbidTags().contains(tag)) {
                return Verdict.ineligible(FORBIDDEN_TAG);
            }
        }
        if (policy.maxPrice() != null) {
            if (fact.price() == null) {
                return Verdict.unknown(PRICE_UNKNOWN);
            }
            if (fact.price().compareTo(policy.maxPrice()) > 0) {
                return Verdict.ineligible(OVER_BUDGET);
            }
        }
        if (policy.requireAccessible()) {
            if (fact.accessible() == null) {
                return Verdict.unknown(ACCESSIBILITY_UNKNOWN);
            }
            if (!fact.accessible()) {
                return Verdict.ineligible(NOT_ACCESSIBLE);
            }
        }
        return Verdict.eligible();
    }
}
