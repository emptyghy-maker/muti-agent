package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.repository.entity.Restaurant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨天去重规则（S05 防重复地点）：同一景点/餐厅/休息点全行程只出现一次；
 * 酒店是每天驻地、交通节点是行程骨架，不受此限；同一天内允许重复。
 */
class ItineraryDedupTest {

    private static PlanNode node(String type, Long placeId, String name) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setName(name);
        n.setTime("12:00");
        return n;
    }

    private static DailyPlan day(int index, PlanNode... nodes) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(index);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    private static Attraction attr(long id, String name) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static Restaurant food(long id, String name) {
        Restaurant r = new Restaurant();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private static Map<Long, Attraction> attById(List<Attraction> list) {
        Map<Long, Attraction> m = new HashMap<>();
        list.forEach(a -> m.put(a.getId(), a));
        return m;
    }

    private static Map<Long, Restaurant> foodById(List<Restaurant> list) {
        Map<Long, Restaurant> m = new HashMap<>();
        list.forEach(r -> m.put(r.getId(), r));
        return m;
    }

    private void run(ItineraryPlan plan, List<Attraction> attrs, List<Restaurant> foods, List<Attraction> rests) {
        ItineraryService.dedupAcrossDays(plan, attById(attrs), foodById(foods), attById(rests),
                attrs, foods, rests);
    }

    @Test
    void crossDayDuplicateAttractionReplacedWithUnusedAlternative() {
        List<Attraction> attrs = List.of(attr(1L, "景点A"), attr(2L, "景点B"));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(
                day(1, node("attraction", 1L, "景点A")),
                day(2, node("attraction", 1L, "景点A"), node("restaurant", 10L, "餐厅X")))));

        run(plan, attrs, List.of(food(10L, "餐厅X")), List.of());

        PlanNode day2Attr = plan.getDays().get(1).getNodes().get(0);
        assertThat(day2Attr.getPlaceId()).isEqualTo(2L);
        assertThat(day2Attr.getName()).isEqualTo("景点B");
        assertThat(plan.getDays().get(1).getNodes()).hasSize(2);
    }

    @Test
    void crossDayDuplicateRestSpotWithoutAlternativeIsRemoved() {
        List<Attraction> rests = List.of(attr(30L, "休息点甲"));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(
                day(1, node("rest", 30L, "休息点甲")),
                day(2, node("rest", 30L, "休息点甲")))));

        run(plan, List.of(), List.of(), rests);

        assertThat(plan.getDays().get(0).getNodes()).hasSize(1);
        assertThat(plan.getDays().get(1).getNodes()).isEmpty();
    }

    @Test
    void crossDayDuplicateRestaurantWithoutAlternativeIsKept() {
        List<Restaurant> foods = List.of(food(10L, "餐厅X"));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(
                day(1, node("restaurant", 10L, "餐厅X")),
                day(2, node("restaurant", 10L, "餐厅X")))));

        run(plan, List.of(), foods, List.of());

        assertThat(plan.getDays().get(1).getNodes()).hasSize(1);
        assertThat(plan.getDays().get(1).getNodes().get(0).getPlaceId()).isEqualTo(10L);
    }

    @Test
    void hotelTransportAndSameDayRepeatsAreUntouched() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(
                day(1, node("transport", null, "交通节点"), node("hotel", 7L, "酒店"),
                        node("attraction", 1L, "景点A"), node("attraction", 1L, "景点A")),
                day(2, node("hotel", 7L, "酒店"), node("transport", null, "交通节点"),
                        node("attraction", 1L, "景点A")))));

        run(plan, List.of(attr(1L, "景点A")), List.of(), List.of());

        // 同一天内的重复与酒店/交通节点都原样保留（去重只针对跨天）
        assertThat(plan.getDays().get(0).getNodes()).hasSize(4);
        assertThat(plan.getDays().get(1).getNodes()).hasSize(3);
    }

    @Test
    void sameNumericIdAcrossTypesAreNotTreatedAsDuplicates() {
        List<Attraction> attrs = List.of(attr(1L, "景点A"));
        List<Restaurant> foods = List.of(food(1L, "餐厅X"));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(
                day(1, node("attraction", 1L, "景点A"), node("restaurant", 1L, "餐厅X")),
                day(2, node("attraction", 1L, "景点A"), node("restaurant", 1L, "餐厅X")))));

        run(plan, attrs, foods, List.of());

        // 景点/餐厅分属不同表（id 相同也互不冲突）：各自跨天重复且无备选 → 保留
        assertThat(plan.getDays().get(1).getNodes()).hasSize(2);
        assertThat(plan.getDays().get(1).getNodes().get(0).getPlaceId()).isEqualTo(1L);
        assertThat(plan.getDays().get(1).getNodes().get(1).getPlaceId()).isEqualTo(1L);
    }
}
