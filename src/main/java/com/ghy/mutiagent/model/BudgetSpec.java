package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 预算口径（S02）：min/max 为区间、target 为兼容中值，
 * scope = GROUP_TRIP（全团）/ PER_CAPITA（人均）。
 */
@Data
public class BudgetSpec {
    private BigDecimal min;
    private BigDecimal max;
    private BigDecimal target;
    private String scope;
}
