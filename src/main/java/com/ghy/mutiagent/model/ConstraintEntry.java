package com.ghy.mutiagent.model;

import lombok.Data;

/**
 * 需求快照中的一条约束（S02）：key/value 表达语义约束（如 avoidClimbing=TRUE、interest=NIGHT_VIEW）。
 * - hardness：HARD（用户确认）/ SOFT（偏好）/ PROPOSED（模型推断，不得凭置信度提升为 HARD）；
 * - status：ACTIVE / REVOKED（明确撤销目标时标记，历史保留）；
 * - sourceTurnId：产生本条约束的轮次。
 */
@Data
public class ConstraintEntry {
    private String id;
    private String key;
    private String value;
    private String hardness;
    private String status;
    private String source;
    private String sourceTurnId;
    private String originalText;
}
