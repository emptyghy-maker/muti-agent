package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.enums.TravelStage;

import java.util.Set;

/**
 * 需求应用策略（S02）：上游条件变化识别与 DONE 结果失效。
 * 天数影响住宿、餐数与行程，预算影响各类候选与账单，人数影响计价——
 * 这些上游字段变化后，已完成行程必须回到待重新确认状态，不能悄悄沿用旧完成计划。
 */
public final class RequirementApplier {

    public static final Set<String> UPSTREAM_FIELDS = Set.of("days", "totalBudget", "peopleCount");

    private RequirementApplier() {
    }

    /** 本轮解析是否触及上游条件字段 */
    public static boolean isUpstreamChange(RuleParseResult parsed) {
        if (parsed == null || parsed.getUpdates() == null) {
            return false;
        }
        return parsed.getUpdates().keySet().stream().anyMatch(UPSTREAM_FIELDS::contains);
    }

    /**
     * 上游条件变化：作废会话中的 DONE 结果，回到需重新确认状态。
     * 已落库的旧行程保持为历史版本（数据库行不动），会话不再把它当作新方案展示。
     */
    public static void invalidateDone(TravelState state) {
        state.setStage(TravelStage.ITINERARY);
        state.setPlan(null);
        state.setItineraryText(null);
        state.setItineraryId(null);
        state.setResolvedPlanningPolicy(null);
        if (state.getRequirementFulfillmentReport() != null) {
            state.getRequirementFulfillmentReport().markStale();
        }
    }

    /** 是否存在结构化待澄清需求或未解析原文 */
    public static boolean clarificationRequired(TravelState state) {
        RequirementSnapshot snap = state.getRequirementSnapshot();
        if (snap == null) {
            return false;
        }
        if (snap.getConstraints() != null) {
            for (ConstraintEntry entry : snap.getConstraints()) {
                if (RequirementMerger.ACTIVE.equals(entry.getStatus())
                        && (entry.getInterpretationStatus() == InterpretationStatus.NEEDS_CLARIFICATION
                        || entry.getInterpretationStatus() == InterpretationStatus.LEGACY_UNRESOLVED)) {
                    return true;
                }
            }
        }
        return snap.getUnparsedTexts() != null && !snap.getUnparsedTexts().isEmpty();
    }
}
