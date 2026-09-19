package com.ghy.mutiagent.model;

import java.math.BigDecimal;

/**
 * 账单明细行（S05）：类别（门票/餐费/住宿/交通）、引用地点、单价、数量、金额、是否估算。
 * 金额统一 BigDecimal；估算行不计入已核实小计；金额未知的项目不进明细，只进未知类别集合。
 */
public record CostLine(String category, String reference, BigDecimal unitPrice,
                       BigDecimal quantity, BigDecimal amount, boolean estimated) {
}
