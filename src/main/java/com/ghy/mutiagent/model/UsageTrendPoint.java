package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/** 按天用量趋势点（调用次数 / token / 费用） */
@Data
public class UsageTrendPoint {
    private String date;
    private int calls;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private BigDecimal cost = BigDecimal.ZERO;
}
