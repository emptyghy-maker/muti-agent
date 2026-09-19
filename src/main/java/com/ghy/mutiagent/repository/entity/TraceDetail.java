package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * S12 追踪明细（含原始文本，受保留期管理）：独立于 P0 operation 账本，
 * 清理不得触碰业务事实。ageDays 为受控老化口径（生产按 created_at 推导）。
 */
@Data
@TableName("t_trace_detail")
public class TraceDetail {
    @TableId
    private String id;
    private String sessionId;
    private Long ownerId;
    private String rawJson;
    private Integer ageDays;
}
