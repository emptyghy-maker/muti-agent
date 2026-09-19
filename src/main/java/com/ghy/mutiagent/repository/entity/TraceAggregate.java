package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** S12 追踪聚合（安全计数，不含原文）：保留期清理后仍可报告规模指标 */
@Data
@TableName("t_trace_aggregate")
public class TraceAggregate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long ownerId;
    private String status;
    private Integer recordCount;
    private Long durationMs;
}
