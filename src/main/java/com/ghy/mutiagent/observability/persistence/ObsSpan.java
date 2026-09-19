package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_span：span 行（乱序不回退状态） */
@Data
@TableName("obs_span")
public class ObsSpan {
    @TableId(type = IdType.INPUT)
    private String spanId;
    private String runId;
    private String parentSpanId;
    private String nodeExecutionId;
    private String kind;
    private String agent;
    private Integer attemptNo;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String summary;
    private String payloadRef;
    private LocalDateTime createdAt;
}
