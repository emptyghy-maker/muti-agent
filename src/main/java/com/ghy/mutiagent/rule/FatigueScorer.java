package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.repository.entity.Attraction;

import java.util.List;

/**
 * 操劳程度评分（纯函数，参数为初始经验值，可后续按反馈调参）。
 *
 * 某天基础分 = Σ(景点强度1~5 × 建议时长) × 体力系数 + 交通时长 × 1.5
 * 综合分   = 当天基础分 + 0.5×前一天基础分 + 0.3×后一天基础分
 * 阈值    > 9 判定「较劳累」，需要插入休息点
 *
 * 注意：这是「预估」而非实测；用户真实体感通过评价反馈回流（节奏评分「太赶」→ 迭代调阈值）。
 */
public final class FatigueScorer {

    public static final double REST_THRESHOLD = 9.0;
    public static final double PREV_DAY_WEIGHT = 0.5;   // 睡眠恢复一半，但劳累会延续
    public static final double NEXT_DAY_WEIGHT = 0.3;   // 前瞻保留体力
    public static final double TRANSPORT_PER_HOUR = 1.5;

    private FatigueScorer() {
    }

    /** 体力自报 → 系数：好 0.8 / 一般 1.0 / 偏弱 1.3 */
    public static double energyFactor(String energyLevel) {
        return switch (energyLevel == null ? "一般" : energyLevel) {
            case "体力好" -> 0.8;
            case "偏弱" -> 1.3;
            default -> 1.0;
        };
    }

    /** 某天基础劳累分 */
    public static double baseScore(List<Attraction> attractions, double transportHours, String energyLevel) {
        double score = attractions.stream()
                .mapToDouble(a -> a.getIntensity() * a.getSuggestHours())
                .sum();
        return score * energyFactor(energyLevel) + transportHours * TRANSPORT_PER_HOUR;
    }

    /** 按行程节点计算当天基础劳累分（S04 发布检查同口径）：Σ(强度×时长)×体力系数 + 通勤时长×1.5 */
    public static double dayScore(com.ghy.mutiagent.model.DailyPlan d, java.util.Map<Long, Attraction> attById,
                                  String energyLevel) {
        double score = d.getNodes().stream()
                .filter(n -> "attraction".equals(n.getType()) && attById.containsKey(n.getPlaceId()))
                .mapToDouble(n -> attById.get(n.getPlaceId()).getIntensity() * attById.get(n.getPlaceId()).getSuggestHours())
                .sum();
        double commuteHours = d.getNodes().stream()
                .mapToDouble(n -> n.getTravelMinutes() == null ? 0 : n.getTravelMinutes()).sum() / 60.0;
        double stationHours = d.getNodes().stream().filter(n -> "transport".equals(n.getType())).count() * 0.5;
        return score * energyFactor(energyLevel) + (commuteHours + stationHours) * TRANSPORT_PER_HOUR;
    }

    /** 综合分：当天 + 0.5×前一天 + 0.3×后一天 */
    public static double combined(double prevDay, double today, double nextDay) {
        return today + PREV_DAY_WEIGHT * prevDay + NEXT_DAY_WEIGHT * nextDay;
    }

    public static boolean needsRest(double combinedScore) {
        return combinedScore > REST_THRESHOLD;
    }
}
