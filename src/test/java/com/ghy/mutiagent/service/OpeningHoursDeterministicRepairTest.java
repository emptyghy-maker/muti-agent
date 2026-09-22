package com.ghy.mutiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.agent.ItineraryRepairAgent;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.model.TravelState;
import com.ghy.mutiagent.repository.entity.Attraction;
import com.ghy.mutiagent.rule.ScheduleBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 营业时间违规的确定性治理（422 事故根修）：
 * - 排程封顶：建议时长超出营业时间时，停留压缩到关门时间（不再产生可避免的开放时间违规）；
 * - 修复详情：OPENING_HOURS_VIOLATION 附「景点名/开放时间/当前安排/超出量」，随修复上下文下发；
 * - 修复上下文携带 detail，模型不再瞎猜。
 */
class OpeningHoursDeterministicRepairTest {

    private static Attraction attraction(long id, String open, double hours) {
        Attraction a = new Attraction();
        a.setId(id);
        a.setName("雨花台风景区");
        a.setOpenTime(open);
        a.setSuggestHours(hours);
        return a;
    }

    private static PlanNode node(String type, Long placeId, String name) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setName(name);
        return n;
    }

    @Test
    void 排程封顶_建议时长超出闭馆时间时压缩到关门() {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(1);
        PlanNode t = node("transport", null, "交通节点");
        PlanNode a = node("attraction", 10L, "雨花台风景区");
        day.setNodes(List.of(t, a));
        // 开放 09:00-12:00：09:30 到馆（通勤 30 分钟），建议 3 小时 → 压缩到 150 分钟（12:00 闭馆）
        Map<Long, Attraction> attById = Map.of(10L, attraction(10L, "09:00-12:00", 3.0));

        ScheduleBuilder.schedule(day, Map.of(), attById);

        assertThat(a.getTime()).isEqualTo("09:30");
        assertThat(a.getDurationMinutes()).isEqualTo(150);
    }

    @Test
    void 排程封顶_全天开放不压缩() {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(1);
        PlanNode t = node("transport", null, "交通节点");
        PlanNode a = node("attraction", 10L, "雨花台风景区");
        day.setNodes(List.of(t, a));
        Map<Long, Attraction> attById = Map.of(10L, attraction(10L, "全天", 3.0));

        ScheduleBuilder.schedule(day, Map.of(), attById);

        assertThat(a.getDurationMinutes()).isEqualTo(180);
    }

    @Test
    void 排程封顶_压缩后不足最短停留则保持原时长交发布检查() {
        DailyPlan day = new DailyPlan();
        day.setDayIndex(1);
        PlanNode t = node("transport", null, "交通节点");
        t.setType("attraction");
        t.setPlaceId(99L);
        t.setName("前序景点");
        PlanNode a = node("attraction", 10L, "雨花台风景区");
        day.setNodes(List.of(t, a));
        // 前序节点 09:00 停留 240 分钟（开放 09:00-16:00，容量 420 → 压缩到 240? 不——前序开放 09:00-16:00，
        // 容量 420 分钟 > 240 不压缩），到雨花台 13:30，闭馆 14:00：容量 30 分钟 < 最短 60 → 不压缩（180 保持），
        // 留给发布检查拦截、修复循环处理
        Map<Long, Attraction> attById = Map.of(99L, attraction(99L, "09:00-16:00", 4.0),
                10L, attraction(10L, "09:00-14:00", 3.0));

        ScheduleBuilder.schedule(day, Map.of(), attById);

        assertThat(a.getTime()).isEqualTo("13:30");
        assertThat(a.getDurationMinutes()).isEqualTo(180);
    }

    @Test
    void 违规详情_给出景点名开放时间当前安排与超出量() {
        ItineraryPlan plan = new ItineraryPlan();
        DailyPlan d = new DailyPlan();
        d.setDayIndex(1);
        PlanNode n = node("attraction", 10L, "雨花台风景区");
        n.setTime("15:00");
        n.setDurationMinutes(150);
        d.setNodes(List.of(n));
        plan.setDays(List.of(d));
        List<ItineraryRepairEngine.RepairViolation> vs = List.of(
                new ItineraryRepairEngine.RepairViolation(
                        "OPENING_HOURS_VIOLATION", 1, List.of("d1-a1"), true, null),
                new ItineraryRepairEngine.RepairViolation(
                        "DAY_END_EXCEEDED", 1, List.of("d1-t2"), true, null));

        List<ItineraryRepairEngine.RepairViolation> out = ItineraryService.withOpeningDetails(
                vs, plan, List.of(attraction(10L, "08:00-17:00", 2.5)), List.of());

        assertThat(out).hasSize(2);
        assertThat(out.get(0).detail())
                .contains("雨花台风景区", "08:00-17:00", "15:00-17:30", "超出 30 分钟");
        assertThat(out.get(1).detail()).isNull();
    }

    @Test
    void 修复上下文携带详情() throws Exception {
        ItineraryRepairEngine engine = new ItineraryRepairEngine(mock(ItineraryRepairAgent.class),
                new ObjectMapper(), TimeSource.SYSTEM, OperationBudgetConfig.defaults());
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(List.of());
        TravelState state = new TravelState();
        state.setPreference(new TravelPreference());

        String ctx = engine.buildRepairContext(plan, List.of(
                new ItineraryRepairEngine.RepairViolation(
                        "OPENING_HOURS_VIOLATION", 1, List.of("d1-a1"), true,
                        "「雨花台风景区」开放时间 08:00-17:00，当前安排 15:00-17:30，超出 30 分钟")),
                state);

        assertThat(ctx).contains("\"detail\"", "雨花台风景区", "超出 30 分钟");
    }
}
