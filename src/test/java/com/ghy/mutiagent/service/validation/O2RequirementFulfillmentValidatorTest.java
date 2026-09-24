package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RequirementFulfillmentReport;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.requirement.FulfillmentStatus;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.rule.PlanningPolicyResolver;
import com.ghy.mutiagent.rule.RequirementMerger;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** O2-T07~T10：逐需求验收、证据与小吃隔离。 */
class O2RequirementFulfillmentValidatorTest {

    private final RulePreferenceParser parser = new RulePreferenceParser();

    @Test
    void O2_REPORT_全程两午一晚生成逐条满足证据() {
        TravelState state = policyState("全程2顿午餐和1顿晚餐", 3);
        ItineraryPlan plan = plan(
                day(1, meal(1L, "12:00", "午餐")),
                day(2, meal(2L, "12:00", "午餐")),
                day(3, meal(3L, "18:00", "晚餐")));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 7,
                Map.of(1L, restaurant(1L, "本地菜"), 2L, restaurant(2L, "火锅"),
                        3L, restaurant(3L, "杭帮菜")));

        assertThat(report.getPlanRevision()).isEqualTo(7);
        assertThat(report.getRequirementSnapshotRevision()).isEqualTo(state.getRequirementSnapshot().getRevision());
        assertThat(report.hardRequirementsSatisfied()).isTrue();
        assertThat(report.getResults()).hasSize(3)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(FulfillmentStatus.SATISFIED));
        assertThat(report.getResults().stream().flatMap(r -> r.getNodeIds().stream()).toList()).hasSize(3);
    }

    @Test
    void O2_T06_不要小吃时小吃不能充当午餐且给出失败原因() {
        TravelState state = policyState("全程1顿午餐，不要小吃", 2);
        ItineraryPlan plan = plan(day(1, meal(9L, "12:00", "午餐")), day(2));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 1,
                Map.of(9L, restaurant(9L, "特色小吃")));

        assertThat(report.hardRequirementsSatisfied()).isFalse();
        assertThat(report.getResults()).anySatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(FulfillmentStatus.VIOLATED);
            assertThat(r.getReasonCode()).isIn("MEAL_COUNT_MISMATCH", "SNACK_NOT_ALLOWED");
        });
    }

    @Test
    void O2_T08_餐厅事实缺失时不可伪装为满足() {
        TravelState state = policyState("全程1顿午餐", 1);
        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(
                plan(day(1, meal(99L, "12:00", "午餐"))), PlanningPolicyResolver.resolve(state),
                state.getRequirementSnapshot(), 1, Map.of());

        assertThat(report.getResults()).anySatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(FulfillmentStatus.UNVERIFIABLE);
            assertThat(r.getReasonCode()).isEqualTo("RESTAURANT_FACT_MISSING");
        });
    }

    @Test
    void O2_T02_每天口径逐日验收而不是只看全程总数() {
        TravelState state = policyState("每天1顿午餐", 2);
        ItineraryPlan plan = plan(day(1, meal(1L, "12:00", "午餐"), meal(2L, "12:30", "午餐")), day(2));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 2,
                Map.of(1L, restaurant(1L, "本地菜"), 2L, restaurant(2L, "火锅")));

        assertThat(report.hardRequirementsSatisfied()).isFalse();
        assertThat(report.getResults()).anySatisfy(r -> {
            assertThat(r.getActual()).containsEntry("day-1", 2).containsEntry("day-2", 0);
            assertThat(r.getStatus()).isEqualTo(FulfillmentStatus.VIOLATED);
        });
    }

    @Test
    void O2_T05_允许小吃时仍必须另有正餐满足午餐() {
        TravelState state = policyState("全程1顿午餐，要小吃", 1);
        ItineraryPlan plan = plan(day(1, meal(1L, "12:00", "午餐"),
                meal(2L, "18:00", "晚餐"), meal(9L, "15:00", "小吃")));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 1,
                Map.of(1L, restaurant(1L, "本地菜"), 2L, restaurant(2L, "火锅"),
                        9L, restaurant(9L, "特色小吃")));

        assertThat(report.hardRequirementsSatisfied()).isTrue();
        assertThat(report.getResults()).filteredOn(r -> r.getSubject()
                        == com.ghy.mutiagent.model.requirement.RequirementSubject.LUNCH)
                .singleElement().satisfies(r -> assertThat(r.getActual()).containsEntry("trip", 1));
    }

    @Test
    void 明确一顿小吃一顿正餐时小吃可以占午餐时间窗并分别验收() {
        TravelState state = policyState("饭店要一顿小吃一顿正餐", 1);
        ItineraryPlan plan = plan(day(1,
                meal(9L, "12:00", "午餐"),
                meal(2L, "18:00", "晚餐")));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 1,
                Map.of(9L, restaurant(9L, "特色小吃"), 2L, restaurant(2L, "火锅")));

        assertThat(report.hardRequirementsSatisfied()).isTrue();
        assertThat(report.getResults())
                .filteredOn(r -> r.getSubject() == com.ghy.mutiagent.model.requirement.RequirementSubject.SNACK)
                .singleElement().satisfies(r -> assertThat(r.getActual()).containsEntry("trip", 1));
        assertThat(report.getResults())
                .filteredOn(r -> r.getSubject() == com.ghy.mutiagent.model.requirement.RequirementSubject.MAIN_MEAL)
                .singleElement().satisfies(r -> assertThat(r.getActual()).containsEntry("trip", 1));
    }

    @Test
    void 餐食组成不满足时分别指出小吃和正餐数量问题() {
        TravelState state = policyState("饭店要一顿小吃一顿正餐", 1);
        ItineraryPlan plan = plan(day(1,
                meal(1L, "12:00", "午餐"),
                meal(2L, "18:00", "晚餐")));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 1,
                Map.of(1L, restaurant(1L, "本地菜"), 2L, restaurant(2L, "火锅")));

        assertThat(report.hardRequirementsSatisfied()).isFalse();
        assertThat(report.getResults()).anySatisfy(r -> {
            assertThat(r.getSubject()).isEqualTo(
                    com.ghy.mutiagent.model.requirement.RequirementSubject.SNACK);
            assertThat(r.getReasonCode()).isEqualTo("SNACK_COUNT_MISMATCH");
        }).anySatisfy(r -> {
            assertThat(r.getSubject()).isEqualTo(
                    com.ghy.mutiagent.model.requirement.RequirementSubject.MAIN_MEAL);
            assertThat(r.getReasonCode()).isEqualTo("MAIN_MEAL_COUNT_MISMATCH");
        });
    }

    @Test
    void O2_T09_餐饮自理不会被默认餐次补回() {
        TravelState state = new TravelState();
        state.setSessionId("o2-no-food");
        state.getPreference().setDays(2);
        state.setNoFoodNeeded(true);
        state.setNoHotelNeeded(true);
        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(
                plan(day(1), day(2)), policy, state.getRequirementSnapshot(), 1, Map.of());

        assertThat(policy.getMeal().isNoFood()).isTrue();
        assertThat(policy.getMeal().getLunch()).isNull();
        assertThat(policy.getMeal().getDinner()).isNull();
        assertThat(report.hardRequirementsSatisfied()).isTrue();
    }

    @Test
    void O2_T11_局部调整破坏餐次后全局发布校验拒绝() {
        TravelState state = policyState("全程1顿午餐", 1);
        PlanNode transport = new PlanNode();
        transport.setType("transport");
        transport.setTime("09:00");
        transport.setDurationMinutes(0);
        ItineraryPlan patched = plan(day(1, transport));

        ItineraryValidator.ValidationResult result = ItineraryValidator.validate(patched,
                state.getPreference(), state.getRequirementSnapshot(), new BigDecimal("1000"),
                BigDecimal.ZERO, Map.of(), Map.of(), false);

        assertThat(result.publishable()).isFalse();
        assertThat(result.violations()).contains(ItineraryValidator.MEAL_WINDOW_UNSATISFIABLE);
    }

    @Test
    void O2_BREAKFAST_不需要早餐作为硬需求也有逐条报告() {
        TravelState state = policyState("不需要早餐", 1);
        ItineraryPlan plan = plan(day(1, meal(1L, "12:00", "午餐"), meal(2L, "18:00", "晚餐")));

        RequirementFulfillmentReport report = RequirementFulfillmentValidator.validate(plan,
                PlanningPolicyResolver.resolve(state), state.getRequirementSnapshot(), 1,
                Map.of(1L, restaurant(1L, "本地菜"), 2L, restaurant(2L, "火锅")));

        assertThat(report.getResults()).filteredOn(r -> r.getSubject()
                        == com.ghy.mutiagent.model.requirement.RequirementSubject.BREAKFAST)
                .singleElement().satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(FulfillmentStatus.SATISFIED);
                    assertThat(r.getActual()).containsEntry("trip", 0);
                });
    }

    private TravelState policyState(String request, int days) {
        TravelState state = new TravelState();
        state.setSessionId("o2-fulfillment");
        state.getPreference().setDays(days);
        RequirementMerger.mergeInto(state, parser.parseResult(request, null, state.getPreference()));
        return state;
    }

    private static Restaurant restaurant(long id, String cuisine) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setCuisine(cuisine);
        return restaurant;
    }

    private static PlanNode meal(long id, String time, String note) {
        PlanNode node = new PlanNode();
        node.setType("restaurant");
        node.setPlaceId(id);
        node.setTime(time);
        node.setNote(note);
        node.setDurationMinutes(60);
        return node;
    }

    private static DailyPlan day(int index, PlanNode... nodes) {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(index);
        day.setNodes(new ArrayList<>(List.of(nodes)));
        return day;
    }

    private static ItineraryPlan plan(DailyPlan... days) {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(days)));
        return plan;
    }
}
