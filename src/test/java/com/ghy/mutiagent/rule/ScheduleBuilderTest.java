package com.ghy.mutiagent.rule;

import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.repository.entity.Attraction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行程时间轴重算测试（docs/agent-io-spec.md 行程时间规范）：
 * 通勤分钟、停留时长、饭点锚定、单调性、晚到告警。
 */
class ScheduleBuilderTest {

    private PlanNode node(String type, Long placeId, String note) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setNote(note);
        return n;
    }

    private Attraction attraction(long id, double hours) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setSuggestHours(hours);
        return a;
    }

    private DailyPlan dayOf(List<PlanNode> nodes) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(1);
        d.setNodes(new ArrayList<>(nodes));
        return d;
    }

    /** 约 5 公里（纬度差 0.045°）：20km/h → 15 分钟 + 10 缓冲 = 25 分钟通勤 */
    @Test
    void 五公里通勤二十五分钟() {
        DailyPlan d = dayOf(List.of(
                node("hotel", 1L, null),
                node("attraction", 2L, null)));
        Map<PlaceKey, double[]> coords = Map.of(
                PlaceKey.of(PlaceType.HOTEL, 1L), new double[]{0, 0},
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{0, 0.045});
        ScheduleBuilder.schedule(d, coords, Map.of(2L, attraction(2L, 2.0)));
        PlanNode second = d.getNodes().get(1);
        assertThat(second.getTravelMinutes()).isEqualTo(25);
        assertThat(second.getTime()).isEqualTo("09:25");
        assertThat(second.getDepartTime()).isEqualTo("09:00");
    }

    /** 早到饭点：交通节点无坐标按 30 分钟通勤，9:30 本可到 → 锚定 11:30 开饭，11:00 出发（出发 = 到达 - 通勤） */
    @Test
    void 早到饭点锚定到窗口起点() {
        DailyPlan d = dayOf(List.of(
                node("transport", null, "抵达"),
                node("restaurant", 3L, "午餐")));
        Map<PlaceKey, double[]> coords = Map.of(PlaceKey.of(PlaceType.RESTAURANT, 3L), new double[]{0, 0.001});
        ScheduleBuilder.schedule(d, coords, Map.of());
        PlanNode meal = d.getNodes().get(1);
        assertThat(meal.getTime()).isEqualTo("11:30");
        assertThat(meal.getDepartTime()).isEqualTo("11:00");
        assertThat(meal.getTravelMinutes()).isEqualTo(30);
    }

    /** 景点停留 2 小时：下一节点到达 = 上一到达 + 停留 + 通勤；时间单调递增 */
    @Test
    void 景点停留时长参与推进且时间单调() {
        DailyPlan d = dayOf(List.of(
                node("hotel", 1L, null),
                node("attraction", 2L, null),
                node("attraction", 3L, null)));
        Map<PlaceKey, double[]> coords = Map.of(
                PlaceKey.of(PlaceType.HOTEL, 1L), new double[]{0, 0},
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{0, 0.001},
                PlaceKey.of(PlaceType.ATTRACTION, 3L), new double[]{0, 0.001});
        ScheduleBuilder.schedule(d, coords, Map.of(2L, attraction(2L, 2.0), 3L, attraction(3L, 2.0)));
        assertThat(d.getNodes().get(0).getTime()).isEqualTo("09:00");
        assertThat(d.getNodes().get(1).getTime()).isEqualTo("09:15");
        assertThat(d.getNodes().get(1).getDurationMinutes()).isEqualTo(120);
        assertThat(d.getNodes().get(2).getTime()).isEqualTo("11:30");
        List<String> times = d.getNodes().stream().map(PlanNode::getTime).toList();
        assertThat(times).isSorted();
    }

    /** 无坐标节点（交通节点到首站）固定 30 分钟通勤 */
    @Test
    void 无坐标节点通勤三十分钟() {
        DailyPlan d = dayOf(List.of(
                node("transport", null, "抵达"),
                node("hotel", 1L, null)));
        Map<PlaceKey, double[]> coords = Map.of(PlaceKey.of(PlaceType.HOTEL, 1L), new double[]{0, 0});
        ScheduleBuilder.schedule(d, coords, Map.of());
        PlanNode hotel = d.getNodes().get(1);
        assertThat(hotel.getTravelMinutes()).isEqualTo(30);
        assertThat(hotel.getTime()).isEqualTo("09:30");
    }

    /** 到晚了不再回拨，保留真实时间并告警 */
    @Test
    void 晚到饭点保留真实时间并告警() {
        DailyPlan d = dayOf(List.of(
                node("hotel", 1L, null),
                node("attraction", 2L, null),
                node("restaurant", 3L, "午餐")));
        Map<PlaceKey, double[]> coords = Map.of(
                PlaceKey.of(PlaceType.HOTEL, 1L), new double[]{0, 0},
                PlaceKey.of(PlaceType.ATTRACTION, 2L), new double[]{0, 0.001},
                PlaceKey.of(PlaceType.RESTAURANT, 3L), new double[]{0, 0.03});
        // 景点 5 小时：09:15 到、14:15 结束；14:15 + 通勤 ≈ 14:30 到餐厅，超出 13:30
        List<String> warnings = ScheduleBuilder.schedule(d, coords, Map.of(2L, attraction(2L, 5.0)));
        assertThat(d.getNodes().get(2).getTime()).isGreaterThan("13:30");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("午餐"));
    }

    /** 问卷起床时间：startMin 决定当天首个节点时间（默认 09:00，可参数化） */
    @Test
    void 起床时间参数化首节点() {
        DailyPlan d = dayOf(List.of(
                node("transport", null, null),
                node("attraction", 2L, null)));
        Map<PlaceKey, double[]> coords = Map.of();
        Map<Long, Attraction> attById = Map.of(2L, attraction(2L, 1.0));

        ScheduleBuilder.schedule(d, coords, attById, null, 10 * 60);

        assertThat(d.getNodes().get(0).getTime()).isEqualTo("10:00");
    }

    @Test
    void 早到景点时等待至开放时间再进入() {
        Attraction lateOpen = attraction(2L, 2.0);
        lateOpen.setOpenTime("10:00-17:30");
        DailyPlan d = dayOf(List.of(
                node("hotel", 1L, null),
                node("attraction", 2L, null)));

        ScheduleBuilder.schedule(d, Map.of(), Map.of(2L, lateOpen));

        assertThat(d.getNodes().get(1).getTime()).isEqualTo("10:00");
        assertThat(d.getNodes().get(1).getDurationMinutes()).isEqualTo(120);
    }

    /** 夜景时段（18:30 后）到访的夜景标签景点按 2 小时封顶（用户口径：晚餐后约 20:00-22:00 夜景） */
    @Test
    void 夜景时段到访的夜景景点时长两小时封顶() {
        Attraction dayLong = attraction(21L, 8.5); // 白天景点撑到 18:00，保证夜景景点 18:30 后到访
        Attraction night = attraction(26L, 3.0);
        night.setTags("自然风光,湖景,夜景,音乐喷泉");
        Map<Long, Attraction> attById = Map.of(21L, dayLong, 26L, night);
        DailyPlan d = dayOf(List.of(
                node("transport", null, null),
                node("attraction", 21L, null),
                node("attraction", 26L, null),
                node("transport", null, null)));

        ScheduleBuilder.schedule(d, Map.of(), attById, null, 9 * 60);

        assertThat(d.getNodes().get(1).getDurationMinutes()).isEqualTo(510); // 白天到访不封顶
        assertThat(d.getNodes().get(2).getTime()).isEqualTo("18:30");
        assertThat(d.getNodes().get(2).getDurationMinutes()).isEqualTo(120); // 3h 建议时长 → 夜景时段封顶 2h
    }
}
