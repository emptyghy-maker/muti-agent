package com.ghy.mutiagent.context.optimization;

import java.util.List;
import java.util.Map;

/** Token 优化审计摘要；禁止保存完整用户原文或完整候选内容。 */
public record ContextReductionReport(
        String role,
        String policyVersion,
        int beforeEstimatedTokens,
        int afterEstimatedTokens,
        int beforeCandidates,
        int afterCandidates,
        List<String> protectedCandidateKeys,
        Map<String, Integer> droppedFieldCounts,
        List<String> retainedRequirementIds,
        List<String> reasonCodes,
        boolean hardLimitExceeded) {
}
