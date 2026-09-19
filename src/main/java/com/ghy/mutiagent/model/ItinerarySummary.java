package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 我的行程列表项 */
@Data
public class ItinerarySummary {
    private Long id;
    private Long destinationId;
    private String destinationName;
    private String title;
    private Integer days;
    private BigDecimal totalCost;
    private Integer version;
    private String status;
    private LocalDateTime createdAt;
}
