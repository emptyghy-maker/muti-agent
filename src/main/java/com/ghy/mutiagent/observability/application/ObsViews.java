package com.ghy.mutiagent.observability.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 查询投影（新模块独立 DTO，不暴露旧对象；前端只展示后端算好的指标） */
public final class ObsViews {

    private ObsViews() {
    }

    public record RunSummary(String runId, String operationId, String sessionId, Long ownerId,
                             String runStatus, String businessStatus, String dataCompleteness,
                             String sourceSystem, Long startedAt, Long endedAt, Long durationMs,
                             String promptVersion, String modelVersion, String snapshotHash) {
    }

    public record RunPage(List<RunSummary> items, String nextCursor, Long asOf,
                          int snapshotVersion, int totalVisible) {
    }

    public record RunDetail(RunSummary run, List<SpanView> spans, List<EventView> recentEvents,
                            Map<String, Object> metrics, List<Map<String, Object>> legacyAttempts,
                            int annotationCount, boolean pathDefinitionMissing) {
    }

    public record SpanView(String spanId, String parentSpanId, String nodeExecutionId,
                           String kind, String agent, Integer attemptNo, String status,
                           Long startedAt, Long endedAt, Long durationMs, String summary) {
    }

    public record EventView(String eventId, String eventType, Long occurredAt, Long receivedAt,
                            String spanId, String nodeExecutionId, long sequence,
                            String digest, String payloadRef, Map<String, Object> summary) {
    }

    public record EventPage(List<EventView> items, Long nextCursor) {
    }

    public record PayloadView(String payloadId, String runId, String kind, String status,
                              int sizeBytes, String digest, Long createdAt, Long expiresAt,
                              boolean expired, boolean contentClosed) {
    }

    public record TaskView(Long id, String title, String status, Integer version,
                           Long createdBy, Long createdAt, Long updatedAt) {
    }

    public record RevisionView(Long id, Integer revisionNo, String promptVersion,
                               String modelVersion, String gitHash, String changes,
                               String rationale, String status, Long createdBy, Long createdAt) {
    }

    public record AnnotationView(Long id, Long authorId, String authorUsername,
                                 String content, Long createdAt) {
    }

    public record ComparisonView(String decision, List<String> reasonCodes, int requiredPairs,
                                 int observedPairs, int missingRuns, int unknownCostCount,
                                 BigDecimal baselineScore, BigDecimal candidateScore,
                                 Map<String, Object> byCategory) {
    }
}
