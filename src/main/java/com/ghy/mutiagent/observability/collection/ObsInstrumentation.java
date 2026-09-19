package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.ObsEventTypes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务薄埋点门面（开发文档 §7.4）：业务文件持有可空引用，
 * 模块关闭时为 Noop 记录器（零开销、零副作用）；任何异常只计数不传播。
 * 事件带 operationId/sessionId/ownerId 上下文；owner 未知进入隔离域（ownerScope=null）。
 */
public final class ObsInstrumentation {

    private final ObsEventSink sink;
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean enabled = true;

    public ObsInstrumentation(ObsEventSink sink) {
        this.sink = sink == null ? ObsEventSink.noop() : sink;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void operationStarted(String operationId, String sessionId, Long ownerId) {
        emit(operationId, sessionId, ownerId, operationId, null, ObsEventTypes.OPERATION_STARTED,
                "STARTED", Map.of("operationId", String.valueOf(operationId)));
    }

    public void operationEnded(String operationId, String sessionId, Long ownerId,
                               String businessStatus, String persistenceStatus) {
        emit(operationId, sessionId, ownerId, operationId, null, ObsEventTypes.OPERATION_FINISHED,
                businessStatus == null ? "ENDED" : businessStatus,
                Map.of("businessStatus", String.valueOf(businessStatus),
                        "persistenceStatus", String.valueOf(persistenceStatus)));
    }

    public void nodeStarted(String operationId, String sessionId, Long ownerId,
                            String nodeExecutionId, String nodeType,
                            Map<String, Object> summary) {
        emit(operationId, sessionId, ownerId, nodeExecutionId, null, ObsEventTypes.NODE_STARTED,
                "STARTED", withType(nodeType, summary));
    }

    public void nodeEnded(String operationId, String sessionId, Long ownerId,
                          String nodeExecutionId, String nodeType, String outcome,
                          Map<String, Object> summary) {
        emit(operationId, sessionId, ownerId, nodeExecutionId, null, ObsEventTypes.NODE_ENDED,
                outcome, withType(nodeType, summary));
    }

    public void userConfirmed(String operationId, String sessionId, Long ownerId,
                              String candidateType, int selectedCount, int blockedCount) {
        emit(operationId, sessionId, ownerId, "confirm-" + candidateType, null,
                ObsEventTypes.USER_CONFIRMED, "CONFIRMED",
                Map.of("candidateType", candidateType, "selectedCount", selectedCount,
                        "blockedCount", blockedCount));
    }

    public void cancelled(String operationId, String sessionId, Long ownerId, String reason) {
        emit(operationId, sessionId, ownerId, "cancel", null, ObsEventTypes.CANCELLED,
                "CANCELLED", Map.of("reason", reason == null ? "unknown" : reason));
    }

    public void validation(String operationId, String sessionId, Long ownerId,
                           List<String> codes, int round, Map<String, Object> summary) {
        Map<String, Object> merged = summary == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(summary);
        merged.put("codes", codes == null ? List.of() : codes);
        merged.put("round", round);
        emit(operationId, sessionId, ownerId, "validate", null, ObsEventTypes.VALIDATION,
                codes == null || codes.isEmpty() ? "PASS" : "VIOLATIONS", merged);
    }

    public void repairRound(String operationId, String sessionId, Long ownerId,
                            int attempt, String outcome) {
        emit(operationId, sessionId, ownerId, "repair-r" + attempt, null,
                ObsEventTypes.REPAIR_ROUND, outcome, Map.of("attempt", attempt));
    }

    public void patchEvent(String operationId, String sessionId, Long ownerId,
                           boolean proposed, String outcome, Map<String, Object> summary) {
        emit(operationId, sessionId, ownerId, "patch", null,
                proposed ? ObsEventTypes.PATCH_PROPOSED : ObsEventTypes.PATCH_REJECTED,
                outcome, summary);
    }

    public void commitResult(String operationId, String sessionId, Long ownerId,
                             String outcome, Map<String, Object> summary) {
        emit(operationId, sessionId, ownerId, "commit", null, ObsEventTypes.COMMIT_RESULT,
                outcome, summary);
    }

    public void toolEvent(String operationId, String sessionId, Long ownerId,
                          String tool, String outcome, boolean external,
                          Map<String, Object> summary) {
        Map<String, Object> merged = summary == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(summary);
        merged.put("tool", tool);
        merged.put("external", external);
        emit(operationId, sessionId, ownerId, "tool-" + tool, null, ObsEventTypes.TOOL_CALL,
                outcome, merged);
    }

    private Map<String, Object> withType(String nodeType, Map<String, Object> summary) {
        Map<String, Object> merged = summary == null ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(summary);
        if (nodeType != null) {
            merged.put("nodeType", nodeType);
        }
        return merged;
    }

    private void emit(String operationId, String sessionId, Long ownerId,
                      String nodeExecutionId, String spanId, String eventType,
                      String outcome, Map<String, Object> summary) {
        if (!enabled) {
            return;
        }
        try {
            long seq = sequence.incrementAndGet();
            long now = System.currentTimeMillis();
            String runId = operationId != null && !operationId.isBlank()
                    ? operationId : "sess-" + sessionId;
            String eventId = (nodeExecutionId == null ? "n" : nodeExecutionId)
                    + "|" + eventType + "|" + seq;
            // outcome 是事件业务结果（CACHE_HIT/PROVIDER_OK/VIOLATIONS/…）：obs_event 无独立
            // status 列，outcome 必须并入 summary 才可持久化查询
            Map<String, Object> withOutcome = new java.util.LinkedHashMap<>();
            if (outcome != null) {
                withOutcome.put("outcome", outcome);
            }
            if (summary != null) {
                withOutcome.putAll(summary);
            }
            ObsEvent event = new ObsEvent(ObsEvent.SCHEMA_VERSION, eventId, runId,
                    operationId, spanId, null, nodeExecutionId, eventType, now, now,
                    "instrumentation", seq, ownerId,
                    PayloadSanitizer.sha256(String.valueOf(withOutcome)), null, withOutcome);
            sink.record(event);
        } catch (RuntimeException ignored) {
            // 埋点失败不传播（sink 自身已防护，这里双保险）
        }
    }
}
