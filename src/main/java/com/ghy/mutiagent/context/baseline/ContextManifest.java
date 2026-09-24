package com.ghy.mutiagent.context.baseline;

import java.util.List;

/** 一次 Agent 调用的上下文目录，记录组成和规模，不记录原始载荷。 */
public record ContextManifest(
        String manifestId,
        String sessionId,
        String turnId,
        String operationId,
        String agentName,
        String stage,
        String contextSchemaVersion,
        int requirementRevision,
        int constraintRevision,
        int planRevision,
        List<ContextSectionMetric> sections,
        int totalChars,
        int totalUtf8Bytes,
        int estimatedInputTokens,
        String payloadHash,
        String estimateMethod,
        long buildDurationMs) {
}
