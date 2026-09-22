package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.repository.entity.Restaurant;

import java.util.List;

/** O2：规划、后处理与验收共享的餐次事实判定。 */
public final class MealPolicySupport {

    private MealPolicySupport() {
    }

    public static boolean isSnack(Restaurant r) {
        if (r == null || r.getCuisine() == null) {
            return false;
        }
        return r.getCuisine().contains("小吃") || r.getCuisine().contains("夜宵");
    }

    public static boolean isMealNode(PlanNode node, RequirementSubject subject) {
        if (node == null || !"restaurant".equals(node.getType())) {
            return false;
        }
        String note = node.getNote() == null ? "" : node.getNote();
        String time = node.getTime();
        // 明确餐次标签优先，避免“午餐”节点因时间偏晚又被同时计作晚餐。
        if (note.contains("午餐") || note.contains("午饭") || note.contains("中饭")) {
            return subject == RequirementSubject.LUNCH;
        }
        if (note.contains("晚餐") || note.contains("晚饭")) {
            return subject == RequirementSubject.DINNER;
        }
        if (subject == RequirementSubject.LUNCH) {
            return inWindow(time, MealTimeChecker.LUNCH_FROM, MealTimeChecker.LUNCH_TO);
        }
        if (subject == RequirementSubject.DINNER) {
            return inWindow(time, MealTimeChecker.DINNER_FROM, MealTimeChecker.DINNER_TO);
        }
        return false;
    }

    public static boolean eligible(DailyPlan day, RequirementSubject subject) {
        if (day == null || day.getNodes() == null || day.getNodes().isEmpty()) {
            return true;
        }
        List<PlanNode> nodes = day.getNodes();
        PlanNode first = nodes.get(0);
        PlanNode last = nodes.get(nodes.size() - 1);
        if (subject == RequirementSubject.LUNCH) {
            return !("transport".equals(first.getType()) && first.getTime() != null
                    && first.getTime().compareTo(MealTimeChecker.LUNCH_TO) >= 0);
        }
        if (subject == RequirementSubject.DINNER) {
            return !("transport".equals(last.getType()) && last.getTime() != null
                    && last.getTime().compareTo(MealTimeChecker.DINNER_TO) < 0);
        }
        return false;
    }

    public static String evidenceNodeId(DailyPlan day, PlanNode node, int index) {
        if (node.getNodeId() != null && !node.getNodeId().isBlank()) {
            return node.getNodeId();
        }
        return "d" + day.getDayIndex() + "-n" + (index + 1);
    }

    private static boolean inWindow(String time, String from, String to) {
        return time != null && time.compareTo(from) >= 0 && time.compareTo(to) <= 0;
    }
}
