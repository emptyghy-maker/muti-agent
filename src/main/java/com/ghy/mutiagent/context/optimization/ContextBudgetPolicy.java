package com.ghy.mutiagent.context.optimization;

import com.ghy.mutiagent.context.agent.AgentContextRole;

import java.util.List;
import java.util.Map;

public record ContextBudgetPolicy(
        AgentContextRole role,
        int targetEstimatedTokens,
        int hardMaxEstimatedTokens,
        int maxCandidates,
        Map<String, Integer> sectionTargetTokens,
        List<String> reductionOrder,
        String estimateMethod) {
}
