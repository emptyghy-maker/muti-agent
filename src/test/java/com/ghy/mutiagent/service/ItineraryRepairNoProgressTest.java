package com.ghy.mutiagent.service;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryRepairNoProgressTest {

    @Test
    void 修复只删除确定性字段但地点顺序和餐次相同应判定为无进展() {
        ItineraryPlan processed = plan(
                node("transport", null, "09:00", "抵达"),
                node("attraction", 45L, "09:30", "游览"),
                node("restaurant", 40L, "11:45", "午餐（按需求补齐）"),
                node("attraction", 5L, "13:30", "游览"),
                node("restaurant", 9L, "17:30", "晚餐"),
                node("transport", null, "19:30", "返程"));
        ItineraryPlan repairedSkeleton = plan(
                node("attraction", 45L, null, null),
                node("restaurant", 40L, null, "午餐"),
                node("attraction", 5L, null, null),
                node("restaurant", 9L, null, "晚餐"));

        assertThat(ItineraryService.decisionSignature(repairedSkeleton))
                .isEqualTo(ItineraryService.decisionSignature(processed));
    }

    @Test
    void 修复改变餐次时应视为有进展() {
        ItineraryPlan before = plan(node("restaurant", 40L, "12:00", "午餐"));
        ItineraryPlan after = plan(node("restaurant", 40L, "18:00", "晚餐"));

        assertThat(ItineraryService.decisionSignature(after))
                .isNotEqualTo(ItineraryService.decisionSignature(before));
    }

    private static ItineraryPlan plan(PlanNode... nodes) {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(1);
        day.setNodes(new ArrayList<>(List.of(nodes)));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(day)));
        return plan;
    }

    private static PlanNode node(String type, Long placeId, String time, String note) {
        PlanNode node = new PlanNode();
        node.setType(type);
        node.setPlaceId(placeId);
        node.setTime(time);
        node.setNote(note);
        return node;
    }
}
