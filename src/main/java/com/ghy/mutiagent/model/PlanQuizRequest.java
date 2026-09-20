package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 行程偏好问卷提交（PLAN_QUIZ 阶段）。
 * wakeTime：HH:mm（05:00-12:00）；returnDeadline：HH:mm（17:00-24:00）或 UNLIMITED；
 * activityBias：MORNING / BALANCED / EVENING；nightPlan：ONE / ALL。
 * 后两者仅当编排器判定需要提问时才校验必填。
 */
@Data
public class PlanQuizRequest {
    private String sessionId;
    private String wakeTime;
    private String returnDeadline;
    private String activityBias;
    private String nightPlan;
}
