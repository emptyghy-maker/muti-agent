package com.ghy.mutiagent.common;

import java.util.List;
import java.util.Map;

/**
 * S08 编排终止信号：修复上限耗尽 / 预算不足 / 截止已过 时，以明确操作终态终止，
 * 而不是通用失败。driveGenerate 捕获后按 {@link #getOpStatus()} 写操作终态
 * （NEEDS_CONFIRMATION / DEADLINE_EXCEEDED），errorDetail 携带原因码与违规码供用户与验收观察。
 */
public class OpAbortException extends BizException {

    /** 操作终态（写入 t_travel_operation.status，如 NEEDS_CONFIRMATION / DEADLINE_EXCEEDED） */
    private final String opStatus;
    private final List<String> reasonCodes;
    private final List<String> violations;
    private final Map<String, Object> evidence;
    /** 面向用户的自定义失败文案（为空表示沿用 ResultCode 文案） */
    private final String customMessage;

    public OpAbortException(ResultCode rc, String opStatus, List<String> reasonCodes,
                            List<String> violations, Map<String, Object> evidence) {
        this(rc, null, opStatus, reasonCodes, violations, evidence);
    }

    /** userMessage 为空时沿用 ResultCode 文案；非空时面向用户给出可操作的失败说明 */
    public OpAbortException(ResultCode rc, String userMessage, String opStatus, List<String> reasonCodes,
                            List<String> violations, Map<String, Object> evidence) {
        super(rc.getCode(), userMessage == null ? rc.getMessage() : userMessage);
        this.opStatus = opStatus;
        this.customMessage = userMessage;
        this.reasonCodes = reasonCodes == null ? List.of() : reasonCodes;
        this.violations = violations == null ? List.of() : violations;
        this.evidence = evidence == null ? Map.of() : evidence;
    }

    public String getOpStatus() {
        return opStatus;
    }

    /** 自定义用户文案（无则为 null，展示侧回退到 ResultCode/通用详情） */
    public String getCustomMessage() {
        return customMessage;
    }

    public List<String> getReasonCodes() {
        return reasonCodes;
    }

    public List<String> getViolations() {
        return violations;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }
}
