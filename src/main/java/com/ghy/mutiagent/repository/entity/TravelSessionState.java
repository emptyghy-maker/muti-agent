package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话权威快照（S06-B）：P0 阶段所有需要正确性的会话读写以本表为准，
 * Redis 仅作可失效缓存。revision 由操作协议（T2）递增，active_operation_id
 * 标记当前占用会话的操作；expires_at 沿用 2 小时业务有效期。
 */
@Data
@TableName("t_travel_session_state")
public class TravelSessionState {
    @TableId
    private String sessionId;
    private Long userId;
    private Long revision;
    private Integer schemaVersion;
    private String stage;
    private String stateJson;
    private String activeOperationId;
    private LocalDateTime expiresAt;
    private LocalDateTime updatedAt;
}
