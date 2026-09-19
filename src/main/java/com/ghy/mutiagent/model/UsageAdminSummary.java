package com.ghy.mutiagent.model;

import lombok.Data;

import java.util.List;

/** 管理员多维汇总：按用户 / 模型 / 阶段 / 渠道 */
@Data
public class UsageAdminSummary {
    private List<UsageUserSummary> byUser = List.of();
    private List<UsageGroupSummary> byModel = List.of();
    private List<UsageGroupSummary> byStage = List.of();
    private List<UsageGroupSummary> byChannel = List.of();
}
