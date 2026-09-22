package com.ghy.mutiagent.model;

import com.ghy.mutiagent.model.requirement.InterpretationStatus;
import com.ghy.mutiagent.model.requirement.RequirementOperator;
import com.ghy.mutiagent.model.requirement.RequirementScope;
import com.ghy.mutiagent.model.requirement.RequirementSubject;
import com.ghy.mutiagent.model.requirement.RequirementUnit;
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
    /** 单条需求版本；同一逻辑需求更正时递增，旧条目保留为 SUPERSEDED。 */
    private int revision = 1;
    private String key;
    private String value;
    private String hardness;
    private String status;
    private String source;
    private String sourceTurnId;
    private String originalText;
    /** O2 结构化需求字段；旧快照允许为空，由兼容层按 LEGACY 处理。 */
    private RequirementSubject subject;
    private Integer count;
    private RequirementOperator operator;
    private RequirementUnit unit;
    private RequirementScope scope;
    private InterpretationStatus interpretationStatus;
    /** 本条更正所替代的旧需求 ID。 */
    private String supersedesId;
    /** 解析/澄清/不支持原因，供测试和证据链使用。 */
    private String reasonCode;
}
