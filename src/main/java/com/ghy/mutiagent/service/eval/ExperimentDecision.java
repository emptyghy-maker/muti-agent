package com.ghy.mutiagent.service.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对照实验决策引擎（手册 §5.2）：预先定义的决策规则，输出
 * ELIGIBLE_FOR_REVIEW / REJECT / INCONCLUSIVE，自动门禁不直接发布。
 *
 * 检查顺序（先证据、后质量、再成本）：
 * - 证据不足：成对样本量不足、存在缺失运行、费用未知 → INCONCLUSIVE（不能算通过）；
 * - 硬约束不得回退：候选硬违规数高于基线 → REJECT；
 * - 单位成功成本上限：候选/基线比值超过 maxCostRatio → REJECT；
 * - 质量最低改善：提升低于 minimumImprovement → REJECT；
 * - 全部通过 → ELIGIBLE_FOR_REVIEW（仅进入人工审查，published 恒为 false）。
 *
 * 测试中的 maxCostRatio=1.2、requiredPairs=30 等仅为验证决策分支的输入，
 * 不是项目通用生产标准；实际门槛需在查看实验结果前登记。
 */
public final class ExperimentDecision {

    public static final String ELIGIBLE_FOR_REVIEW = "ELIGIBLE_FOR_REVIEW";
    public static final String REJECT = "REJECT";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    public static final String HARD_CONSTRAINT_REGRESSION = "HARD_CONSTRAINT_REGRESSION";
    public static final String COST_LIMIT_EXCEEDED = "COST_LIMIT_EXCEEDED";
    public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    public static final String UNKNOWN_COST = "UNKNOWN_COST";
    public static final String INSUFFICIENT_IMPROVEMENT = "INSUFFICIENT_IMPROVEMENT";

    private ExperimentDecision() {
    }

    /** 一臂汇总：质量分、硬违规数、单位成功成本 */
    public record ArmSummary(Double score, Integer hardViolations, BigDecimal costPerSuccess) {

        public static ArmSummary fromMap(Map<String, Object> m) {
            if (m == null) {
                return new ArmSummary(null, null, null);
            }
            return new ArmSummary(
                    asDouble(m.get("score")),
                    m.get("hardViolations") == null ? null : ((Number) m.get("hardViolations")).intValue(),
                    m.get("costPerSuccess") == null ? null : new BigDecimal(String.valueOf(m.get("costPerSuccess"))));
        }

        private static Double asDouble(Object v) {
            return v == null ? null : ((Number) v).doubleValue();
        }
    }

    /** 预注册判据 + 观测结果；未提供的判据字段按「不设限」处理 */
    public record DecisionInput(Integer observedPairs, Integer requiredPairs,
                                Integer missingRuns, Integer unknownCostCount,
                                ArmSummary baseline, ArmSummary candidate,
                                Double minimumImprovement, BigDecimal maxCostRatio,
                                List<Double> pairedImprovementInterval,
                                String providerMode, boolean autoPublish) {
    }

    public record DecisionResult(String decision, List<String> reasonCodes, boolean published) {

        public DecisionResult {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", decision);
            m.put("reasonCodes", reasonCodes);
            m.put("published", published);
            return m;
        }
    }

    public static DecisionResult decide(DecisionInput in) {
        List<String> codes = new ArrayList<>();

        // 证据门（先于质量与成本）：不足/缺失/未知费用一律 INCONCLUSIVE，不能算通过
        if (in.requiredPairs() != null && in.observedPairs() != null
                && in.observedPairs() < in.requiredPairs()) {
            addOnce(codes, INSUFFICIENT_EVIDENCE);
        }
        if (in.missingRuns() != null && in.missingRuns() > 0) {
            addOnce(codes, INSUFFICIENT_EVIDENCE);
        }
        if (in.unknownCostCount() != null && in.unknownCostCount() > 0) {
            addOnce(codes, UNKNOWN_COST);
        }
        if (!codes.isEmpty()) {
            return new DecisionResult(INCONCLUSIVE, codes, false);
        }

        // 硬约束不得回退
        if (in.baseline() != null && in.candidate() != null
                && in.baseline().hardViolations() != null && in.candidate().hardViolations() != null
                && in.candidate().hardViolations() > in.baseline().hardViolations()) {
            addOnce(codes, HARD_CONSTRAINT_REGRESSION);
        }

        // 单位成功成本上限（候选/基线 > 容忍度）
        if (in.maxCostRatio() != null
                && in.baseline() != null && in.candidate() != null
                && in.baseline().costPerSuccess() != null && in.candidate().costPerSuccess() != null
                && in.baseline().costPerSuccess().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = in.candidate().costPerSuccess()
                    .divide(in.baseline().costPerSuccess(), 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(in.maxCostRatio()) > 0) {
                addOnce(codes, COST_LIMIT_EXCEEDED);
            }
        }

        // 质量最低改善
        if (in.minimumImprovement() != null
                && in.baseline() != null && in.candidate() != null
                && in.baseline().score() != null && in.candidate().score() != null) {
            double improvement = in.candidate().score() - in.baseline().score();
            if (improvement < in.minimumImprovement()) {
                addOnce(codes, INSUFFICIENT_IMPROVEMENT);
            }
        }

        if (!codes.isEmpty()) {
            return new DecisionResult(REJECT, codes, false);
        }
        // 满足预注册判据：只进入人工审查，不自动发布
        return new DecisionResult(ELIGIBLE_FOR_REVIEW, codes, false);
    }

    private static void addOnce(List<String> codes, String code) {
        if (!codes.contains(code)) {
            codes.add(code);
        }
    }
}
