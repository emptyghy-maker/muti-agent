package com.ghy.mutiagent.observability.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测事件协议（docs/observability/01_事件协议与字段字典.md §3）：
 * 至少包含 schemaVersion/eventId/runId/operationId/spanId/parentSpanId/
 * nodeExecutionId/eventType/occurredAt/receivedAt/producerId/sequence/ownerScope/digest/payloadRef。
 * summary 为脱敏后的结构化摘要（不含原文）；外部用户不能直接 POST 任意事件伪造历史。
 */
public record ObsEvent(String schemaVersion, String eventId, String runId, String operationId,
                       String spanId, String parentSpanId, String nodeExecutionId,
                       String eventType, long occurredAt, long receivedAt,
                       String producerId, long sequence, Long ownerId,
                       String digest, String payloadRef, Map<String, Object> summary) {

    public static final String SCHEMA_VERSION = "obs-event-v1";

    public ObsEvent {
        // summary 来自可空信封字段（如 promptVersion/modelVersion），Map.copyOf 不接受 null：
        // 边界统一剔除空键值，缺失信息由字段缺失表达，而非 null 值
        if (summary == null || summary.isEmpty()) {
            summary = Map.of();
        } else {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            summary.forEach((k, v) -> {
                if (k != null && v != null) {
                    cleaned.put(k, v);
                }
            });
            summary = Map.copyOf(cleaned);
        }
    }

    /** 事件 → 持久化/传输形态（summary 拷贝，禁止共享可变引用） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", schemaVersion);
        m.put("eventId", eventId);
        m.put("runId", runId);
        m.put("operationId", operationId);
        m.put("spanId", spanId);
        m.put("parentSpanId", parentSpanId);
        m.put("nodeExecutionId", nodeExecutionId);
        m.put("eventType", eventType);
        m.put("occurredAt", occurredAt);
        m.put("receivedAt", receivedAt);
        m.put("producerId", producerId);
        m.put("sequence", sequence);
        m.put("ownerScope", ownerId);
        m.put("digest", digest);
        m.put("payloadRef", payloadRef);
        m.put("summary", new LinkedHashMap<>(summary));
        return m;
    }
}
