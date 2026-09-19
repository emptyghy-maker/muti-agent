package com.ghy.mutiagent.service.eval;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.rule.PlaceKeyResolver;
import com.ghy.mutiagent.service.validation.ItineraryValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性评判（手册 §3.3）：完整天数、时间可行性、预算、锁定覆盖和非目标节点差异
 * 用真实 Java 验证器（ItineraryValidator）检查，不使用模型评审。
 * 期望中的 forbiddenPlaceKeys 按地点键逐一核对；违规列表即硬约束结果，
 * 硬约束无法判定（needsConfirmation）单独上报，不并入通过。
 */
public final class EvalJudge {

    private EvalJudge() {
    }

    /** 评判输入：待评计划 + 固定事实（费用/开放时间/景点属性/期望天数/禁选地点） */
    public record JudgeInput(ItineraryPlan plan, BigDecimal budget, BigDecimal knownSubtotal,
                             Map<PlaceKey, String> openTimes, Map<Long, Attraction> attById,
                             int expectedDays, Set<PlaceKey> forbiddenKeys) {
    }

    /** 评判结果：硬违规码列表 + 待确认 + 警告 */
    public record JudgeOutcome(List<String> violations, boolean needsConfirmation,
                               List<String> warnings) {
    }

    /** 用真实发布检查器评判：确定性规则优先，模型输出不能自报「全部满足」 */
    public static JudgeOutcome judge(JudgeInput in) {
        TravelPreference preference = new TravelPreference();
        preference.setDays(in.expectedDays());
        // 期望约束里没有 RequirementSnapshot 式硬限制；约束以 forbiddenKeys 等确定性判据承载
        RequirementSnapshot snapshot = new RequirementSnapshot();
        ItineraryValidator.ValidationResult result = ItineraryValidator.validate(
                in.plan(), preference, snapshot, in.budget(), in.knownSubtotal(),
                in.openTimes(), in.attById(), false);

        List<String> violations = new ArrayList<>(result.violations());
        if (containsForbidden(in.plan(), in.forbiddenKeys())
                && !violations.contains(ItineraryValidator.HARD_CONSTRAINT_VIOLATED)) {
            violations.add(ItineraryValidator.HARD_CONSTRAINT_VIOLATED);
        }
        return new JudgeOutcome(violations, result.needsConfirmation(), result.warnings());
    }

    /** 计划里是否出现任一禁选地点 */
    private static boolean containsForbidden(ItineraryPlan plan, Set<PlaceKey> forbiddenKeys) {
        if (forbiddenKeys == null || forbiddenKeys.isEmpty() || plan == null || plan.getDays() == null) {
            return false;
        }
        for (DailyPlan d : plan.getDays()) {
            if (d == null || d.getNodes() == null) {
                continue;
            }
            for (PlanNode n : d.getNodes()) {
                Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
                if (key.isPresent() && forbiddenKeys.contains(key.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 计划已知费用小计：地点键 → 固定费用（酒店按夜计，由调用方给出固定事实价） */
    public static BigDecimal subtotal(ItineraryPlan plan, Map<PlaceKey, BigDecimal> costs) {
        if (plan == null || plan.getDays() == null || costs == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (DailyPlan d : plan.getDays()) {
            if (d == null || d.getNodes() == null) {
                continue;
            }
            for (PlanNode n : d.getNodes()) {
                Optional<PlaceKey> key = PlaceKeyResolver.fromNode(n);
                if (key.isEmpty()) {
                    continue;
                }
                BigDecimal cost = costs.get(key.get());
                if (cost != null) {
                    total = total.add(cost);
                }
            }
        }
        return total;
    }

    /** 数据集地点键字符串（ATTRACTION:1 / FOOD:2 / HOTEL:1）→ 复合地点键；未知类型返回 null */
    public static PlaceKey keyOf(String text) {
        if (text == null) {
            return null;
        }
        int i = text.indexOf(':');
        if (i <= 0 || i == text.length() - 1) {
            return null;
        }
        String type = text.substring(0, i).toUpperCase();
        long id;
        try {
            id = Long.parseLong(text.substring(i + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (type) {
            case "ATTRACTION" -> PlaceKey.of(PlaceType.ATTRACTION, id);
            case "FOOD", "RESTAURANT" -> PlaceKey.of(PlaceType.RESTAURANT, id);
            case "HOTEL" -> PlaceKey.of(PlaceType.HOTEL, id);
            default -> null;
        };
    }
}
