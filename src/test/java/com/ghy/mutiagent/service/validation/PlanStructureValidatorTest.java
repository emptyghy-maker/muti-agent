package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S03 契约测试：行程结构验证（缺天/null 天/节点上限/正常通过）。
 */
class PlanStructureValidatorTest {

    private static ItineraryPlan planOf(int days) {
        ItineraryPlan p = new ItineraryPlan();
        List<DailyPlan> list = new ArrayList<>();
        for (int i = 1; i <= days; i++) {
            DailyPlan d = new DailyPlan();
            d.setDayIndex(i);
            d.setNodes(new ArrayList<>());
            list.add(d);
        }
        p.setDays(list);
        return p;
    }

    @Test
    void 需求三天只给一天是违规() {
        List<String> v = PlanStructureValidator.validate(planOf(1), 3);
        assertThat(PlanStructureValidator.status(v)).isEqualTo(PlanStructureValidator.FAILED);
        assertThat(v).anySatisfy(s -> assertThat(s).contains("少于需求"));
    }

    @Test
    void null天是违规() {
        ItineraryPlan p = new ItineraryPlan();
        List<DailyPlan> days = new ArrayList<>();
        days.add(null);
        p.setDays(days);
        List<String> v = PlanStructureValidator.validate(p, 1);
        assertThat(PlanStructureValidator.status(v)).isEqualTo(PlanStructureValidator.FAILED);
        assertThat(v).anySatisfy(s -> assertThat(s).contains("null"));
    }

    @Test
    void 节点数超上限是违规() {
        ItineraryPlan p = planOf(3);
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < PlanStructureValidator.MAX_NODES_PER_DAY + 1; i++) {
            PlanNode n = new PlanNode();
            n.setType("restaurant");
            n.setPlaceId(1L);
            nodes.add(n);
        }
        p.getDays().get(0).setNodes(nodes);
        List<String> v = PlanStructureValidator.validate(p, 3);
        assertThat(PlanStructureValidator.status(v)).isEqualTo(PlanStructureValidator.FAILED);
        assertThat(v).anySatisfy(s -> assertThat(s).contains("超过上限"));
    }

    @Test
    void 完整三天通过() {
        List<String> v = PlanStructureValidator.validate(planOf(3), 3);
        assertThat(PlanStructureValidator.status(v)).isEqualTo(PlanStructureValidator.OK);
    }

    @Test
    void 行程为空是违规() {
        assertThat(PlanStructureValidator.status(PlanStructureValidator.validate(null, 3)))
                .isEqualTo(PlanStructureValidator.FAILED);
    }
}
