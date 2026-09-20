package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Restaurant;
import com.ghy.mutiagent.repository.mapper.AttractionMapper;
import com.ghy.mutiagent.repository.mapper.DestinationMapper;
import com.ghy.mutiagent.repository.mapper.HotelMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryFeedbackMapper;
import com.ghy.mutiagent.repository.mapper.ItineraryMapper;
import com.ghy.mutiagent.repository.mapper.RestaurantMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 饭点兜底注入回归：已有同餐次节点（哪怕时间在窗口外）不得再注入，
 * 防止「模型排了 21:30 晚餐 → 窗口检查判缺 → 再注入一个晚餐」的双晚餐事故。
 */
class ItineraryMealInjectionTest {

    private ItineraryService svc;

    @BeforeEach
    void setUp() {
        svc = new ItineraryService(mock(AttractionMapper.class), mock(RestaurantMapper.class),
                mock(HotelMapper.class), mock(ItineraryMapper.class), mock(ItineraryFeedbackMapper.class),
                mock(DestinationMapper.class), mock(ItineraryAgent.class), mock(TraceService.class),
                mock(UsageService.class), new ObjectMapper(), mock(ItineraryCommitService.class));
    }

    private static PlanNode n(String type, Long placeId, String time, String note) {
        PlanNode x = new PlanNode();
        x.setType(type);
        x.setPlaceId(placeId);
        x.setTime(time);
        x.setNote(note);
        return x;
    }

    private static DailyPlan day(PlanNode... nodes) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(1);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    private static List<Restaurant> foods() {
        Restaurant r = new Restaurant();
        r.setId(10L);
        r.setName("湖畔餐厅");
        r.setLng(120.7);
        r.setLat(31.3);
        return List.of(r);
    }

    private void inject(DailyPlan d) {
        ReflectionTestUtils.invokeMethod(svc, "injectMeals", d, foods(), Map.<PlaceKey, double[]>of());
    }

    @Test
    void lateEveningDinnerNodeDoesNotGetSecondDinnerInjected() {
        // 模型把晚餐排到 21:30（窗口外）：注入侧只认「已有晚餐节点」，不重复注入
        DailyPlan d = day(n("transport", null, "09:00", "抵达"),
                n("attraction", 1L, "10:00", "游览"),
                n("restaurant", 10L, "21:30", "晚餐"));
        inject(d);

        long dinners = d.getNodes().stream().filter(x -> "restaurant".equals(x.getType())
                && x.getNote() != null && x.getNote().contains("晚餐")).count();
        long lunches = d.getNodes().stream().filter(x -> "restaurant".equals(x.getType())
                && x.getNote() != null && x.getNote().contains("午餐")).count();
        assertThat(dinners).isEqualTo(1);
        assertThat(lunches).isEqualTo(1); // 午餐缺失仍正常注入
    }

    @Test
    void dayWithoutAnyMealGetsBothInjected() {
        DailyPlan d = day(n("transport", null, "09:00", "抵达"),
                n("attraction", 1L, "10:00", "游览"));
        inject(d);

        long meals = d.getNodes().stream().filter(x -> "restaurant".equals(x.getType())).count();
        assertThat(meals).isEqualTo(2);
    }

    @Test
    void earlyReturnStillSkipsDinnerInjection() {
        // 既有口径保持：末节点返程早于 19:30 时不注入晚餐
        DailyPlan d = day(n("transport", null, "09:00", "抵达"),
                n("attraction", 1L, "10:00", "游览"),
                n("transport", null, "18:00", "返程"));
        inject(d);

        long dinners = d.getNodes().stream().filter(x -> "restaurant".equals(x.getType())
                && x.getNote() != null && x.getNote().contains("晚餐")).count();
        long lunches = d.getNodes().stream().filter(x -> "restaurant".equals(x.getType())
                && x.getNote() != null && x.getNote().contains("午餐")).count();
        assertThat(dinners).isZero();
        assertThat(lunches).isEqualTo(1);
    }
}
