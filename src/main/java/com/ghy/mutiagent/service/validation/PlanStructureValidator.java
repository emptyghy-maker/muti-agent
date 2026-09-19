package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 行程结构验证器（S03）：纯函数，不依赖 Spring。
 * 提供方调用成功后，结构验证是发布前的唯一关口：违规一律拒绝发布，不静默替换为规则方案。
 * 违规项：行程为空、缺少天数结构、null 天、天数少于需求、单天节点数超上限。
 */
public final class PlanStructureValidator {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final int MAX_NODES_PER_DAY = 30;

    private PlanStructureValidator() {
    }

    public static List<String> validate(ItineraryPlan plan, int expectedDays) {
        List<String> v = new ArrayList<>();
        if (plan == null) {
            v.add("行程为空");
            return v;
        }
        List<DailyPlan> days = plan.getDays();
        if (days == null || days.isEmpty()) {
            v.add("行程缺少天数结构");
            return v;
        }
        for (int i = 0; i < days.size(); i++) {
            DailyPlan d = days.get(i);
            if (d == null) {
                v.add("第" + (i + 1) + "天为 null");
                continue;
            }
            if (d.getDayIndex() <= 0) {
                v.add("第" + (i + 1) + "天 dayIndex 非法：" + d.getDayIndex());
            }
            List<PlanNode> nodes = d.getNodes();
            if (nodes == null) {
                v.add("第" + (i + 1) + "天节点列表为 null");
            } else if (nodes.size() > MAX_NODES_PER_DAY) {
                v.add("第" + (i + 1) + "天节点数 " + nodes.size() + " 超过上限 " + MAX_NODES_PER_DAY);
            }
        }
        if (days.size() < expectedDays) {
            v.add("行程天数 " + days.size() + " 少于需求 " + expectedDays + " 天");
        }
        return v;
    }

    public static String status(List<String> violations) {
        return violations.isEmpty() ? OK : FAILED;
    }
}
