package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.FoodCandidate;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.rule.RequirementMerger;
import com.ghy.mutiagent.rule.RulePreferenceParser;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C3 契约测试：美食候选的餐次配额 / 小吃排除 / mealType 透传与 AI 修正。
 */
class CandidateServiceTest {

    private RestaurantMapper restaurantMapper;
    private FoodAgent foodAgent;
    private TraceService traceService;
    private CandidateService svc;

    @BeforeEach
    void setUp() {
        restaurantMapper = mock(RestaurantMapper.class);
        foodAgent = mock(FoodAgent.class);
        traceService = mock(TraceService.class);
        TraceContext ctx = mock(TraceContext.class);
        when(traceService.newTrace(anyString(), anyString())).thenReturn(ctx);
        svc = new CandidateService(
                mock(AttractionMapper.class), restaurantMapper, mock(HotelMapper.class),
                mock(AttractionAgent.class), foodAgent, mock(HotelAgent.class),
                traceService, mock(UsageService.class),
                new ObjectMapper(), new CandidateFoodConfig());
    }

    private Restaurant r(long id, String cuisine, double rating) {
        Restaurant x = new Restaurant();
        x.setId(id);
        x.setDestinationId(1L);
        x.setName(cuisine + id);
        x.setCuisine(cuisine);
        x.setSignatureDish("招牌菜");
        x.setAvgPrice(new BigDecimal("80"));
        x.setRating(rating);
        x.setBusinessHours("10:00-22:00");
        x.setStatus(1);
        return x;
    }

    private TravelState stateWithMealPlan() {
        TravelState st = new TravelState();
        st.setSessionId("m-food");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.FOODS);
        TravelPreference p = new TravelPreference();
        p.setDays(2);
        p.setSpecialRequests("每天2顿午餐和1顿晚餐，不需要早餐");
        TravelPreference.MealPlan mp = new TravelPreference.MealPlan();
        mp.setLunchPerDay(2);
        mp.setDinnerPerDay(1);
        mp.setBreakfastPerDay(0);
        p.setMealPlan(mp);
        st.setPreference(p);
        return st;
    }

    private Map<Long, String> mealTypesOf(TravelState st) {
        Map<Long, String> types = new LinkedHashMap<>();
        for (FoodCandidate g : st.getFoodPool()) {
            for (FoodCandidate.FoodItem it : g.getRestaurants()) {
                types.put(it.getRestaurantId(), it.getMealType());
            }
        }
        return types;
    }

    @Test
    void 餐次需求排除小吃并按顿数配额标注() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(2, "本地菜", 4.6), r(3, "本地菜", 4.5),
                r(4, "火锅", 4.7), r(5, "火锅", 4.4), r(6, "火锅", 4.3),
                r(7, "素斋", 4.2), r(8, "素斋", 4.0),
                r(9, "小吃", 4.9), r(10, "小吃", 4.8)));
        TravelState st = stateWithMealPlan();
        svc.generateFoods(st);

        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine)).doesNotContain("小吃");
        Map<Long, String> types = mealTypesOf(st);
        assertThat(types).hasSize(8);
        // 午餐配额 2×2 天、晚餐 1×2 天：按池序先午 4 后晚 2，超出归午餐展示
        assertThat(types).containsEntry(1L, "午餐").containsEntry(2L, "午餐")
                .containsEntry(3L, "午餐").containsEntry(4L, "午餐")
                .containsEntry(5L, "晚餐").containsEntry(6L, "晚餐")
                .containsEntry(7L, "午餐").containsEntry(8L, "午餐");
        // 批次按餐次分组展示
        assertThat(st.getFoodCandidates().stream().map(FoodCandidate::getCuisine))
                .contains("午餐", "晚餐")
                .doesNotContain("小吃", "本地菜", "火锅", "素斋");
        assertThat(st.getFoodCandidates().stream()
                .flatMap(g -> g.getRestaurants().stream())).hasSize(8);
    }

    @Test
    void 显式要小吃时保留并归为小吃餐次() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(2, "本地菜", 4.6),
                r(9, "小吃", 4.9), r(10, "小吃", 4.8),
                r(4, "火锅", 4.7), r(5, "火锅", 4.4),
                r(6, "火锅", 4.3), r(7, "火锅", 4.0)));
        TravelState st = stateWithMealPlan();
        st.getPreference().getMealPlan().setSnacksAllowed(true);
        svc.generateFoods(st);

        Map<Long, String> types = mealTypesOf(st);
        assertThat(types).containsEntry(9L, "小吃").containsEntry(10L, "小吃");
        assertThat(types).containsEntry(6L, "晚餐").containsEntry(7L, "晚餐");
        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine)).contains("小吃");
    }

    @Test
    void 无餐次需求时保持原有行为() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(9, "小吃", 4.9), r(4, "火锅", 4.7)));
        TravelState st = new TravelState();
        st.setSessionId("m-food2");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.FOODS);
        TravelPreference p = new TravelPreference();
        p.setDays(2);
        st.setPreference(p);
        svc.generateFoods(st);

        assertThat(mealTypesOf(st)).containsOnlyKeys(1L, 9L, 4L);
        assertThat(mealTypesOf(st).values()).allMatch(java.util.Objects::isNull);
        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine))
                .contains("小吃");
        assertThat(st.getFoodCandidates().stream().map(FoodCandidate::getCuisine))
                .contains("本地菜", "小吃", "火锅");
    }

    @Test
    void AI重筛不足时按餐次配额补足且可修正餐次() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(2, "本地菜", 4.6), r(3, "本地菜", 4.5),
                r(4, "火锅", 4.7), r(5, "火锅", 4.4), r(6, "火锅", 4.3),
                r(7, "素斋", 4.2), r(8, "素斋", 4.0),
                r(9, "小吃", 4.9), r(10, "小吃", 4.8)));
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder().content(
                        "{\"items\":[{\"restaurantId\":1,\"mealType\":\"早餐\"},"
                                + "{\"restaurantId\":4,\"mealType\":\"午饭\"}]}").build());
        TravelState st = stateWithMealPlan();
        st.setExtraRequest("想吃清淡点");
        svc.generateFoods(st);

        // 目标 = (2+1)×2 天 = 6：AI 2 家 + 规则池补足 4 家，不含小吃
        int total = st.getFoodPool().stream().mapToInt(g -> g.getRestaurants().size()).sum();
        assertThat(total).isEqualTo(6);
        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine)).doesNotContain("小吃");
        Map<Long, String> types = mealTypesOf(st);
        // AI 修正生效且归一（早饭→早餐、午饭→午餐）；补足项沿用规则配额
        assertThat(types).containsEntry(1L, "早餐").containsEntry(4L, "午餐");
        assertThat(types).containsValue("晚餐");
    }

    @Test
    void O2_CANDIDATE_全程两午一晚的候选目标是三而不是按天放大() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(2, "本地菜", 4.6), r(3, "火锅", 4.5),
                r(4, "火锅", 4.4), r(5, "素斋", 4.3), r(9, "小吃", 4.9)));
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder().content(
                        "{\"items\":[{\"restaurantId\":1},{\"restaurantId\":2},{\"restaurantId\":3}]}")
                        .build());
        TravelState st = new TravelState();
        st.setSessionId("o2-trip-meals");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.FOODS);
        st.getPreference().setDays(4);
        RequirementMerger.mergeInto(st, new RulePreferenceParser().parseResult(
                "全程2顿午餐和1顿晚餐", null, st.getPreference()));
        st.setExtraRequest("严格按餐次需求筛选");

        svc.generateFoods(st);

        int total = st.getFoodPool().stream().mapToInt(g -> g.getRestaurants().size()).sum();
        assertThat(total).isEqualTo(3);
        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine)).doesNotContain("小吃");
        assertThat(mealTypesOf(st).values()).containsExactlyInAnyOrder("午餐", "午餐", "晚餐");
    }

    @Test
    void O2_T06_单独说不要小吃也会在候选层排除() {
        when(restaurantMapper.selectList(any())).thenReturn(List.of(
                r(1, "本地菜", 4.8), r(2, "火锅", 4.6), r(9, "小吃", 4.9)));
        TravelState st = new TravelState();
        st.setSessionId("o2-no-snack");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("杭州");
        st.setStage(TravelStage.FOODS);
        st.getPreference().setDays(1);
        RequirementMerger.mergeInto(st, new RulePreferenceParser().parseResult(
                "不要小吃", null, st.getPreference()));

        svc.generateFoods(st);

        assertThat(st.getFoodPool().stream().map(FoodCandidate::getCuisine)).doesNotContain("小吃");
    }
}
