package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** obs_usage_attempt：唯一外部尝试 + 价格快照（历史不漂移） */
@Data
@TableName("obs_usage_attempt")
public class ObsUsageAttempt {
    @TableId(type = IdType.INPUT)
    private String attemptKey;
    private String runId;
    private String spanId;
    private String sourceSystem;
    private String agent;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private String usageStatus;
    private Long durationMs;
    private BigDecimal costAmount;
    private String currency;
    private String amountStatus;
    private String priceSnapshot;
    private LocalDateTime recordedAt;
}
