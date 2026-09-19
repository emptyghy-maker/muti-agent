package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 行程文本渲染（纯函数）：由结构化 plan 生成「第一天：…→ 预计消费 xxx 元」的人类可读文本，
 * 保证文本与结构化数据永远一致（文本不作为独立产物，避免两者不一致）。
 */
public final class ItineraryTextRenderer {

    private static final String[] CN_NUM = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    private ItineraryTextRenderer() {
    }

    public static String render(ItineraryPlan plan) {
        if (plan == null || plan.getDays() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DailyPlan d : plan.getDays()) {
            String dayCn = d.getDayIndex() >= 1 && d.getDayIndex() <= CN_NUM.length
                    ? CN_NUM[d.getDayIndex() - 1] : String.valueOf(d.getDayIndex());
            sb.append("第").append(dayCn).append("天（")
                    .append(d.getTheme() == null ? "游玩" : d.getTheme()).append("）：");
            List<String> parts = new ArrayList<>();
            for (PlanNode n : d.getNodes()) {
                String time = n.getTime() == null ? "" : n.getTime() + " ";
                parts.add(time + n.getName() + (n.getNote() == null || n.getNote().isBlank() ? "" : "（" + n.getNote() + "）"));
            }
            sb.append(String.join(" → ", parts));
            sb.append("   预计消费 ").append(d.getEstimatedCost()).append(" 元\n");
        }
        sb.append("\n全程预计消费 ").append(totalCost(plan)).append(" 元");
        if (plan.getBudgetBreakdown() != null
                && TripBilling.COVERAGE_UNKNOWN.equals(plan.getBudgetBreakdown().getBudgetCoverage())) {
            sb.append("（含估算与未核实费用，预算未完全核实）");
        }
        return sb.toString();
    }

    public static BigDecimal totalCost(ItineraryPlan plan) {
        if (plan == null || plan.getDays() == null) {
            return BigDecimal.ZERO;
        }
        // S05：全程账单是唯一入口，总额优先取账单合计；旧行程无账单时回退按天小计
        if (plan.getBudgetBreakdown() != null && plan.getBudgetBreakdown().getTotalAmount() != null) {
            return plan.getBudgetBreakdown().getTotalAmount();
        }
        return plan.getDays().stream()
                .map(DailyPlan::getEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
