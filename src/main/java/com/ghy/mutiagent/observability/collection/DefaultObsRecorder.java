package com.ghy.mutiagent.observability.collection;

import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.ObsEventTypes;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.observability.domain.TraceEnvelope;
import com.ghy.mutiagent.trace.AgentTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认安全记录器（开发文档 §7.1）：
 * - record 不向业务传播任何异常；保护只包围记录动作，不包围业务调用；
 * - 同步工作限定为快照/脱敏摘要 + 有界队列入队；昂贵处理在批写线程；
 * - 队列满丢明细记 gap；写失败累计 failureCount 并在查询层显式暴露（PARTIAL）。
 */
public final class DefaultObsRecorder implements ObsEventSink {

    private final EventBuffer buffer;
    private final ObsPayloadStore payloadStore;
    private final String producerId;
    private final int payloadMaxBytes;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public DefaultObsRecorder(EventBuffer buffer, ObsPayloadStore payloadStore,
                              String producerId, int payloadMaxBytes) {
        this.buffer = buffer;
        this.payloadStore = payloadStore;
        this.producerId = producerId;
        this.payloadMaxBytes = payloadMaxBytes > 0 ? payloadMaxBytes : PayloadSanitizer.DEFAULT_MAX_BYTES;
    }

    @Override
    public void onSpan(TraceEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", envelope.kind());
        summary.put("sessionId", envelope.sessionId());
        summary.put("promptVersion", envelope.promptVersion());
        summary.put("modelVersion", envelope.modelVersion());
        summary.put("constraintRevision", envelope.constraintRevision());
        summary.put("snapshotHash", envelope.snapshotHash());
        record(build(envelope, ObsEventTypes.SPAN_STARTED, "STARTED", summary));
    }

    @Override
    public void onFinished(TraceEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        String spanStatus = spanStatusOf(envelope);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", envelope.kind());
        summary.put("durationMs", envelope.durationMs());
        summary.put("businessStatus", envelope.businessStatus());
        summary.put("persistenceStatus", envelope.persistenceStatus());
        summary.put("terminalEvents", envelope.terminalEventCount());
        summary.put("promptVersion", envelope.promptVersion());
        summary.put("modelVersion", envelope.modelVersion());
        summary.put("constraintRevision", envelope.constraintRevision());
        summary.put("snapshotHash", envelope.snapshotHash());
        record(build(envelope, ObsEventTypes.SPAN_FINISHED, spanStatus, summary));

        if (isOperation(envelope)) {
            summary = new LinkedHashMap<>();
            summary.put("durationMs", envelope.durationMs());
            summary.put("businessStatus", envelope.businessStatus());
            summary.put("persistenceStatus", envelope.persistenceStatus());
            record(build(envelope, ObsEventTypes.OPERATION_FINISHED,
                    envelope.status(), summary));
        }

        // 用量尝试只从 span 终态采集一次（register+finish 不双计）
        int attemptNo = envelope.providerAttemptId() == null ? 0 : envelope.providerAttemptId();
        for (AgentTrace agent : envelope.agents()) {
            if (agent == null) {
                continue;
            }
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("agent", agent.getAgent());
            attempt.put("attemptNo", attemptNo);
            attempt.put("model", envelope.modelVersion());
            attempt.put("inputTokens", agent.getInputTokens());
            attempt.put("outputTokens", agent.getOutputTokens());
            attempt.put("usageStatus", agent.getUsageStatus());
            attempt.put("durationMs", agent.getDurationMs());
            attempt.put("providerStatus", agent.getProviderStatus());
            attempt.put("parseStatus", agent.getParseStatus());
            attempt.put("validationStatus", agent.getValidationStatus());
            record(build(envelope, ObsEventTypes.USAGE_ATTEMPT,
                    agent.isSuccess() ? ObsStatuses.SPAN_SUCCESS : ObsStatuses.SPAN_FAILED, attempt));
        }
    }

    @Override
    public void record(ObsEvent event) {
        if (event == null) {
            return;
        }
        try {
            buffer.offer(event);
        } catch (RuntimeException e) {
            failureCount.incrementAndGet();
        }
    }

    @Override
    public String storePayload(String runId, Long ownerId, String kind, String raw) {
        try {
            if (payloadStore == null) {
                return null;
            }
            PayloadSanitizer.Sanitized sanitized = PayloadSanitizer.sanitize(raw, payloadMaxBytes);
            return payloadStore.save(runId, ownerId, kind, sanitized.content(),
                    sanitized.status(), sanitized.sizeBytes(), sanitized.digest());
        } catch (RuntimeException e) {
            failureCount.incrementAndGet();
            return null;
        }
    }

    public EventBuffer buffer() {
        return buffer;
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long recordedCount() {
        return sequence.get();
    }

    private ObsEvent build(TraceEnvelope envelope, String eventType, String status,
                           Map<String, Object> summary) {
        long seq = sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        String runId = envelope.operationId() != null && !envelope.operationId().isBlank()
                ? envelope.operationId() : "sess-" + envelope.sessionId();
        return new ObsEvent(ObsEvent.SCHEMA_VERSION,
                envelope.spanId() + "|" + eventType + "|" + seq,
                runId, envelope.operationId(), envelope.spanId(), envelope.parentSpanId(),
                envelope.spanId() + "-x1", eventType, now, now, producerId, seq,
                envelope.ownerId(), digestOf(envelope, eventType, status), null, summary);
    }

    private static String spanStatusOf(TraceEnvelope envelope) {
        if ("SUCCESS".equals(envelope.status()) || ObsStatuses.SPAN_SUCCESS.equals(envelope.status())) {
            return ObsStatuses.SPAN_SUCCESS;
        }
        if ("FAILED".equals(envelope.status()) || "CANCELLED".equals(envelope.status())
                || "ABORTED".equals(envelope.status())) {
            return ObsStatuses.SPAN_FAILED;
        }
        return ObsStatuses.SPAN_STARTED;
    }

    private static boolean isOperation(TraceEnvelope envelope) {
        return "OPERATION".equals(envelope.kind())
                || (envelope.operationId() != null && envelope.operationId().equals(envelope.spanId()));
    }

    private static String digestOf(TraceEnvelope envelope, String eventType, String status) {
        String material = envelope.spanId() + "|" + eventType + "|" + status + "|"
                + envelope.businessStatus() + "|" + envelope.durationMs();
        return PayloadSanitizer.sha256(material);
    }
}
