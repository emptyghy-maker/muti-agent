package com.ghy.mutiagent.observability.domain;

/** 观测状态字典（docs/observability/01_事件协议与字段字典.md §2） */
public final class ObsStatuses {

    // runStatus：记录执行生命周期
    public static final String RUN_RUNNING = "RUNNING";
    public static final String RUN_COMPLETED = "COMPLETED";
    public static final String RUN_ABORTED = "ABORTED";
    public static final String RUN_UNKNOWN = "UNKNOWN";

    // dataCompleteness：观测完整性，独立于业务结果
    public static final String DATA_COMPLETE = "COMPLETE";
    public static final String DATA_PARTIAL = "PARTIAL";
    public static final String DATA_UNKNOWN = "UNKNOWN";

    // spanStatus
    public static final String SPAN_STARTED = "STARTED";
    public static final String SPAN_SUCCESS = "SUCCESS";
    public static final String SPAN_FAILED = "FAILED";
    public static final String SPAN_CANCELLED = "CANCELLED";

    // usageStatus
    public static final String USAGE_KNOWN = "KNOWN";
    public static final String USAGE_UNKNOWN = "UNKNOWN";
    public static final String USAGE_NOT_APPLICABLE = "NOT_APPLICABLE";

    // amountStatus
    public static final String AMOUNT_ESTIMATED = "ESTIMATED";
    public static final String AMOUNT_SETTLED = "SETTLED";
    public static final String AMOUNT_UNKNOWN = "UNKNOWN";
    public static final String AMOUNT_LEGACY_ESTIMATE = "LEGACY_ESTIMATE";

    // payloadStatus
    public static final String PAYLOAD_FULL = "FULL";
    public static final String PAYLOAD_TRUNCATED = "TRUNCATED";
    public static final String PAYLOAD_REDACTED = "REDACTED";
    public static final String PAYLOAD_EXPIRED = "EXPIRED";

    // sourceSystem
    public static final String SOURCE_NEW = "NEW";
    public static final String SOURCE_RECONCILED = "RECONCILED";
    public static final String SOURCE_LEGACY = "LEGACY";
    public static final String SOURCE_IMPORTED = "IMPORTED";

    private ObsStatuses() {
    }
}
