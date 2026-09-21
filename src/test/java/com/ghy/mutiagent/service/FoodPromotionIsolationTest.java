package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.AttractionAgent;
import com.ghy.mutiagent.agent.FoodAgent;
import com.ghy.mutiagent.agent.HotelAgent;
import com.ghy.mutiagent.config.CandidateFoodConfig;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.model.enums.TravelStage;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POI 晋升组件的功能隔离契约（CandidateService 侧）：
 * - 已装配：存在可复用历史网搜店 → 跳过联网检索、并入候选池、记录 RECOMMEND 事件；
 * - 未装配（promotion=null，手动装配进程/门禁）：保持既有行为——仍发起联网检索。
 */
@ExtendWith(MockitoExtension.class)
class FoodPromotionIsolationTest {

    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private FoodAgent foodAgent;
    @Mock
    private TraceService traceService;
    @Mock
    private FoodPromotionService promotion;
    @Mock
    private DashScopeSearchClient searchClient;

    private CandidateService svc;

    @BeforeEach
    void setUp() {
        when(traceService.newTrace(anyString(), anyString())).thenReturn(mock(TraceContext.class));
        svc = new CandidateService(mock(AttractionMapper.class), restaurantMapper, mock(HotelMapper.class),
                mock(AttractionAgent.class), foodAgent, mock(HotelAgent.class),
                traceService, mock(UsageService.class), new ObjectMapper(), new CandidateFoodConfig());
    }

    private Restaurant kb(long id, String cuisine) {
        Restaurant x = new Restaurant();
        x.setId(id);
        x.setDestinationId(1L);
        x.setName(cuisine + id);
        x.setCuisine(cuisine);
        x.setSignatureDish("招牌菜");
        x.setAvgPrice(BigDecimal.valueOf(80));
        x.setRating(4.5);
        x.setStatus(1);
        return x;
    }

    private Restaurant reusedWeb() {
        Restaurant w = new Restaurant();
        w.setId(99L);
        w.setDestinationId(1L);
        w.setName("梧桐树下的Bistro");
        w.setCuisine("西式简餐");
        w.setAvgPrice(BigDecimal.valueOf(150));
        w.setRating(4.5);
        w.setStatus(1);
        w.setSource("WEB_SEARCH");
        w.setAddress("颐和路某号");
        return w;
    }

    /** 1 天 2 午 1 晚 → 目标 6 家；AI 只挑出 2 家 → 覆盖不足触发补充 */
    private TravelState state() {
        TravelState st = new TravelState();
        st.setSessionId("m-promo");
        st.setUserId(1L);
        st.setDestinationId(1L);
        st.setDestinationName("苏州");
        st.setStage(TravelStage.FOODS);
        st.setExtraRequest("苏州工业园区附近，饭店要有氛围感");
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        TravelPreference.MealPlan mp = new TravelPreference.MealPlan();
        mp.setBreakfastPerDay(0);
        mp.setLunchPerDay(2);
        mp.setDinnerPerDay(1);
        p.setMealPlan(mp);
        st.setPreference(p);
        return st;
    }

    private void stubKbAndAi() {
        List<Restaurant> kbRows = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            kbRows.add(kb(i, "本地菜"));
        }
        when(restaurantMapper.selectList(any())).thenReturn(kbRows);
        when(foodAgent.select(anyString(), anyString(), anyInt())).thenReturn(
                Result.<String>builder().content(
                        "{\"items\":[{\"restaurantId\":1,\"mealType\":\"午餐\"},"
                                + "{\"restaurantId\":2,\"mealType\":\"晚餐\"}]}").build());
    }

    @Test
    void 装配后复用历史网搜店跳过联网检索() throws Exception {
        stubKbAndAi();
        ReflectionTestUtils.setField(svc, "promotion", promotion);
        ReflectionTestUtils.setField(svc, "dashScopeSearchClient", searchClient);
        when(promotion.reusableWebRestaurants(any(), any())).thenReturn(List.of(reusedWeb()));
        TravelState st = state();

        svc.generateFoods(st);

        // 同需求不再重复付费搜索：复用并入候选池并记录推荐事件
        verify(searchClient, never()).search(anyString(), anyString());
        List<Long> poolIds = st.getFoodPool().stream()
                .flatMap(c -> c.getRestaurants().stream())
                .map(g -> g.getRestaurantId()).toList();
        assertThat(poolIds).contains(99L);
        verify(promotion).recordRecommend("m-promo", 99L, 1L);
        assertThat(st.getCandidateAdvice()).contains("已复用历史网搜结果补充");
    }

    @Test
    void 未装配时保持既有行为仍发起联网检索() throws Exception {
        stubKbAndAi();
        // 隔离契约：promotion 未装配（门禁/手动装配进程同构）→ 检索照旧，行为完全不变
        ReflectionTestUtils.setField(svc, "dashScopeSearchClient", searchClient);
        when(searchClient.search(anyString(), anyString()))
                .thenReturn(new DashScopeSearchClient.SearchResult("{\"items\":[]}", 0, 0));
        TravelState st = state();

        svc.generateFoods(st);

        verify(searchClient).search(anyString(), anyString());
        // 未装配：检索照旧发起，失败提示沿用既有文案（行为完全不变）
        assertThat(st.getCandidateAdvice()).contains("联网检索暂不可用");
    }
}
