package com.ghy.mutiagent.rule;

import org.junit.jupiter.api.Test;
import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementScope;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RulePreferenceParserTest {

    private final RulePreferenceParser parser = new RulePreferenceParser();

    @Test
    void 解析天数() {
        assertThat(parser.parse("我们打算玩3天", null)).containsEntry("days", "3");
    }

    @Test
    void 解析预算() {
        assertThat(parser.parse("预算4000左右", null)).containsEntry("totalBudget", "4000");
    }

    @Test
    void 解析人数() {
        assertThat(parser.parse("2个人一起去", null)).containsEntry("peopleCount", "2");
    }

    @Test
    void 解析景点类型打卡() {
        assertThat(parser.parse("喜欢打卡拍照", null)).containsEntry("attractionType", "打卡拍照");
    }

    @Test
    void 不辣优先于辣() {
        assertThat(parser.parse("想吃不辣的", null)).containsEntry("foodTaste", "清淡");
    }

    @Test
    void 一句多字段可同时解析() {
        Map<String, String> r = parser.parse("玩3天预算4000，2个人", null);
        assertThat(r).containsEntry("days", "3")
                .containsEntry("totalBudget", "4000")
                .containsEntry("peopleCount", "2");
    }

    @Test
    void 选项按钮精确匹配() {
        assertThat(parser.parse("4天", "days")).containsEntry("days", "4");
        assertThat(parser.parse("体验优先", "hotelStyle")).containsEntry("hotelStyle", "体验优先");
    }

    @Test
    void 预算问题下纯数字按预算解析且没有残余() {
        RuleParseResult r = parser.parseResult("600", "totalBudget", null);

        assertThat(r.getUpdates()).containsEntry("totalBudget", "600");
        assertThat(r.getBudget()).isNotNull();
        assertThat(r.getBudget().getTarget()).isEqualByComparingTo("600");
        assertThat(r.getUnresolvedText()).isNull();
    }

    @Test
    void 天数和人数问题支持纯数字短答() {
        assertThat(parser.parse("6", "days")).containsEntry("days", "6");
        assertThat(parser.parse("4", "peopleCount")).containsEntry("peopleCount", "4");
    }

    @Test
    void 没有字段上下文时不把纯数字猜成预算() {
        RuleParseResult r = parser.parseResult("600", null, null);

        assertThat(r.getUpdates()).doesNotContainKey("totalBudget");
        assertThat(r.getUnresolvedText()).isEqualTo("600");
    }

    @Test
    void 带其他字段单位的数字仍按显式字段解析() {
        RuleParseResult r = parser.parseResult("3天", "totalBudget", null);

        assertThat(r.getUpdates()).containsEntry("days", "3")
                .doesNotContainKey("totalBudget");
    }

    @Test
    void 短句婉拒标记当前字段为不确定() {
        assertThat(parser.parse("随便", "days")).containsEntry("days", "UNSURE");
        assertThat(parser.parse("还没想好", "totalBudget")).containsEntry("totalBudget", "UNSURE");
    }

    @Test
    void 空消息返回空更新() {
        assertThat(parser.parse("", null)).isEmpty();
        assertThat(parser.parse(null, "days")).isEmpty();
    }

    // ==================== S02 扩展 ====================

    @Test
    void 预算区间优先取中值() {
        assertThat(parser.parse("预算3000-5000元", "totalBudget")).containsEntry("totalBudget", "4000");
    }

    @Test
    void 预算上限不被单值抢先() {
        assertThat(parser.parse("预算不超过5000", "totalBudget")).containsEntry("totalBudget", "5000");
    }

    @Test
    void 人均预算按人数换算全团() {
        RuleParseResult r = parser.parseResult("2人，人均3000", "totalBudget", null);
        assertThat(r.getUpdates()).containsEntry("peopleCount", "2")
                .containsEntry("totalBudget", "6000");
        assertThat(r.getBudget().getScope()).isEqualTo("PER_CAPITA");
    }

    @Test
    void 否定纠正取后值() {
        assertThat(parser.parse("不是3天，是5天", "days")).containsEntry("days", "5");
    }

    @Test
    void 混合意图翻页与条件并存() {
        RuleParseResult r = parser.parseResult("换一批，不要爬山", null, null);
        assertThat(r.getIntents()).contains("PAGING", "CONDITION");
        assertThat(r.getConstraints()).anySatisfy(c -> assertThat(c.getKey()).isEqualTo("avoidClimbing"));
    }

    @Test
    void 未消费残余保留原文() {
        RuleParseResult r = parser.parseResult("要能看极光概率最高的那种住宿", "specialRequests", null);
        assertThat(r.getUnresolvedText()).contains("极光");
        assertThat(r.getUpdates()).isEmpty();
    }

    @Test
    void 部分命中时残余不含已消费片段() {
        RuleParseResult r = parser.parseResult("玩3天，带老人，不能爬山", "days", null);
        assertThat(r.getUpdates()).containsEntry("days", "3");
        assertThat(r.getUpdates().get("specialRequests")).contains("老人", "不能爬山");
        assertThat(r.getUnresolvedText()).isNullOrEmpty();
    }

    // ==================== C3 餐次结构 ====================

    @Test
    void 无范围餐次进入待澄清且不污染每日投影() {
        RuleParseResult r = parser.parseResult("我要2顿午饭1顿晚饭，不需要早餐", null, null);
        assertThat(r.getUpdates())
                .containsEntry("breakfastPerDay", "0")
                .doesNotContainKeys("lunchPerDay", "dinnerPerDay");
        assertThat(r.getConstraints()).filteredOn(c -> c.getInterpretationStatus()
                        == InterpretationStatus.NEEDS_CLARIFICATION)
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.getScope()).isEqualTo(RequirementScope.UNRESOLVED));
        assertThat(r.getUnresolvedText()).isNullOrEmpty();
    }

    @Test
    void 小吃取舍正反两向() {
        assertThat(parser.parse("不要小吃", null)).containsEntry("snacksAllowed", "false");
        assertThat(parser.parse("带点小吃", null)).containsEntry("snacksAllowed", "true");
    }

    @Test
    void 明确每天后才写入旧每日投影且可叠加小吃排除() {
        assertThat(parser.parse("每天2顿午饭1顿晚饭，不要小吃", null))
                .containsEntry("lunchPerDay", "2")
                .containsEntry("dinnerPerDay", "1")
                .containsEntry("snacksAllowed", "false");
    }

    @Test
    void 无顿数的餐次表达不触发餐次规则() {
        RuleParseResult r = parser.parseResult("午饭想吃火锅", null, null);
        assertThat(r.getUpdates()).doesNotContainKeys(
                "lunchPerDay", "dinnerPerDay", "breakfastPerDay", "snacksAllowed");
        assertThat(r.getUnresolvedText()).contains("火锅");
    }

    @Test
    void 问卷起床时间解析() {
        assertThat(parser.parse("9点起床", null)).containsEntry("wakeTime", "09:00");
        assertThat(parser.parse("8点半起来", null)).containsEntry("wakeTime", "08:30");
    }

    @Test
    void 问卷回家截止时间解析() {
        // 口语「10点回家」= 晚上 22:00
        assertThat(parser.parse("晚上10点前回家", null)).containsEntry("returnDeadline", "22:00");
        assertThat(parser.parse("21:30 回酒店", null)).containsEntry("returnDeadline", "21:30");
    }

    @Test
    void 问卷活动倾向与夜景数量解析() {
        assertThat(parser.parse("想早点出发", null)).containsEntry("activityBias", "MORNING");
        assertThat(parser.parse("晚上为主", null)).containsEntry("activityBias", "EVENING");
        assertThat(parser.parse("夜景都要", null)).containsEntry("nightPlan", "ALL");
        assertThat(parser.parse("只看一个夜景", null)).containsEntry("nightPlan", "ONE");
    }
}
