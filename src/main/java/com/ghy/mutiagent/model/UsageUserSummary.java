package com.ghy.mutiagent.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 按用户汇总（管理员视角：谁用了多少、花了多少钱、最近何时活跃） */
@Data
public class UsageUserSummary {
    private Long userId;
    private String username;
    private long count;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private long durationMs;
    private BigDecimal cost;
    private LocalDateTime lastActiveAt;
}
