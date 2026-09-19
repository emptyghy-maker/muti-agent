package com.ghy.mutiagent.service;

import com.ghy.mutiagent.common.JsonUtils;
import com.ghy.mutiagent.model.DailyPlan;
import com.ghy.mutiagent.model.ItineraryPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S11 长行程分块：块范围提示携带分块标记与边界上下文；块内天数过滤
 * 只保留请求范围内的天（越界/缺失输出丢弃，不做字符串修补）。
 */
class ChunkPlanningTest {

    private static DailyPlan day(int i) {
        DailyPlan d = new DailyPlan();
        d.setDayIndex(i);
        return d;
    }

    @Test
    void chunkRulesCarryRangeMarkerAndBoundaryContext() {
        String rules = ItineraryService.chunkRules("基础规则", 4, 4, 7);
        assertTrue(rules.contains("第4天 至 第4天"), "块范围标记必须可解析（供观测请求天数）");
        assertTrue(rules.contains("全行程共 7 天"));
        assertTrue(rules.contains("住宿") && rules.contains("剩余预算") && rules.contains("锁定"),
                "块提示必须携带住宿/预算/锁定项边界上下文");
        assertTrue(rules.startsWith("基础规则"), "分块提示在基础规则之上追加，不得替换");
    }

    @Test
    void daysInRangeKeepsOnlyRequestedDays() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(List.of(day(1), day(2), day(3), day(4)));
        List<DailyPlan> wanted = ItineraryService.daysInRange(plan, 2, 3);
        assertEquals(List.of(2, 3), wanted.stream().map(DailyPlan::getDayIndex).toList());
    }

    @Test
    void daysInRangeEmptyOnMissingOrNull() {
        ItineraryPlan plan = new ItineraryPlan();
        plan.setDays(List.of(day(1)));
        assertTrue(ItineraryService.daysInRange(plan, 4, 4).isEmpty(), "缺块必须视为不完整");
        assertTrue(ItineraryService.daysInRange(null, 1, 1).isEmpty());
        ItineraryPlan noDays = new ItineraryPlan();
        assertTrue(ItineraryService.daysInRange(noDays, 1, 1).isEmpty());
    }

    @Test
    void strictParseRejectsTruncatedOutput() {
        // S11：分块输出截断必须视为不完整——严格解析不补括号、不做截断修复
        assertNull(JsonUtils.parseStrict("{\"days\":[{\"dayIndex\":4,\"nodes\":[", ItineraryPlan.class));
        assertNull(JsonUtils.parseStrict("", ItineraryPlan.class));
        ItineraryPlan ok = JsonUtils.parseStrict(
                "{\"days\":[{\"dayIndex\":1,\"theme\":\"t\",\"nodes\":[]}]}", ItineraryPlan.class);
        assertNotNull(ok);
        assertEquals(1, ok.getDays().size());
    }
}
