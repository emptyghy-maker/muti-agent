package com.ghy.mutiagent.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 失败告警行：窗口内有失败记录的会话（含失败次数与最近失败详情） */
@Data
public class UsageAlertRow {
    private String sessionId;
    private String username;
    private String action;
    private String model;
    private String remark;
    private int failCount;
    private LocalDateTime lastFailedAt;
}
