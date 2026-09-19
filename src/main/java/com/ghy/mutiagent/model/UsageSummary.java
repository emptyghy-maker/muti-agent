package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/** 用量汇总：按阶段统计调用次数与 token 消耗（含费用估算） */
@Data
public class UsageSummary {
    private String stage;
    private long count;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private long totalDurationMs;
    /** 预估费用（元，未知模型为 null） */
    private BigDecimal totalCost;
}
