package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 一种交通方案 */
@Data
public class RouteOption {
    private String mode;
    private Integer durationMin;
    private BigDecimal cost;
    private List<String> steps;
}
