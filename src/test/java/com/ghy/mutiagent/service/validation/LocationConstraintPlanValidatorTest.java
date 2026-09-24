package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.LocationConstraint;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.rule.LocationConstraintSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocationConstraintPlanValidatorTest {

    @Test
    void 发布检查能定位超出新街口范围的计划节点() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京", "景点在新街口附近",
                List.of(LocationConstraintSupport.ATTRACTION));
        Attraction near = attraction(1L, "1912街区", 118.795, 32.055);
        Attraction far = attraction(2L, "牛首山文化旅游区", 118.735, 31.911);
        ItineraryPlan plan = plan(node(1L), node(2L));

        LocationConstraintPlanValidator.Result result = LocationConstraintPlanValidator.validate(
                plan, c, Map.of(1L, near, 2L, far), Map.of(), Map.of());

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).containsExactly("d1-n2");
        assertThat(result.unverifiable()).isEmpty();
    }

    @Test
    void 缺少地点事实时不得宣称位置约束已满足() {
        LocationConstraint c = LocationConstraintSupport.resolve("南京", "景点在新街口附近",
                List.of(LocationConstraintSupport.ATTRACTION));

        LocationConstraintPlanValidator.Result result = LocationConstraintPlanValidator.validate(
                plan(node(99L)), c, Map.of(), Map.of(), Map.of());

        assertThat(result.unverifiable()).containsExactly("d1-n1");
    }

    private static Attraction attraction(long id, String name, double lng, double lat) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName(name);
        a.setLng(lng);
        a.setLat(lat);
        return a;
    }

    private static PlanNode node(long id) {
        PlanNode n = new PlanNode();
        n.setType("attraction");
        n.setPlaceId(id);
        return n;
    }

    private static ItineraryPlan plan(PlanNode... nodes) {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(1);
        day.setNodes(new ArrayList<>(List.of(nodes)));
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(List.of(day));
        return plan;
    }
}
