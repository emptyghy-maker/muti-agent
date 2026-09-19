package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 全程消费账单（S05）：预算检查的唯一入口。
 * - lines：全部明细行（含估算项）；
 * - knownSubtotal：已核实费用小计（非估算行之和），超支判断只看它；
 * - totalAmount：明细合计（含估算），用于展示与「细项合计=总额」校验；
 * - unknownCategories：金额未知的类别（如往返大交通），不把 0 元当作未知；
 * - budgetCoverage：FULLY_VERIFIED / UNKNOWN（有未知金额或估算项即为 UNKNOWN）；
 * - overLimit：已核实费用是否超过硬上限（超支阻止发布）。
 */
@Data
public class BudgetBreakdown {
    private List<CostLine> lines;
    private BigDecimal knownSubtotal;
    private BigDecimal totalAmount;
    private List<String> unknownCategories;
    private String budgetCoverage;
    private boolean overLimit;
    private BigDecimal totalBudget;
}
