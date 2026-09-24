package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RequirementFulfillmentReport;
import com.ghy.mutiagent.model.RequirementFulfillmentResult;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.requirement.FulfillmentStatus;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.rule.MealPolicySupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** O2：把“用户说过”与最终计划中的证据逐条关联，并给出确定性验收状态。 */
public final class RequirementFulfillmentValidator {

    private RequirementFulfillmentValidator() {
    }

    public static RequirementFulfillmentReport validate(ItineraryPlan plan,
                                                         ResolvedPlanningPolicy policy,
                                                         RequirementSnapshot snapshot,
                                                         int planRevision,
                                                         Map<Long, Restaurant> restaurants) {
        RequirementFulfillmentReport report = new RequirementFulfillmentReport();
        report.setReportId(UUID.randomUUID().toString());
        report.setRequirementSnapshotRevision(snapshot == null ? 0 : snapshot.getRevision());
        report.setPlanRevision(planRevision);
        if (plan == null || plan.getDays() == null || policy == null) {
            return report;
        }
        if (!policy.getMeal().isNoFood()) {
            boolean snackCanFillWindow = policy.getMeal().isExplicitMealComposition();
            addMealResult(report, plan, policy.getMeal().getLunch(), restaurants, snackCanFillWindow);
            addMealResult(report, plan, policy.getMeal().getDinner(), restaurants, snackCanFillWindow);
            addMealKindResult(report, plan, policy.getMeal().getSnack(), restaurants, true);
            addMealKindResult(report, plan, policy.getMeal().getMainMeal(), restaurants, false);
        } else {
            RequirementFulfillmentResult noFood = base("POLICY-NO-FOOD", 1, "HARD",
                    RequirementSubject.NO_FOOD, RequirementOperator.FORBID, 0,
                    RequirementUnit.MEAL_OCCASION, RequirementScope.TRIP);
            int actual = restaurantNodes(plan).size();
            noFood.getActual().put("trip", actual);
            noFood.setStatus(actual == 0 ? FulfillmentStatus.SATISFIED : FulfillmentStatus.VIOLATED);
            noFood.setReasonCode(actual == 0 ? null : "NO_FOOD_HAS_RESTAURANT_NODE");
            addEvidence(noFood, plan, n -> "restaurant".equals(n.getType()));
            report.getResults().add(noFood);
        }
        addBreakfastResults(report, plan, snapshot);
        addSnackResult(report, plan, policy, restaurants);
        return report;
    }

    private static void addBreakfastResults(RequirementFulfillmentReport report, ItineraryPlan plan,
                                            RequirementSnapshot snapshot) {
        if (snapshot == null || snapshot.getConstraints() == null) {
            return;
        }
        for (ConstraintEntry entry : snapshot.getConstraints()) {
            if (!"ACTIVE".equals(entry.getStatus()) || entry.getSubject() != RequirementSubject.BREAKFAST
                    || entry.getInterpretationStatus() != InterpretationStatus.CONFIRMED
                    || entry.getCount() == null) {
                continue;
            }
            RequirementFulfillmentResult result = base(entry.getId(), entry.getRevision(), entry.getHardness(),
                    RequirementSubject.BREAKFAST, entry.getOperator(), entry.getCount(),
                    RequirementUnit.MEAL_OCCASION, entry.getScope());
            List<Integer> perDay = new ArrayList<>();
            for (DailyPlan day : plan.getDays()) {
                int count = 0;
                if (day.getNodes() != null) {
                    for (int i = 0; i < day.getNodes().size(); i++) {
                        PlanNode node = day.getNodes().get(i);
                        String note = node.getNote() == null ? "" : node.getNote();
                        boolean breakfast = "restaurant".equals(node.getType())
                                && (note.contains("早餐") || note.contains("早饭")
                                || node.getTime() != null && node.getTime().compareTo("06:00") >= 0
                                && node.getTime().compareTo("10:30") <= 0);
                        if (breakfast) {
                            count++;
                            result.getNodeIds().add(MealPolicySupport.evidenceNodeId(day, node, i));
                        }
                    }
                }
                perDay.add(count);
                result.getActual().put("day-" + day.getDayIndex(), count);
            }
            int total = perDay.stream().mapToInt(Integer::intValue).sum();
            result.getActual().put("trip", total);
            boolean satisfied = entry.getScope() == RequirementScope.PER_DAY
                    ? perDay.stream().allMatch(v -> compare(v, entry.getCount(), entry.getOperator()))
                    : compare(total, entry.getCount(), entry.getOperator());
            result.setStatus(satisfied ? FulfillmentStatus.SATISFIED : FulfillmentStatus.VIOLATED);
            result.setReasonCode(satisfied ? null : "BREAKFAST_COUNT_MISMATCH");
            report.getResults().add(result);
        }
    }

    private static void addMealResult(RequirementFulfillmentReport report, ItineraryPlan plan,
                                      ResolvedPlanningPolicy.MealRule rule,
                                      Map<Long, Restaurant> restaurants,
                                      boolean snackCanFillWindow) {
        if (rule == null) {
            return;
        }
        RequirementFulfillmentResult result = base(rule.getRequirementId(), rule.getRequirementRevision(),
                rule.getHardness(), rule.getSubject(), rule.getOperator(), rule.getCount(),
                RequirementUnit.MEAL_OCCASION, rule.getScope());
        List<Integer> counts = new ArrayList<>();
        boolean unknownFact = false;
        for (DailyPlan day : plan.getDays()) {
            int count = 0;
            if (day.getNodes() != null) {
                for (int i = 0; i < day.getNodes().size(); i++) {
                    PlanNode node = day.getNodes().get(i);
                    if (!MealPolicySupport.isMealNode(node, rule.getSubject())) {
                        continue;
                    }
                    Restaurant restaurant = node.getPlaceId() == null || restaurants == null
                            ? null : restaurants.get(node.getPlaceId());
                    if (restaurant == null) {
                        unknownFact = true;
                        continue;
                    }
                    if (!snackCanFillWindow && MealPolicySupport.isSnack(restaurant)) {
                        continue;
                    }
                    count++;
                    result.getNodeIds().add(MealPolicySupport.evidenceNodeId(day, node, i));
                    result.getFactIds().add("restaurant:" + restaurant.getId());
                }
            }
            result.getActual().put("day-" + day.getDayIndex(), count);
            if (MealPolicySupport.eligible(day, rule.getSubject())) {
                counts.add(count);
            }
        }
        int total = counts.stream().mapToInt(Integer::intValue).sum();
        result.getActual().put("trip", total);
        boolean satisfied = rule.getScope() == RequirementScope.PER_DAY
                ? counts.stream().allMatch(v -> compare(v, rule.getCount(), rule.getOperator()))
                : compare(total, rule.getCount(), rule.getOperator());
        if (satisfied) {
            result.setStatus(FulfillmentStatus.SATISFIED);
        } else if (unknownFact) {
            result.setStatus(FulfillmentStatus.UNVERIFIABLE);
            result.setReasonCode("RESTAURANT_FACT_MISSING");
        } else {
            result.setStatus(FulfillmentStatus.VIOLATED);
            result.setReasonCode("MEAL_COUNT_MISMATCH");
        }
        report.getResults().add(result);
    }

    /** 验收餐食组成；它与午餐/晚餐时间窗分开计数。 */
    private static void addMealKindResult(RequirementFulfillmentReport report, ItineraryPlan plan,
                                          ResolvedPlanningPolicy.MealRule rule,
                                          Map<Long, Restaurant> restaurants,
                                          boolean expectSnack) {
        if (rule == null) {
            return;
        }
        RequirementFulfillmentResult result = base(rule.getRequirementId(), rule.getRequirementRevision(),
                rule.getHardness(), rule.getSubject(), rule.getOperator(), rule.getCount(),
                RequirementUnit.MEAL_OCCASION, rule.getScope());
        List<Integer> counts = new ArrayList<>();
        boolean unknownFact = false;
        for (DailyPlan day : plan.getDays()) {
            int count = 0;
            if (day.getNodes() != null) {
                for (int i = 0; i < day.getNodes().size(); i++) {
                    PlanNode node = day.getNodes().get(i);
                    if (!"restaurant".equals(node.getType())) {
                        continue;
                    }
                    Restaurant restaurant = node.getPlaceId() == null || restaurants == null
                            ? null : restaurants.get(node.getPlaceId());
                    if (restaurant == null) {
                        unknownFact = true;
                        continue;
                    }
                    if (MealPolicySupport.isSnack(restaurant) != expectSnack) {
                        continue;
                    }
                    count++;
                    result.getNodeIds().add(MealPolicySupport.evidenceNodeId(day, node, i));
                    result.getFactIds().add("restaurant:" + restaurant.getId());
                }
            }
            counts.add(count);
            result.getActual().put("day-" + day.getDayIndex(), count);
        }
        int total = counts.stream().mapToInt(Integer::intValue).sum();
        result.getActual().put("trip", total);
        boolean satisfied = rule.getScope() == RequirementScope.PER_DAY
                ? counts.stream().allMatch(v -> compare(v, rule.getCount(), rule.getOperator()))
                : compare(total, rule.getCount(), rule.getOperator());
        if (satisfied) {
            result.setStatus(FulfillmentStatus.SATISFIED);
        } else if (unknownFact) {
            result.setStatus(FulfillmentStatus.UNVERIFIABLE);
            result.setReasonCode("RESTAURANT_FACT_MISSING");
        } else {
            result.setStatus(FulfillmentStatus.VIOLATED);
            result.setReasonCode(expectSnack ? "SNACK_COUNT_MISMATCH" : "MAIN_MEAL_COUNT_MISMATCH");
        }
        report.getResults().add(result);
    }

    private static void addSnackResult(RequirementFulfillmentReport report, ItineraryPlan plan,
                                       ResolvedPlanningPolicy policy, Map<Long, Restaurant> restaurants) {
        if (policy.getMeal().isSnacksAllowed()) {
            return;
        }
        String requirementId = policy.getMeal().getSnackRequirementId() == null
                ? "POLICY-SNACK-FORBID" : policy.getMeal().getSnackRequirementId();
        int revision = policy.getMeal().getSnackRequirementId() == null
                ? 1 : policy.getMeal().getSnackRequirementRevision();
        String hardness = policy.getMeal().getSnackHardness() == null
                ? "HARD" : policy.getMeal().getSnackHardness();
        RequirementFulfillmentResult result = base(requirementId, revision, hardness,
                RequirementSubject.SNACK_ALLOWED, RequirementOperator.FORBID, 0,
                RequirementUnit.BOOLEAN, RequirementScope.TRIP);
        int snacks = 0;
        boolean unknown = false;
        for (DailyPlan day : plan.getDays()) {
            if (day.getNodes() == null) {
                continue;
            }
            for (int i = 0; i < day.getNodes().size(); i++) {
                PlanNode node = day.getNodes().get(i);
                if (!"restaurant".equals(node.getType())) {
                    continue;
                }
                Restaurant fact = node.getPlaceId() == null || restaurants == null
                        ? null : restaurants.get(node.getPlaceId());
                if (fact == null) {
                    unknown = true;
                } else if (MealPolicySupport.isSnack(fact)) {
                    snacks++;
                    result.getNodeIds().add(MealPolicySupport.evidenceNodeId(day, node, i));
                    result.getFactIds().add("restaurant:" + fact.getId());
                }
            }
        }
        result.getActual().put("snackNodes", snacks);
        if (snacks > 0) {
            result.setStatus(FulfillmentStatus.VIOLATED);
            result.setReasonCode("SNACK_NOT_ALLOWED");
        } else if (unknown) {
            result.setStatus(FulfillmentStatus.UNVERIFIABLE);
            result.setReasonCode("RESTAURANT_FACT_MISSING");
        } else {
            result.setStatus(FulfillmentStatus.SATISFIED);
        }
        report.getResults().add(result);
    }

    private static RequirementFulfillmentResult base(String id, int revision, String hardness,
                                                       RequirementSubject subject, RequirementOperator operator,
                                                       int count, RequirementUnit unit, RequirementScope scope) {
        RequirementFulfillmentResult result = new RequirementFulfillmentResult();
        result.setRequirementId(id);
        result.setRequirementRevision(revision);
        result.setHardness(hardness);
        result.setSubject(subject);
        result.setOperator(operator == null ? RequirementOperator.EQ : operator);
        result.setExpectedCount(count);
        result.setUnit(unit);
        result.setScope(scope);
        return result;
    }

    private static boolean compare(int actual, int expected, RequirementOperator operator) {
        RequirementOperator op = operator == null ? RequirementOperator.EQ : operator;
        return switch (op) {
            case EQ -> actual == expected;
            case AT_LEAST -> actual >= expected;
            case AT_MOST -> actual <= expected;
            case ALLOW -> true;
            case FORBID -> actual == 0;
        };
    }

    private static List<PlanNode> restaurantNodes(ItineraryPlan plan) {
        return plan.getDays().stream().flatMap(d -> d.getNodes() == null
                ? java.util.stream.Stream.empty() : d.getNodes().stream())
                .filter(n -> "restaurant".equals(n.getType())).toList();
    }

    private static void addEvidence(RequirementFulfillmentResult result, ItineraryPlan plan,
                                    java.util.function.Predicate<PlanNode> predicate) {
        for (DailyPlan day : plan.getDays()) {
            if (day.getNodes() == null) {
                continue;
            }
            for (int i = 0; i < day.getNodes().size(); i++) {
                if (predicate.test(day.getNodes().get(i))) {
                    result.getNodeIds().add(MealPolicySupport.evidenceNodeId(day, day.getNodes().get(i), i));
                }
            }
        }
    }
}
