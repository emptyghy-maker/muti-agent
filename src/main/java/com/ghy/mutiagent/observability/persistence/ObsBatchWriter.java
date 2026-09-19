package com.ghy.mutiagent.observability.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.ObsEventTypes;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.service.ModelPricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批写器（开发文档 §8）：事件批 → run/span/event/attempt 行。
 * - eventId / attemptKey 幂等（INSERT IGNORE / upsert）；
 * - 乱序不回退：RUNNING 不覆盖终态（SQL 侧守卫）；
 * - 缺父节点暂存/标 orphan，不造父节点；
 * - 费用按记录时价格快照估算（amount_status=ESTIMATED；LEGACY→LEGACY_ESTIMATE），不改原 costOf。
 * 写失败向上计 failure（由记录器累加并标记 run PARTIAL），绝不触发业务重跑。
 */
public final class ObsBatchWriter {

    private final ObsRunMapper runMapper;
    private final ObsSpanMapper spanMapper;
    private final ObsEventRowMapper eventMapper;
    private final ObsUsageAttemptMapper attemptMapper;
    private final ObjectMapper json;
    private final ModelPricing modelPricing;
    private final Map<String, String> runStartGuard = new ConcurrentHashMap<>();

    public ObsBatchWriter(ObsRunMapper runMapper, ObsSpanMapper spanMapper,
                          ObsEventRowMapper eventMapper, ObsUsageAttemptMapper attemptMapper,
                          ObjectMapper json, ModelPricing modelPricing) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.json = json;
        this.modelPricing = modelPricing;
    }

    public int write(List<ObsEvent> events) {
        int written = 0;
        if (events == null) {
            return 0;
        }
        for (ObsEvent event : events) {
            if (event == null) {
                continue;
            }
            try {
                upsertRun(event);
                upsertSpan(event);
                writeEvent(event);
                if (ObsEventTypes.USAGE_ATTEMPT.equals(event.eventType())) {
                    writeAttempt(event);
                }
                written++;
            } catch (RuntimeException e) {
                // 单事件失败不中断批次；由记录器 failureCount 暴露（PARTIAL）
            }
        }
        return written;
    }

    /** 缓冲缺口标记：把受影响 run 的完整性降级为 PARTIAL（显式暴露，不伪装完整） */
    public void markGap(List<String> runIds) {
        if (runIds == null) {
            return;
        }
        for (String runId : runIds) {
            try {
                ObsRun row = new ObsRun();
                row.setRunId(runId);
                row.setRunStatus(ObsStatuses.RUN_RUNNING);
                row.setDataCompleteness(ObsStatuses.DATA_PARTIAL);
                row.setSourceSystem(ObsStatuses.SOURCE_NEW);
                runMapper.upsert(row);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void upsertRun(ObsEvent e) {
        ObsRun row = new ObsRun();
        row.setRunId(e.runId());
        row.setOperationId(e.operationId());
        row.setOwnerId(e.ownerId());
        row.setSourceSystem(ObsStatuses.SOURCE_NEW);
        row.setDataCompleteness(ObsStatuses.DATA_UNKNOWN);
        switch (e.eventType()) {
            case ObsEventTypes.SPAN_STARTED, ObsEventTypes.OPERATION_STARTED, ObsEventTypes.NODE_STARTED -> {
                if (runStartGuard.putIfAbsent(e.runId(), "S") == null) {
                    row.setRunStatus(ObsStatuses.RUN_RUNNING);
                    row.setStartedAt(ts(e.occurredAt()));
                } else {
                    row.setRunStatus(ObsStatuses.RUN_RUNNING);
                }
            }
            case ObsEventTypes.OPERATION_FINISHED -> {
                row.setRunStatus(ObsStatuses.RUN_COMPLETED);
                row.setBusinessStatus(bizStatus(e));
                row.setEndedAt(ts(e.occurredAt()));
                row.setDurationMs(duration(e));
                row.setDataCompleteness(ObsStatuses.DATA_COMPLETE);
            }
            case ObsEventTypes.CANCELLED -> {
                row.setRunStatus(ObsStatuses.RUN_ABORTED);
                row.setBusinessStatus(e.summary() == null ? null : str(e.summary().get("reason")));
                row.setEndedAt(ts(e.occurredAt()));
            }
            default -> {
                return;
            }
        }
        Map<String, Object> summary = e.summary();
        if (summary != null) {
            row.setSessionId(str(summary.get("sessionId")));
            row.setPromptVersion(str(summary.get("promptVersion")));
            row.setModelVersion(str(summary.get("modelVersion")));
            if (summary.get("constraintRevision") instanceof Number n) {
                row.setConstraintRevision(n.longValue());
            }
            row.setSnapshotHash(str(summary.get("snapshotHash")));
        }
        runMapper.upsert(row);
    }

    private void upsertSpan(ObsEvent e) {
        if (e.spanId() == null) {
            return;
        }
        ObsSpan span = new ObsSpan();
        span.setSpanId(e.spanId());
        span.setRunId(e.runId());
        span.setParentSpanId(e.parentSpanId());
        span.setNodeExecutionId(e.nodeExecutionId());
        span.setKind(str(e.summary() == null ? null : e.summary().get("kind")));
        switch (e.eventType()) {
            case ObsEventTypes.SPAN_STARTED -> {
                span.setStatus(ObsStatuses.SPAN_STARTED);
                span.setStartedAt(ts(e.occurredAt()));
            }
            case ObsEventTypes.SPAN_FINISHED, ObsEventTypes.OPERATION_FINISHED -> {
                span.setStatus(e.summary() == null ? ObsStatuses.SPAN_SUCCESS
                        : str(e.summary().get("businessStatus")) == null
                                ? (isFailed(e) ? ObsStatuses.SPAN_FAILED : ObsStatuses.SPAN_SUCCESS)
                                : (("FAILED".equals(str(e.summary().get("businessStatus")))
                                        || "CANCELLED".equals(str(e.summary().get("businessStatus"))))
                                        ? ObsStatuses.SPAN_FAILED : ObsStatuses.SPAN_SUCCESS));
                span.setEndedAt(ts(e.occurredAt()));
                span.setDurationMs(duration(e));
            }
            default -> {
                return;
            }
        }
        if (e.summary() != null && e.summary().get("durationMs") instanceof Number n) {
            span.setDurationMs(n.longValue());
        }
        if (e.summary() != null) {
            span.setSummary(safeJson(e.summary(), 1024));
        }
        spanMapper.upsert(span);
    }

    private void writeEvent(ObsEvent e) {
        ObsEventRow row = new ObsEventRow();
        row.setEventId(e.eventId());
        row.setRunId(e.runId());
        row.setOperationId(e.operationId());
        row.setSpanId(e.spanId());
        row.setParentSpanId(e.parentSpanId());
        row.setNodeExecutionId(e.nodeExecutionId());
        row.setEventType(e.eventType());
        row.setOccurredAt(e.occurredAt());
        row.setReceivedAt(e.receivedAt());
        row.setOwnerId(e.ownerId());
        row.setSchemaVersion(e.schemaVersion());
        row.setProducerId(e.producerId());
        row.setSequenceNo(e.sequence());
        row.setDigest(e.digest());
        row.setPayloadRef(e.payloadRef());
        row.setSummaryJson(safeJson(e.summary(), 262144));
        eventMapper.insertIgnore(row);
    }

    private void writeAttempt(ObsEvent e) {
        Map<String, Object> s = e.summary();
        if (s == null) {
            return;
        }
        String agent = str(s.get("agent"));
        if (agent == null) {
            return;
        }
        int attemptNo = s.get("attemptNo") instanceof Number n ? n.intValue() : 0;
        String source = ObsStatuses.SOURCE_NEW;
        String attemptKey = e.runId() + "|" + agent + "|" + attemptNo + "|" + source;
        ObsUsageAttempt attempt = new ObsUsageAttempt();
        attempt.setAttemptKey(attemptKey);
        attempt.setRunId(e.runId());
        attempt.setSpanId(e.spanId());
        attempt.setSourceSystem(source);
        attempt.setAgent(agent);
        attempt.setModel(str(s.get("model")));
        Integer input = s.get("inputTokens") instanceof Number n ? n.intValue() : null;
        Integer output = s.get("outputTokens") instanceof Number n ? n.intValue() : null;
        attempt.setInputTokens(input);
        attempt.setOutputTokens(output);
        attempt.setUsageStatus(str(s.get("usageStatus")) == null
                ? ObsStatuses.USAGE_UNKNOWN : str(s.get("usageStatus")));
        attempt.setDurationMs(s.get("durationMs") instanceof Number n ? n.longValue() : null);
        String model = attempt.getModel();
        if (model != null && input != null && output != null) {
            BigDecimal cost = modelPricing == null ? null
                    : modelPricing.estimate(model, input, output);
            attempt.setCostAmount(cost);
            attempt.setAmountStatus(cost == null ? ObsStatuses.AMOUNT_UNKNOWN
                    : ObsStatuses.AMOUNT_ESTIMATED);
            attempt.setCurrency("CNY");
            attempt.setPriceSnapshot(safeJson(Map.of("pricing", modelPricing.getPrices()), 262144));
        } else {
            attempt.setAmountStatus(ObsStatuses.AMOUNT_UNKNOWN);
        }
        attemptMapper.insertIgnore(attempt);
    }

    private static boolean isFailed(ObsEvent e) {
        return e.summary() != null && ("FAILED".equals(str(e.summary().get("status")))
                || "FAILED".equals(str(e.summary().get("businessStatus"))));
    }

    private static String bizStatus(ObsEvent e) {
        String b = e.summary() == null ? null : str(e.summary().get("businessStatus"));
        if (b != null) {
            return b;
        }
        return e.summary() == null ? null : str(e.summary().get("status"));
    }

    private static Long duration(ObsEvent e) {
        return e.summary() != null && e.summary().get("durationMs") instanceof Number n
                ? n.longValue() : null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static LocalDateTime ts(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private String safeJson(Object value, int maxLen) {
        try {
            String text = json.writeValueAsString(value);
            return text.length() > maxLen ? text.substring(0, maxLen) : text;
        } catch (Exception e) {
            return "{}";
        }
    }
}
