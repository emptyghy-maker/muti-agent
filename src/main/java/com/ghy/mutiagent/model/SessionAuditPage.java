package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/** 会话维度审计分页结果 */
@Data
public class SessionAuditPage {
    private long total;
    private List<SessionAuditRow> list = List.of();
}
