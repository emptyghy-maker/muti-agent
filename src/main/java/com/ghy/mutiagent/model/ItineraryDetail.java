package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 行程详情（从 DB 加载，刷新不丢） */
@Data
public class ItineraryDetail {
    private Long id;
    private Long destinationId;
    private String destinationName;
    private String title;
    private Integer days;
    private BigDecimal totalBudget;
    private BigDecimal totalCost;
    private String status;
    private Integer version;
    private Long parentId;
    private LocalDateTime createdAt;
    private TravelPreference preference;
    private ItineraryPlan plan;
    /** 由 plan 渲染的文本（含预计消费） */
    private String text;
}
