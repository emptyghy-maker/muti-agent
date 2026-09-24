package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.agent.ItineraryRepairAgent;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Hotel;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.trace.TraceContext;
import com.ghy.mutiagent.trace.TraceService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预算知情放行守卫回归（S08 修复循环内的预算分支）：
 * 1. 预算超支是当前唯一违规 → 立即进入「待确认」知情放行（不走模型修复耗到 422）；
 * 2. 预算超支与其他违规（如开放时间冲突）并存 → 立即进入「待确认」并返回具体阻断，
 *    不调用 RepairAgent；publishPending 复验仍必须拦截其余违规。
 * 回归背景：BUDGET_EXCEEDED 在统一层标记为 repairable=true，若用 repairableViolations.isEmpty()
 * 判断「唯一违规」会把知情放行彻底堵死（每次修复耗尽后 422）。
 */
@ExtendWith(MockitoExtension.class)
class ItineraryBudgetGuardTest {

    @Mock
    private AttractionMapper attractionMapper;
    @Mock
    private RestaurantMapper restaurantMapper;
    @Mock
    private HotelMapper hotelMapper;
    @Mock
    private ItineraryMapper itineraryMapper;
    @Mock
    private ItineraryFeedbackMapper itineraryFeedbackMapper;
    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private ItineraryAgent itineraryAgent;
    @Mock
    private TraceService traceService;
    @Mock
    private UsageService usageService;
    @Mock
    private ItineraryCommitService commitService;
    @Mock
    private ItineraryRepairAgent repairAgent;

    private ItineraryService svc;

    @BeforeEach
    void setUp() {
        svc = new ItineraryService(attractionMapper, restaurantMapper, hotelMapper, itineraryMapper,
                itineraryFeedbackMapper, destinationMapper, itineraryAgent, traceService,
                usageService, new ObjectMapper(), commitService);
        svc.setRepairEngine(new ItineraryRepairEngine(repairAgent, new ObjectMapper(), () -> 0L,
                OperationBudgetConfig.defaults()));
        when(traceService.newTrace(anyString(), anyString()))
                .thenAnswer(inv -> new TraceContext(inv.getArgument(0, String.class),
                        inv.getArgument(1, String.class)));
        // 休息点召回走 selectList：本测试无休息点
        when(attractionMapper.selectList(any())).thenReturn(List.of());
    }

    private static Attraction attraction(long id, String name, double hours) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName(name);
        a.setCategory("公园");
        a.setTags(""); // buildRules 里 NightScorer 直接切分 tags，必须非 null
        a.setIntensity(2); // 规划载荷 Map.of 与兜底排序都要求非 null
        a.setSuggestHours(hours);
        a.setTicketPrice(BigDecimal.ZERO);
        a.setLng(120.60 + id * 0.01);
        a.setLat(31.30);
        return a;
    }

    private static Restaurant restaurant(long id, int avgPrice) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName("高价餐厅");
        r.setCuisine("本地菜");
        r.setAvgPrice(BigDecimal.valueOf(avgPrice));
        r.setLng(120.605);
        r.setLat(31.301);
        return r;
    }

    private static Hotel hotel(long id) {
        Hotel h = new Hotel();
        h.setId(id);
        h.setName("酒店X");
        h.setPricePerNight(BigDecimal.valueOf(400));
        h.setLng(120.601);
        h.setLat(31.302);
        return h;
    }

    private static TravelState state(String sessionId, List<Long> attractionIds) {
        TravelState s = new TravelState();
        s.setSessionId(sessionId);
        s.setUserId(1L);
        s.setUsername("admin");
        s.setSelectedAttractionIds(attractionIds);
        s.setSelectedFoodIds(List.of(10L));
        s.setSelectedHotelIds(List.of(100L));
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setPeopleCount(2);
        // 午餐+晚餐两顿 × 人均 300 × 2 人 = 1200 元 > 预算 500：确定性超支
        p.setTotalBudget(BigDecimal.valueOf(500));
        p.setEnergyLevel("一般");
        s.setPreference(p);
        return s;
    }

    /** 2 个景点 + 午/晚餐 + 20:00 返程：时间线在 22:00 日终上限内，预算外无其他违规 */
    private static String planJson(List<Long> ids) {
        StringBuilder nodes = new StringBuilder();
        nodes.append("{\"type\":\"transport\",\"time\":\"09:00\",\"note\":\"抵达\"},");
        for (int i = 0; i < ids.size(); i++) {
            nodes.append("{\"type\":\"attraction\",\"placeId\":").append(ids.get(i))
                    .append(",\"time\":\"").append(String.format("%02d:00", 10 + i * 2))
                    .append("\",\"note\":\"游览\"},");
            if (i == 0) {
                nodes.append("{\"type\":\"restaurant\",\"placeId\":10,\"time\":\"12:00\",\"note\":\"午餐\"},");
            }
        }
        nodes.append("{\"type\":\"restaurant\",\"placeId\":10,\"time\":\"18:00\",\"note\":\"晚餐\"},");
        nodes.append("{\"type\":\"transport\",\"time\":\"20:00\",\"note\":\"返程\"}");
        return "{\"days\":[{\"dayIndex\":1,\"theme\":\"湖滨一日\",\"nodes\":[" + nodes + "]}]}";
    }

    private void stubPlanEntities(List<Attraction> attractions, String planJson) {
        when(attractionMapper.selectBatchIds(anyCollection())).thenReturn(attractions);
        when(restaurantMapper.selectBatchIds(anyCollection())).thenReturn(List.of(restaurant(10, 300)));
        when(hotelMapper.selectBatchIds(anyCollection())).thenReturn(List.of(hotel(100)));
        when(itineraryAgent.plan(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Result.<String>builder().content(planJson).build());
    }

    @Test
    void 仅预算超限时进入待确认知情放行() {
        List<Long> ids = List.of(1L, 2L);
        stubPlanEntities(List.of(attraction(1, "景点A", 2.0), attraction(2, "景点B", 2.0)), planJson(ids));
        TravelState st = state("s-budget-only", ids);

        svc.generate(st);

        assertThat(st.getPendingBudgetConfirm()).isTrue();
        assertThat(st.getPendingPlan()).isNotNull();
        assertThat(st.getPendingBudgetOver()).isPositive(); // 1200 - 500 = 700
        // 预算超支走确定性换店/知情放行，绝不进模型修复
        verifyNoInteractions(repairAgent);
    }

    @Test
    void 预算与开放时间冲突并存时知情放行待确认且发布前复验拦截() {
        List<Long> ids = List.of(1L, 2L);
        Attraction a1 = attraction(1, "景点A", 2.0);
        a1.setOpenTime("08:00-10:00"); // 09:30 到达后不足 2h，仍为 OPENING_HOURS_CONFLICT
        stubPlanEntities(List.of(a1, attraction(2, "景点B", 2.0)), planJson(ids));
        TravelState st = state("s-budget-mixed", ids);

        svc.generate(st);

        assertThat(st.getPendingBudgetConfirm()).isTrue();
        assertThat(st.getPendingPlan()).isNotNull();
        assertThat(st.getPendingBudgetOver()).isPositive();
        assertThat(st.getPendingPlanIssues()).singleElement().asString()
                .contains("景点A", "超出开放时间");
        verifyNoInteractions(repairAgent);

        // 用户「确认发布」必须复验：开放时间冲突仍在 → 拒绝发布（26:30 事故根修不因预算放行而失守），
        // 且拒绝消息指向具体节点（第1天「景点A」超出开放时间）
        assertThatThrownBy(() -> svc.publishPending(st))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("景点A")
                .hasMessageContaining("超出开放时间");
    }
}
