package com.ghy.mutiagent.context.agent;

/** Agent 输入的版本化信封。payload 只能是角色专属投影，不能放完整 TravelState。 */
public record AgentContextEnvelope<T>(
        String contextId,
        AgentContextRole role,
        String schemaVersion,
        String sessionId,
        String turnId,
        String operationId,
        int requirementRevision,
        int constraintRevision,
        int planRevision,
        String candidateSnapshotId,
        T payload) {
}
