package com.ghy.mutiagent.context.agent;

import java.util.List;

/** 影子比较只保存大小、Hash 和字段名，不保存完整用户内容。 */
public record AgentContextDifference(
        boolean equivalent,
        int legacyEstimatedTokens,
        int enrichedEstimatedTokens,
        String legacyHash,
        String enrichedHash,
        List<String> reasonCodes) {
}
