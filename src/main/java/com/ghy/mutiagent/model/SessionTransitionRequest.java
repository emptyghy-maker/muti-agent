package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 会话控制请求：requestId 用于“重新开始”重试去重，expectedRevision 防止旧页面覆盖新状态；
 * targetStage 仅用于阶段回退，可选 ATTRACTIONS / FOODS / HOTELS / PLAN_QUIZ。
 */
@Data
public class SessionTransitionRequest {
    private String requestId;
    private Long expectedRevision;
    private String targetStage;
}
