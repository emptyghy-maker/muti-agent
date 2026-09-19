package com.ghy.mutiagent.service.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 指标与费用口径计算器（手册 §4）：分母先写进契约，分子/分母/未知数/排除原因同时上报。
 *
 * - 业务完成率：COMMITTED 逻辑操作数 / 纳入实验的逻辑操作数；失败、取消计入分母，幂等重放去重；
 * - 成功单位成本：实验全部已知费用 / 成功计划数；失败及重试费用必须计入；
 *   成功数为 0 时单位成功成本为 null；有未知费用时已知合计只能作为下界（costComplete=false）；
 * - 硬约束通过率：无违规受评计划数 / 有完整判据的受评计划数；缺判据另报 missingCriteriaCount；
 * - 换批/越界：越界修改数 / 已受评局部修改数；再报越界节点数；
 * - 离线 P95 使用 nearest-rank：排序后取第 ceil(0.95*n) 个值；并行 span 的工作量求和，
 *   但不等于墙钟总耗时（operationWallMs 单独上报）。
 */
public final class MetricsCalculator {

    public static final String P95_ALGORITHM = "NEAREST_RANK";

    /** 权威终态集合：同一 operation 的乱序日志中，终态（版本相同时）胜过运行中状态 */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMMITTED", "FAILED", "CANCELLED", "NEEDS_CONFIRMATION", "DEADLINE_EXCEEDED");

    /** O1.2 物理 attempt 事件：三层状态分层（provider/parse/validation），usage/cost 可缺失。
     *  usageKnown=false 或 costKnown=false 表示 UNKNOWN——缺失不是 0，聚合时单独上报。 */
    public record AttemptEvent(String logicalTaskId, String operationId, String attemptId,
                               String arm, String sampleId, Integer repeatIndex,
                               String model, String promptHash, String paramsHash, String contextHash,
                               Long startedAtMs, Long endedAtMs,
                               Integer inputTokens, Integer outputTokens, boolean usageKnown,
                               String providerStatus, String parseStatus, String validationStatus,
                               BigDecimal cost, boolean costKnown, String error) {
    }

    /** 任务级聚合：墙钟用真实 startedAt/endedAt；token 任一 attempt 缺失则整体 PARTIAL（null） */
    public record TaskAggregate(String logicalTaskId, int attemptCount, Long wallMs,
                                Integer inputTokens, Integer outputTokens, boolean usageComplete,
                                BigDecimal knownCost, int unknownCostAttempts,
                                boolean success, int failedAttempts) {
    }

    /** 跨度事件：provider 工作量只计 PROVIDER 叶子，父 span 不重复算 */
    public record SpanEvent(String spanId, String parentSpanId, String kind,
                            long durationMs, Long startMs, Long endMs) {
    }

    private MetricsCalculator() {
    }

    /** 逻辑操作事件：同一 operationId 重复出现即幂等重放（不新增分母）。
     *  version 用于乱序日志下的权威归并：版本高者胜；同版本时终态（COMMITTED/FAILED 等）胜过运行中 */
    public record LogicalOperationEvent(String operationId, String status, boolean replay, long version) {
        public LogicalOperationEvent(String operationId, String status, boolean replay) {
            this(operationId, status, replay, 0L);
        }
    }

    /** 一次 provider 尝试的费用事件：真实重试是不同的尝试，必须计费；
     *  attemptId 非空时同 (operationId, attemptId) 幂等去重（重放不重复计费） */
    public record CostAttemptEvent(String operationId, String attemptId, BigDecimal cost,
                                   boolean success) {
        public CostAttemptEvent(String operationId, BigDecimal cost, boolean success) {
            this(operationId, null, cost, success);
        }
    }

    /** 受评计划：hardViolations 为 null 表示无完整判据（不进分母，单独上报缺失） */
    public record EvaluatedPlanEvent(String planId, Integer hardViolations) {
    }

    /** 局部修改：outsideChangedNodes 为改动非目标区域的节点数 */
    public record PatchOutcomeEvent(String patchId, int outsideChangedNodes) {
    }

    /** 逻辑操作口径 */
    public record OperationMetrics(int operationCount, int successCount, Double successRate,
                                   int replayedCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operationCount", operationCount);
            m.put("successCount", successCount);
            m.put("successRate", successRate);
            m.put("replayedCount", replayedCount);
            return m;
        }
    }

    /** 费用口径 */
    public record CostMetrics(BigDecimal totalKnownCost, int successfulPlanCount,
                              BigDecimal costPerSuccessfulPlan, int unknownCostCount,
                              boolean costComplete, int totalAttempts) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalCost", totalKnownCost == null ? null : totalKnownCost.setScale(2, RoundingMode.HALF_UP).toPlainString());
            m.put("successfulPlanCount", successfulPlanCount);
            m.put("costPerSuccessfulPlan", costPerSuccessfulPlan == null ? null
                    : costPerSuccessfulPlan.setScale(2, RoundingMode.HALF_UP).toPlainString());
            m.put("unknownCostCount", unknownCostCount);
            m.put("costComplete", costComplete);
            m.put("totalAttempts", totalAttempts);
            return m;
        }
    }

    /** 约束与局部调整口径 */
    public record ConstraintMetrics(int hardPassNumerator, int hardPassDenominator,
                                    int missingCriteriaCount, Double patchViolationRate,
                                    int outsideChangedNodes, int patchCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hardPassNumerator", hardPassNumerator);
            m.put("hardPassDenominator", hardPassDenominator);
            m.put("missingCriteriaCount", missingCriteriaCount);
            m.put("patchViolationRate", patchViolationRate);
            m.put("outsideChangedNodes", outsideChangedNodes);
            m.put("patchCount", patchCount);
            return m;
        }
    }

    /** 耗时口径：并行 span 工作量求和（providerWorkMs）不等于墙钟（operationWallMs） */
    public record LatencyMetrics(int sampleCount, Long p95Ms, long providerWorkMs,
                                 long operationWallMs, long queueMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sampleCount", sampleCount);
            m.put("p95Ms", p95Ms);
            m.put("providerWorkMs", providerWorkMs);
            m.put("operationWallMs", operationWallMs);
            m.put("queueMs", queueMs);
            m.put("p95Algorithm", P95_ALGORITHM);
            return m;
        }
    }

    /** 逻辑操作聚合：按 operationId 去重；乱序日志用权威归并（版本高者胜，同版本终态胜过运行中） */
    public static OperationMetrics operations(List<LogicalOperationEvent> events) {
        Map<String, LogicalOperationEvent> best = new LinkedHashMap<>();
        int replayed = 0;
        if (events != null) {
            for (LogicalOperationEvent e : events) {
                if (e == null || e.operationId() == null || e.operationId().isBlank()) {
                    continue;
                }
                if (e.replay()) {
                    replayed++;
                }
                LogicalOperationEvent cur = best.get(e.operationId());
                if (cur == null) {
                    best.put(e.operationId(), e);
                    continue;
                }
                boolean curTerminal = TERMINAL_STATUSES.contains(cur.status());
                boolean eTerminal = TERMINAL_STATUSES.contains(e.status());
                if (e.version() > cur.version()
                        || (e.version() == cur.version() && eTerminal && !curTerminal)) {
                    best.put(e.operationId(), e);
                }
            }
        }
        int success = 0;
        for (LogicalOperationEvent e : best.values()) {
            if ("COMMITTED".equals(e.status())) {
                success++;
            }
        }
        Double rate = best.isEmpty() ? null : (double) success / best.size();
        return new OperationMetrics(best.size(), success, rate, replayed);
    }

    /** 费用聚合：全部已知费用（含失败与重试）为分子；
     *  (operationId, attemptId) 幂等去重；分母 = 成功计划去重（按 operationId 计任务，重试不新增分母）；
     *  未知费用单独计数并置 costComplete=false */
    public static CostMetrics costs(List<CostAttemptEvent> attempts) {
        BigDecimal known = BigDecimal.ZERO;
        int unknown = 0;
        int total = 0;
        Set<String> attemptKeys = new LinkedHashSet<>();
        Set<String> successfulOps = new LinkedHashSet<>();
        if (attempts != null) {
            for (CostAttemptEvent a : attempts) {
                if (a == null) {
                    continue;
                }
                if (a.attemptId() != null && !attemptKeys.add(a.operationId() + "#" + a.attemptId())) {
                    continue; // 同 operation 同 attempt 的重放：不重复计费
                }
                total++;
                if (a.success()) {
                    successfulOps.add(a.operationId());
                }
                if (a.cost() == null) {
                    unknown++;
                } else {
                    known = known.add(a.cost());
                }
            }
        }
        BigDecimal unit = successfulOps.isEmpty()
                ? null : known.divide(BigDecimal.valueOf(successfulOps.size()), 2, RoundingMode.HALF_UP);
        return new CostMetrics(known, successfulOps.size(), unit, unknown, unknown == 0, total);
    }

    /** 约束与局部调整聚合：有完整判据的计划为分母，越界修改数为分子 */
    public static ConstraintMetrics constraints(List<EvaluatedPlanEvent> plans,
                                                List<PatchOutcomeEvent> patches) {
        int pass = 0;
        int denominator = 0;
        int missing = 0;
        if (plans != null) {
            for (EvaluatedPlanEvent p : plans) {
                if (p == null) {
                    continue;
                }
                if (p.hardViolations() == null) {
                    missing++;
                    continue;
                }
                denominator++;
                if (p.hardViolations() == 0) {
                    pass++;
                }
            }
        }
        int patchCount = 0;
        int violating = 0;
        int outsideNodes = 0;
        if (patches != null) {
            for (PatchOutcomeEvent m : patches) {
                if (m == null) {
                    continue;
                }
                patchCount++;
                outsideNodes += m.outsideChangedNodes();
                if (m.outsideChangedNodes() > 0) {
                    violating++;
                }
            }
        }
        Double rate = patchCount == 0 ? null : (double) violating / patchCount;
        return new ConstraintMetrics(pass, denominator, missing, rate, outsideNodes, patchCount);
    }

    /** 耗时聚合：nearest-rank P95（ceil(0.95*n) 第 k 个，k 从 1 起），并行工作量求和与墙钟分开 */
    public static LatencyMetrics latency(List<Long> durationsMs, List<Long> parallelSpanMs,
                                         long operationWallMs, long queueMs) {
        List<Long> sorted = new ArrayList<>();
        if (durationsMs != null) {
            for (Long d : durationsMs) {
                if (d != null && d >= 0) {
                    sorted.add(d);
                }
            }
        }
        sorted.sort(Long::compareTo);
        Long p95 = null;
        if (!sorted.isEmpty()) {
            int k = (int) Math.ceil(0.95 * sorted.size());
            if (k < 1) {
                k = 1;
            }
            p95 = sorted.get(k - 1);
        }
        long work = 0;
        if (parallelSpanMs != null) {
            for (Long s : parallelSpanMs) {
                if (s != null && s > 0) {
                    work += s;
                }
            }
        }
        return new LatencyMetrics(sorted.size(), p95, work, operationWallMs, queueMs);
    }

    /** O1.3：attempt 重放去重——attemptId 非空时按 (operationId, attemptId) 幂等；无 attemptId 的记录逐条保留 */
    public static List<AttemptEvent> dedupeAttempts(List<AttemptEvent> attempts) {
        List<AttemptEvent> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (attempts != null) {
            for (AttemptEvent a : attempts) {
                if (a == null) {
                    continue;
                }
                if (a.attemptId() == null
                        || seen.add(a.operationId() + "#" + a.attemptId())) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    /** O1.2：任务级聚合——一条任务展开到全部物理 attempt。
     *  墙钟 = max(endedAt) - min(startedAt)（真实起止）；任一 attempt 用量缺失 → 用量 PARTIAL（null）；
     *  任务成功 = 任一 attempt validationStatus==SUCCESS（provider/parse 成功不代表任务成功）。 */
    public static List<TaskAggregate> taskAggregates(List<AttemptEvent> attempts) {
        Map<String, List<AttemptEvent>> groups = new LinkedHashMap<>();
        if (attempts != null) {
            for (AttemptEvent a : attempts) {
                if (a == null || a.logicalTaskId() == null) {
                    continue;
                }
                groups.computeIfAbsent(a.logicalTaskId(), k -> new ArrayList<>()).add(a);
            }
        }
        List<TaskAggregate> out = new ArrayList<>();
        for (Map.Entry<String, List<AttemptEvent>> e : groups.entrySet()) {
            List<AttemptEvent> list = e.getValue();
            long minStart = Long.MAX_VALUE;
            long maxEnd = Long.MIN_VALUE;
            boolean wallComplete = true;
            int inTokens = 0;
            int outTokens = 0;
            boolean usageComplete = true;
            BigDecimal cost = BigDecimal.ZERO;
            int unknownCost = 0;
            boolean success = false;
            int failed = 0;
            for (AttemptEvent a : list) {
                if (a.startedAtMs() == null || a.endedAtMs() == null) {
                    wallComplete = false;
                } else {
                    minStart = Math.min(minStart, a.startedAtMs());
                    maxEnd = Math.max(maxEnd, a.endedAtMs());
                }
                if (a.usageKnown() && a.inputTokens() != null && a.outputTokens() != null) {
                    inTokens += a.inputTokens();
                    outTokens += a.outputTokens();
                } else {
                    usageComplete = false;
                }
                if (a.costKnown() && a.cost() != null) {
                    cost = cost.add(a.cost());
                } else {
                    unknownCost++;
                }
                if ("SUCCESS".equals(a.validationStatus())) {
                    success = true;
                }
                if ("FAILED".equals(a.providerStatus())) {
                    failed++;
                }
            }
            out.add(new TaskAggregate(e.getKey(), list.size(),
                    wallComplete ? (maxEnd - minStart) : null,
                    usageComplete ? inTokens : null, usageComplete ? outTokens : null,
                    usageComplete, cost, unknownCost, success, failed));
        }
        return out;
    }

    /** O1.3：provider 工作量 = PROVIDER 叶子 span 耗时之和（父/OPERATION span 不重复算） */
    public static long providerWorkloadMs(List<SpanEvent> spans) {
        long sum = 0;
        if (spans != null) {
            for (SpanEvent s : spans) {
                if (s != null && "PROVIDER".equals(s.kind()) && s.durationMs() > 0) {
                    sum += s.durationMs();
                }
            }
        }
        return sum;
    }

    /** O1.3：根 span 墙钟 = 根（无 parentSpanId）真实起止差；无起止时退回 durationMs；无根返回 null */
    public static Long rootWallMs(List<SpanEvent> spans) {
        if (spans == null) {
            return null;
        }
        for (SpanEvent s : spans) {
            if (s != null && (s.parentSpanId() == null || s.parentSpanId().isBlank())) {
                if (s.startMs() != null && s.endMs() != null) {
                    return s.endMs() - s.startMs();
                }
                return s.durationMs();
            }
        }
        return null;
    }

    /** O1.3：attempt 级费用口径（复用 costs 的 (operationId, attemptId) 去重与任务级分母）；
     *  任务成功判定与 taskAggregates 一致（任一 attempt validationStatus==SUCCESS） */
    public static CostMetrics costsFromAttempts(List<AttemptEvent> attempts) {
        List<CostAttemptEvent> events = new ArrayList<>();
        if (attempts != null) {
            for (AttemptEvent a : attempts) {
                if (a == null) {
                    continue;
                }
                events.add(new CostAttemptEvent(a.operationId(), a.attemptId(),
                        a.costKnown() ? a.cost() : null,
                        "SUCCESS".equals(a.validationStatus())));
            }
        }
        return costs(events);
    }
}
