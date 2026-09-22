package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.ResolvedPlanningPolicy;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.rule.PlanningPolicyResolver;
import com.ghy.mutiagent.rule.RequirementMerger;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** O2-T12：确定性后处理使用 TRIP 口径，并保持餐厅不重复。 */
class O2MealPolicyReconciliationTest {

    private ItineraryService service;

    @BeforeEach
    void setUp() {
        service = new ItineraryService(mock(AttractionMapper.class), mock(RestaurantMapper.class),
                mock(HotelMapper.class), mock(ItineraryMapper.class), mock(ItineraryFeedbackMapper.class),
                mock(DestinationMapper.class), mock(ItineraryAgent.class), mock(TraceService.class),
                mock(UsageService.class), new ObjectMapper(), mock(ItineraryCommitService.class));
    }

    @Test
    void O2_POSTPROCESS_四天行程全程只补两顿午餐一顿晚餐() {
        TravelState state = new TravelState();
        state.setSessionId("o2-reconcile");
        state.getPreference().setDays(4);
        RequirementMerger.mergeInto(state, new RulePreferenceParser().parseResult(
                "全程2顿午餐和1顿晚餐", null, state.getPreference()));
        ResolvedPlanningPolicy policy = PlanningPolicyResolver.resolve(state);
        List<Restaurant> foods = List.of(restaurant(1), restaurant(2), restaurant(3), restaurant(4));
        Map<Long, Restaurant> byId = new LinkedHashMap<>();
        foods.forEach(r -> byId.put(r.getId(), r));
        ItineraryPlan plan = plan(day(1), day(2), day(3), day(4));

        ReflectionTestUtils.invokeMethod(service, "reconcileMealPolicy", plan, policy, foods, byId,
                Map.<PlaceKey, double[]>of());

        List<PlanNode> meals = plan.getDays().stream().flatMap(d -> d.getNodes().stream())
                .filter(n -> "restaurant".equals(n.getType())).toList();
        assertThat(meals).hasSize(3);
        assertThat(meals).filteredOn(n -> n.getNote().contains("午餐")).hasSize(2);
        assertThat(meals).filteredOn(n -> n.getNote().contains("晚餐")).hasSize(1);
        assertThat(meals.stream().map(PlanNode::getPlaceId)).doesNotHaveDuplicates();
    }

    private static Restaurant restaurant(long id) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName("正餐店" + id);
        restaurant.setCuisine("本地菜");
        return restaurant;
    }

    private static DailyPlan day(int index) {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(index);
        day.setNodes(new ArrayList<>());
        return day;
    }

    private static ItineraryPlan plan(DailyPlan... days) {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(days)));
        return plan;
    }
}
