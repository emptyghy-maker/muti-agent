package com.ghy.mutiagent.observability.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** obs_event：事件行（eventId 幂等主键；摘要为脱敏 JSON） */
@Data
@TableName("obs_event")
public class ObsEventRow {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String runId;
    private String operationId;
    private String spanId;
    private String parentSpanId;
    private String nodeExecutionId;
    private String eventType;
    private Long occurredAt;
    private Long receivedAt;
    private Long ownerId;
    private String schemaVersion;
    private String producerId;
    private Long sequenceNo;
    private String digest;
    private String payloadRef;
    private String summaryJson;
    private LocalDateTime createdAt;
}
