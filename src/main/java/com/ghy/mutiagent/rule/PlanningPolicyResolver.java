package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
import com.ghy.mutiagent.model.requirement.RequirementOperator;

import java.util.List;

/** O2：把一个需求快照确定性解析成所有下游共用的规划策略。 */
public final class PlanningPolicyResolver {

    private PlanningPolicyResolver() {
    }

    public static ResolvedPlanningPolicy resolve(TravelState state) {
        migrateLegacyMealPlan(state);
        TravelPreference preference = state == null ? null : state.getPreference();
        RequirementSnapshot snapshot = state == null ? null : state.getRequirementSnapshot();
        ResolvedPlanningPolicy out = resolve(snapshot, preference,
                state != null && Boolean.TRUE.equals(state.getNoFoodNeeded()));
        if (state != null) {
            state.setResolvedPlanningPolicy(out);
        }
        return out;
    }

    public static ResolvedPlanningPolicy resolve(RequirementSnapshot snapshot, TravelPreference preference,
                                                  boolean noFoodNeeded) {
        ResolvedPlanningPolicy out = new ResolvedPlanningPolicy();
        out.setRequirementSnapshotRevision(snapshot == null ? 0 : snapshot.getRevision());
        ResolvedPlanningPolicy.MealPolicy meal = out.getMeal();
        meal.setNoFood(noFoodNeeded);
        meal.setRestaurantReuseAllowed(false);

        boolean structuredLunch = false;
        boolean structuredDinner = false;
        boolean structuredSnacks = false;
        boolean explicitMealStructure = false;
        List<ConstraintEntry> entries = snapshot == null || snapshot.getConstraints() == null
                ? List.of() : snapshot.getConstraints();
        for (ConstraintEntry e : entries) {
            if (e == null || !RequirementMerger.ACTIVE.equals(e.getStatus())) {
                continue;
            }
            if (e.getInterpretationStatus() == InterpretationStatus.NEEDS_CLARIFICATION
                    || e.getInterpretationStatus() == InterpretationStatus.LEGACY_UNRESOLVED
                    || (e.getInterpretationStatus() == InterpretationStatus.UNSUPPORTED
                    && "HARD".equals(e.getHardness()))) {
                out.getBlockingRequirementIds().add(idOf(e));
                continue;
            }
            if (e.getInterpretationStatus() != null
                    && e.getInterpretationStatus() != InterpretationStatus.CONFIRMED) {
                continue;
            }
            if (e.getSubject() == RequirementSubject.NO_FOOD) {
                meal.setNoFood(booleanValue(e, true));
                continue;
            }
            if (e.getSubject() == RequirementSubject.SNACK_ALLOWED) {
                meal.setSnacksAllowed(booleanValue(e, true));
                meal.setSnackRequirementId(idOf(e));
                meal.setSnackRequirementRevision(e.getRevision());
                meal.setSnackHardness(e.getHardness());
                structuredSnacks = true;
                continue;
            }
            if (e.getUnit() == RequirementUnit.CANDIDATE_COUNT) {
                if (e.getSubject() == RequirementSubject.LUNCH_RESTAURANT) {
                    meal.setLunchCandidateCount(e.getCount());
                } else if (e.getSubject() == RequirementSubject.DINNER_RESTAURANT) {
                    meal.setDinnerCandidateCount(e.getCount());
                }
                continue;
            }
            if (e.getUnit() != RequirementUnit.MEAL_OCCASION || e.getCount() == null) {
                continue;
            }
            if (e.getSubject() == RequirementSubject.LUNCH) {
                meal.setLunch(ruleOf(e));
                structuredLunch = true;
                explicitMealStructure = true;
            } else if (e.getSubject() == RequirementSubject.DINNER) {
                meal.setDinner(ruleOf(e));
                structuredDinner = true;
                explicitMealStructure = true;
            } else if (e.getSubject() == RequirementSubject.BREAKFAST && e.getCount() > 0) {
                out.getBlockingRequirementIds().add(idOf(e));
            }
        }

        // 旧会话兼容：只有原文能证明范围时才投影；无证据时必须阻断，不把字段名当作语义证据。
        TravelPreference.MealPlan legacy = preference == null ? null : preference.getMealPlan();
        if (!structuredLunch && legacy != null && legacy.getLunchPerDay() != null) {
            RequirementScope scope = legacyScopeEvidence(snapshot, preference);
            if (scope == null) {
                out.getBlockingRequirementIds().add("LEGACY-LUNCH");
            } else {
                meal.setLunch(legacyRule(RequirementSubject.LUNCH, legacy.getLunchPerDay(), scope));
                structuredLunch = true;
                explicitMealStructure = true;
            }
        }
        if (!structuredDinner && legacy != null && legacy.getDinnerPerDay() != null) {
            RequirementScope scope = legacyScopeEvidence(snapshot, preference);
            if (scope == null) {
                out.getBlockingRequirementIds().add("LEGACY-DINNER");
            } else {
                meal.setDinner(legacyRule(RequirementSubject.DINNER, legacy.getDinnerPerDay(), scope));
                structuredDinner = true;
                explicitMealStructure = true;
            }
        }
        if (!structuredSnacks && legacy != null && legacy.getSnacksAllowed() != null) {
            meal.setSnacksAllowed(legacy.getSnacksAllowed());
            structuredSnacks = true;
        }
        if (explicitMealStructure && !structuredSnacks) {
            // 用户明确给出正餐数量时，未提小吃不允许用小吃凑正餐数量。
            meal.setSnacksAllowed(false);
        }

        if (!structuredLunch) {
            meal.setLunch(ResolvedPlanningPolicy.MealRule.defaultPerDay(RequirementSubject.LUNCH));
        }
        if (!structuredDinner) {
            meal.setDinner(ResolvedPlanningPolicy.MealRule.defaultPerDay(RequirementSubject.DINNER));
        }

        validate(meal, preference, out);
        return out;
    }

    private static void validate(ResolvedPlanningPolicy.MealPolicy meal, TravelPreference preference,
                                 ResolvedPlanningPolicy out) {
        if (meal.isNoFood() && (positive(meal.getLunch()) || positive(meal.getDinner()))) {
            boolean explicit = meal.getLunch() != null && meal.getLunch().isExplicit()
                    || meal.getDinner() != null && meal.getDinner().isExplicit();
            if (explicit) {
                out.getConflictCodes().add("NO_FOOD_WITH_REQUIRED_MEALS");
            } else {
                meal.setLunch(null);
                meal.setDinner(null);
            }
        }
        int days = preference == null || preference.getDays() == null ? 0 : preference.getDays();
        validateRule(meal.getLunch(), days, out);
        validateRule(meal.getDinner(), days, out);
    }

    private static void validateRule(ResolvedPlanningPolicy.MealRule rule, int days,
                                     ResolvedPlanningPolicy out) {
        if (rule == null) {
            return;
        }
        if (rule.getCount() < 0) {
            out.getConflictCodes().add(rule.getSubject() + "_NEGATIVE_COUNT");
        }
        // 当前节点模型一天只有一个午餐窗和一个晚餐窗；大于 1 必须让用户改成全程口径或放宽。
        if (rule.getScope() == RequirementScope.PER_DAY && rule.getCount() > 1) {
            out.getConflictCodes().add(rule.getSubject() + "_PER_DAY_LIMIT_EXCEEDED");
        }
        if (days > 0 && rule.getScope() == RequirementScope.TRIP && rule.getCount() > days) {
            out.getConflictCodes().add(rule.getSubject() + "_TRIP_EXCEEDS_AVAILABLE_DAYS");
        }
    }

    private static boolean positive(ResolvedPlanningPolicy.MealRule rule) {
        return rule != null && rule.getCount() > 0;
    }

    private static ResolvedPlanningPolicy.MealRule ruleOf(ConstraintEntry e) {
        ResolvedPlanningPolicy.MealRule r = new ResolvedPlanningPolicy.MealRule();
        r.setSubject(e.getSubject());
        r.setOperator(e.getOperator());
        r.setCount(e.getCount());
        r.setScope(e.getScope());
        r.setExplicit(true);
        r.setRequirementId(idOf(e));
        r.setRequirementRevision(e.getRevision());
        r.setHardness(e.getHardness() == null ? "HARD" : e.getHardness());
        return r;
    }

    private static ResolvedPlanningPolicy.MealRule legacyRule(RequirementSubject subject, int count,
                                                               RequirementScope scope) {
        ResolvedPlanningPolicy.MealRule r = new ResolvedPlanningPolicy.MealRule();
        r.setSubject(subject);
        r.setCount(count);
        r.setScope(scope);
        r.setExplicit(true);
        r.setRequirementId("LEGACY-" + subject.name());
        r.setRequirementRevision(1);
        r.setHardness("HARD");
        return r;
    }

    /** 把旧 MealPlan 显式迁入 RequirementSnapshot；无范围证据时标记 LEGACY_UNRESOLVED。 */
    private static void migrateLegacyMealPlan(TravelState state) {
        if (state == null || state.getPreference() == null || state.getPreference().getMealPlan() == null) {
            return;
        }
        RequirementSnapshot snapshot = state.getRequirementSnapshot();
        TravelPreference.MealPlan legacy = state.getPreference().getMealPlan();
        RuleParseResult parsed = new RuleParseResult();
        RequirementScope scope = legacyScopeEvidence(snapshot, state.getPreference());
        InterpretationStatus status = scope == null
                ? InterpretationStatus.LEGACY_UNRESOLVED : InterpretationStatus.CONFIRMED;
        RequirementScope storedScope = scope == null ? RequirementScope.UNRESOLVED : scope;
        if (!hasSubject(snapshot, RequirementSubject.LUNCH) && legacy.getLunchPerDay() != null) {
            parsed.getConstraints().add(legacyEntry(RequirementSubject.LUNCH, legacy.getLunchPerDay(),
                    storedScope, status));
        }
        if (!hasSubject(snapshot, RequirementSubject.DINNER) && legacy.getDinnerPerDay() != null) {
            parsed.getConstraints().add(legacyEntry(RequirementSubject.DINNER, legacy.getDinnerPerDay(),
                    storedScope, status));
        }
        if (!hasSubject(snapshot, RequirementSubject.BREAKFAST) && legacy.getBreakfastPerDay() != null) {
            ConstraintEntry breakfast = legacyEntry(RequirementSubject.BREAKFAST, legacy.getBreakfastPerDay(),
                    storedScope, legacy.getBreakfastPerDay() == 0 ? status : InterpretationStatus.UNSUPPORTED);
            breakfast.setReasonCode(legacy.getBreakfastPerDay() == 0 && status == InterpretationStatus.CONFIRMED
                    ? null : legacy.getBreakfastPerDay() == 0 ? "LEGACY_SCOPE_REQUIRED"
                    : "BREAKFAST_PLANNING_NOT_SUPPORTED");
            parsed.getConstraints().add(breakfast);
        }
        if (!hasSubject(snapshot, RequirementSubject.SNACK_ALLOWED) && legacy.getSnacksAllowed() != null) {
            ConstraintEntry snack = legacyEntry(RequirementSubject.SNACK_ALLOWED, null,
                    RequirementScope.TRIP, InterpretationStatus.CONFIRMED);
            snack.setUnit(RequirementUnit.BOOLEAN);
            snack.setOperator(Boolean.TRUE.equals(legacy.getSnacksAllowed())
                    ? RequirementOperator.ALLOW : RequirementOperator.FORBID);
            snack.setValue(String.valueOf(legacy.getSnacksAllowed()).toUpperCase());
            snack.setHardness(Boolean.TRUE.equals(legacy.getSnacksAllowed()) ? "SOFT" : "HARD");
            parsed.getConstraints().add(snack);
        }
        if (!parsed.getConstraints().isEmpty()) {
            RequirementMerger.mergeInto(state, parsed);
        }
    }

    private static ConstraintEntry legacyEntry(RequirementSubject subject, Integer count,
                                               RequirementScope scope, InterpretationStatus status) {
        ConstraintEntry entry = new ConstraintEntry();
        entry.setKey("meal." + subject.name());
        entry.setValue(count == null ? null : String.valueOf(count));
        entry.setSubject(subject);
        entry.setCount(count);
        entry.setOperator(RequirementOperator.EQ);
        entry.setUnit(RequirementUnit.MEAL_OCCASION);
        entry.setScope(scope);
        entry.setInterpretationStatus(status);
        entry.setHardness("HARD");
        entry.setStatus(RequirementMerger.ACTIVE);
        entry.setSource("LEGACY_MIGRATION");
        entry.setOriginalText("旧会话餐次字段：" + subject + "=" + count);
        entry.setReasonCode(status == InterpretationStatus.LEGACY_UNRESOLVED
                ? "LEGACY_SCOPE_REQUIRED" : null);
        return entry;
    }

    private static boolean hasSubject(RequirementSnapshot snapshot, RequirementSubject subject) {
        return snapshot != null && snapshot.getConstraints() != null
                && snapshot.getConstraints().stream().anyMatch(e -> RequirementMerger.ACTIVE.equals(e.getStatus())
                && e.getSubject() == subject);
    }

    private static RequirementScope legacyScopeEvidence(RequirementSnapshot snapshot, TravelPreference preference) {
        StringBuilder evidence = new StringBuilder();
        if (preference != null && preference.getSpecialRequests() != null) {
            evidence.append(preference.getSpecialRequests()).append(' ');
        }
        if (snapshot != null && snapshot.getUnparsedTexts() != null) {
            snapshot.getUnparsedTexts().forEach(t -> evidence.append(t).append(' '));
        }
        if (snapshot != null && snapshot.getConstraints() != null) {
            snapshot.getConstraints().stream().map(ConstraintEntry::getOriginalText)
                    .filter(java.util.Objects::nonNull).forEach(t -> evidence.append(t).append(' '));
        }
        String text = evidence.toString();
        boolean trip = text.matches(".*(全程|整个行程|整个旅程|一共|总共).*" );
        boolean perDay = text.matches(".*(每天|每日|每一天|天天).*" );
        if (trip == perDay) {
            return null;
        }
        return trip ? RequirementScope.TRIP : RequirementScope.PER_DAY;
    }

    private static boolean booleanValue(ConstraintEntry e, boolean fallback) {
        if (e.getValue() == null) {
            return fallback;
        }
        return "TRUE".equalsIgnoreCase(e.getValue()) || "ALLOW".equalsIgnoreCase(e.getValue());
    }

    private static String idOf(ConstraintEntry e) {
        return e.getId() == null ? "UNASSIGNED" : e.getId();
    }
}
