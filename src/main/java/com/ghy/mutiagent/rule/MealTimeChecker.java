package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.PlanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 饭点约束检查：午餐 11:30–13:30、晚餐 17:30–19:30 窗口内必须有 restaurant 节点；
 * 车程/赶路中不安排用餐（就餐点必须是实体节点）。
 * 返回缺失的窗口列表，由编排层在对应时间点插入最近顺路的已选饭店。
 */
public final class MealTimeChecker {

    public static final String LUNCH_FROM = "11:30";
    public static final String LUNCH_TO = "13:30";
    public static final String DINNER_FROM = "17:30";
    public static final String DINNER_TO = "19:30";

    private MealTimeChecker() {
    }

    /** 返回当天缺失的用餐窗口：返回 "午餐"/"晚餐" 列表；空表示约束满足 */
    public static List<String> missingWindows(List<PlanNode> nodes) {
        List<String> missing = new ArrayList<>();
        if (!hasRestaurantInWindow(nodes, LUNCH_FROM, LUNCH_TO)) {
            missing.add("午餐");
        }
        if (!hasRestaurantInWindow(nodes, DINNER_FROM, DINNER_TO)) {
            missing.add("晚餐");
        }
        return missing;
    }

    public static boolean hasRestaurantInWindow(List<PlanNode> nodes, String from, String to) {
        return nodes.stream().anyMatch(n ->
                "restaurant".equals(n.getType())
                        && n.getTime() != null
                        && n.getTime().compareTo(from) >= 0
                        && n.getTime().compareTo(to) <= 0);
    }

    /** 窗口对应的默认就餐时间点 */
    public static String defaultMealTime(String window) {
        return "晚餐".equals(window) ? "18:00" : "12:00";
    }
}
