package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/**
 * 结构化行程（LLM 输出骨架，Java 侧回填名称、校验、补餐点/休息点、算消费）。
 * stays/budgetBreakdown 为 S05 住宿夜次与全程账单，随 plan_json 落库；旧行程无这两个字段。
 */
@Data
public class ItineraryPlan {
    private List<DailyPlan> days;
    private List<StayBooking> stays;
    private BudgetBreakdown budgetBreakdown;
}
