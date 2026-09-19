package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/** 通用分组汇总（按阶段/渠道/模型等维度，含费用估算） */
@Data
public class UsageGroupSummary {
    /** 维度值：阶段名 / 渠道 / 模型名 */
    private String key;
    private long count;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private long durationMs;
    /** 预估费用（元，未知模型为 null） */
    private BigDecimal cost;
}
