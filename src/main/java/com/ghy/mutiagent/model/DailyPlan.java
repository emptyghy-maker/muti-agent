package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 单日行程 */
@Data
public class DailyPlan {
    private int dayIndex;
    /** 当日主题，如「抵达 + 老门东夜景」 */
    private String theme;
    /** 综合劳累分（Java 计算） */
    private double fatigueScore;
    /** 当日预计消费（Java 计算） */
    private BigDecimal estimatedCost;
    private List<PlanNode> nodes;
}
