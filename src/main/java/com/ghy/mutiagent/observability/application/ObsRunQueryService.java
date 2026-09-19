package com.ghy.mutiagent.observability.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghy.mutiagent.observability.application.ObsViews.EventPage;
import com.ghy.mutiagent.observability.application.ObsViews.EventView;
import com.ghy.mutiagent.observability.application.ObsViews.RunDetail;
import com.ghy.mutiagent.observability.application.ObsViews.RunPage;
import com.ghy.mutiagent.observability.application.ObsViews.RunSummary;
import com.ghy.mutiagent.observability.application.ObsViews.SpanView;
import com.ghy.mutiagent.observability.collection.ObsEventSink;
import com.ghy.mutiagent.observability.integration.ObsOperationReconciler;
import com.ghy.mutiagent.observability.integration.UsageLegacyAdapter;
import com.ghy.mutiagent.observability.persistence.ObsAnnotation;
import com.ghy.mutiagent.observability.persistence.ObsAnnotationMapper;
import com.ghy.mutiagent.observability.persistence.ObsEventRow;
import com.ghy.mutiagent.observability.persistence.ObsEventRowMapper;
import com.ghy.mutiagent.observability.persistence.ObsRun;
import com.ghy.mutiagent.observability.persistence.ObsRunMapper;
import com.ghy.mutiagent.observability.persistence.ObsSpan;
import com.ghy.mutiagent.observability.persistence.ObsSpanMapper;
import com.ghy.mutiagent.observability.security.ObsScope;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行查询服务（开发文档 §10）：所有查询携带 Scope；owner=null 隔离域不可见；
 * 非法 cursor/超限按 400 语义；事件游标只表示接收顺序；详情缺失时先只读对账再回答。
 */
public final class ObsRunQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ObsRunMapper runMapper;
    private final ObsSpanMapper spanMapper;
    private final ObsEventRowMapper eventMapper;
    private final ObsAnnotationMapper annotationMapper;
    private final ObsMetricsService metrics;
    private final UsageLegacyAdapter legacyAdapter;
    private final ObsOperationReconciler reconciler;
    private final ObjectProvider<ObsEventSink> sink;
    private final ObjectMapper json;

    public ObsRunQueryService(ObsRunMapper runMapper, ObsSpanMapper spanMapper,
                              ObsEventRowMapper eventMapper, ObsAnnotationMapper annotationMapper,
                              ObsMetricsService metrics, UsageLegacyAdapter legacyAdapter,
                              ObsOperationReconciler reconciler, ObjectProvider<ObsEventSink> sink,
                              ObjectMapper json) {
        this.runMapper = runMapper;
        this.spanMapper = spanMapper;
        this.eventMapper = eventMapper;
        this.annotationMapper = annotationMapper;
        this.metrics = metrics;
        this.legacyAdapter = legacyAdapter;
        this.reconciler = reconciler;
        this.sink = sink;
        this.json = json;
    }

    public RunPage listRuns(ObsScope scope, String cursor, int limit) {
        int n = clamp(limit);
        long asOf = System.currentTimeMillis();
        LambdaQueryWrapper<ObsRun> q = scopeQuery(scope);
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = decodeCursor(cursor);
            long ts = Long.parseLong(parts[0]);
            String runId = parts[1];
            q.and(w -> w.lt(ObsRun::getCreatedAt, millisToLocal(ts))
                    .or(t -> t.eq(ObsRun::getCreatedAt, millisToLocal(ts))
                            .lt(ObsRun::getRunId, runId)));
        }
        q.orderByDesc(ObsRun::getCreatedAt).orderByDesc(ObsRun::getRunId).last("LIMIT " + (n + 1));
        List<ObsRun> rows = runMapper.selectList(q);
        boolean hasMore = rows.size() > n;
        List<ObsRun> page = hasMore ? rows.subList(0, n) : rows;
        List<RunSummary> items = new ArrayList<>();
        for (ObsRun r : page) {
            items.add(summaryOf(r));
        }
        String next = null;
        if (hasMore && !page.isEmpty()) {
            ObsRun last = page.get(page.size() - 1);
            next = encodeCursor(last.getCreatedAt(), last.getRunId());
        }
        int totalVisible = Math.toIntExact(runMapper.selectCount(scopeQuery(scope)));
        return new RunPage(items, next, asOf, totalVisible, totalVisible);
    }

    /** 详情：run 不存在时先对账（operationId 可查账本则 RECONCILED 补录），仍无 → null（404） */
    public RunDetail detail(ObsScope scope, String runId) {
        ObsRun run = runMapper.selectById(runId);
        if (run == null) {
            run = reconcileFallback(runId);
        }
        if (run == null) {
            return null;
        }
        if (!scope.canSee(run.getOwnerId())) {
            return null;
        }
        List<ObsSpan> spans = spanMapper.selectList(new LambdaQueryWrapper<ObsSpan>()
                .eq(ObsSpan::getRunId, runId)
                .orderByAsc(ObsSpan::getStartedAt).last("LIMIT 200"));
        List<SpanView> spanViews = new ArrayList<>();
        for (ObsSpan s : spans) {
            spanViews.add(new SpanView(s.getSpanId(), s.getParentSpanId(), s.getNodeExecutionId(),
                    s.getKind(), s.getAgent(), s.getAttemptNo(), s.getStatus(),
                    s.getStartedAt() == null ? null : millis(s.getStartedAt()),
                    s.getEndedAt() == null ? null : millis(s.getEndedAt()),
                    s.getDurationMs(), s.getSummary()));
        }
        List<EventView> recentEvents = new ArrayList<>();
        for (ObsEventRow e : eventMapper.selectList(new LambdaQueryWrapper<ObsEventRow>()
                .eq(ObsEventRow::getRunId, runId)
                .orderByDesc(ObsEventRow::getSequenceNo).last("LIMIT 50"))) {
            recentEvents.add(eventView(e));
        }
        Map<String, Object> metricsMap = new LinkedHashMap<>();
        ObsMetricsService.RunMetrics rm = metrics.runMetrics(scope);
        ObsMetricsService.CostMetrics cm = metrics.costMetrics(scope);
        ObsMetricsService.DurationMetrics dm = metrics.durations(runId);
        metricsMap.put("operationCount", rm.operationCount());
        metricsMap.put("successCount", rm.successCount());
        metricsMap.put("inFlightCount", rm.inFlightCount());
        metricsMap.put("successRate", rm.successRate());
        metricsMap.put("totalKnownCost", cm.totalKnownCost() == null ? null
                : cm.totalKnownCost().toPlainString());
        metricsMap.put("costPerSuccessfulPlan", cm.costPerSuccessfulPlan() == null ? null
                : cm.costPerSuccessfulPlan().toPlainString());
        metricsMap.put("unknownCostCount", cm.unknownCostCount());
        metricsMap.put("costComplete", cm.costComplete());
        metricsMap.put("wallMs", dm.wallMs());
        metricsMap.put("providerWorkMs", dm.providerWorkMs());
        List<Map<String, Object>> legacy = legacyAdapter == null ? List.of()
                : legacyAdapter.legacyAttempts(run.getSessionId(), 50);
        int annotationCount = Math.toIntExact(annotationMapper.selectCount(
                new LambdaQueryWrapper<ObsAnnotation>().eq(ObsAnnotation::getRunId, runId)));
        return new RunDetail(summaryOf(run), spanViews, recentEvents, metricsMap, legacy,
                annotationCount, spanViews.isEmpty());
    }

    public List<SpanView> spans(ObsScope scope, String runId, int offset, int limit) {
        ObsRun run = runMapper.selectById(runId);
        if (run == null || !scope.canSee(run.getOwnerId())) {
            return null;
        }
        List<ObsSpan> spans = spanMapper.selectList(new LambdaQueryWrapper<ObsSpan>()
                .eq(ObsSpan::getRunId, runId)
                .orderByAsc(ObsSpan::getStartedAt)
                .last("LIMIT " + clamp(limit) + " OFFSET " + Math.max(0, offset)));
        List<SpanView> out = new ArrayList<>();
        for (ObsSpan s : spans) {
            out.add(new SpanView(s.getSpanId(), s.getParentSpanId(), s.getNodeExecutionId(),
                    s.getKind(), s.getAgent(), s.getAttemptNo(), s.getStatus(),
                    s.getStartedAt() == null ? null : millis(s.getStartedAt()),
                    s.getEndedAt() == null ? null : millis(s.getEndedAt()),
                    s.getDurationMs(), s.getSummary()));
        }
        return out;
    }

    public EventPage events(ObsScope scope, String runId, long cursor, int limit) {
        ObsRun run = runMapper.selectById(runId);
        if (run == null || !scope.canSee(run.getOwnerId())) {
            return null;
        }
        int n = clamp(limit);
        List<ObsEventRow> rows = eventMapper.selectAfterSequence(runId, cursor, n + 1);
        boolean hasMore = rows.size() > n;
        List<EventView> items = new ArrayList<>();
        for (ObsEventRow e : (hasMore ? rows.subList(0, n) : rows)) {
            items.add(eventView(e));
        }
        Long next = hasMore && !items.isEmpty()
                ? items.get(items.size() - 1).sequence() : null;
        return new EventPage(items, next);
    }

    private ObsRun reconcileFallback(String runId) {
        try {
            if (reconciler == null) {
                return null;
            }
            reconciler.reconcile(runId, sink.getIfAvailable(ObsEventSink::noop));
            return runMapper.selectById(runId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LambdaQueryWrapper<ObsRun> scopeQuery(ObsScope scope) {
        LambdaQueryWrapper<ObsRun> q = new LambdaQueryWrapper<>();
        if (scope == null || !scope.admin()) {
            q.eq(ObsRun::getOwnerId, scope == null ? -1L : scope.ownerId());
        } else {
            q.isNotNull(ObsRun::getOwnerId);
        }
        return q;
    }

    private static RunSummary summaryOf(ObsRun r) {
        return new RunSummary(r.getRunId(), r.getOperationId(), r.getSessionId(), r.getOwnerId(),
                r.getRunStatus(), r.getBusinessStatus(), r.getDataCompleteness(), r.getSourceSystem(),
                r.getStartedAt() == null ? null : millis(r.getStartedAt()),
                r.getEndedAt() == null ? null : millis(r.getEndedAt()),
                r.getDurationMs(), r.getPromptVersion(), r.getModelVersion(), r.getSnapshotHash());
    }

    private EventView eventView(ObsEventRow e) {
        Map<String, Object> summary = parseSummary(e.getSummaryJson());
        return new EventView(e.getEventId(), e.getEventType(), e.getOccurredAt(), e.getReceivedAt(),
                e.getSpanId(), e.getNodeExecutionId(),
                e.getSequenceNo() == null ? 0 : e.getSequenceNo(),
                e.getDigest(), e.getPayloadRef(), summary);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSummary(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(summaryJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static java.time.LocalDateTime millisToLocal(long ms) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms),
                java.time.ZoneId.systemDefault());
    }

    private static long millis(java.time.LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String encodeCursor(java.time.LocalDateTime createdAt, String runId) {
        String raw = millis(createdAt) + "|" + runId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static String[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            Long.parseLong(parts[0]);
            if (parts.length < 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException("非法 cursor");
            }
            return parts;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法 cursor");
        }
    }
}
