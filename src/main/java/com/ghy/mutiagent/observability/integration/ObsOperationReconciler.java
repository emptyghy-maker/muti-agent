package com.ghy.mutiagent.observability.integration;

import com.ghy.mutiagent.observability.domain.ObsEvent;
import com.ghy.mutiagent.observability.domain.ObsEventTypes;
import com.ghy.mutiagent.observability.domain.ObsStatuses;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.persistence.OperationProjectionMapper;
import com.ghy.mutiagent.observability.collection.PayloadSanitizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只读对账（开发文档 §8）：在「保持业务事务不变」约束下，用既有业务账本补录
 * 可证实的终态/行程引用（source=RECONCILED）；工具原始 IO 和未持久化分支无法凭账本恢复，保留缺口。
 * 不实施 outbox，不加业务行锁，不写业务命令。
 */
public final class ObsOperationReconciler {

    private final OperationProjectionMapper projectionMapper;
    private final ObsRunMapper runMapper;
    private final AtomicLong sequence = new AtomicLong();

    public ObsOperationReconciler(OperationProjectionMapper projectionMapper,
                                  ObsRunMapper runMapper) {
        this.projectionMapper = projectionMapper;
        this.runMapper = runMapper;
    }

    /**
     * 按 operationId 对账：obs_run 缺失时以账本行补录（RUNNING/终态映射），
     * 返回生成的 RECONCILED 事件；账本也无记录返回 null。
     */
    public ObsEvent reconcile(String operationId, com.ghy.mutiagent.observability.collection.ObsEventSink sink) {
        try {
            OperationProjectionMapper.OperationProjection op =
                    projectionMapper.selectOperation(operationId);
            if (op == null) {
                return null;
            }
            String runStatus = mapRunStatus(op.status());
            String businessStatus = op.status();
            long now = System.currentTimeMillis();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("operationId", operationId);
            summary.put("businessStatus", businessStatus);
            summary.put("itineraryId", op.itineraryId());
            summary.put("errorCode", op.errorCode());

            ObsRun row = new ObsRun();
            row.setRunId(operationId);
            row.setOperationId(operationId);
            row.setSessionId(op.sessionId());
            row.setOwnerId(op.userId());
            row.setRunStatus(runStatus);
            row.setBusinessStatus(businessStatus);
            row.setDataCompleteness(ObsStatuses.DATA_PARTIAL);
            row.setSourceSystem(ObsStatuses.SOURCE_RECONCILED);
            row.setStartedAt(op.createdAt());
            row.setEndedAt(op.updatedAt());
            row.setDurationMs(op.createdAt() == null || op.updatedAt() == null ? null
                    : java.time.Duration.between(op.createdAt(), op.updatedAt()).toMillis());
            runMapper.upsert(row);

            ObsEvent event = new ObsEvent(ObsEvent.SCHEMA_VERSION,
                    operationId + "|RECONCILED|" + sequence.incrementAndGet(),
                    operationId, operationId, operationId, null, operationId + "-r1",
                    ObsEventTypes.RECONCILED, now, now, "reconciler",
                    sequence.get(), op.userId(),
                    PayloadSanitizer.sha256(String.valueOf(summary)), null, summary);
            if (sink != null) {
                sink.record(event);
            }
            return event;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String mapRunStatus(String opStatus) {
        if (opStatus == null) {
            return ObsStatuses.RUN_UNKNOWN;
        }
        return switch (opStatus) {
            case "COMPLETED" -> ObsStatuses.RUN_COMPLETED;
            case "FAILED", "CANCELLED", "NEEDS_CONFIRMATION", "DEADLINE_EXCEEDED" ->
                    ObsStatuses.RUN_ABORTED;
            case "RUNNING", "VALIDATED", "BUSY" -> ObsStatuses.RUN_RUNNING;
            default -> ObsStatuses.RUN_UNKNOWN;
        };
    }
}
