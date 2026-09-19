package com.ghy.mutiagent.trace;

import dev.langchain4j.model.output.TokenUsage;
import lombok.Data;

/**
 * 单个 Agent 的一次调用记录：耗时 + token 消耗。
 *
 * S12 分层状态：providerStatus（外部调用结果）、parseStatus（输出解析结果）、
 * validationStatus（语义/约束验证结果）、fallbackReason（回退原因）分开记录，
 * 不再用一个 success 表达全部。usageStatus 区分 KNOWN 与 UNKNOWN：
 * 未知用量不得被默认整型 0 消除（失败/超时的调用也可能计费）。
 */
@Data
public class AgentTrace {
    public static final String NOT_ATTEMPTED = "NOT_ATTEMPTED";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String USAGE_KNOWN = "KNOWN";
    public static final String USAGE_UNKNOWN = "UNKNOWN";

    private String agent;          // Agent 名，如 SqlGenerationAgent
    private long durationMs;       // 本次调用耗时（毫秒）
    private int inputTokens;       // 输入 token（usageStatus=UNKNOWN 时不可信，观察口径按 null 输出）
    private int outputTokens;      // 输出 token
    private int totalTokens;       // 总 token
    private boolean success;       // 是否成功
    private String error;          // 失败信息（成功时为空）

    // ---- S12 分层状态 ----
    /** 原始输出片段（截断保存；对外渲染/导出前必须过 Redactor） */
    private String answer;
    private String providerStatus = NOT_ATTEMPTED;
    private String parseStatus = NOT_ATTEMPTED;
    private String validationStatus = NOT_ATTEMPTED;
    private String fallbackReason;
    private String usageStatus = USAGE_UNKNOWN;

    public static AgentTrace success(String agent, long durationMs, TokenUsage usage) {
        AgentTrace t = new AgentTrace();
        t.agent = agent;
        t.durationMs = durationMs;
        t.success = true;
        t.providerStatus = SUCCESS;
        if (usage != null) {
            t.inputTokens = nvl(usage.inputTokenCount());
            t.outputTokens = nvl(usage.outputTokenCount());
            t.totalTokens = nvl(usage.totalTokenCount());
            t.usageStatus = USAGE_KNOWN;
        }
        return t;
    }

    public static AgentTrace failure(String agent, long durationMs, String error) {
        AgentTrace t = new AgentTrace();
        t.agent = agent;
        t.durationMs = durationMs;
        t.success = false;
        t.error = error;
        t.providerStatus = FAILED;
        return t;
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
