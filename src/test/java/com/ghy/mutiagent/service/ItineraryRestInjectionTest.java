package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryAgent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlanNode;
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
import com.ghy.mutiagent.rule.PlaceIndex;
import com.ghy.mutiagent.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 休息点注入回归（S04 疲劳口径）：休息点是软性节点——
 * 1. 候选休息点距当天活动超过 8km 时不注入（宁可不注入，不为休息专程绕远路）：
 *    事故复盘：鼓楼区情侣一日游被注入 30km 外的「汤山温泉」作休息点，
 *    多出 75 分钟 + 两段出租车费，把返程推到 24:15、费用推到超预算。
 * 2. 注入的休息点把当天推到时间上限之外时自动移除并重算（休息绝不成为行程超时的原因）。
 */
class ItineraryRestInjectionTest {

    private ItineraryService svc;

    @BeforeEach
    void setUp() {
        svc = new ItineraryService(mock(AttractionMapper.class), mock(RestaurantMapper.class),
                mock(HotelMapper.class), mock(ItineraryMapper.class), mock(ItineraryFeedbackMapper.class),
                mock(DestinationMapper.class), mock(ItineraryAgent.class), mock(TraceService.class),
                mock(UsageService.class), new ObjectMapper(), mock(ItineraryCommitService.class));
    }

    private static Attraction attraction(long id, String name, double hours, int intensity, String tags,
                                         double lng, double lat) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName(name);
        a.setCategory("景点");
        a.setSuggestHours(hours);
        a.setIntensity(intensity);
        a.setTags(tags);
        a.setTicketPrice(BigDecimal.ZERO);
        a.setLng(lng);
        a.setLat(lat);
        return a;
    }

    private static Restaurant restaurant(long id, String name, double lng, double lat) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName(name);
        r.setCuisine("本地菜");
        r.setAvgPrice(BigDecimal.valueOf(50));
        r.setLng(lng);
        r.setLat(lat);
        return r;
    }

    private static PlanNode node(String type, Long placeId, String time, String note) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setTime(time);
        n.setNote(note);
        return n;
    }

    /** 事故复刻：玄武湖(3h)+1912街区(2h)+夫子庙夜游(3h)，午晚餐标注齐全，返程 21:30 */
    private static DailyPlan userLikeDay() {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(1);
        d.setNodes(new ArrayList<>(List.of(
                node("transport", null, "09:00", "抵达"),
                node("attraction", 5L, "09:30", "湖景漫步"),
                node("restaurant", 86L, "12:00", "午餐"),
                node("attraction", 44L, "14:30", "街区拍照"),
                node("restaurant", 85L, "18:00", "晚餐"),
                node("attraction", 1L, "19:00", "夜游秦淮"),
                node("transport", null, "21:30", "返程"))));
        return d;
    }

    /** P4 决策协议：Agent 只返回地点顺序，不返回交通节点和时间。 */
    private static DailyPlan decisionOnlyDay() {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(1);
        d.setNodes(new ArrayList<>(List.of(
                node("attraction", 5L, null, null),
                node("restaurant", 86L, null, "午餐"),
                node("attraction", 44L, null, null),
                node("restaurant", 85L, null, "晚餐"),
                node("attraction", 1L, null, null))));
        return d;
    }

    private TravelState state(String deadline) {
        TravelState s = new TravelState();
        s.setSessionId("s-rest");
        s.setUserId(1L);
        s.setUsername("admin");
        TravelPreference p = new TravelPreference();
        p.setDays(1);
        p.setPeopleCount(2);
        p.setTotalBudget(BigDecimal.valueOf(5000));
        p.setEnergyLevel("一般");
        p.setReturnDeadline(deadline);
        s.setPreference(p);
        return s;
    }

    private void postProcess(TravelState st, ItineraryPlan plan, List<Attraction> attractions,
                             List<Restaurant> restaurants, List<Attraction> restSpots) {
        Map<PlaceKey, double[]> coords = PlaceIndex.coords(attractions, restaurants, List.of(), restSpots);
        ReflectionTestUtils.invokeMethod(svc, "postProcess", st, plan, attractions, restaurants,
                List.<Hotel>of(), restSpots, coords, null);
    }

    @Test
    void 远处的休息点候选不注入() {
        // 事故复刻实体：鼓楼区景点 + 30km 外汤山温泉休息点（强度2，在休息候选口径内）
        List<Attraction> attractions = List.of(
                attraction(1, "夫子庙秦淮风光带", 3.0, 2, "夜景,游船,街区", 120.68, 31.29),
                attraction(44, "1912街区", 2.0, 1, "街区,夜景,酒吧", 120.62, 31.30),
                attraction(5, "玄武湖", 3.0, 2, "自然风光,湖景", 120.63, 31.31));
        List<Restaurant> restaurants = List.of(
                restaurant(86, "小厨娘淮扬菜（鼓楼广场店）", 120.61, 31.30),
                restaurant(85, "芳婆糕团店（莫愁路总店）", 120.60, 31.29));
        List<Attraction> restSpots = List.of(
                attraction(99, "汤山温泉度假区", 2.0, 2, "温泉,舒适", 119.05, 32.05)); // 距当天活动 > 40km

        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(userLikeDay())));
        TravelState st = state(null);

        postProcess(st, plan, attractions, restaurants, restSpots);

        assertThat(plan.getDays().get(0).getNodes())
                .as("远距离休息点不得注入")
                .noneMatch(n -> "rest".equals(n.getType()));
        // 有夜景节点的当天日终上限为 24:00：不注入休息点的自然日程必须落在上限内
        PlanNode last = plan.getDays().get(0).getNodes().get(plan.getDays().get(0).getNodes().size() - 1);
        assertThat(last.getType()).isEqualTo("transport");
        assertThat(last.getTime()).isNotNull().isLessThan("24:00");
    }

    @Test
    void 注入休息点导致超时上限时自动移除() {
        // 夜景封顶 2h 后当天自然结束约 22:03（deadline 22:30 内）；
        // 注入附近休息点（+60 分钟 + 通勤）推到 22:52 越界 → 应移除并重算
        List<Attraction> attractions = List.of(
                attraction(1, "夫子庙秦淮风光带", 3.0, 2, "夜景,游船,街区", 120.68, 31.29),
                attraction(44, "1912街区", 2.0, 1, "街区,夜景,酒吧", 120.62, 31.30),
                attraction(5, "玄武湖", 3.0, 2, "自然风光,湖景", 120.63, 31.31));
        List<Restaurant> restaurants = List.of(
                restaurant(86, "小厨娘淮扬菜（鼓楼广场店）", 120.61, 31.30),
                restaurant(85, "芳婆糕团店（莫愁路总店）", 120.60, 31.29));
        List<Attraction> restSpots = List.of(
                attraction(98, "城市休闲广场", 1.0, 1, "休闲,公园", 120.615, 31.305)); // 距当天活动 ~2km，符合注入条件

        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(userLikeDay())));
        TravelState st = state("22:30");

        postProcess(st, plan, attractions, restaurants, restSpots);

        List<PlanNode> nodes = plan.getDays().get(0).getNodes();
        assertThat(nodes).as("越界休息点必须移除").noneMatch(n -> "rest".equals(n.getType()));
        PlanNode last = nodes.get(nodes.size() - 1);
        assertThat(last.getType()).isEqualTo("transport");
        assertThat(last.getTime()).isNotNull().isLessThan("22:31");
    }

    @Test
    void 注入休息点超过自身开放时间时自动移除() {
        List<Attraction> attractions = List.of(
                attraction(1, "夫子庙秦淮风光带", 3.0, 2, "夜景,游船,街区", 120.68, 31.29),
                attraction(44, "1912街区", 2.0, 1, "街区,夜景,酒吧", 120.62, 31.30),
                attraction(5, "玄武湖", 3.0, 2, "自然风光,湖景", 120.63, 31.31));
        List<Restaurant> restaurants = List.of(
                restaurant(86, "小厨娘淮扬菜（鼓楼广场店）", 120.61, 31.30),
                restaurant(85, "芳婆糕团店（莫愁路总店）", 120.60, 31.29));
        Attraction earlyClosing = attraction(97, "瞻园", 1.0, 1, "园林,休闲", 120.615, 31.305);
        earlyClosing.setOpenTime("08:00-17:30");

        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(userLikeDay())));
        postProcess(state(null), plan, attractions, restaurants, List.of(earlyClosing));

        assertThat(plan.getDays().get(0).getNodes())
                .as("闭馆后的自动休息点必须移除")
                .noneMatch(n -> "rest".equals(n.getType()) && Long.valueOf(97L).equals(n.getPlaceId()));
    }

    @Test
    void 决策节点无时间时休息点不得成为早晨首个活动() {
        List<Attraction> attractions = List.of(
                attraction(1, "夫子庙秦淮风光带", 2.0, 2, "夜景,游船,街区", 120.68, 31.29),
                attraction(44, "1912街区", 2.0, 1, "街区,夜景,酒吧", 120.62, 31.30),
                attraction(5, "熙南里", 2.0, 2, "街区,拍照", 120.63, 31.31));
        List<Restaurant> restaurants = List.of(
                restaurant(86, "午餐店", 120.61, 31.30),
                restaurant(85, "晚餐店", 120.60, 31.29));
        Attraction restSpot = attraction(98, "城市休闲花园", 1.0, 1,
                "休闲,公园", 120.615, 31.305);

        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(decisionOnlyDay())));
        postProcess(state(null), plan, attractions, restaurants, List.of(restSpot));

        List<PlanNode> nodes = plan.getDays().get(0).getNodes();
        int restIndex = -1;
        List<Integer> attractionIndexes = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if ("rest".equals(nodes.get(i).getType())) restIndex = i;
            if ("attraction".equals(nodes.get(i).getType())) attractionIndexes.add(i);
        }

        assertThat(restIndex).as("高疲劳日应保留一个附近休息点").isGreaterThanOrEqualTo(0);
        assertThat(nodes.get(1).getType()).as("抵达后的首个活动必须是实质游览").isEqualTo("attraction");
        assertThat(restIndex).as("休息点应位于游览过程之中")
                .isGreaterThan(attractionIndexes.get(0))
                .isLessThan(attractionIndexes.get(attractionIndexes.size() - 1));
        assertThat(nodes.get(restIndex).getTime()).as("休息点应由统一时间轴计算真实到达时间")
                .isNotNull().isGreaterThanOrEqualTo("14:00");
    }
}
