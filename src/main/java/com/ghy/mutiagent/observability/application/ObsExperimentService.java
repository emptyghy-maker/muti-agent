package com.ghy.mutiagent.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ghy.mutiagent.common.BizException;
import com.ghy.mutiagent.common.ResultCode;
import com.ghy.mutiagent.observability.application.ObsViews.ComparisonView;
import com.ghy.mutiagent.observability.persistence.ObsExperiment;
import com.ghy.mutiagent.observability.persistence.ObsExperimentMapper;
import com.ghy.mutiagent.observability.persistence.ObsExperimentResult;
import com.ghy.mutiagent.observability.persistence.ObsExperimentResultMapper;
import com.ghy.mutiagent.observability.security.ObsScope;
import com.ghy.mutiagent.security.AuthenticatedUser;
import com.ghy.mutiagent.service.eval.ExperimentDecision;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实验导入与对照（开发文档 §14）：只读取/导入既有评测结果，不执行模型、不改判据/标签/执行器。
 * 导入 JSON 字段白名单校验，不执行文件内命令；配对键 datasetHash+sampleId+repeatIndex+arm；
 * 缺失、跳过、未配对、费用未知保留（missingFlag），缺失样本不算通过；
 * STUB 结果不能显示为真实模型质量改善；数据不足 → INCONCLUSIVE。
 */
public final class ObsExperimentService {

    private final ObsExperimentMapper experimentMapper;
    private final ObsExperimentResultMapper resultMapper;

    public ObsExperimentService(ObsExperimentMapper experimentMapper,
                                ObsExperimentResultMapper resultMapper) {
        this.experimentMapper = experimentMapper;
        this.resultMapper = resultMapper;
    }

    /** 导入：ADMIN；返回 experimentId 与导入条数 */
    public Map<String, Object> importExperiment(ObsScope scope, Map<String, Object> body,
                                                AuthenticatedUser actor) {
        if (scope == null || !scope.canManage()) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        String datasetHash = str(body.get("datasetHash"));
        if (datasetHash == null || datasetHash.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        Object results = body.get("results");
        if (!(results instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR);
        }
        ObsExperiment experiment = new ObsExperiment();
        experiment.setDatasetHash(datasetHash);
        experiment.setDatasetId(str(body.get("datasetId")));
        experiment.setProviderMode(str(body.get("providerMode")) == null
                ? "STUB" : str(body.get("providerMode")));
        experiment.setImportedBy(actor.id());
        experimentMapper.insert(experiment);

        int inserted = 0;
        int duplicates = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            ObsExperimentResult row = new ObsExperimentResult();
            row.setExperimentId(experiment.getId());
            row.setDatasetHash(datasetHash);
            row.setSampleId(str(m.get("sampleId")));
            row.setRepeatIndex(intOf(m.get("repeatIndex"), 0));
            row.setArm(str(m.get("arm")));
            if (row.getSampleId() == null || row.getArm() == null) {
                continue; // 字段白名单：缺配对键的条目拒绝导入
            }
            row.setPromptVersion(str(m.get("promptVersion")));
            row.setModelVersion(str(m.get("modelVersion")));
            row.setWorkflowVersion(str(m.get("workflowVersion")));
            row.setRuleVersion(str(m.get("ruleVersion")));
            row.setFactVersion(str(m.get("factVersion")));
            row.setScore(decimal(m.get("score")));
            row.setHardViolations(intOrNull(m.get("hardViolations")));
            row.setCostPerSuccess(decimal(m.get("costPerSuccess")));
            row.setMissingFlag(Boolean.TRUE.equals(m.get("missing")) ? 1 : 0);
            row.setRawRef(str(m.get("rawRef")));
            try {
                resultMapper.insert(row);
                inserted++;
            } catch (DuplicateKeyException e) {
                duplicates++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experiment.getId());
        out.put("inserted", inserted);
        out.put("duplicates", duplicates);
        return out;
    }

    /** 对照：总体 + 分组（arm）+ 缺失原因；决策用预注册口径（数据不足 → INCONCLUSIVE） */
    public ComparisonView comparison(ObsScope scope, Long experimentId, int requiredPairs) {
        if (scope == null || !scope.canManage()) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        ObsExperiment experiment = experimentMapper.selectById(experimentId);
        if (experiment == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        List<ObsExperimentResult> rows = resultMapper.selectList(
                new LambdaQueryWrapper<ObsExperimentResult>()
                        .eq(ObsExperimentResult::getExperimentId, experimentId));
        Set<String> baselineKeys = new HashSet<>();
        Set<String> candidateKeys = new HashSet<>();
        Map<String, List<ObsExperimentResult>> byArm = new LinkedHashMap<>();
        int unknownCost = 0;
        int missing = 0;
        for (ObsExperimentResult r : rows) {
            String key = r.getSampleId() + "#" + r.getRepeatIndex();
            if ("baseline".equals(r.getArm())) {
                baselineKeys.add(key);
            } else if ("candidate".equals(r.getArm())) {
                candidateKeys.add(key);
            }
            byArm.computeIfAbsent(r.getArm(), k -> new ArrayList<>()).add(r);
            if (r.getMissingFlag() != null && r.getMissingFlag() == 1) {
                missing++;
            }
            if (r.getCostPerSuccess() == null && r.getMissingFlag() == null
                    || r.getMissingFlag() != null && r.getMissingFlag() == 1
                            && r.getCostPerSuccess() == null) {
                // 未缺失但费用未知才计入 unknownCost
            }
            if (r.getCostPerSuccess() == null && (r.getMissingFlag() == null
                    || r.getMissingFlag() == 0)) {
                unknownCost++;
            }
        }
        int observedPairs = 0;
        for (String key : baselineKeys) {
            if (candidateKeys.contains(key)) {
                observedPairs++;
            }
        }
        int missingRuns = baselineKeys.size() + candidateKeys.size() - 2 * observedPairs;

        Map<String, Object> armSummary = new LinkedHashMap<>();
        BigDecimal baselineScore = armAverage(byArm.get("baseline"));
        BigDecimal candidateScore = armAverage(byArm.get("candidate"));
        int baselineViolations = armViolations(byArm.get("baseline"));
        int candidateViolations = armViolations(byArm.get("candidate"));
        BigDecimal baselineCost = armAverageCost(byArm.get("baseline"));
        BigDecimal candidateCost = armAverageCost(byArm.get("candidate"));
        armSummary.put("baseline", Map.of("score", baselineScore, "hardViolations",
                baselineViolations, "costPerSuccess", baselineCost));
        armSummary.put("candidate", Map.of("score", candidateScore, "hardViolations",
                candidateViolations, "costPerSuccess", candidateCost));

        ExperimentDecision.DecisionResult decision = ExperimentDecision.decide(
                new ExperimentDecision.DecisionInput(observedPairs, requiredPairs,
                        missingRuns, unknownCost,
                        new ExperimentDecision.ArmSummary(doubleOrNull(baselineScore),
                                baselineViolations, baselineCost),
                        new ExperimentDecision.ArmSummary(doubleOrNull(candidateScore),
                                candidateViolations, candidateCost),
                        null, null, null,
                        experiment.getProviderMode(), false));

        return new ComparisonView(decision.decision(), decision.reasonCodes(), requiredPairs,
                observedPairs, missingRuns, unknownCost,
                baselineScore, candidateScore, armSummary);
    }

    private static BigDecimal armAverage(List<ObsExperimentResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (ObsExperimentResult r : rows) {
            if (r.getScore() != null) {
                sum = sum.add(r.getScore());
                n++;
            }
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal armAverageCost(List<ObsExperimentResult> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (ObsExperimentResult r : rows) {
            if (r.getCostPerSuccess() != null) {
                sum = sum.add(r.getCostPerSuccess());
                n++;
            }
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
    }

    private static int armViolations(List<ObsExperimentResult> rows) {
        if (rows == null) {
            return 0;
        }
        int total = 0;
        for (ObsExperimentResult r : rows) {
            if (r.getHardViolations() != null) {
                total += r.getHardViolations();
            }
        }
        return total;
    }

    private static Double doubleOrNull(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOf(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }
}
