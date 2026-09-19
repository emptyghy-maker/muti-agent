package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 两点间路径规划结果（超链接点击后展示）；S10 起可携带共享路线事实（与排程/计费同源） */
@Data
public class RouteResult {
    private String fromName;
    private String toName;
    private Double distanceKm;
    private List<RouteOption> options;
    /** S10 共享路线事实：与排程/计费同一快照的 factId / 时长 / 费用 */
    private String factId;
    private Integer durationMin;
    private BigDecimal transportCost;
    private String factKind;
    private String factSource;
    private String factConfidence;
}
