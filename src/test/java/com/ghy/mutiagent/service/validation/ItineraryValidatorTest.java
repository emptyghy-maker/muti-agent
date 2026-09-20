package com.ghy.mutiagent.service.validation;

import com.ghy.mutiagent.model.ConstraintEntry;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import com.ghy.mutiagent.model.PlaceKey;
import com.ghy.mutiagent.model.PlaceType;
import com.ghy.mutiagent.model.PlanNode;
import com.ghy.mutiagent.model.RequirementSnapshot;
import com.ghy.mutiagent.model.TravelPreference;
import com.ghy.mutiagent.repository.entity.Attraction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S04 契约测试：发布检查器（结构化违规码、不发布语义）。
 */
class ItineraryValidatorTest {

    private static PlanNode node(String type, Long placeId, String time, int dur) {
        PlanNode n = new PlanNode();
        n.setType(type);
        n.setPlaceId(placeId);
        n.setTime(time);
        n.setDurationMinutes(dur);
        return n;
    }

    private static DailyPlan day(int index, PlanNode... nodes) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(index);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    private static ItineraryPlan plan3(DailyPlan d1) {
        ItineraryPlan p = new ItineraryPlan();
        p.setDays(new ArrayList<>(List.of(d1, day(2, node("hotel", 1L, "09:00", 0)),
                day(3, node("hotel", 1L, "09:00", 0)))));
        return p;
    }

    private static TravelPreference pref() {
        TravelPreference p = new TravelPreference();
        p.setDays(3);
        p.setPeopleCount(2);
        return p;
    }

    private static Attraction attraction(String category) {
        Attraction a = new Attraction();
        a.setId(1L);
        a.setCategory(category);
        a.setIntensity(2);
        a.setSuggestHours(1.0);
        return a;
    }

    @Test
    void 超过22点不发布() {
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("attraction", 1L, "21:00", 120))),
                pref(), null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(), Map.of(1L, attraction("休闲")), false);
        assertThat(r.violations()).contains(ItineraryValidator.DAY_END_EXCEEDED);
        assertThat(r.publishable()).isFalse();
    }

    @Test
    void 夜景标签景点晚上安排不触发日终上限() {
        Attraction night = attraction("休闲");
        night.setTags("夜景,湖景,情侣");
        TravelPreference p1 = pref();
        p1.setDays(1);
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(new ArrayList<>(List.of(day(1,
                node("transport", null, "09:00", 0),
                node("attraction", 1L, "09:30", 90),
                node("restaurant", 10L, "12:00", 90),
                node("attraction", 2L, "14:00", 120),
                node("restaurant", 10L, "18:00", 90),
                node("attraction", 1L, "20:00", 90),
                node("transport", null, "22:30", 0)))));
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan, p1, null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(),
                Map.of(1L, night, 2L, attraction("休闲")), false);
        assertThat(r.violations()).doesNotContain(ItineraryValidator.DAY_END_EXCEEDED);
        assertThat(r.publishable()).isTrue();
    }

    @Test
    void 夜间需求快照放宽日终上限() {
        RequirementSnapshot snap = new RequirementSnapshot();
        ConstraintEntry night = new ConstraintEntry();
        night.setKey("interest");
        night.setValue("NIGHT_VIEW");
        night.setHardness("SOFT");
        night.setStatus("ACTIVE");
        snap.setConstraints(new ArrayList<>(List.of(night)));
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("transport", null, "09:00", 0),
                        node("attraction", 1L, "23:00", 60),
                        node("transport", null, "23:30", 0))),
                pref(), snap, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(),
                Map.of(1L, attraction("休闲")), false);
        assertThat(r.violations()).doesNotContain(ItineraryValidator.DAY_END_EXCEEDED);
    }

    @Test
    void 偏好原文含酒吧同样放宽日终上限() {
        TravelPreference p = pref();
        p.setSpecialRequests("晚上想去酒吧坐坐");
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("transport", null, "09:00", 0),
                        node("attraction", 1L, "23:00", 60),
                        node("transport", null, "23:30", 0))),
                p, null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(),
                Map.of(1L, attraction("休闲")), false);
        assertThat(r.violations()).doesNotContain(ItineraryValidator.DAY_END_EXCEEDED);
    }

    @Test
    void 夜景标签景点排白天不触发放宽() {
        Attraction night = attraction("休闲");
        night.setTags("夜景,湖景");
        // 夜景标签景点排在 14:00（白天）不构成夜间节点：晚场普通节点 23:00 结束仍超 22:00 上限
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("attraction", 1L, "14:00", 60),
                        node("attraction", 2L, "23:00", 60))),
                pref(), null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(),
                Map.of(1L, night, 2L, attraction("休闲")), false);
        assertThat(r.violations()).contains(ItineraryValidator.DAY_END_EXCEEDED);
    }

    @Test
    void 返程后仍有活动不发布() {
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("hotel", 1L, "09:00", 0), node("transport", null, "18:00", 0),
                        node("attraction", 1L, "19:00", 60))),
                pref(), null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(), Map.of(1L, attraction("休闲")), false);
        assertThat(r.violations()).contains(ItineraryValidator.DEPARTURE_ORDER);
        assertThat(r.publishable()).isFalse();
    }

    @Test
    void 已知费用超预算不发布() {
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("hotel", 1L, "09:00", 0))),
                pref(), null, new BigDecimal("5000"), new BigDecimal("6000"), Map.of(), Map.of(), false);
        assertThat(r.violations()).contains(ItineraryValidator.BUDGET_EXCEEDED);
        assertThat(r.publishable()).isFalse();
    }

    @Test
    void 硬限制数据未知待确认() {
        RequirementSnapshot snap = new RequirementSnapshot();
        ConstraintEntry c = new ConstraintEntry();
        c.setKey("avoidClimbing");
        c.setValue("TRUE");
        c.setHardness("HARD");
        c.setStatus("ACTIVE");
        c.setSource("USER");
        c.setOriginalText("不能爬山");
        snap.setConstraints(new ArrayList<>(List.of(c)));
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("attraction", 1L, "10:00", 60))),
                pref(), snap, new BigDecimal("5000"), BigDecimal.ZERO,
                Map.of(PlaceKey.of(PlaceType.ATTRACTION, 1L), "09:00-18:00"),
                Map.of(1L, attraction("")), false);
        assertThat(r.needsConfirmation()).isTrue();
        assertThat(r.publishable()).isFalse();
    }

    @Test
    void 明确轻松限制仍超载不发布() {
        TravelPreference weak = pref();
        weak.setEnergyLevel("偏弱");
        ItineraryValidator.ValidationResult r = ItineraryValidator.validate(
                plan3(day(1, node("attraction", 1L, "10:00", 60))),
                weak, null, new BigDecimal("5000"), BigDecimal.ZERO, Map.of(), Map.of(1L, attraction("休闲")), true);
        assertThat(r.violations()).contains(ItineraryValidator.HARD_FATIGUE_EXCEEDED);
        assertThat(r.publishable()).isFalse();
    }
}
