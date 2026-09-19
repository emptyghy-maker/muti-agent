package com.ghy.mutiagent.service;

/**
 * S08 操作预算配置：整个 operation 共用的调用与 token 预算。
 * 候选、Planner、Repair、分块以及 SDK 传输重试都不得另起预算。
 * S11：chunkDays 为长行程分块粒度（每块一次规划调用，请求天数 ≤ chunkDays）；
 * 行程天数 > chunkDays 时启用分块。默认值是生产推荐口径（短规划：最多 1 次规划
 * + 2 次修复；3 天一块）；门禁用例注入的 1000ms/1000token 只是测试参数，不是生产推荐值。
 */
public record OperationBudgetConfig(int maxRepairs, int maxModelCalls,
                                    long tokenBudget, long maxOutputTokens, long deadlineMs,
                                    int chunkDays) {

    public OperationBudgetConfig {
        if (maxRepairs < 0 || maxModelCalls < 1 || tokenBudget < 0 || maxOutputTokens < 0
                || deadlineMs <= 0 || chunkDays < 1) {
            throw new IllegalArgumentException("非法预算配置: " + maxRepairs + "/" + maxModelCalls
                    + "/" + tokenBudget + "/" + maxOutputTokens + "/" + deadlineMs + "/" + chunkDays);
        }
    }

    public static OperationBudgetConfig defaults() {
        // deadline 必须 ≥ 单次模型调用超时 × 最大调用数：规划调用超时已放宽到 300s
        // （LlmConfig.sqlChatModel），maxModelCalls=3 → 3×300s。若 deadline 小于单次调用超时，
        // 慢但成功的规划会在「提交前复查」被误杀（表现为生成成功后仍报「操作超出截止时间」）。
        return new OperationBudgetConfig(2, 3, 200_000L, 4096L, 900_000L, 3);
    }
}
