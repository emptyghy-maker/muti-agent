package com.ghy.mutiagent.context.baseline;

import java.util.LinkedHashMap;
import java.util.Map;

/** 调用方显式声明本次 Agent 实际消费的上下文段，避免观测层反向依赖整个 TravelState。 */
public record ContextCaptureRequest(
        String sessionId,
        Long ownerId,
        String turnId,
        String operationId,
        String agentName,
        String stage,
        String schemaVersion,
        int requirementRevision,
        int constraintRevision,
        int planRevision,
        Map<String, Object> sections) {

    public ContextCaptureRequest {
        sections = sections == null ? Map.of() : new LinkedHashMap<>(sections);
    }
}
