package com.ghy.mutiagent.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.RequirementFulfillmentReport;
import com.ghy.mutiagent.model.RequirementFulfillmentResult;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.FulfillmentStatus;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O2-T01~T06：解析、澄清、版本与统一执行策略。 */
class O2RequirementUnderstandingTest {

    private final RulePreferenceParser parser = new RulePreferenceParser();

    private TravelState state(int days) {
        TravelState state = new TravelState();
        state.setSessionId("o2-requirement");
        state.getPreference().setDays(days);
        return state;
    }

    @Test
    void O2_T01_全程餐次不会乘以天数() {
        TravelState state = state(4);
        RequirementMerger.mergeInto(state,
                parser.parseResult("全程需要2顿午餐和1顿晚餐", null, state.getPreference()));

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);
        assertThat(policy.executable()).isTrue();
        assertThat(policy.getMeal().getLunch().getScope()).isEqualTo(RequirementScope.TRIP);
        assertThat(policy.getMeal().getLunch().getCount()).isEqualTo(2);
        assertThat(policy.getMeal().getDinner().getCount()).isEqualTo(1);
        assertThat(state.getPreference().getMealPlan()).as("TRIP 需求不得写成每日报表字段").isNull();
    }

    @Test
    void O2_T02_每天口径按适用日执行且保留兼容投影() {
        TravelState state = state(3);
        RuleParseResult parsed = parser.parseResult("每天1顿午餐和1顿晚餐", null, state.getPreference());
        RequirementMerger.mergeInto(state, parsed);
        PreferenceUpdater.apply(state, parsed.getUpdates());

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);
        assertThat(policy.getMeal().getLunch().getScope()).isEqualTo(RequirementScope.PER_DAY);
        assertThat(state.getPreference().getMealPlan().getLunchPerDay()).isEqualTo(1);
    }

    @Test
    void O2_T04_无范围必须先澄清再执行() {
        TravelState state = state(3);
        RequirementMerger.mergeInto(state,
                parser.parseResult("需要2顿午餐和1顿晚餐", null, state.getPreference()));

        ResolvedPlanningPolicy before = PlanningPolicyResolver.resolve(state);
        assertThat(before.executable()).isFalse();
        assertThat(before.getBlockingRequirementIds()).hasSize(2);
        assertThat(RequirementApplier.clarificationRequired(state)).isTrue();

        ConstraintEntry resolved = RequirementMerger.resolvePendingMealScope(state, "全程");
        assertThat(resolved).isNotNull();
        assertThat(PlanningPolicyResolver.resolve(state).executable()).isTrue();
        assertThat(state.getRequirementSnapshot().getConstraints())
                .filteredOn(c -> RequirementMerger.ACTIVE.equals(c.getStatus()))
                .allSatisfy(c -> assertThat(c.getInterpretationStatus())
                        .isNotEqualTo(InterpretationStatus.NEEDS_CLARIFICATION));
    }

    @Test
    void O2_T07_更正生成新版本并保留替代链() {
        TravelState state = state(3);
        RequirementMerger.mergeInto(state,
                parser.parseResult("全程2顿午餐", null, state.getPreference()));
        RequirementMerger.mergeInto(state,
                parser.parseResult("全程1顿午餐", null, state.getPreference()));

        assertThat(state.getRequirementSnapshot().getConstraints())
                .filteredOn(c -> c.getSubject() == RequirementSubject.LUNCH)
                .hasSize(2)
                .anySatisfy(c -> assertThat(c.getStatus()).isEqualTo(RequirementMerger.SUPERSEDED))
                .anySatisfy(c -> {
                    assertThat(c.getStatus()).isEqualTo(RequirementMerger.ACTIVE);
                    assertThat(c.getRevision()).isEqualTo(2);
                    assertThat(c.getSupersedesId()).startsWith("REQ-");
                });
    }

    @Test
    void O2_SCHEMA_候选家数菜品数与餐次数不会混用() {
        RuleParseResult parsed = parser.parseResult(
                "全程2顿午餐，推荐至少6家午餐店，每家至少3道菜", null, null);

        assertThat(parsed.getConstraints()).anySatisfy(c -> {
            assertThat(c.getSubject()).isEqualTo(RequirementSubject.LUNCH);
            assertThat(c.getUnit()).isEqualTo(RequirementUnit.MEAL_OCCASION);
            assertThat(c.getCount()).isEqualTo(2);
        }).anySatisfy(c -> {
            assertThat(c.getSubject()).isEqualTo(RequirementSubject.LUNCH_RESTAURANT);
            assertThat(c.getUnit()).isEqualTo(RequirementUnit.CANDIDATE_COUNT);
            assertThat(c.getCount()).isEqualTo(6);
        }).anySatisfy(c -> {
            assertThat(c.getSubject()).isEqualTo(RequirementSubject.DISH);
            assertThat(c.getUnit()).isEqualTo(RequirementUnit.DISH_COUNT);
            assertThat(c.getInterpretationStatus()).isEqualTo(InterpretationStatus.UNSUPPORTED);
        });
    }

    @Test
    void O2_T10_当前节点模型无法执行每天两顿午餐时明确报冲突() {
        TravelState state = state(3);
        RequirementMerger.mergeInto(state,
                parser.parseResult("每天2顿午餐", null, state.getPreference()));

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);
        assertThat(policy.executable()).isFalse();
        assertThat(policy.getConflictCodes()).contains("LUNCH_PER_DAY_LIMIT_EXCEEDED");
    }

    @Test
    void O2_T03_两家午餐店是候选数量而不是两顿午餐() {
        TravelState state = state(2);
        RequirementMerger.mergeInto(state,
                parser.parseResult("推荐2家午餐店供选择", null, state.getPreference()));

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);
        assertThat(policy.getMeal().getLunchCandidateCount()).isEqualTo(2);
        assertThat(policy.getMeal().getLunch().getCount()).isEqualTo(1);
        assertThat(policy.getMeal().getLunch().isExplicit()).isFalse();
    }

    @Test
    void 单日一顿小吃一顿正餐解析为餐食组成而不是午晚餐类型() {
        TravelState state = state(1);
        RequirementMerger.mergeInto(state,
                parser.parseResult("饭店要一顿小吃一顿正餐", null, state.getPreference()));

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);

        assertThat(policy.executable()).isTrue();
        assertThat(policy.getMeal().isExplicitMealComposition()).isTrue();
        assertThat(policy.getMeal().getSnack().getCount()).isEqualTo(1);
        assertThat(policy.getMeal().getSnack().getScope()).isEqualTo(RequirementScope.TRIP);
        assertThat(policy.getMeal().getMainMeal().getCount()).isEqualTo(1);
        assertThat(state.getRequirementSnapshot().getUnparsedTexts()).isEmpty();
    }

    @Test
    void 多日餐食组成未说明范围时仍要求澄清() {
        TravelState state = state(3);
        RequirementMerger.mergeInto(state,
                parser.parseResult("一顿小吃一顿正餐", null, state.getPreference()));

        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);

        assertThat(policy.executable()).isFalse();
        assertThat(policy.getBlockingRequirementIds()).hasSize(2);
    }

    @Test
    void O2_T12_需求版本变化时旧验收报告保留但标记为过期() {
        TravelState state = state(2);
        RequirementMerger.mergeInto(state,
                parser.parseResult("全程1顿午餐", null, state.getPreference()));
        RequirementFulfillmentReport report = new RequirementFulfillmentReport();
        report.setRequirementSnapshotRevision(state.getRequirementSnapshot().getRevision());
        report.setPlanRevision(3);
        RequirementFulfillmentResult result = new RequirementFulfillmentResult();
        result.setRequirementId("REQ-0001");
        result.setHardness("HARD");
        result.setStatus(FulfillmentStatus.SATISFIED);
        report.getResults().add(result);
        state.setPlanRevision(3);
        state.setRequirementFulfillmentReport(report);

        RequirementMerger.mergeInto(state,
                parser.parseResult("全程2顿午餐", null, state.getPreference()));

        assertThat(report.getResults()).allSatisfy(r -> assertThat(r.getStatus())
                .isEqualTo(FulfillmentStatus.STALE));
        assertThat(report.matches(state.getRequirementSnapshot().getRevision(), state.getPlanRevision())).isFalse();
    }

    @Test
    void O2_LEGACY_只有旧每日字段没有原文证据时必须重新确认范围() {
        TravelState state = state(3);
        TravelPreference.MealPlan legacy = new TravelPreference.MealPlan();
        legacy.setLunchPerDay(2);
        legacy.setDinnerPerDay(1);
        state.getPreference().setMealPlan(legacy);

        ResolvedPlanningPolicy before = PlanningPolicyResolver.resolve(state);
        assertThat(before.executable()).isFalse();
        assertThat(state.getRequirementSnapshot().getConstraints())
                .filteredOn(c -> RequirementMerger.ACTIVE.equals(c.getStatus()))
                .allSatisfy(c -> {
                    assertThat(c.getInterpretationStatus()).isEqualTo(InterpretationStatus.LEGACY_UNRESOLVED);
                    assertThat(c.getScope()).isEqualTo(RequirementScope.UNRESOLVED);
                });

        RequirementMerger.resolvePendingMealScope(state, "全程");
        ResolvedPlanningPolicy after = PlanningPolicyResolver.resolve(state);
        assertThat(after.executable()).isTrue();
        assertThat(after.getMeal().getLunch().getScope()).isEqualTo(RequirementScope.TRIP);
        assertThat(after.getMeal().getLunch().getCount()).isEqualTo(2);
    }

    @Test
    void O2_LEGACY_schemaVersion2快照仍可反序列化且新字段保持未知() throws Exception {
        String oldJson = "{\"schemaVersion\":2,\"revision\":4,\"constraints\":["
                + "{\"id\":\"c-001\",\"key\":\"meal.LUNCH\",\"value\":\"2\","
                + "\"hardness\":\"HARD\",\"status\":\"ACTIVE\"}]}";

        com.ghy.mutiagent.model.RequirementSnapshot snapshot =
                new ObjectMapper().readValue(oldJson, com.ghy.mutiagent.model.RequirementSnapshot.class);

        assertThat(snapshot.getSchemaVersion()).isEqualTo(2);
        assertThat(snapshot.getRevision()).isEqualTo(4);
        assertThat(snapshot.getConstraints()).singleElement().satisfies(c -> {
            assertThat(c.getSubject()).isNull();
            assertThat(c.getScope()).isNull();
            assertThat(c.getInterpretationStatus()).isNull();
        });
    }
}
