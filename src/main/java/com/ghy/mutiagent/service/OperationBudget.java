package com.ghy.mutiagent.service;

import java.util.ArrayList;
import java.util.List;

/**
 * S08 共享操作预算：一次 operation 内 Planner 与 Repair 共用调用配额与 token 额度。
 *
 * - deadline 在预算创建（执行起点）锚定，单调时钟衡量，调用前/后/修复前/提交前都要复查；
 * - 调用前原子预留「输入估计值 + 本次最大输出」，剩余额度不足就不发请求；
 * - 调用后用实际 usage 结算；usage 未知（提供方已响应但无计量）保留预留并标记待核对，不按零扣除；
 * - 原因码（DEADLINE_EXCEEDED / TOKEN_BUDGET_EXHAUSTED / MODEL_CALLS_EXCEEDED）供终止语义与验收观察。
 */
public class OperationBudget {

    public static final String REASON_DEADLINE = "DEADLINE_EXCEEDED";
    public static final String REASON_TOKEN_BUDGET = "TOKEN_BUDGET_EXHAUSTED";
    public static final String REASON_MODEL_CALLS = "MODEL_CALLS_EXCEEDED";

    /** 一次调用的预留额度：实际结算前占用（结算或标记未知前不得释放） */
    public record Reserve(long reserved) {
    }

    private final TimeSource time;
    private final OperationBudgetConfig config;
    private final long deadlineNanos;

    private int callsStarted;
    private long settledTokens;
    /** 已预留未结算（含未知用量挂账），结算时先释放预留再记实际 */
    private long reservedTokens;
    private final List<String> reasonCodes = new ArrayList<>();

    public OperationBudget(TimeSource time, OperationBudgetConfig config) {
        this.time = time;
        this.config = config;
        this.deadlineNanos = time.nanoTime() + config.deadlineMs() * 1_000_000L;
    }

    public OperationBudgetConfig config() {
        return config;
    }

    /** 已越过截止：此后不得再发起任何提供方调用 */
    public synchronized boolean deadlineExceeded() {
        return time.nanoTime() > deadlineNanos;
    }

    /** 剩余可结算额度（含未决预留） */
    public synchronized long remainingTokens() {
        return config.tokenBudget() - settledTokens - reservedTokens;
    }

    public synchronized int callsStarted() {
        return callsStarted;
    }

    public synchronized List<String> reasonCodes() {
        return new ArrayList<>(reasonCodes);
    }

    /**
     * 原子预留：截止 / 调用配额 / token 额度任一不满足即返回 null 并记录原因（不发请求）。
     * 预留 = 输入估计 + 本次最大输出。
     */
    public synchronized Reserve reserveCall(long estimatedInputTokens) {
        if (deadlineExceeded()) {
            reasonCodes.add(REASON_DEADLINE);
            return null;
        }
        if (callsStarted >= config.maxModelCalls()) {
            reasonCodes.add(REASON_MODEL_CALLS);
            return null;
        }
        long need = estimatedInputTokens + config.maxOutputTokens();
        if (need > config.tokenBudget() - settledTokens - reservedTokens) {
            reasonCodes.add(REASON_TOKEN_BUDGET);
            return null;
        }
        callsStarted++;
        reservedTokens += need;
        return new Reserve(need);
    }

    /** 按实际用量结算：actualTokens==null 表示提供方已响应但计量未知——保留预留（挂账待核对） */
    public synchronized void settle(Reserve reserve, Long actualTokens) {
        if (reserve == null) {
            return;
        }
        reservedTokens -= reserve.reserved();
        if (actualTokens == null) {
            // 未知用量：保留预留，不按零扣除（避免超发）
            reservedTokens += reserve.reserved();
            return;
        }
        settledTokens += actualTokens;
    }
}
