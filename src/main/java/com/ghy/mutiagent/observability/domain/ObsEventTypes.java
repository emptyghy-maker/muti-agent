package com.ghy.mutiagent.observability.domain;

/** 事件类型字典（埋点契约，与 docs/observability/05_埋点清单.md 对应） */
public final class ObsEventTypes {

    public static final String SPAN_STARTED = "SPAN_STARTED";
    public static final String SPAN_FINISHED = "SPAN_FINISHED";
    public static final String OPERATION_STARTED = "OPERATION_STARTED";
    public static final String OPERATION_FINISHED = "OPERATION_FINISHED";
    public static final String NODE_STARTED = "NODE_STARTED";
    public static final String NODE_ENDED = "NODE_ENDED";
    public static final String USER_CONFIRMED = "USER_CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String VALIDATION = "VALIDATION";
    public static final String REPAIR_ROUND = "REPAIR_ROUND";
    public static final String PATCH_PROPOSED = "PATCH_PROPOSED";
    public static final String PATCH_REJECTED = "PATCH_REJECTED";
    public static final String COMMIT_RESULT = "COMMIT_RESULT";
    public static final String USAGE_ATTEMPT = "USAGE_ATTEMPT";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String TOOL_REJECTED = "TOOL_REJECTED";
    public static final String REPLAY = "REPLAY";
    public static final String RECONCILED = "RECONCILED";
    public static final String GAP = "GAP";

    private ObsEventTypes() {
    }
}
