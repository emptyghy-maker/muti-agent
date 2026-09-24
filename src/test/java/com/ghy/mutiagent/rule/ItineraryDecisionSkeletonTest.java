package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Hotel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryDecisionSkeletonTest {

    @Test
    void noHotelDecisionGetsTransportBoundariesAndDisplayDefaults() {
        DailyPlan day = day(1, attraction(7L, "老门东"), restaurant(9L, "晚餐"));
        ItineraryPlan plan = plan(day);

        ItineraryDecisionSkeleton.complete(plan, List.of(), true);

        assertEquals(List.of("transport", "attraction", "restaurant", "transport"),
                day.getNodes().stream().map(PlanNode::getType).toList());
        assertEquals("游览", day.getNodes().get(1).getNote());
        assertEquals("老门东", day.getTheme());
        assertEquals("抵达", day.getNodes().get(0).getNote());
        assertEquals("返程", day.getNodes().get(3).getNote());
    }

    @Test
    void hotelTripGetsDeterministicFirstMiddleAndLastDayBoundaries() {
        ItineraryPlan plan = plan(day(1, attraction(1L, "景点1")),
                day(2, attraction(2L, "景点2")), day(3, attraction(3L, "景点3")));
        Hotel hotel = new Hotel();
        hotel.setId(88L);
        hotel.setName("中心酒店");

        ItineraryDecisionSkeleton.complete(plan, List.of(hotel), false);

        assertEquals(List.of("transport", "hotel", "attraction", "hotel"), types(plan, 0));
        assertEquals(List.of("hotel", "attraction", "hotel"), types(plan, 1));
        assertEquals(List.of("hotel", "attraction", "transport"), types(plan, 2));
        assertTrue(plan.getDays().stream().flatMap(d -> d.getNodes().stream())
                .filter(n -> "hotel".equals(n.getType())).allMatch(n -> n.getPlaceId() == 88L));
    }

    private static List<String> types(ItineraryPlan plan, int day) {
        return plan.getDays().get(day).getNodes().stream().map(PlanNode::getType).toList();
    }

    private static ItineraryPlan plan(DailyPlan... days) {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(days)));
        return plan;
    }

    private static DailyPlan day(int index, PlanNode... nodes) {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(index);
        day.setNodes(new ArrayList<>(List.of(nodes)));
        return day;
    }

    private static PlanNode attraction(long id, String name) {
        PlanNode node = new PlanNode();
        node.setType("attraction");
        node.setPlaceId(id);
        node.setName(name);
        return node;
    }

    private static PlanNode restaurant(long id, String note) {
        PlanNode node = new PlanNode();
        node.setType("restaurant");
        node.setPlaceId(id);
        node.setNote(note);
        return node;
    }
}
