package com.ghy.mutiagent.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 终态事件持久化行（手册 §6）：eventId 幂等主键，重启后恢复与去重的唯一依据 */
@Data
@TableName("t_terminal_event")
public class TerminalEvent {
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String operationId;
    private String status;
    private Long durationMs;
    private String payload;
    private LocalDateTime createdAt;
}
